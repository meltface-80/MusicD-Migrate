package com.musicd.migrate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The twin of test/unit/apple-music.test.js. Nothing here has talked to the
 * real API — there is no Apple Developer account in CI or where this was
 * written — so what it pins is what can be checked without one: the token is
 * a valid ES256 JWT, the request shapes match Apple's documentation, the
 * library-vs-catalogue id rule holds, and the rate-limit rules are the ones
 * CLAUDE.md sets for every client.
 */
class AppleMusicClientTest {

    private val pair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val pem = "-----BEGIN PRIVATE KEY-----\n" +
        Base64.getMimeEncoder().encodeToString(pair.private.encoded) +
        "\n-----END PRIVATE KEY-----\n"
    private val key = AppleMusicKey("ABCDE12345", "KEY1234567", pem)

    /** A scripted Http that also records headers, thread-safely. */
    private class Script(val answer: (Int, String) -> HttpResponse) : Http {
        data class Call(val method: String, val url: String, val headers: Map<String, String>,
                        val body: String?)
        val calls = java.util.Collections.synchronizedList(ArrayList<Call>())
        override fun request(method: String, url: String, headers: Map<String, String>,
                             body: ByteArray?, contentType: String?, timeoutMs: Int): HttpResponse {
            val n = synchronized(calls) {
                calls.add(Call(method, url, headers, body?.toString(Charsets.UTF_8)))
                calls.size - 1
            }
            return answer(n, url)
        }
    }

    private fun ok(body: String) = HttpResponse(200, emptyMap(), body)

    private fun client(http: Http, storefront: String = "gb", waits: MutableList<Long>? = null,
                       userToken: String = "mut") =
        AppleMusicClient(AppleMusicSession(key, userToken, storefront), http,
            sleeper = { ms -> waits?.add(ms); Thread.sleep(minOf(ms, 5)) })

    private fun param(url: String, name: String): List<String> =
        url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
            .map { URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                   URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
            .filter { it.first == name }.map { it.second }

    // ------------------------------------------------------------ the token

    @Test fun `the developer token is an ES256 JWT Apple can verify`() {
        val token = AppleMusicClient.developerToken(key, 1_700_000_000L)
        val (h, b, s) = token.split(".")
        val head = JSONObject(String(Base64.getUrlDecoder().decode(h)))
        val body = JSONObject(String(Base64.getUrlDecoder().decode(b)))
        assertEquals("ES256", head.getString("alg"))
        assertEquals("KEY1234567", head.getString("kid"))
        assertEquals("ABCDE12345", body.getString("iss"))
        assertEquals(1_700_000_000L, body.getLong("iat"))
        assertTrue(body.getLong("exp") - body.getLong("iat") <= 15_777_000L)

        // JOSE wants raw r||s, 64 bytes. The JVM produces DER, which Apple
        // rejects with a 401 that reads exactly like a bad key.
        val raw = Base64.getUrlDecoder().decode(s)
        assertEquals(64, raw.size)
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public)
            update("$h.$b".toByteArray())
            verify(rawToDer(raw))
        }
        assertTrue("the signature verifies against the public key", ok)
    }

    @Test fun `derToRaw pads a short r and strips a sign byte`() {
        // r = 0x00FF.. (33 bytes with sign byte), s = 0x01 (1 byte)
        val r = ByteArray(33).also { it[0] = 0; it[1] = 0xFF.toByte() }
        val der = byteArrayOf(0x30, (2 + 33 + 2 + 1).toByte(), 0x02, 33) + r +
            byteArrayOf(0x02, 1, 1)
        val out = AppleMusicClient.derToRaw(der)
        assertEquals(64, out.size)
        assertEquals(0xFF.toByte(), out[0])
        assertEquals(1.toByte(), out[63])
    }

    @Test fun `the key is checked field by field`() {
        assertEquals("ABCDE12345", AppleMusicKey.parse(" abcde12345 ", "key1234567", pem).teamId)
        expectMessage("Team ID") { AppleMusicKey.parse("short", "KEY1234567", pem) }
        expectMessage("Key ID") { AppleMusicKey.parse("ABCDE12345", "", pem) }
        expectMessage("BEGIN PRIVATE KEY") { AppleMusicKey.parse("ABCDE12345", "KEY1234567", "x") }
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val rsaPem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder().encodeToString(rsa.private.encoded) + "\n-----END PRIVATE KEY-----"
        expectMessage("MusicKit") { AppleMusicKey.parse("ABCDE12345", "KEY1234567", rsaPem) }
    }

    @Test fun `every request carries both tokens`() {
        val http = Script { _, _ -> ok("""{"data":[]}""") }
        client(http).searchByIsrc("gbaye0601498")
        val c = http.calls[0]
        assertTrue(c.headers["Authorization"]!!.matches(Regex("Bearer [\\w-]+\\.[\\w-]+\\.[\\w-]+")))
        assertEquals("mut", c.headers["Music-User-Token"])
        assertTrue(c.url.contains("/v1/catalog/gb/songs?"))
        assertEquals(listOf("GBAYE0601498"), param(c.url, "filter[isrc]"))
    }

    @Test fun `the storefront is asked for once`() {
        val http = Script { n, _ ->
            if (n == 0) ok("""{"data":[{"id":"us","type":"storefronts"}]}""") else ok("""{"data":[]}""")
        }
        val am = client(http, storefront = "")
        am.searchByUpc("0602547")
        am.searchByUpc("0602548")
        assertTrue(http.calls[0].url.endsWith("/v1/me/storefront"))
        assertTrue(http.calls[1].url.contains("/v1/catalog/us/albums?"))
        assertEquals(3, http.calls.size)
    }

    // ---------------------------------------------------- library vs catalogue

    private val librarySong = """{"id":"i.abc","type":"library-songs","attributes":{
        "name":"Entropy","artistName":"Beach Bunny","albumName":"Emotional Creature",
        "durationInMillis":221000,"playParams":{"id":"i.abc","isLibrary":true}},
        "relationships":{"catalog":{"data":[{"id":"1613600188","type":"songs",
        "attributes":{"isrc":"USQE92100257","name":"Entropy"}}]}}}"""

    @Test fun `a library song reports its CATALOGUE id and the catalogue's ISRC`() {
        val t = appleTrack(JSONObject(librarySong))!!
        assertEquals("1613600188", t.id)
        assertEquals("USQE92100257", t.isrc)
        assertEquals(listOf("Beach Bunny"), t.artists)
        assertEquals(221000L, t.durationMs)
    }

    @Test fun `an uploaded song with no catalogue entry keeps its library id`() {
        val t = appleTrack(JSONObject("""{"id":"i.up","type":"library-songs",
            "attributes":{"name":"Demo","artistName":"Me","durationInMillis":1000}}"""))!!
        assertEquals("i.up", t.id)
        assertEquals("", t.isrc)
    }

    @Test fun `a library album takes its barcode from the catalogue`() {
        val a = appleAlbum(JSONObject("""{"id":"l.xyz","type":"library-albums","attributes":{
            "name":"Rec","artistName":"Band","trackCount":10,"releaseDate":"1999-01-02"},
            "relationships":{"catalog":{"data":[{"id":"555","attributes":{"upc":"0602547"}}]}}}"""))
        assertEquals(Album("555", "0602547", "Rec", listOf("Band"), 10, 1999), a)
    }

    @Test fun `a music video in a playlist is skipped by name`() {
        val v = appleFromPlaylistItem(JSONObject(
            """{"id":"i.v","type":"library-music-videos","attributes":{"name":"Clip"}}"""))!!
        assertEquals("music video", v.skip)
        assertEquals("Clip", v.title)
    }

    @Test fun `library reads ask for the catalogue and follow next to the end`() {
        val http = Script { n, _ ->
            if (n == 0) ok("""{"data":[$librarySong],"next":"/v1/me/library/songs?offset=100"}""")
            else ok("""{"data":[{"id":"i.def","type":"library-songs","attributes":{"name":"B"}}]}""")
        }
        val tracks = client(http).savedTracks()
        assertEquals(2, tracks.size)
        assertEquals(listOf("catalog"), param(http.calls[0].url, "include"))
        // The second page keeps what `next` dropped, and does not repeat the
        // offset `next` already carries.
        assertEquals(listOf("100"), param(http.calls[1].url, "offset"))
        assertEquals(listOf("catalog"), param(http.calls[1].url, "include"))
    }

    @Test fun `a playlist the user cannot edit reads as a followed one`() {
        val http = Script { _, _ -> ok("""{"data":[
            {"id":"p.1","attributes":{"name":"Mine","canEdit":true}},
            {"id":"p.2","attributes":{"name":"Apple's","canEdit":false}}]}""") }
        val (mine, theirs) = client(http).playlists()
        assertEquals("", mine.ownerId)
        assertTrue(theirs.ownerId.isNotEmpty())
        assertNull("unknown, not zero", mine.trackCount)
    }

    @Test fun `albumTracks reads a library album from the library and a catalogue one from the catalogue`() {
        val http = Script { _, _ -> ok("""{"data":[]}""") }
        val am = client(http)
        am.albumTracks("l.xyz")
        am.albumTracks("555")
        assertTrue(http.calls[0].url.contains("/v1/me/library/albums/l.xyz/tracks"))
        assertTrue(http.calls[1].url.contains("/v1/catalog/gb/albums/555/tracks"))
    }

    @Test fun `album search results carry their own barcode`() {
        val http = Script { _, _ -> ok("""{"results":{"albums":{"data":[{"id":"555",
            "type":"albums","attributes":{"name":"Rec","artistName":"Band","upc":"0602547"}}]}}}""") }
        val a = client(http).searchAlbums("Rec", "Band").single()
        assertEquals("0602547", a.upc)
        assertEquals(listOf("Rec Band"), param(http.calls[0].url, "term"))
        assertEquals(listOf("albums"), param(http.calls[0].url, "types"))
    }

    // ------------------------------------------------------------------ writes

    @Test fun `library writes batch ids into the query by type`() {
        val http = Script { _, _ -> HttpResponse(202, emptyMap(), "") }
        val am = client(http)
        am.saveAlbums((0 until 120).map { "$it" })
        assertEquals(3, http.calls.size)
        assertEquals("POST", http.calls[0].method)
        assertEquals(50, param(http.calls[0].url, "ids[albums]").single().split(",").size)
        am.saveTracks(listOf("9"))
        assertEquals(listOf("9"), param(http.calls[3].url, "ids[songs]"))
    }

    @Test fun `a playlist is created private and filled with catalogue songs in order`() {
        val http = Script { n, _ ->
            if (n == 0) HttpResponse(201, emptyMap(),
                """{"data":[{"id":"p.new","attributes":{"name":"Mix"}}]}""")
            else HttpResponse(204, emptyMap(), "")
        }
        val am = client(http)
        assertEquals("p.new", am.createPlaylist("Mix", "from Spotify", true).id)
        val made = JSONObject(http.calls[0].body!!)
        assertTrue("never published", !made.getJSONObject("attributes").has("isPublic"))
        am.addToPlaylist("p.new", listOf("3", "1", "2"))
        assertTrue(http.calls[1].url.endsWith("/v1/me/library/playlists/p.new/tracks"))
        val data = JSONObject(http.calls[1].body!!).getJSONArray("data")
        assertEquals(listOf("3", "1", "2"), (0 until data.length()).map {
            data.getJSONObject(it).getString("id") })
        assertEquals("songs", data.getJSONObject(0).getString("type"))
    }

    @Test fun `artists are declared unsupported, and a stray call is loud`() {
        val am = client(Script { _, _ -> ok("{}") })
        assertTrue(am.unsupported["artists"]!!.contains("follow an artist"))
        expectMessage("follow an artist") { am.followArtists(listOf("1")) }
    }

    // ------------------------------------------------------- refusals and 429s

    @Test fun `401 is the developer token and 403 the user's sign-in, both auth errors`() {
        try {
            client(Script { _, _ -> HttpResponse(401, emptyMap(), "") }).searchByIsrc("X")
            fail("expected an AuthError")
        } catch (e: AuthError) { assertTrue(e.message!!.contains("Team ID")) }
        try {
            client(Script { _, _ -> HttpResponse(403, emptyMap(),
                """{"errors":[{"detail":"Forbidden"}]}""") }).searchByIsrc("X")
            fail("expected an AuthError")
        } catch (e: AuthError) { assertTrue(e.message!!.contains("Connect Apple Music again")) }
    }

    @Test fun `no user token is an auth error before any request`() {
        val http = Script { _, _ -> ok("{}") }
        try {
            client(http, userToken = "").searchByIsrc("X")
            fail("expected an AuthError")
        } catch (e: AuthError) { /* expected */ }
        assertEquals(0, http.calls.size)
    }

    @Test fun `Retry-After is obeyed, reported, and turns on pacing`() {
        val waits = ArrayList<Long>()
        val http = Script { n, _ ->
            if (n == 0) HttpResponse(429, mapOf("retry-after" to "2"), "") else ok("""{"data":[]}""")
        }
        val am = client(http, waits = waits)
        assertEquals("an unthrottled client is never paced", 0L, am.paceMsForTest())
        am.searchByIsrc("X")
        assertEquals(2, http.calls.size)
        assertTrue("never less than it asked for: $waits", waits.any { it >= 2000 })
        assertTrue(am.paceMsForTest() > 0)
    }

    @Test fun `a Retry-After longer than a run will hold fails at once, quoting it`() {
        val http = Script { _, _ -> HttpResponse(429, mapOf("retry-after" to "600"), "") }
        try {
            client(http).searchByIsrc("X")
            fail("expected a RateLimitError")
        } catch (e: RateLimitError) { assertTrue(e.message!!.contains("600s")) }
        assertEquals(1, http.calls.size)
    }

    @Test fun `one worker's 429 holds every worker, not just itself`() {
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val http = Script { _, _ ->
            if (first.getAndSet(false)) HttpResponse(429, mapOf("retry-after" to "0.6"), "")
            else ok("""{"data":[]}""")
        }
        val am = AppleMusicClient(AppleMusicSession(key, "mut", "gb"), http)
        val a = Thread { am.searchByIsrc("A") }.apply { start() }
        Thread.sleep(50)
        val t0 = System.currentTimeMillis()
        am.searchByIsrc("B") // a second worker, arriving during the hold
        // 500ms, not just "some wait": pacing alone delays it ~250ms.
        assertTrue("the second worker waited out the first one's 429",
            System.currentTimeMillis() - t0 >= 500)
        a.join()
        assertEquals(3, http.calls.size)
    }

    // ---------------------------------------------------------------- helpers

    private fun expectMessage(part: String, block: () -> Unit) {
        try {
            block()
            fail("expected a refusal mentioning \"$part\"")
        } catch (e: Exception) {
            assertTrue("\"${e.message}\" should mention \"$part\"", e.message!!.contains(part))
        }
    }

    private fun rawToDer(raw: ByteArray): ByteArray {
        fun int(b: ByteArray): ByteArray {
            val v = BigInteger(1, b).toByteArray()
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = int(raw.copyOfRange(0, 32)) + int(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
