package com.musicd.migrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.json.JSONObject

/** Both service clients, driven against a scripted fake HTTP layer. */
class ClientsTest {

    private fun liveSession() = SpotifySession(
        clientId = "cid", accessToken = "tok", refreshToken = "ref",
        expiresAt = System.currentTimeMillis() + 600_000, userId = "me")

    // ----------------------------------------------------------- the heap

    /**
     * An Http that answers with REAL-SHAPED Spotify album pages and watches
     * the heap between them.
     *
     * Sampling between pages is the whole trick: what killed the app was the
     * PEAK during the walk, not what was left at the end. A pager that
     * collects raw pages and lets the caller map them afterwards looks
     * innocent once it has returned.
     */
    private class HeapWatchingHttp(private val pages: Int, private val per: Int) : Http {
        var requests = 0
        var peakBytes = 0L
        private val baseline: Long

        init {
            baseline = used()
        }

        private fun used(): Long {
            val rt = Runtime.getRuntime()
            // Weakly-reachable garbage is not the thing being measured.
            System.gc()
            Thread.sleep(10)
            System.gc()
            return rt.totalMemory() - rt.freeMemory()
        }

        override fun request(
            method: String, url: String, headers: Map<String, String>, body: ByteArray?,
            contentType: String?, timeoutMs: Int
        ): HttpResponse {
            val grew = used() - baseline
            if (grew > peakBytes) peakBytes = grew
            requests++
            if (requests > pages) return HttpResponse(200, emptyMap(), """{"items":[],"total":0}""")
            return HttpResponse(200, emptyMap(), page((requests - 1) * per))
        }

        /**
         * One page of `/me/albums`, shaped like the real thing.
         *
         * `available_markets` is the point. Spotify sends about 180 country
         * codes on the album AND on every track of it unless a `market` is
         * given, which is what makes one saved album some 15KB of JSON.
         */
        private fun page(from: Int): String {
            val markets = (1..180).joinToString(",") { "\"M$it\"" }
            val sb = StringBuilder(per * 16000)
            sb.append("{\"total\":").append(pages * per).append(",\"items\":[")
            for (i in 0 until per) {
                val n = from + i
                if (i > 0) sb.append(',')
                sb.append("{\"added_at\":\"2024-01-01T00:00:00Z\",\"album\":{")
                sb.append("\"id\":\"al$n\",\"name\":\"Album Number $n\",")
                sb.append("\"total_tracks\":12,\"external_ids\":{\"upc\":\"00000000000$n\"},")
                sb.append("\"artists\":[{\"id\":\"ar$n\",\"name\":\"Artist $n\"}],")
                sb.append("\"available_markets\":[").append(markets).append("],")
                sb.append("\"tracks\":{\"items\":[")
                for (t in 1..12) {
                    if (t > 1) sb.append(',')
                    sb.append("{\"id\":\"t$n-$t\",\"name\":\"Track $t of album $n\",")
                    sb.append("\"duration_ms\":210000,\"available_markets\":[")
                    sb.append(markets).append("]}")
                }
                sb.append("]}}}")
            }
            sb.append("]}")
            return sb.toString()
        }
    }

    @Test fun `walking a big library does not hold every page in memory`() {
        // What this is about, from a real phone running 0.2.3:
        //
        //   the app hit a OutOfMemoryError: Failed to allocate a 664 byte
        //   allocation with 351648 free bytes and 343KB until OOM, target
        //   footprint 268435456, growth limit 268435456; giving up on
        //   allocation because <1% of heap free after GC
        //
        // A 256MB heap, gone before the run had matched a single album. It was
        // reading the target's saved albums: /me/albums returns the FULL album
        // object, ~15KB of JSON each once available_markets is counted, and
        // the pager kept every page until the walk finished.
        val http = HeapWatchingHttp(pages = 30, per = 50)
        val sp = SpotifyClient(liveSession(), http)

        val albums = sp.savedAlbums()

        assertEquals("every album still comes back", 1500, albums.size)
        assertEquals("Album Number 0", albums[0].title)
        assertEquals("and mapped, not raw", "al1499", albums[1499].id)
        val mb = http.peakBytes / (1024 * 1024)
        assertTrue(
            "the walk should stay small: 1,500 albums is about a megabyte of " +
            "Album objects, against ~22MB of JSON to parse. Peak growth was ${mb}MB",
            http.peakBytes < 40L * 1024 * 1024)
    }

    // ------------------------------------------------------- the token race

    /**
     * An Http that models Spotify's ROTATING refresh token: presenting the
     * same one twice is refused, exactly as the real endpoint refuses it.
     */
    private class RotatingTokenHttp : Http {
        val tokenCalls = java.util.concurrent.atomic.AtomicInteger(0)
        override fun request(
            method: String, url: String, headers: Map<String, String>, body: ByteArray?,
            contentType: String?, timeoutMs: Int
        ): HttpResponse {
            if (url.contains("/api/token")) {
                val form = body?.toString(Charsets.UTF_8).orEmpty()
                val n = tokenCalls.incrementAndGet()
                // A little latency, so the race is a race rather than a
                // theory: without the lock the other threads are inside here
                // before the winner has stored anything.
                Thread.sleep(50)
                if (n > 1 && form.contains("refresh_token=ref")) {
                    return HttpResponse(400, emptyMap(),
                        """{"error":"invalid_grant","error_description":"Refresh token revoked"}""")
                }
                return HttpResponse(200, emptyMap(),
                    """{"access_token":"AT1","refresh_token":"RT1","expires_in":3600}""")
            }
            return HttpResponse(200, emptyMap(), """{"albums":{"items":[]}}""")
        }
    }

    @Test fun `four workers hitting an expired token refresh it ONCE between them`() {
        // Spotify ROTATES refresh tokens: the first refresh invalidates the
        // one it was given. Lookups run four at a time, so with an expired
        // access token every worker in flight asks for a refresh at the same
        // moment -- and without the lock three of them present a token that
        // has just been revoked. A loser's answer can overwrite the winner's,
        // which does not fail a request: it destroys the sign-in.
        //
        // A Qobuz user_auth_token neither expires nor rotates, which is
        // exactly why a run into Qobuz never showed this and a run into
        // Spotify does.
        val http = RotatingTokenHttp()
        val saved = java.util.Collections.synchronizedList(ArrayList<SpotifySession>())
        val sp = SpotifyClient(
            liveSession().copy(accessToken = "stale",
                expiresAt = System.currentTimeMillis() - 1000),
            http, onTokens = { saved.add(it) })

        val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val threads = (1..4).map {
            Thread { try { sp.searchAlbums("a$it", "x") } catch (e: Throwable) { errors.add(e) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(20_000) }

        assertEquals("no worker was told its token was revoked: $errors", 0, errors.size)
        assertEquals("one refresh, shared, not four races for a single-use token",
            1, http.tokenCalls.get())
        assertEquals("and the rotated token is the one kept", "RT1", sp.session.refreshToken)
        assertEquals("persisted once", 1, saved.size)
    }

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
}
