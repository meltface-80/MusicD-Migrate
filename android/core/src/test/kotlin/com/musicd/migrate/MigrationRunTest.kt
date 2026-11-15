package com.musicd.migrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A run as a whole: its circuit breakers, rate-limit notices, writing as it
 *  goes, and the album tiers that only show up across a run. */
class MigrationRunTest : MigrationFixtures() {

    @Test fun `a check that never once works stops the run rather than costing an hour`() {
        // 0.2.0 did the opposite: the corroboration read was broken and the
        // run carried on regardless, for forty minutes and three thousand
        // searches against a rate-limited API, to report 2325 albums "not
        // found" -- which was not about the library at all. A read that fails
        // EVERY time is the same category as a dead sign-in, which CLAUDE.md
        // already says must stop a run.
        val many = (0 until Migration.UNREADABLE_LIMIT + 20).map { i ->
            alb(id = "qal$i", title = "Record $i", upc = "")
        }.toMutableList()
        val source = FakeService("qobuz", libAlbums = many)
        source.albumTracksFails = true
        val target = FakeService("spotify", catalogueAlbums = many.mapIndexed { i, a ->
            Album("sal$i", "", a.title, listOf("Metallica"), 8)
        }.toMutableList())
        for (i in many.indices) target.albumTrackListings["sal$i"] = listing("Battery")

        val e = try {
            run(source, target, NOTHING.copy(doAlbums = true, concurrency = 1))
            fail("the run should have stopped"); null
        } catch (e: BrokenRead) {
            e
        }
        val message = e?.message.orEmpty()
        assertTrue("it says what happened: $message",
            message.contains("could not be checked and not one could"))
        assertTrue("the user is told where to look", message.contains("not your library"))
        assertTrue("and how to carry on without it", message.contains("track listing"))
        assertTrue("it stopped early: ${target.searchCount.get()} searches, not ${many.size}",
            target.searchCount.get() <= Migration.UNREADABLE_LIMIT + 1)
    }

    @Test fun `a run whose searches never once work stops instead of grinding for days`() {
        // "Roon to Spotify seems unresponsive", from a real 9,635-album
        // library. Every search was being rate limited, every one waited out
        // its backoff and was then recorded as a plain miss, and the progress
        // counter crawled with nothing on the page to say why. Days to report
        // a library as absent.
        //
        // The same rule as the corroboration breaker above, and deliberately
        // a SEPARATE pair of counters: a run where searches work and the
        // listing check is broken must still trip that one.
        val many = (0 until Migration.UNREADABLE_LIMIT + 20).map { i ->
            alb(id = "qal$i", title = "Record $i", upc = "")
        }.toMutableList()
        val source = FakeService("qobuz", libAlbums = many)
        val target = FakeService("spotify")
        target.albumSearchFails = { true }

        val e = try {
            run(source, target, NOTHING.copy(doAlbums = true, concurrency = 1))
            fail("the run should have stopped"); null
        } catch (e: BrokenRead) {
            e
        }
        val message = e?.message.orEmpty()
        assertTrue("it says what happened: $message",
            message.contains("searches failed and not one succeeded"))
        assertTrue("and it quotes what the service actually said",
            message.contains("rate limiting this app"))
        assertTrue("because none of them was cached",
            message.contains("re-running picks up where this left off"))
    }

    @Test fun `one search working disarms the search breaker for good`() {
        // Proof the breaker is armed by "no search has EVER worked" and not
        // merely by a count of failures. MORE than the limit fail here, and
        // the run still finishes, because the first one answered.
        val many = mutableListOf(alb(id = "qgood", title = "Reachable", upc = ""))
        for (i in 0 until Migration.UNREADABLE_LIMIT + 20) {
            many.add(alb(id = "qal$i", title = "Record $i", upc = ""))
        }
        val source = FakeService("qobuz", libAlbums = many)
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sgood", "", "Reachable", listOf("Metallica"), 8)))
        target.albumSearchFails = { it != "Reachable" }

        val r = run(source, target,
            NOTHING.copy(doAlbums = true, concurrency = 1, corroborate = false))
        assertEquals("the one whose search answered", 1, r.counts["matched"])
        assertEquals("every other search could not be made", many.size - 1,
            r.counts["failed"])
        assertTrue("and there are more of them than the limit",
            (r.counts["failed"] ?: 0) > Migration.UNREADABLE_LIMIT)
    }

    @Test fun `a search that comes back EMPTY is a miss, not a failure`() {
        // The distinction the whole change rests on. "We asked and there is
        // nothing" is a real answer: amber, cached, and not counted against
        // the breaker. Only "we could not ask" is red.
        val source = FakeService("qobuz",
            libAlbums = mutableListOf(alb(id = "qa", upc = "")))
        val target = FakeService("spotify")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertNull(r.counts["failed"])
        assertEquals(1, r.counts["unmatched"])
        assertNotNull("and a real miss IS cached, so the next run does not pay again",
            r.store.cachedMatch("qobuz", "qa", "spotify", "album"))
    }

    @Test fun `a failed barcode search does not matter once the title search finds it`() {
        // The failure only counts if nothing matched in the end. Otherwise a
        // service with a flaky `upc:` filter would turn every correct match
        // into a red row.
        val source = FakeService("qobuz",
            libAlbums = mutableListOf(alb(id = "qa", upc = "0123456789012")))
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sa", "", "Master Of Puppets", listOf("Metallica"), 8)))
        target.upcSearchFails = true

        val r = run(source, target, NOTHING.copy(doAlbums = true, corroborate = false))
        assertEquals(1, r.counts["matched"])
        assertNull("the barcode search failing is irrelevant once the title search answered",
            r.counts["failed"])
    }

    @Test fun `a service holding the run says so on the page`() {
        // "Roon to Spotify seems unresponsive." The progress label only
        // changes when an album FINISHES, so a run whose every search is held
        // for thirty seconds shows a counter that does not move and no reason
        // at all. onRateLimit existed on both clients in both languages and
        // was passed by nothing but the tests -- dead code in production,
        // which is why a throttled run and a hung one looked identical.
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb(id = "qa")))
        val target = FakeService("spotify")
        val store = MemoryStore()
        store.createJob("job1", "a->b", "{}", false)
        val m = Migration(source, target, store, "job1", NOTHING)

        m.noteRateLimit(30_000)
        val first = store.job("job1")!!.progressJson
        assertTrue("it names the service and what is happening: $first",
            first.contains("spotify is rate limiting this app"))
        assertTrue("and how long", first.contains("waiting 30s"))
        assertTrue("and how many times", first.contains("1 so far"))
        assertTrue("and the count is on the progress", first.contains("\"rateLimits\":1"))

        m.noteRateLimit(5_000)
        val second = store.job("job1")!!.progressJson
        assertTrue("they add up: $second", second.contains("\"rateLimits\":2"))
        assertTrue(second.contains("2 so far"))
    }

    @Test fun `the progress carries the field names the page reads`() {
        // public/app.js is the authority on every field name, and it reads
        // progress.searches, progress.cacheHits and progress.rateLimits.
        // Nothing else checks this: ContractTest catches a renamed ROUTE or
        // option, not a renamed FIELD.
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb(id = "qa")))
        val target = FakeService("spotify")
        val r = run(source, target, NOTHING.copy(doAlbums = true))
        val progress = r.store.job("job1")!!.progressJson
        for (field in listOf("phase", "step", "label", "done", "total", "counts",
                             "searches", "cacheHits", "rateLimits")) {
            assertTrue("the page reads progress.$field, and it is missing: $progress",
                progress.contains("\"$field\":"))
        }
    }

    @Test fun `matches are written DURING the phase, not banked until the end`() {
        // THE "nothing appeared in Spotify" BUG. write() ran once, after the
        // whole matching loop, so a run whose process was KILLED -- Android's
        // six-hour foreground cap, an OOM, a reboot -- wrote nothing at all,
        // however many albums it had matched. No catch block runs for a killed
        // process, so the only thing that can save those matches is having
        // already written them.
        //
        // Into Qobuz the phase finishes and the write happens, which is why
        // that direction looked fine. Into Spotify a 9,635-album library takes
        // hours against a rate-limited search.
        val many = (0 until 120).map { i ->
            alb(id = "qal$i", title = "Record $i", upc = "")
        }.toMutableList()
        val source = FakeService("qobuz", libAlbums = many)
        val target = FakeService("spotify", catalogueAlbums = many.mapIndexed { i, a ->
            Album("sal$i", "", a.title, listOf("Metallica"), 8)
        }.toMutableList())

        // Watched from INSIDE the run: what had been written by the time the
        // hundredth search went out. Asserting after the run cannot tell an
        // incremental write from one at the end.
        var writtenMidRun = -1
        var searches = 0
        target.onAlbumSearch = {
            if (++searches == 100) writtenMidRun = target.writtenAlbums.size
        }

        val r = run(source, target,
            NOTHING.copy(doAlbums = true, concurrency = 1, corroborate = false))
        assertEquals(120, r.counts["matched"])
        assertTrue("a full batch was written while the run was still going, got $writtenMidRun",
            writtenMidRun >= Migration.WRITE_BATCH)
        assertEquals("and all of them landed", 120, target.writtenAlbums.size)
    }

    @Test fun `a run stopped with less than a batch matched still keeps those`() {
        // The other half, and the one the mid-phase flush cannot cover: five
        // matches held when the run is cancelled are fewer than a batch, so
        // only a flush on the way out writes them. 0.3.0's error text already
        // promised "nothing already matched has been lost" -- this is what
        // makes that true.
        val many = (0 until 10).map { i ->
            alb(id = "qal$i", title = "Record $i", upc = "")
        }.toMutableList()
        val source = FakeService("qobuz", libAlbums = many)
        val target = FakeService("spotify", catalogueAlbums = many.mapIndexed { i, a ->
            Album("sal$i", "", a.title, listOf("Metallica"), 8)
        }.toMutableList())

        val store = MemoryStore()
        store.createJob("job1", "a->b", "{}", false)
        val m = Migration(source, target, store, "job1",
            NOTHING.copy(doAlbums = true, concurrency = 1, corroborate = false))
        var searches = 0
        target.onAlbumSearch = { if (++searches >= 5) m.cancel() }

        try {
            m.run()
            fail("the run should have been cancelled")
        } catch (e: Cancelled) {
            // expected
        }
        assertTrue("the handful matched before the stop was written, not stranded",
            target.writtenAlbums.size > 0)
        assertTrue("and it really did stop early", target.writtenAlbums.size < many.size)
        assertTrue("fewer than a batch, so only the flush on the way out did it",
            target.writtenAlbums.size < Migration.WRITE_BATCH)
    }

    @Test fun `writing in batches costs no more requests than writing once`() {
        // The reason incremental writing is free: the clients already chunk at
        // the endpoint maximum, so flushing a full batch makes exactly the
        // request that batch would have made at the end anyway.
        val many = (0 until 100).map { i ->
            alb(id = "qal$i", title = "Record $i", upc = "")
        }.toMutableList()
        val source = FakeService("qobuz", libAlbums = many)
        val target = FakeService("spotify", catalogueAlbums = many.mapIndexed { i, a ->
            Album("sal$i", "", a.title, listOf("Metallica"), 8)
        }.toMutableList())

        val r = run(source, target, NOTHING.copy(doAlbums = true, corroborate = false))
        assertEquals(100, r.counts["matched"])
        assertEquals("every one landed", 100, target.writtenAlbums.size)
        assertEquals("two batches of fifty, exactly as one final write would have made",
            2, target.saveAlbumCalls.get())
    }

    @Test fun `one album corroborating disarms that for good`() {
        // The breaker must not fire on a healthy run. Proof that it is armed
        // by "the check has NEVER worked" and not merely by a count of
        // failures: the same library, with the first album readable, runs to
        // the end.
        val many = mutableListOf(alb(id = "qgood", title = "Readable", upc = ""))
        for (i in 0 until Migration.UNREADABLE_LIMIT + 20) {
            many.add(alb(id = "qal$i", title = "Record $i", upc = ""))
        }
        val source = FakeService("qobuz", libAlbums = many)
        source.albumTrackListings["qgood"] = listing("Aaa", "Bbb")
        // Every album is FOUND on the other side; only the first one's
        // listing can be read. So the rest are `unreadable` -- the very thing
        // that is counted -- and there are more of them than the limit.
        val cands = mutableListOf(Album("sgood", "", "Readable", listOf("Metallica"), 2))
        for (i in 1 until many.size) {
            cands.add(Album("sal$i", "", many[i].title, listOf("Metallica"), 8))
        }
        val target = FakeService("spotify", catalogueAlbums = cands)
        target.albumTrackListings["sgood"] = listing("Aaa", "Bbb")

        val r = run(source, target, NOTHING.copy(doAlbums = true, concurrency = 1))
        assertEquals("the one that could be checked", 1, r.counts["matched"])
        assertEquals("every other album is a failed CHECK, and there are more than the limit",
            many.size - 1, r.counts["failed"])
        assertTrue("so the run only finished because one success disarmed the breaker",
            (r.counts["failed"] ?: 0) > Migration.UNREADABLE_LIMIT)
    }

    @Test fun `a live tag is accepted only when the track listing agrees`() {
        // The owner's decision, and the reason it is safe: "Rio" against "Rio
        // (Live)" is a title-and-artist match, which this app refuses on
        // principle -- so the album's own TRACK LISTING has to carry it,
        // exactly as it does for any other barcode-less album.
        val mine = listing("One", "Two", "Three", "Four")
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(id = "qal", title = "Rio", upc = "", trackCount = null)))
        source.albumTrackListings["qal"] = mine
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Rio (Live)", listOf("Metallica"), null)))
        target.albumTrackListings["sal"] = mine

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(r.items.firstOrNull()?.note, 1, r.counts["matched"])
        val note = r.items[0].note.orEmpty()
        assertTrue(note, note.contains("the title differs"))
        assertTrue(note, note.contains("\"Rio (Live)\""))
        assertTrue(note, note.contains("which is the evidence that decides it"))
        assertEquals("the listing carried it, and the method says so",
            "tracklist", r.items[0].method)
    }

    @Test fun `a live tag is refused when the listing disagrees, keeping the title reason`() {
        // A live record's tracks are usually tagged "(Live)" too, and a
        // canonical "one live" is not "one" -- which is what stops a live
        // album passing as the studio one.
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(id = "qal", title = "Rio", upc = "", trackCount = null)))
        source.albumTrackListings["qal"] = listing("One", "Two", "Three", "Four")
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Rio (Live)", listOf("Metallica"), null)))
        target.albumTrackListings["sal"] =
            listing("One (Live)", "Two (Live)", "Three (Live)", "Nine")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertNull(r.counts["matched"])
        assertTrue(r.items[0].note.orEmpty(),
            r.items[0].note.orEmpty().contains("the closest that artist has is \"Rio (Live)\""))
    }

    @Test fun `with the listing check off a live tag is refused and costs no reads`() {
        // The other half of the owner's decision: this tier does NOTHING
        // unless the track listing is being read.
        val mine = listing("One", "Two", "Three", "Four")
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(id = "qal", title = "Rio", upc = "", trackCount = null)))
        source.albumTrackListings["qal"] = mine
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Rio (Live)", listOf("Metallica"), null)))
        target.albumTrackListings["sal"] = mine

        val r = run(source, target, NOTHING.copy(doAlbums = true, corroborate = false))
        assertNull(r.counts["matched"])
        assertEquals(1, r.counts["unmatched"])
        assertTrue(r.items[0].note.orEmpty(),
            r.items[0].note.orEmpty().contains("the closest that artist has is \"Rio (Live)\""))
        assertEquals("and nothing was read", 0, target.albumTrackCalls.get())
    }

    @Test fun `a barcode match is never second-guessed by a track listing`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb(upc = "0075596040129")))
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "0075596040129", "Completely Different Title",
                  listOf("Metallica"), 8)))
        target.albumTrackListings["sal"] = listing("Nothing In Common")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["matched"])
        assertEquals("upc", r.items[0].method)
        assertEquals(0, target.albumTrackCalls.get())
    }

    @Test fun `a barcode that finds nothing falls back rather than giving up`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb(upc = "000000000000")))
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "075992736121", "Master Of Puppets", listOf("Metallica"), 8)))

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals("the title tier picked it up", 1, r.counts["matched"])
    }

    @Test fun `a barcode search that throws costs the tier, not the album`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb()))
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "075992736121", "Master Of Puppets", listOf("Metallica"), 8)))
        target.upcSearchFails = true

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["matched"])
    }

    @Test fun `strict mode still accepts only the barcode for albums`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb(upc = "")))
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Master Of Puppets", listOf("Metallica"), 8)))

        val r = run(source, target, NOTHING.copy(doAlbums = true, strict = true))
        assertEquals("no barcode, no match under strict", 1, r.counts["unmatched"])
    }
}
