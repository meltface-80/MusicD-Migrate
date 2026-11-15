package com.musicd.migrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.json.JSONObject


/** Spotify's rate limit: obeying a 429, holding every worker, pacing, and
 *  asking for an album a second way. */
class SpotifyRateLimitTest {

    private fun liveSession() = SpotifySession(
        clientId = "cid", accessToken = "tok", refreshToken = "ref",
        expiresAt = System.currentTimeMillis() + 600_000, userId = "me")


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

    @Test fun `a Retry-After longer than a run will hold fails at once and quotes the number`() {
        // The bug this replaces: the wait was capped with minOf, so "wait 600
        // seconds" became a 60-second wait and another 429 -- five times, then
        // a give-up. CLAUDE.md's rule is that Retry-After is authoritative and
        // guessing shorter is what turns one 429 into a cascade; waiting less
        // than asked was doing exactly that.
        val http = FakeHttp(listOf(FakeHttp.res(429, "", mapOf("retry-after" to "600"))))
        val waits = ArrayList<Long>()
        val sp = SpotifyClient(liveSession(), http, onRateLimit = { waits.add(it) },
            sleeper = { fail("it must not sleep before failing") })
        try {
            sp.me()
            fail("a delay longer than the run will hold for must fail at once")
        } catch (e: RateLimitError) {
            assertTrue("it quotes what Spotify asked for: ${e.message}",
                e.message!!.contains("asked this app to wait 600s"))
            assertTrue("and says what to do about it",
                e.message!!.contains("wait and re-run"))
        }
        assertEquals("it did not sleep at all", 0, waits.size)
        assertEquals("and it did not ask again", 1, http.calls.size)
    }

    @Test fun `a 429 holds every worker, not just the one that got it`() {
        // Four workers each backing off privately all resume at the same
        // instant and earn the next 429 together. Spotify counts per
        // APPLICATION, so the hold has to be shared -- which is what stopped a
        // rate-limited run from ever getting going again.
        val http = FakeHttp(listOf(
            FakeHttp.res(429, "", mapOf("retry-after" to "1")),
            FakeHttp.res(200, """{"id":"me","display_name":"Me"}"""),
            FakeHttp.res(200, """{"id":"me","display_name":"Me"}""")))
        val slept = ArrayList<Long>()
        val sp = SpotifyClient(liveSession(), http, sleeper = { slept.add(it) })

        sp.me()                      // takes the 429 and sets the hold
        val afterFirst = slept.size
        assertTrue("the backoff was slept", afterFirst >= 1)

        // The SECOND call sees no 429 of its own and must still wait out the
        // hold the first one set. Without the shared hold it sleeps not at all.
        //
        // Counted as "more than before" rather than an exact total: a 429 also
        // turns PACING on, so the second call legitimately sleeps twice — once
        // on the hold and once on its paced slot. Pinning the total made this
        // test fail when pacing was added, which is the assertion's fault, not
        // the behaviour's.
        sp.me()
        assertTrue("a worker that saw no 429 still waited out the shared hold: $slept",
            slept.size > afterFirst)
        assertTrue("and it waited a real amount: $slept", slept.drop(afterFirst).any { it > 0 })
    }

    @Test fun `a 429 makes the client ask more slowly from then on`() {
        // A real 9,635-album Roon library into Spotify: four workers burst
        // past the rolling window in the first few requests, earn a 429 with
        // a 20s Retry-After, wait it out, burst again. The page said "rate
        // limiting this app -- waiting 20s (3 so far)" with the counter still
        // at zero. Obeying the delay is necessary but not sufficient --
        // something has to stop us asking too fast.
        val http = FakeHttp(listOf(
            FakeHttp.res(429, "", mapOf("retry-after" to "1")),
            FakeHttp.res(200, """{"id":"me","display_name":"Me"}"""),
            FakeHttp.res(429, "", mapOf("retry-after" to "1")),
            FakeHttp.res(200, """{"id":"me","display_name":"Me"}""")))
        val sp = SpotifyClient(liveSession(), http, sleeper = {})
        assertEquals("a run that has never been throttled is not paced",
            0L, sp.paceMsForTest())

        sp.me()
        val first = sp.paceMsForTest()
        assertTrue("after a 429 it paces itself: $first", first > 0)

        // A second 429 doubles it: the gap that works is unknown and the
        // Client ID is shared, so it converges rather than guessing.
        sp.me()
        assertEquals("and doubles on the next one", first * 2, sp.paceMsForTest())
    }

    @Test fun `pacing spaces the workers out instead of letting them all fire at once`() {
        // Claimed under the lock, before the sleep, so four callers take four
        // DIFFERENT slots. Reading a shared "next" and then sleeping would
        // give them all the same one, which is the burst this exists to stop.
        val http = FakeHttp(listOf(FakeHttp.res(200, """{"id":"me"}""")))
        val slept = java.util.Collections.synchronizedList(ArrayList<Long>())
        val sp = SpotifyClient(liveSession(), http, sleeper = { slept.add(it) })
        sp.setPaceForTest(40L)

        for (i in 0 until 4) sp.me()
        // The first takes the slot that is already due; the rest each wait one
        // more gap than the last.
        assertTrue("the workers were spread out rather than fired together: $slept",
            slept.count { it > 0 } >= 3)
    }

    @Test fun `a long clean run eases the pacing back off`() {
        // One bad patch must not slow the rest of a two-hour migration for
        // ever.
        val http = FakeHttp(listOf(FakeHttp.res(200, """{"id":"me"}""")))
        val sp = SpotifyClient(liveSession(), http, sleeper = {})
        sp.setPaceForTest(1_000L)

        for (i in 0 until SpotifyClient.PACE_DECAY_AFTER) sp.me()
        assertTrue("it eased back: ${sp.paceMsForTest()}", sp.paceMsForTest() < 1_000L)
    }

    @Test fun `a healthy run is never paced at all`() {
        // The cost of this has to be zero for everyone who is not being
        // throttled -- Qobuz, a small library, the tests.
        val http = FakeHttp(listOf(FakeHttp.res(200, """{"id":"me"}""")))
        val slept = java.util.Collections.synchronizedList(ArrayList<Long>())
        val sp = SpotifyClient(liveSession(), http, sleeper = { slept.add(it) })
        for (i in 0 until 20) sp.me()
        assertEquals(0L, sp.paceMsForTest())
        assertEquals("nothing was ever slept for pacing", 0, slept.size)
        // The slot is untouched too, not merely harmless: the `paceMs > 0`
        // guard is what keeps an unthrottled run out of the pacing path
        // altogether. Without this the guard is an equivalent mutant — the
        // slot maths happens to be a no-op at a gap of zero — and this
        // assertion is what catches it.
        assertEquals("no slot was ever claimed", 0L, sp.nextSlotForTest())
    }

    @Test fun `an album the filtered query misses is asked for again as free text`() {
        // album:"X" artist:"Y" is a quoted AND of two field filters, so any
        // difference of spelling on either side returns zero rows and the
        // matcher never sees a candidate. Spotify calls them "Thirty Seconds
        // to Mars"; Roon says "30 Seconds to Mars". On a real 9,681-album
        // library 1,969 albums came back "the search returned nothing" against
        // 908 for the same library on Qobuz, whose search is plain text.
        val hit = """{"albums":{"items":[{"id":"sa","name":"A Beautiful Lie",""" +
            """"artists":[{"name":"Thirty Seconds to Mars"}],"total_tracks":12}]}}"""
        val http = object : Http {
            val queries = ArrayList<String>()
            override fun request(
                method: String, url: String, headers: Map<String, String>, body: ByteArray?,
                contentType: String?, timeoutMs: Int
            ): HttpResponse {
                val q = java.net.URLDecoder.decode(
                    url.substringAfter("q=").substringBefore("&"), "UTF-8")
                queries.add(q)
                // Only the loose query answers.
                return HttpResponse(200, emptyMap(),
                    if (q.contains("artist:")) """{"albums":{"items":[]}}""" else hit)
            }
        }
        val sp = SpotifyClient(liveSession(), http, sleeper = {})
        val out = sp.searchAlbums("A Beautiful Lie", "30 Seconds to Mars")

        assertEquals("it asked twice", 2, http.queries.size)
        assertTrue("the filtered query first: ${http.queries[0]}",
            http.queries[0].contains("album:\"A Beautiful Lie\"") &&
                http.queries[0].contains("artist:\"30 Seconds to Mars\""))
        assertEquals("then free text, no field filters and no quotes",
            "A Beautiful Lie 30 Seconds to Mars", http.queries[1])
        assertEquals("and the candidate came back", 1, out.size)
        assertEquals("A Beautiful Lie", out[0].title)
    }

    @Test fun `the filtered query answering costs no second request`() {
        // The fallback must be free for the albums found the first way,
        // because it is one extra request per album on a rate-limited service.
        val http = FakeHttp(listOf(FakeHttp.res(200,
            """{"albums":{"items":[{"id":"sa","name":"Rumours",""" +
            """"artists":[{"name":"Fleetwood Mac"}],"total_tracks":11}]}}""")))
        val sp = SpotifyClient(liveSession(), http, sleeper = {})
        val out = sp.searchAlbums("Rumours", "Fleetwood Mac")
        assertEquals(1, out.size)
        assertEquals("asked once", 1, http.calls.size)
    }

    @Test fun `with no artist there is no looser question to ask`() {
        // The loose query would be the title on its own, which is what the
        // filtered one already asked. Asking it twice is a wasted request.
        val http = FakeHttp(listOf(FakeHttp.res(200, """{"albums":{"items":[]}}""")))
        val sp = SpotifyClient(liveSession(), http, sleeper = {})
        assertEquals(emptyList<Album>(), sp.searchAlbums("Obscure B-side", ""))
        assertEquals("asked once, not twice", 1, http.calls.size)
    }
}
