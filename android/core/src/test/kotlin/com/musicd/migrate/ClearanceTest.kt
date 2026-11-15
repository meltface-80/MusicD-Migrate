package com.musicd.migrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The clear-out engine.
 *
 * Every test here asserts on what is LEFT in the fake library as well as on
 * what the engine said it did, because the two can disagree and only one of
 * them is what the user will see when they open the app.
 */
class ClearanceTest {

    private fun t(id: String) = Track(id = id, title = "Song $id", artists = listOf("Artist"),
        durationMs = 200_000)
    private fun a(id: String) = Album(id = id, title = "Album $id", artists = listOf("Artist"))

    private fun library(tracks: Int = 3, albums: Int = 2, artists: Int = 1) = FakeService(
        "spotify",
        libTracks = (1..tracks).map { t("t$it") }.toMutableList(),
        libAlbums = (1..albums).map { a("a$it") }.toMutableList(),
        libArtists = (1..artists).map { Artist("r$it", "Artist $it") }.toMutableList())

    private fun run(
        svc: FakeService, opts: ClearOptions, store: MemoryStore = MemoryStore()
    ): Pair<Map<String, Int>, List<JobItem>> {
        store.createJob("j", "clear-${svc.serviceName}", "{}", opts.dryRun)
        val counts = Clearance(svc, store, "j", opts).run()
        return counts to store.items("j")
    }

    private val everything = ClearOptions(doTracks = true, doAlbums = true, doArtists = true,
        doPlaylists = true)

    // ------------------------------------------------------------- previewing

    @Test fun `a preview is the default and removes nothing`() {
        val svc = library()
        svc.libPlaylists["p1"] = "Mine" to mutableListOf(t("x"))
        // No dryRun named: the default has to be the safe one.
        val (counts, items) = run(svc, everything)

        assertEquals("nothing was asked of the service: ${svc.removeCalls}",
            0, svc.removeCalls.size)
        assertEquals(3, svc.libTracks.size)
        assertEquals(2, svc.libAlbums.size)
        assertEquals(1, svc.libArtists.size)
        assertEquals(1, svc.libPlaylists.size)
        assertEquals("every item is listed", 7, counts["listed"])
        assertEquals(null, counts["removed"])
        // The preview is the record of what was there: every row carries the
        // item's own id.
        assertEquals(setOf("t1", "t2", "t3", "a1", "a2", "r1", "p1"),
            items.map { it.sourceId }.toSet())
        assertTrue(items.first { it.sourceId == "p1" }.note!!.contains("would be deleted"))
    }

    // ---------------------------------------------------------------- removing

    @Test fun `a real run removes what was ticked and nothing else`() {
        val svc = library()
        svc.libPlaylists["p1"] = "Mine" to mutableListOf(t("x"))
        val (counts, items) = run(svc, ClearOptions(doTracks = true, doAlbums = true,
            dryRun = false))

        assertEquals(0, svc.libTracks.size)
        assertEquals(0, svc.libAlbums.size)
        assertEquals("artists were not ticked", 1, svc.libArtists.size)
        assertEquals("playlists were not ticked", 1, svc.libPlaylists.size)
        assertEquals(5, counts["removed"])
        assertTrue(items.all { it.status == "removed" })
        assertEquals(setOf("tracks", "albums"), svc.removeCalls.map { it.first }.toSet())
    }

    @Test fun `removals are batched so one batch is one request`() {
        val svc = library(tracks = 120, albums = 45, artists = 0)
        run(svc, ClearOptions(doTracks = true, doAlbums = true, dryRun = false))
        assertEquals(listOf(50, 50, 20),
            svc.removeCalls.filter { it.first == "tracks" }.map { it.second.size })
        assertEquals(listOf(20, 20, 5),
            svc.removeCalls.filter { it.first == "albums" }.map { it.second.size })
    }

    // -------------------------------------------------------------- playlists

    @Test fun `a playlist somebody else made is kept unless the user asks`() {
        val svc = library(0, 0, 0)
        svc.libPlaylists["mine"] = "Mine" to mutableListOf()
        svc.libPlaylists["theirs"] = "Theirs" to mutableListOf()
        svc.playlistOwners["theirs"] = "someone-else"

        val (counts, items) = run(svc, ClearOptions(doPlaylists = true, dryRun = false))
        assertEquals("only the owned one went, and it was DELETED",
            listOf("mine" to true), svc.removedPlaylists.toList())
        assertTrue("the followed one is still there", svc.libPlaylists.containsKey("theirs"))
        assertEquals(1, counts["skipped"])
        assertTrue(items.first { it.sourceId == "theirs" }.note!!.contains("you follow"))

        run(svc, ClearOptions(doPlaylists = true, includeOthersPlaylists = true,
            dryRun = false))
        assertEquals("asked for, it is UNFOLLOWED rather than deleted",
            listOf("mine" to true, "theirs" to false), svc.removedPlaylists.toList())
    }

    @Test fun `with no idea who the account is, no playlist is touched`() {
        val svc = library(0, 0, 0)
        svc.libPlaylists["p"] = "Mine" to mutableListOf()
        svc.accountId = ""
        svc.meFails = true
        val (counts, items) = run(svc, ClearOptions(doPlaylists = true,
            includeOthersPlaylists = true, dryRun = false))
        assertEquals(0, svc.removedPlaylists.size)
        assertEquals(1, counts["failed"])
        assertTrue(items[0].note!!.contains("None was touched"))
    }

    @Test fun `a playlist whose owner the service did not report is left alone`() {
        val svc = library(0, 0, 0)
        svc.libPlaylists["p"] = "Mystery" to mutableListOf()
        svc.playlistOwners["p"] = ""
        val (counts, _) = run(svc, ClearOptions(doPlaylists = true,
            includeOthersPlaylists = true, dryRun = false))
        assertEquals(0, svc.removedPlaylists.size)
        assertEquals(1, counts["skipped"])
    }

    // --------------------------------------------------------------- failures

    @Test fun `a failed batch marks every id in it failed, and the run carries on`() {
        val svc = library(tracks = 2, albums = 2, artists = 1)
        svc.removeFails = { it == "albums" }
        val (counts, items) = run(svc, ClearOptions(doTracks = true, doAlbums = true,
            doArtists = true, dryRun = false))
        assertEquals(3, counts["removed"])
        assertEquals("both albums in the failed batch are counted", 2, counts["failed"])
        assertEquals("and both are still there", 2, svc.libAlbums.size)
        assertTrue(items.filter { it.kind == "album" }.all {
            it.status == "failed" && it.note!!.contains("albums removal refused")
        })
        assertEquals("the artists after it were still removed", 0, svc.libArtists.size)
    }

    @Test fun `a removal that never once works stops the run`() {
        val svc = library(tracks = 500, albums = 0, artists = 0)
        svc.removeFails = { true }
        val store = MemoryStore()
        try {
            run(svc, ClearOptions(doTracks = true, dryRun = false), store)
            fail("ten batches of refusals should not be reported as ten red pages")
        } catch (e: BrokenRead) {
            assertTrue(e.message!!, e.message!!.contains("not one worked"))
        }
        assertEquals("stopped at the limit, not at the end",
            Clearance.FAILED_BATCH_LIMIT * Clearance.TRACK_BATCH,
            store.items("j").count { it.status == "failed" })
    }

    @Test fun `a dead sign-in stops the run instead of failing every row`() {
        val svc = library()
        svc.removeAuthFails = true
        val store = MemoryStore()
        try {
            run(svc, everything.copy(dryRun = false), store)
            fail("an AuthError must stop the run")
        } catch (e: AuthError) {
            // expected
        }
        assertEquals(0, store.items("j").count { it.status == "removed" })
        assertEquals(3, svc.libTracks.size)
    }

    @Test fun `a cancelled clear-out removes nothing further`() {
        val svc = library()
        val store = MemoryStore()
        store.createJob("j", "clear-spotify", "{}", false)
        val c = Clearance(svc, store, "j", everything.copy(dryRun = false))
        c.cancel()
        try {
            c.run()
            fail("expected Cancelled")
        } catch (e: Cancelled) {
            // expected
        }
        assertEquals(0, svc.removeCalls.size)
    }

    @Test fun `the progress names the service when it is rate limited`() {
        val svc = library()
        val store = MemoryStore()
        store.createJob("j", "clear-spotify", "{}", true)
        val c = Clearance(svc, store, "j", everything)
        c.noteRateLimit(20_000)
        val p = store.job("j")!!.progressJson
        assertTrue(p, p.contains("spotify is rate limiting this app"))
        assertTrue(p, p.contains("\"rateLimits\":1"))
    }
}
