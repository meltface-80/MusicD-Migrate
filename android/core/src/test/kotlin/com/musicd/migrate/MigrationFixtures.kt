package com.musicd.migrate


/**
 * What the three migration test classes share: tracks, albums and listings to
 * build libraries from, and a way to run a migration and read back its rows.
 */
abstract class MigrationFixtures {

    protected fun src(
        id: String = "q1", title: String = "Song", artists: List<String> = listOf("Band"),
        album: String = "Rec", durationMs: Long? = 200000, isrc: String = ""
    ) = Track(id, isrc, title, artists, album, durationMs)

    protected fun dst(
        id: String = "s1", title: String = "Song", artists: List<String> = listOf("Band"),
        album: String = "Rec", durationMs: Long? = 200000, isrc: String = ""
    ) = Track(id, isrc, title, artists, album, durationMs)

    protected val NOTHING = MigrationOptions(doPlaylists = false, doAlbums = false,
        doArtists = false, doTracks = false)

    protected class Run(
        val counts: Map<String, Int>, val store: Store, val items: List<JobItem>)

    protected fun run(
        source: FakeService, target: FakeService, options: MigrationOptions,
        store: Store = MemoryStore(), jobId: String = "job1"
    ): Run {
        store.createJob(jobId, "a->b", "{}", options.dryRun)
        val m = Migration(source, target, store, jobId, options)
        val counts = m.run()
        return Run(counts, store, store.items(jobId))
    }

    protected fun alb(
        id: String = "qal", title: String = "Master Of Puppets",
        artists: List<String> = listOf("Metallica"), upc: String = "075992736121",
        trackCount: Int? = 8
    ) = Album(id, upc, title, artists, trackCount)

    /** Track rows for a listing, which is all corroboration compares. */
    protected fun listing(vararg titles: String): List<Track> =
        titles.mapIndexed { i, t -> Track("t$i", "", t, emptyList(), "", null) }
}
