package com.musicd.migrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.json.JSONObject

/** The Qobuz client, driven against a scripted fake HTTP layer. */
class ClientsTest {

    // ------------------------------------------------------------- sign-in

    @Test fun `the Qobuz sign-in needs no registration and finds its code`() {
        val url = QobuzOAuth.buildAuthorizeUrl("http://127.0.0.1:3380/api/qobuz/oauth/callback")
        assertTrue(url.startsWith(QobuzOAuth.SIGNIN_URL))
        assertEquals("abc123",
            QobuzOAuth.extractCode("http://x/cb?code_autorisation=abc123"))
        assertEquals("abc123", QobuzOAuth.extractCode("abc123"))
        // A URL that carried no code is an error page or a cancelled sign-in.
        assertEquals("", QobuzOAuth.extractCode("http://x/cb?error=nope"))
    }

    // ---------------------------------------------------- Qobuz conversion

    @Test fun `Qobuz durations convert from seconds to milliseconds`() {
        val t = toQobuzTrack(JSONObject("""
            {"id":7,"title":"Song","duration":213,"isrc":"GBAYE0601498",
             "performer":{"name":"A"},"album":{"title":"Rec"}}"""), null)!!
        assertEquals("seconds must become milliseconds or nothing ever matches",
            213000L, t.durationMs)
    }

    @Test fun `a JSON null is not read as the string null`() {
        // Android's org.json hands back the literal "null" for a JSON null
        // through optString; a null ISRC read that way would be SEARCHED FOR.
        val t = toQobuzTrack(JSONObject(
            """{"id":7,"title":"Song","duration":100,"isrc":null,"version":null}"""), null)!!
        assertEquals("", t.isrc)
        assertEquals("a null version adds no suffix", "Song", t.title)
    }

    @Test fun `Qobuz recomposes title and version into one title`() {
        assertEquals("Blue Monday (2016 Remaster)", toQobuzTrack(JSONObject(
            """{"id":1,"title":"Blue Monday","version":"2016 Remaster","duration":100}"""),
            null)!!.title)
        assertEquals("Blue Monday", toQobuzTrack(JSONObject(
            """{"id":1,"title":"Blue Monday","duration":100}"""), null)!!.title)
    }

    @Test fun `Qobuz album tracks inherit the album block they came from`() {
        val album = toQobuzAlbum(JSONObject(
            """{"id":"al","title":"Rec","artist":{"name":"A"},"tracks_count":2}"""))
        val t = toQobuzTrack(JSONObject("""{"id":1,"title":"Song","duration":100}"""), album)!!
        assertEquals("Rec", t.album)
        assertEquals(listOf("A"), t.artists)
    }

    @Test fun `Qobuz albums convert, with the year from any of its date fields`() {
        assertEquals(1977, toQobuzAlbum(JSONObject(
            """{"id":1,"title":"X","release_date_original":"1977-02-04"}"""))!!.year)
        assertNull(toQobuzAlbum(JSONObject("""{"id":1,"title":"X"}"""))!!.year)
    }

    @Test fun `a Qobuz 502 is retried, and one that persists is described in words`() {
        // A real preview of 14,500 albums had three searches fail with nginx's
        // "502 Bad Gateway" — a passing fault on Qobuz's side — and the report
        // quoted the raw HTML page as the reason.
        val page = "<html><head><title>502 Bad Gateway</title></head></html>"
        val waits = ArrayList<Long>()
        val once = FakeHttp(listOf(FakeHttp.res(502, page),
            FakeHttp.res(200, """{"albums":{"items":[]}}""")))
        QobuzClient(QobuzSession(token = "t"), once, sleeper = { waits.add(it) })
            .searchAlbums("Powerage", "AC/DC")
        assertEquals("retried once and then answered", 2, once.calls.size)
        assertTrue("with a pause first: $waits", waits.isNotEmpty())

        val always = FakeHttp(listOf(FakeHttp.res(503, page)))
        try {
            QobuzClient(QobuzSession(token = "t"), always, sleeper = {})
                .searchAlbums("Powerage", "AC/DC")
            fail("a 503 that never clears must still fail")
        } catch (e: RuntimeException) {
            assertTrue(e.message!!, e.message!!.contains("503"))
            assertTrue(e.message!!, e.message!!.contains("temporary"))
            assertFalse("no raw HTML in the reason: ${e.message}", e.message!!.contains("<html>"))
        }
        assertEquals("three tries in all", 3, always.calls.size)
    }

    @Test fun `a Qobuz 401 is an AuthError, not a generic failure`() {
        val qz = QobuzClient(QobuzSession(token = "t"),
            FakeHttp(listOf(FakeHttp.res(401, "nope"))), sleeper = {})
        try {
            qz.savedTracks()
            fail("a 401 must be an AuthError so the migration stops")
        } catch (e: AuthError) {
            assertTrue(e.message!!.contains("sign in again"))
        }
    }
}
