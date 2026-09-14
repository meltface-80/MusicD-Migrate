package com.musicd.migrate

import org.junit.Assert.assertEquals
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
        assertEquals(1, r.counts["unmatched"])
        assertEquals(1, r.counts["matched"])
        assertEquals(listOf("s2"), target.writtenTracks)
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

    @Test fun `no barcode still falls back to title and artist`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb(upc = "")))
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Master Of Puppets", listOf("Metallica"), 8)))

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["matched"])
        assertEquals("exact", r.items[0].method)
        assertEquals("no barcode search was wasted", 0, target.upcSearchCount.get())
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
