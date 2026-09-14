package com.musicd.migrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.json.JSONObject

/** The JavaScript client tests (test/unit/clients.test.js), translated. */
class ClientsTest {

    private fun liveSession() = SpotifySession(
        clientId = "cid", accessToken = "tok", refreshToken = "ref",
        expiresAt = System.currentTimeMillis() + 600_000, userId = "me")

    // ---------------------------------------------------------------- PKCE

    @Test fun `PKCE verifier and challenge are well formed`() {
        val v = Pkce.createVerifier()
        assertEquals(64, v.length)
        assertTrue(v.all { it.isLetterOrDigit() || it in "-._~" })
        val c = Pkce.challengeFor(v)
        assertTrue("base64url, no padding", c.all { it.isLetterOrDigit() || it in "-_" })
        assertEquals("the challenge is a pure function", c, Pkce.challengeFor(v))
        // The published test vector from RFC 7636 appendix B. If this passes,
        // the hashing and the base64url encoding are both right.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun `checkRedirectUri enforces Spotify's loopback rule`() {
        assertTrue(Pkce.checkRedirectUri("https://example.com/cb").ok)
        assertTrue(Pkce.checkRedirectUri("http://127.0.0.1:3380/api/spotify/callback").ok)
        val localhost = Pkce.checkRedirectUri("http://localhost:3380/cb")
        assertEquals(false, localhost.ok)
        assertTrue(localhost.reason.contains("127.0.0.1"))
        val lan = Pkce.checkRedirectUri("http://192.168.1.50:3380/cb")
        assertEquals(false, lan.ok)
        assertTrue(lan.reason.contains("paste-back"))
    }

    @Test fun `parseCallback accepts a URL, a query string or a bare code`() {
        assertEquals("abc", Pkce.parseCallback("http://x/cb?code=abc&state=s1").code)
        assertEquals("s1", Pkce.parseCallback("code=abc&state=s1").state)
        assertEquals("abc", Pkce.parseCallback("abc").code)
        assertEquals("access_denied", Pkce.parseCallback("http://x/cb?error=access_denied").error)
        assertEquals("", Pkce.parseCallback("").code)
    }

    @Test fun `buildAuthorizeUrl refuses to build half a sign-in`() {
        try {
            Pkce.buildAuthorizeUrl("", "u", "c", "s")
            fail("a missing client id must not produce a URL")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Client ID"))
        }
        val p = Pkce.parseQuery(
            Pkce.buildAuthorizeUrl("cid", "http://127.0.0.1/cb", "ch", "st")
                .substringAfter("?"))
        assertEquals("S256", p["code_challenge_method"])
        assertEquals("code", p["response_type"])
        assertTrue(p["scope"]!!.contains("user-library-modify"))
    }

    @Test fun `the Qobuz sign-in needs no registration and finds its code`() {
        val url = QobuzOAuth.buildAuthorizeUrl("http://127.0.0.1:3380/api/qobuz/oauth/callback")
        assertTrue(url.startsWith(QobuzOAuth.SIGNIN_URL))
        assertEquals("abc123",
            QobuzOAuth.extractCode("http://x/cb?code_autorisation=abc123"))
        assertEquals("abc123", QobuzOAuth.extractCode("abc123"))
        // A URL that carried no code is an error page or a cancelled sign-in.
        assertEquals("", QobuzOAuth.extractCode("http://x/cb?error=nope"))
    }

    // -------------------------------------------------- Spotify conversion

    @Test fun `Spotify tracks convert, and non-tracks are refused`() {
        val t = toTrack(JSONObject("""
            {"id":"1","name":"Song","duration_ms":200000,"type":"track",
             "artists":[{"name":"A"},{"name":"B"}],"album":{"name":"Rec"},
             "external_ids":{"isrc":"GBAYE0601498"}}"""))
        assertEquals(Track("1", "GBAYE0601498", "Song", listOf("A", "B"), "Rec", 200000), t)
        assertNull("a local file has no id",
            toTrack(JSONObject("""{"id":null,"name":"Local"}""")))
        assertNull("a podcast is not a track",
            toTrack(JSONObject("""{"id":"e1","type":"episode"}""")))
        assertNull(toTrack(null))
    }

    @Test fun `a JSON null is not read as the string null`() {
        // The bug this guards is invisible on the JVM if optString is used:
        // Android's org.json returns the LITERAL "null". A null isrc read that
        // way would be SEARCHED FOR on the other service.
        val t = toTrack(JSONObject("""
            {"id":"1","name":"Song","duration_ms":1000,"type":"track",
             "artists":[],"album":null,"external_ids":{"isrc":null}}"""))!!
        assertEquals("", t.isrc)
        assertEquals("", t.album)
    }

    @Test fun `Spotify albums convert, including the release year`() {
        assertEquals(Album("a", "075992736121", "Rumours", listOf("Fleetwood Mac"), 11, 1977),
            toAlbum(JSONObject("""
                {"id":"a","name":"Rumours","total_tracks":11,
                 "artists":[{"name":"Fleetwood Mac"}],
                 "external_ids":{"upc":"075992736121"},"release_date":"1977-02-04"}""")))
    }

    @Test fun `quoteTerm strips quotes that would break the search phrase`() {
        assertEquals("\"The  Real  Thing\"", quoteTerm("The \"Real\" Thing"))
    }

    // ---------------------------------------------------- Qobuz conversion

    @Test fun `Qobuz durations convert from seconds to milliseconds`() {
        val t = toQobuzTrack(JSONObject("""
            {"id":7,"title":"Song","duration":213,"isrc":"GBAYE0601498",
             "performer":{"name":"A"},"album":{"title":"Rec"}}"""), null)!!
        assertEquals("seconds must become milliseconds or nothing ever matches",
            213000L, t.durationMs)
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

    // ------------------------------------------------------ rate limiting

    @Test fun `a Spotify 429 is obeyed with the delay Spotify asked for`() {
        val http = FakeHttp(listOf(
            FakeHttp.res(429, "", mapOf("retry-after" to "1")),
            FakeHttp.res(200, """{"id":"me","display_name":"Me"}""")))
        val waits = ArrayList<Long>()
        val sp = SpotifyClient(liveSession(), http, onRateLimit = { waits.add(it) },
            sleeper = { /* no real sleeping in a test */ })
        assertEquals("me", sp.me().id)
        assertEquals(1, waits.size)
        assertTrue("waited about the second it was told to",
            waits[0] in 1000..2000)
    }

    @Test fun `a Spotify 401 with no refresh token gives up rather than looping`() {
        val http = FakeHttp(listOf(
            FakeHttp.res(401, """{"error":{"message":"expired"}}""")))
        val sp = SpotifyClient(liveSession().copy(refreshToken = ""), http,
            sleeper = {})
        try {
            sp.me()
            fail("a dead sign-in must be reported")
        } catch (e: AuthError) {
            assertTrue(e.message!!.contains("expired"))
        }
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

    // ------------------------------------------------------ write batching

    @Test fun `Spotify playlist writes batch at 100 and use track URIs`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, "{}")))
        SpotifyClient(liveSession(), http, sleeper = {})
            .addToPlaylist("pl", (0 until 250).map { "t$it" })
        assertEquals("250 tracks is three requests, not 250", 3, http.calls.size)
        val first = JSONObject(http.calls[0].body!!)
        assertEquals(100, first.getJSONArray("uris").length())
        assertEquals("spotify:track:t0", first.getJSONArray("uris").getString(0))
    }

    @Test fun `Spotify saved-track writes batch at 50`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, "{}")))
        SpotifyClient(liveSession(), http, sleeper = {})
            .saveTracks((0 until 120).map { "t$it" })
        assertEquals(3, http.calls.size)
        assertEquals(50, JSONObject(http.calls[0].body!!).getJSONArray("ids").length())
    }

    @Test fun `following artists puts the ids in the query, where Spotify wants them`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, "{}")))
        SpotifyClient(liveSession(), http, sleeper = {}).followArtists(listOf("a1", "a2"))
        assertTrue(http.calls[0].url.contains("ids=a1%2Ca2"))
        assertTrue(http.calls[0].url.contains("type=artist"))
    }

    @Test fun `Qobuz writes batch at 50 and go in the query string`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, """{"status":"success"}""")))
        QobuzClient(QobuzSession(token = "t", userId = "1"), http, sleeper = {})
            .addToPlaylist("pl", (0 until 130).map { "t$it" })
        assertEquals(3, http.calls.size)
        assertTrue(http.calls[0].url.contains("track_ids="))
        assertTrue(http.calls[0].url.contains("app_id="))
    }

    @Test fun `empty write lists send no requests at all`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, "{}")))
        val sp = SpotifyClient(liveSession(), http, sleeper = {})
        sp.saveTracks(emptyList())
        sp.saveAlbums(listOf(""))
        assertEquals(0, http.calls.size)
    }

    // ---------------------------------------------------------------- paging

    @Test fun `Spotify paging stops at the reported total`() {
        fun page(n: Int, total: Int) = FakeHttp.res(200,
            """{"total":$total,"items":[""" +
                (0 until n).joinToString(",") { """{"id":"p$it"}""" } + "]}")
        val http = FakeHttp(listOf(page(50, 60), page(10, 60), page(0, 60)))
        val all = SpotifyClient(liveSession(), http, sleeper = {}).playlists()
        assertEquals(60, all.size)
        assertEquals("it stopped without a third empty request", 2, http.calls.size)
    }

    @Test fun `Qobuz paging stops on a short page`() {
        val http = FakeHttp(listOf(FakeHttp.res(200,
            """{"tracks":{"items":[{"id":0,"title":"T0","duration":100},
                                   {"id":1,"title":"T1","duration":100}]}}""")))
        val all = QobuzClient(QobuzSession(token = "t"), http, sleeper = {}).savedTracks()
        assertEquals(2, all.size)
        assertEquals(1, http.calls.size)
    }

    @Test fun `a Spotify playlist page skips local files and episodes, naming them`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, """
            {"total":3,"items":[
              {"is_local":true,"track":{"name":"bootleg.flac"}},
              {"is_local":false,"track":{"id":"e","type":"episode","name":"Some Show"}},
              {"is_local":false,"track":{"id":"t","type":"track","name":"Song",
                "duration_ms":1000,"artists":[{"name":"A"}],"album":{"name":"R"},
                "external_ids":{}}}
            ]}"""), FakeHttp.res(200, """{"total":3,"items":[]}""")))
        val tracks = SpotifyClient(liveSession(), http, sleeper = {}).playlistTracks("p")
        assertEquals(3, tracks.size)
        assertEquals("local file", tracks[0].skip)
        assertEquals("podcast episode", tracks[1].skip)
        assertNull(tracks[2].skip)
    }
    // ------------------------------------------------------- barcode search
    // Spotify's album search returns SimplifiedAlbumObject with no
    // external_ids, so a candidate never carries a barcode and Match's barcode
    // tier could not fire. The `upc:` filter is the fix, and because the result
    // still has no barcode on it, THE FILTER IS THE EVIDENCE — the code is
    // stamped on so the tier can see it. JS twin: test/unit/clients.test.js.

    @Test fun `a Spotify barcode search uses the upc filter and stamps the result`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, """
            {"albums":{"items":[
              {"id":"sal","name":"Master of Puppets","total_tracks":8,
               "artists":[{"name":"Metallica"}]}
            ]}}""")))   // note: NO external_ids, exactly as Spotify sends
        val albums = SpotifyClient(liveSession(), http, sleeper = {})
            .searchByUpc("075992736121")
        assertTrue(http.calls[0].url.contains("q=upc%3A075992736121"))
        assertTrue(http.calls[0].url.contains("type=album"))
        assertEquals(1, albums.size)
        assertEquals("the searched-for barcode must be stamped on, or the tier cannot fire",
            "075992736121", albums[0].upc)
    }

    @Test fun `a barcode search that returns a crowd is not trusted as one`() {
        // A barcode identifies one release. If the filter hands back a pile it
        // is not filtering, and stamping would be a confidently wrong match.
        val items = (0 until 6).joinToString(",") {
            """{"id":"a$it","name":"Something Else","total_tracks":9,
                "artists":[{"name":"Nope"}]}"""
        }
        val http = FakeHttp(listOf(FakeHttp.res(200, """{"albums":{"items":[$items]}}""")))
        val albums = SpotifyClient(liveSession(), http, sleeper = {})
            .searchByUpc("075992736121")
        assertEquals(6, albums.size)
        assertTrue("nothing is stamped, so these are judged on title and artist",
            albums.all { it.upc.isEmpty() })
    }

    @Test fun `an empty barcode makes no request at all`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, "{}")))
        val sp = SpotifyClient(liveSession(), http, sleeper = {})
        assertEquals(emptyList<Album>(), sp.searchByUpc(""))
        assertEquals(emptyList<Album>(), sp.searchByUpc("   "))
        assertEquals(0, http.calls.size)
    }

    @Test fun `Qobuz searches the barcode as a plain query and needs no stamp`() {
        // Unlike Spotify's, Qobuz's album listings DO carry upc, so Match
        // compares the real codes itself.
        val http = FakeHttp(listOf(FakeHttp.res(200, """
            {"albums":{"items":[
              {"id":7,"title":"Master Of Puppets","upc":"075992736121",
               "tracks_count":8,"artist":{"name":"Metallica"}}
            ]}}""")))
        val albums = QobuzClient(QobuzSession(token = "t"), http, sleeper = {})
            .searchByUpc("075992736121")
        assertTrue(http.calls[0].url.contains("query=075992736121"))
        assertTrue(http.calls[0].url.contains("type=albums"))
        assertEquals("read from the response, not stamped",
            "075992736121", albums[0].upc)
    }

}
