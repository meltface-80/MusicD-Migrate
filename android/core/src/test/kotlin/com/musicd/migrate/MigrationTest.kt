package com.musicd.migrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The JavaScript migration tests (test/unit/migrate.test.js), translated. */
class MigrationTest {

    private fun src(
        id: String = "q1", title: String = "Song", artists: List<String> = listOf("Band"),
        album: String = "Rec", durationMs: Long? = 200000, isrc: String = ""
    ) = Track(id, isrc, title, artists, album, durationMs)

    private fun dst(
        id: String = "s1", title: String = "Song", artists: List<String> = listOf("Band"),
        album: String = "Rec", durationMs: Long? = 200000, isrc: String = ""
    ) = Track(id, isrc, title, artists, album, durationMs)

    private val NOTHING = MigrationOptions(doPlaylists = false, doAlbums = false,
        doArtists = false, doTracks = false)

    private class Run(
        val counts: Map<String, Int>, val store: Store, val items: List<JobItem>)

    private fun run(
        source: FakeService, target: FakeService, options: MigrationOptions,
        store: Store = MemoryStore(), jobId: String = "job1"
    ): Run {
        store.createJob(jobId, "a->b", "{}", options.dryRun)
        val m = Migration(source, target, store, jobId, options)
        val counts = m.run()
        return Run(counts, store, store.items(jobId))
    }

    // ------------------------------------------------------------------------

    @Test fun `a matched favourite track is written to the target`() {
        val source = FakeService("qobuz", libTracks = mutableListOf(src(isrc = "GBAYE0601498")))
        val target = FakeService("spotify", catalogue = mutableListOf(dst(isrc = "GBAYE0601498")))
        val r = run(source, target, NOTHING.copy(doTracks = true))
        assertEquals(listOf("s1"), target.writtenTracks)
        assertEquals(1, r.counts["matched"])
        assertEquals("isrc", r.items[0].method)
    }

    @Test fun `an unmatched track is reported with a reason and nothing is written`() {
        val source = FakeService("qobuz",
            libTracks = mutableListOf(src(title = "Obscure B-side")))
        val target = FakeService("spotify")
        val r = run(source, target, NOTHING.copy(doTracks = true))
        assertTrue(target.writtenTracks.isEmpty())
        assertEquals(1, r.counts["unmatched"])
        assertEquals("unmatched", r.items[0].status)
        assertTrue("the reason is recorded, not blank", !r.items[0].note.isNullOrEmpty())
    }

    @Test fun `a dry run does every lookup and writes nothing`() {
        val source = FakeService("qobuz", libTracks = mutableListOf(src(isrc = "GBAYE0601498")))
        val target = FakeService("spotify", catalogue = mutableListOf(dst(isrc = "GBAYE0601498")))
        val r = run(source, target, NOTHING.copy(doTracks = true, dryRun = true))
        assertTrue("a dry run must not write", target.writtenTracks.isEmpty())
        assertEquals("but it still produces the real answer", 1, r.counts["matched"])
        assertEquals("s1", r.items[0].targetId)
    }

    @Test fun `tracks already in the target library are skipped without a search`() {
        val source = FakeService("qobuz", libTracks = mutableListOf(src(isrc = "GBAYE0601498")))
        val target = FakeService("spotify",
            catalogue = mutableListOf(dst(isrc = "GBAYE0601498")),
            libTracks = mutableListOf(dst(isrc = "GBAYE0601498")))
        val r = run(source, target, NOTHING.copy(doTracks = true))
        assertEquals(1, r.counts["already"])
        assertEquals("no search for something already there", 0, target.searchCount.get())
        assertTrue(target.writtenTracks.isEmpty())
    }

    @Test fun `the match cache spares the second run every lookup`() {
        val store = MemoryStore()
        val s1 = FakeService("qobuz", libTracks = mutableListOf(src(isrc = "GBAYE0601498")))
        val t1 = FakeService("spotify", catalogue = mutableListOf(dst(isrc = "GBAYE0601498")))
        run(s1, t1, NOTHING.copy(doTracks = true), store, "job1")
        assertTrue(t1.searchCount.get() > 0)

        val s2 = FakeService("qobuz", libTracks = mutableListOf(src(isrc = "GBAYE0601498")))
        val t2 = FakeService("spotify", catalogue = mutableListOf(dst(isrc = "GBAYE0601498")))
        run(s2, t2, NOTHING.copy(doTracks = true), store, "job2")
        assertEquals("the second run searched nothing", 0, t2.searchCount.get())
        assertEquals("and still wrote the match", listOf("s1"), t2.writtenTracks)
    }

    @Test fun `a cached miss is honoured, not re-searched`() {
        val store = MemoryStore()
        store.cacheMatch("qobuz", "q1", "spotify", "track", null, "not on Spotify")
        val source = FakeService("qobuz", libTracks = mutableListOf(src()))
        val target = FakeService("spotify", catalogue = mutableListOf(dst()))
        val r = run(source, target, NOTHING.copy(doTracks = true), store)
        assertEquals(0, target.searchCount.get())
        assertEquals(1, r.counts["unmatched"])
    }

    // ---------------------------------------------------------------- playlists

    @Test fun `playlist track order survives concurrent lookups`() {
        val n = 25
        val tracks = (0 until n).map {
            src(id = "q$it", title = "Song $it",
                isrc = "GBAYE06014" + it.toString().padStart(2, '0'))
        }.toMutableList()
        val catalogue = tracks.map {
            dst(id = "s" + it.id.substring(1), title = it.title, isrc = it.isrc)
        }.toMutableList()

        val source = FakeService("qobuz")
        source.libPlaylists["p1"] = "Mix" to tracks
        val target = FakeService("spotify", catalogue = catalogue)
        // Answer out of order, slowest first, so a naive implementation
        // shuffles the playlist into completion order.
        target.isrcSearchDelayMs = { isrc -> ((n - isrc.takeLast(2).toInt()) % 7).toLong() }

        run(source, target, NOTHING.copy(doPlaylists = true, concurrency = 6))
        assertEquals("the destination playlist must be in the source's order",
            tracks.map { "s" + it.id.substring(1) },
            target.added[target.created[0].id]?.toList())
    }

    @Test fun `re-running a playlist migration adds only what is missing`() {
        val store = MemoryStore()
        val tracks = mutableListOf(
            src(id = "q1", isrc = "GBAYE0601491", title = "One"),
            src(id = "q2", isrc = "GBAYE0601492", title = "Two"))
        val catalogue = mutableListOf(
            dst(id = "s1", isrc = "GBAYE0601491", title = "One"),
            dst(id = "s2", isrc = "GBAYE0601492", title = "Two"))
        val source = FakeService("qobuz")
        source.libPlaylists["p1"] = "Mix" to tracks
        val target = FakeService("spotify", catalogue = catalogue)
        val opts = NOTHING.copy(doPlaylists = true)

        run(source, target, opts, store, "job1")
        val plId = target.created[0].id
        assertEquals(2, target.added[plId]?.size)

        // A third track appears in the source, and the same migration runs
        // again.
        tracks.add(src(id = "q3", isrc = "GBAYE0601493", title = "Three"))
        catalogue.add(dst(id = "s3", isrc = "GBAYE0601493", title = "Three"))
        run(source, target, opts, store, "job2")

        assertEquals("no second playlist was created", 1, target.created.size)
        assertEquals("only the new track was added", listOf("s1", "s2", "s3"),
            target.added[plId]?.toList())
    }

    @Test fun `two source tracks resolving to one target track add it once`() {
        // The album cut and the single, same ISRC — both legitimately map to
        // one track, and adding it twice is a duplicate the user did not have.
        val source = FakeService("qobuz")
        source.libPlaylists["p1"] = "Mix" to mutableListOf(
            src(id = "q1", isrc = "GBAYE0601491", title = "One"),
            src(id = "q2", isrc = "GBAYE0601491", title = "One"))
        val target = FakeService("spotify",
            catalogue = mutableListOf(dst(id = "s1", isrc = "GBAYE0601491", title = "One")))
        run(source, target, NOTHING.copy(doPlaylists = true))
        assertEquals(listOf("s1"), target.added[target.created[0].id]?.toList())
    }

    @Test fun `local files and podcast episodes are skipped and named`() {
        val source = FakeService("qobuz")
        source.libPlaylists["p1"] = "Mix" to mutableListOf(
            Track(id = "l", title = "bootleg.flac", skip = "local file"),
            Track(id = "e", title = "Some Show", skip = "podcast episode"),
            src(isrc = "GBAYE0601498"))
        val target = FakeService("spotify",
            catalogue = mutableListOf(dst(isrc = "GBAYE0601498")))
        val r = run(source, target, NOTHING.copy(doPlaylists = true))
        assertEquals(2, r.counts["skipped"])
        // Asserted as a SET, not by position. Playlist lookups run on several
        // threads and rows are recorded as each finishes, so the order of the
        // report is whatever order the workers happened to complete in. The
        // order that does matter — the order tracks are written INTO the
        // destination playlist — is guaranteed, and
        // `playlist track order survives concurrent lookups` is the test for
        // it. An earlier version of this test indexed skipped[0] and
        // skipped[1] and passed only by luck of the timing.
        val notes = r.items.filter { it.status == "skipped" }.map { it.note!! }
        assertTrue("the local file is named as one",
            notes.any { it.contains("local file") })
        assertTrue("the podcast episode is named as one",
            notes.any { it.contains("podcast episode") })
    }

    @Test fun `a playlist where nothing matches is not created at all`() {
        val source = FakeService("qobuz")
        source.libPlaylists["p1"] = "Rarities" to mutableListOf(src(title = "Unfindable"))
        val target = FakeService("spotify")
        val r = run(source, target, NOTHING.copy(doPlaylists = true))
        assertTrue(target.created.isEmpty())
        assertTrue(r.items.any {
            it.kind == "playlist" && it.note!!.contains("not created")
        })
    }

    @Test fun `playlists belonging to someone else are left alone by default`() {
        val source = FakeService("qobuz")
        source.libPlaylists["p1"] = "Someone's" to mutableListOf(src())
        source.playlistsOverride = {
            listOf(Playlist(id = "p1", name = "Someone's", ownerId = "other", trackCount = 1))
        }
        val target = FakeService("spotify", catalogue = mutableListOf(dst()))
        run(source, target, NOTHING.copy(doPlaylists = true))
        assertTrue(target.created.isEmpty())
    }

    // ------------------------------------------------------------- robustness

    @Test fun `a search that throws costs one track, not the whole migration`() {
        val source = FakeService("qobuz", libTracks = mutableListOf(
            src(id = "q1", isrc = "GBAYE0601491"), src(id = "q2", isrc = "GBAYE0601492")))
        val target = FakeService("spotify",
            catalogue = mutableListOf(dst(id = "s2", isrc = "GBAYE0601492")))
        // BOTH lookups have to fail for the track to be unlookable: when only
        // the ISRC search throws, falling back to the text search is correct
        // behaviour and the track can still match.
        target.isrcSearchFails = { it == "GBAYE0601491" }
        target.textSearchFails = { _, artist -> artist == "Band" }

        val r = run(source, target, NOTHING.copy(doTracks = true))
        // FAILED, not unmatched. The run still finishes and the other track is
        // still written — what this test has always guarded — but a search that
        // could not be MADE is not a search that found nothing. Amber says
        // "your track is not on that service"; red says "we could not look",
        // and under a rate limit the amber version reported a whole library as
        // absent. Mirrors the same test in test/unit/migrate.test.js.
        assertEquals(1, r.counts["failed"])
        assertNull(r.counts["unmatched"])
        assertEquals(1, r.counts["matched"])
        assertEquals(listOf("s2"), target.writtenTracks)

        val row = r.items.first { it.status == "failed" }
        assertTrue("the row quotes what the service said: ${row.note}",
            row.note!!.contains("could not search spotify"))

        // And it is NOT cached, or the refusal would outlive the thing that
        // caused it and the next run would not even retry.
        assertNull(r.store.cachedMatch("qobuz", "q1", "spotify", "track"))
        assertNotNull("but the one that DID resolve is cached as normal",
            r.store.cachedMatch("qobuz", "q2", "spotify", "track"))
    }

    @Test fun `an expired sign-in stops the migration rather than reporting misses`() {
        val source = FakeService("qobuz", libTracks = mutableListOf(src(isrc = "GBAYE0601491")))
        val target = FakeService("spotify")
        target.searchByIsrcAuthFails = true
        try {
            run(source, target, NOTHING.copy(doTracks = true))
            fail("an AuthError must not be swallowed as 'no results'")
        } catch (e: AuthError) {
            assertTrue(e.message!!.contains("expired"))
        }
    }

    @Test fun `cancelling stops the run and says so`() {
        val tracks = (0 until 40).map { src(id = "q$it", title = "Song $it") }.toMutableList()
        val source = FakeService("qobuz", libTracks = tracks)
        val target = FakeService("spotify")
        val store = MemoryStore()
        store.createJob("jc", "a->b", "{}", false)
        val m = Migration(source, target, store, "jc", NOTHING.copy(doTracks = true))
        m.cancel()
        try {
            m.run()
            fail("a cancelled run must throw")
        } catch (e: Cancelled) {
            assertEquals("cancelled", parseObject(store.job("jc")!!.progressJson)!!.str("phase"))
        }
    }

    @Test fun `a failed write is recorded as failed, not silently lost`() {
        val source = FakeService("qobuz", libTracks = mutableListOf(src(isrc = "GBAYE0601498")))
        val target = FakeService("spotify", catalogue = mutableListOf(dst(isrc = "GBAYE0601498")))
        target.saveTracksFails = true
        val r = run(source, target, NOTHING.copy(doTracks = true))
        assertEquals("one stranded item, counted once", 1, r.counts["failed"])
        assertTrue(r.items.any { it.status == "failed" && it.note!!.contains("Spotify said no") })
    }

    @Test fun `a failed batch write counts every item it stranded`() {
        val tracks = (0 until 7).map {
            src(id = "q$it", isrc = "GBAYE060149" + it, title = "Song $it")
        }.toMutableList()
        val catalogue = tracks.map {
            dst(id = "s" + it.id.substring(1), isrc = it.isrc, title = it.title)
        }.toMutableList()
        val source = FakeService("qobuz", libTracks = tracks)
        val target = FakeService("spotify", catalogue = catalogue)
        target.saveTracksFails = true
        val r = run(source, target, NOTHING.copy(doTracks = true))
        assertEquals("seven matched tracks went unwritten, not one", 7, r.counts["failed"])
    }

    @Test fun `artists are matched on an exact name and followed`() {
        val source = FakeService("qobuz", libArtists = mutableListOf(Artist("qa", "Portishead")))
        val target = FakeService("spotify",
            catalogueArtists = mutableListOf(Artist("sa", "portishead")))
        val r = run(source, target, NOTHING.copy(doArtists = true))
        assertEquals(listOf("sa"), target.writtenArtists)
        assertEquals(1, r.counts["matched"])
    }

    @Test fun `albums match on barcode and are saved`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            Album("qal", "075992736121", "Rumours", listOf("Fleetwood Mac"), 11)))
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "075992736121", "Rumours", listOf("Fleetwood Mac"), 11)))
        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(listOf("sal"), target.writtenAlbums)
        assertEquals(1, r.counts["matched"])
    }

    @Test fun `nothing selected does nothing, and does not fail`() {
        val source = FakeService("qobuz", libTracks = mutableListOf(src()))
        val target = FakeService("spotify", catalogue = mutableListOf(dst()))
        val r = run(source, target, NOTHING)
        assertEquals(null, r.counts["matched"])
        assertEquals(0, target.searchCount.get())
    }

    @Test fun `progress json is valid and carries the counts`() {
        val source = FakeService("qobuz", libTracks = mutableListOf(src(isrc = "GBAYE0601498")))
        val target = FakeService("spotify", catalogue = mutableListOf(dst(isrc = "GBAYE0601498")))
        val r = run(source, target, NOTHING.copy(doTracks = true))
        val p = parseObject(r.store.job("job1")!!.progressJson)
        assertNotNull("the page polls this — it has to parse", p)
        assertEquals("done", p!!.str("phase"))
        assertEquals(1, p.objOrNull("counts")!!.intOrNull("matched"))
    }
    // ----------------------------------------------------------------------
    // Regression: the migration has to know whose playlists are whose. The
    // JavaScript twin of these is at the end of test/unit/migrate.test.js.

    @Test fun `a migration establishes who the source account is before filtering`() {
        val source = FakeService("qobuz")
        source.libPlaylists["p1"] = "Mix" to mutableListOf(src(isrc = "GBAYE0601498"))
        // As a client built from a stored session that never learned its id.
        source.accountId = ""
        val target = FakeService("spotify",
            catalogue = mutableListOf(dst(isrc = "GBAYE0601498")))

        run(source, target, NOTHING.copy(doPlaylists = true))

        assertEquals("it asked the service who it is", 1, source.meCalls)
        assertEquals("the user's own playlist must not be filtered out by an unknown id",
            1, target.created.size)
    }

    @Test fun `a source that cannot say who it is migrates playlists rather than none`() {
        val source = FakeService("qobuz")
        source.libPlaylists["p1"] = "Mix" to mutableListOf(src(isrc = "GBAYE0601498"))
        source.accountId = ""
        source.meFails = true
        val target = FakeService("spotify",
            catalogue = mutableListOf(dst(isrc = "GBAYE0601498")))

        run(source, target, NOTHING.copy(doPlaylists = true))
        assertEquals(1, target.created.size)
    }

    @Test fun `an explicit playlist selection is honoured even with an unknown account`() {
        val source = FakeService("qobuz")
        source.libPlaylists["p1"] = "Mix" to mutableListOf(src(isrc = "GBAYE0601498"))
        source.accountId = ""
        source.meFails = true
        val target = FakeService("spotify",
            catalogue = mutableListOf(dst(isrc = "GBAYE0601498")))

        run(source, target, NOTHING.copy(doPlaylists = true, playlistIds = listOf("p1")))
        assertEquals("an id the user picked needs no ownership check at all",
            1, target.created.size)
        assertEquals("and no request to find out who they are", 0, source.meCalls)
    }

    // ----------------------------------------------------------------------
    // Regression: albums were matched by barcode in name only. The JavaScript
    // twin of these is at the end of test/unit/migrate.test.js.

    private fun alb(
        id: String = "qal", title: String = "Master Of Puppets",
        artists: List<String> = listOf("Metallica"), upc: String = "075992736121",
        trackCount: Int? = 8
    ) = Album(id, upc, title, artists, trackCount)

    @Test fun `an album is looked up by barcode before anything else`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb()))
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            // A title the text search could never find, and a track count that
            // would fail the close-tier gate. Only the barcode can match this.
            Album("sal", "075992736121", "Meisterwerk der Marionetten",
                listOf("Metallica"), 12)))

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(listOf("sal"), target.writtenAlbums)
        assertEquals(1, r.counts["matched"])
        assertEquals("the barcode tier must be what fired", "upc", r.items[0].method)
        assertEquals(1, target.upcSearchCount.get())
    }

    @Test fun `the edition that differs only by a suffix is found by barcode`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(title = "Master Of Puppets (Remastered)", trackCount = 8)))
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "075992736121", "Master of Puppets", listOf("Metallica"), 10)))

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals("a barcode match beats the track-count gate", 1, r.counts["matched"])
        assertEquals(listOf("sal"), target.writtenAlbums)
    }

    /** Track rows for a listing, which is all corroboration compares. */
    private fun listing(vararg titles: String): List<Track> =
        titles.mapIndexed { i, t -> Track("t$i", "", t, emptyList(), "", null) }

    @Test fun `no barcode falls back to title, artist and the track listing`() {
        val titles = listing("Battery", "Master of Puppets", "The Thing That Should Not Be")
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb(upc = "")))
        source.albumTrackListings["qal"] = titles
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Master Of Puppets", listOf("Metallica"), 3)))
        // As Spotify writes a remastered edition's tracks.
        target.albumTrackListings["sal"] =
            listing("Battery - Remastered", "Master of Puppets - Remastered",
                    "The Thing That Should Not Be - Remastered")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["matched"])
        assertEquals("with no barcode, the title tier alone is not decisive evidence",
            "exact+tracklist", r.items[0].method)
        assertEquals("no barcode search was wasted", 0, target.upcSearchCount.get())
    }

    @Test fun `a barcode-less album with a different track listing is refused, with a reason`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(title = "Greatest Hits", upc = "", trackCount = null)))
        source.albumTrackListings["qal"] = listing("One", "Two", "Three", "Four")
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Greatest Hits", listOf("Metallica"), null)))
        target.albumTrackListings["sal"] = listing("Nine", "Ten", "Eleven", "One")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["unmatched"])
        assertTrue("the report says what was wrong with it: ${r.items[0].note}",
            r.items[0].note.orEmpty().contains("1 of your 4 tracks"))
    }

    @Test fun `corroboration works down the shortlist rather than trusting the top one`() {
        val titles = listing("Aaa", "Bbb", "Ccc", "Ddd")
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(title = "The Record", upc = "", trackCount = null)))
        source.albumTrackListings["qal"] = titles
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("wrong", "", "The Record", listOf("Metallica"), null),
            Album("right", "", "The Record", listOf("Metallica"), null)))
        target.albumTrackListings["wrong"] = listing("Zzz", "Yyy")
        target.albumTrackListings["right"] = titles

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["matched"])
        assertEquals(listOf("right"), target.writtenAlbums)
    }

    @Test fun `corroboration is bounded and does not read the whole search result`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(title = "The Record", upc = "", trackCount = null)))
        source.albumTrackListings["qal"] = listing("Aaa", "Bbb")
        val target = FakeService("spotify", catalogueAlbums = (1..4).map {
            Album("c$it", "", "The Record", listOf("Metallica"), null)
        }.toMutableList())
        for (i in 1..4) target.albumTrackListings["c$i"] = listing("No", "Nope")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["unmatched"])
        assertEquals("two candidates, and no more", 2, target.albumTrackCalls.get())
    }

    @Test fun `a deluxe edition is accepted for the standard one the user owns`() {
        // The coverage is measured against WHAT THE USER OWNS, not against
        // what the candidate holds: a deluxe edition contains all of the
        // standard plus bonus tracks, and measuring the other way round
        // refuses a record that is plainly the right one.
        val mine = listing("Aaa", "Bbb", "Ccc", "Ddd")
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(title = "The Record", upc = "", trackCount = null)))
        source.albumTrackListings["qal"] = mine
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("deluxe", "", "The Record", listOf("Metallica"), 8)))
        target.albumTrackListings["deluxe"] =
            listing("Aaa", "Bbb", "Ccc", "Ddd", "Eee", "Fff", "Ggg", "Hhh")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["matched"])
        assertEquals(listOf("deluxe"), target.writtenAlbums)
        assertTrue("which edition was taken is in the report: ${r.items[0].note}",
            r.items[0].note.orEmpty().contains("on an edition of 8"))
    }

    @Test fun `the source album's own length ranks the standard edition above the deluxe`() {
        // A Roon album arrives with no track count at all, so the listing read
        // for corroboration is also what supplies it. Without that the two
        // editions tie and the tie-break is alphabetical on an opaque id: a
        // coin toss between the record the user owns and a different edition.
        val mine = listing("Aaa", "Bbb", "Ccc", "Ddd")
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(title = "The Record", upc = "", trackCount = null)))
        source.albumTrackListings["qal"] = mine
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            // Named so an alphabetical tie-break would pick the wrong one.
            Album("a-deluxe", "", "The Record", listOf("Metallica"), 8),
            Album("z-standard", "", "The Record", listOf("Metallica"), 4)))
        target.albumTrackListings["a-deluxe"] =
            listing("Aaa", "Bbb", "Ccc", "Ddd", "E", "F", "G", "H")
        target.albumTrackListings["z-standard"] = mine

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["matched"])
        assertEquals("the edition with the same number of tracks is the one they own",
            listOf("z-standard"), target.writtenAlbums)
    }

    @Test fun `corroboration can be turned off, and then the title tier decides alone`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb(upc = "")))
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Master Of Puppets", listOf("Metallica"), 8)))

        val r = run(source, target, NOTHING.copy(doAlbums = true, corroborate = false))
        assertEquals(1, r.counts["matched"])
        assertEquals("exact", r.items[0].method)
        assertEquals("and nothing extra was read", 0, target.albumTrackCalls.get())
    }

    @Test fun `a listing that cannot be read is a FAILURE, not a record that is not there`() {
        // 0.2.0 shipped with a broken corroboration read. Every album came
        // back "not found" -- amber, indistinguishable from a library that is
        // genuinely not on the other service -- and the cause took a live run
        // and a screenshot to find. A check that could not be MADE is red.
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb(upc = "")))
        source.albumTracksFails = true
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Master Of Puppets", listOf("Metallica"), 8)))
        target.albumTrackListings["sal"] = listing("Battery")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals("red, so it cannot be mistaken for a miss", 1, r.counts["failed"])
        assertNull(r.counts["unmatched"])
        assertTrue(r.items[0].note.orEmpty().contains("track listing from the source"))

        // And it is NOT cached. "We looked and found nothing" is a real answer
        // worth keeping; "we could not look" is not, and caching it would keep
        // being reused after the thing that broke was fixed.
        assertNull("a failed read must be retried on the next run, not remembered as a miss",
            r.store.cachedMatch("qobuz", "qal", "spotify", "album"))
    }

    @Test fun `the other service refusing to list an album is also a failure`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb(upc = "")))
        source.albumTrackListings["qal"] = listing("Battery", "Leper Messiah")
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Master Of Puppets", listOf("Metallica"), 8)))
        // No listing for "sal" at all: the other side would not say.

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["failed"])
        assertTrue(r.items[0].note.orEmpty().contains("would not list that album's tracks"))
    }

    @Test fun `a listing that disagrees is still an ordinary miss, and is cached`() {
        // The other side of it: a record that is genuinely a different record
        // is amber and IS remembered, or every re-run pays for it again.
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(title = "Greatest Hits", upc = "", trackCount = null)))
        source.albumTrackListings["qal"] = listing("One", "Two", "Three", "Four")
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Greatest Hits", listOf("Metallica"), null)))
        target.albumTrackListings["sal"] = listing("Nine", "Ten", "Eleven", "One")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["unmatched"])
        assertNull(r.counts["failed"])
        val cached = r.store.cachedMatch("qobuz", "qal", "spotify", "album")
        assertTrue("a real miss is remembered", cached != null && cached.toId == null)
    }

    // ------------------------------------------ how an album is searched for

    @Test fun `several artists glued into one string are cut down to the first`() {
        // Roon writes an album's artists as one slash-joined string, and a
        // query naming all three finds nothing on either service. Measured on
        // a real 9,514-album library: 457 of the 1,273 albums whose search
        // came back EMPTY had an artist string naming more than one person,
        // against 2.0% of the 4,639 that matched.
        assertEquals("Carla Bley",
            Migration.searchArtist(listOf("Carla Bley/Steve Swallow/Andy Sheppard")))
        assertEquals("Vincent Peirani",
            Migration.searchArtist(listOf("Vincent Peirani & Emile Parisien")))
        assertEquals("Miles Davis", Migration.searchArtist(listOf("Miles Davis, John Coltrane")))
        assertEquals("Terence Blanchard",
            Migration.searchArtist(listOf("Terence Blanchard featuring the E-Collective")))
    }

    @Test fun `a second artist in the list is a different artist, not a phrasing`() {
        // artists[0] is split again because it may be several names glued
        // together. The REST of the list is left alone: "Little Boots" is not
        // a rephrasing of "Hot Chip".
        assertEquals("Hot Chip", Migration.searchArtist(listOf("Hot Chip", "Little Boots")))
    }

    @Test fun `searchArtist is defined on the awkward inputs a real library holds`() {
        assertEquals("", Migration.searchArtist(emptyList()))
        assertEquals("", Migration.searchArtist(null))
        assertEquals("", Migration.searchArtist(listOf("")))
        assertEquals("", Migration.searchArtist(listOf("/")))
        assertEquals("left as it is -- the gate will refuse it, and that is right",
            "Unknown Artist", Migration.searchArtist(listOf("Unknown Artist")))
    }

    @Test fun `an album whose artist string names three people is found anyway`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            Album("qal", "", "Andando el Tiempo",
                listOf("Carla Bley/Steve Swallow/Andy Sheppard"), 8)))
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Andando el Tiempo",
                listOf("Carla Bley", "Steve Swallow", "Andy Sheppard"), 8)))

        val r = run(source, target, NOTHING.copy(doAlbums = true, corroborate = false))
        assertEquals(r.items.firstOrNull()?.note, 1, r.counts["matched"])
        assertEquals("the query named one artist, not three",
            "Carla Bley", target.albumQueriesSeen[0].second)
        assertEquals("and it still costs exactly one search", 1, target.searchCount.get())
    }

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
        // option, not a renamed FIELD, and the Docker half is the one everyone
        // tests. Mirrored in test/unit/migrate.test.js.
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
