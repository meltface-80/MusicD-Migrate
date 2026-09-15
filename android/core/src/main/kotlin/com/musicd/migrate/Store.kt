package com.musicd.migrate

/*
 * Store.kt — everything the app remembers between runs.
 *
 * The same three things lib/store.js keeps, for the same reasons:
 *
 *   settings     the two services' sign-ins.
 *   match cache  "this Qobuz track is that Spotify track". The expensive part
 *                of a migration is LOOKING UP, not writing: a 2,000-track
 *                library is 2,000 searches against a rate-limited API. Caching
 *                the answer — INCLUDING the misses — makes a second run nearly
 *                free, and the second run is the common one.
 *   jobs         what a run did, item by item, because the useful output of a
 *                migration is the list of records that did NOT move.
 *
 * :core is plain Kotlin/JVM so it can be unit-tested without an Android SDK,
 * which rules out android.database.sqlite here. The app module supplies a
 * SQLite implementation; the tests supply MemoryStore.
 */
interface Store {

    // ------------------------------------------------------------- settings

    fun setting(key: String): String?
    fun putSetting(key: String, value: String)
    fun deleteSetting(key: String)

    // ---------------------------------------------------------- roon library

    /**
     * Store a page of scanned albums.
     *
     * @return (stored, duplicates). Duplicates are albums whose normalised
     *   title and artist collide with one already stored: a second copy of the
     *   same record, a CD and a vinyl rip of it. One of them is enough to
     *   migrate, but the number is reported so the totals add up and the user
     *   is not left wondering why 10,014 albums became 9,987.
     */
    fun saveRoonAlbums(coreId: String, rows: List<RoonAlbumRow>): Pair<Int, Int>
    fun roonAlbums(coreId: String): List<RoonAlbumRow>
    fun roonAlbum(coreId: String, albumKey: String): RoonAlbumRow?
    fun roonAlbumCount(coreId: String): Int
    /** Filled in when something drills into an album, so the next run need not. */
    fun setRoonAlbumTrackCount(coreId: String, albumKey: String, trackCount: Int?)
    fun clearRoonAlbums(coreId: String)
    fun saveRoonScan(summary: com.musicd.migrate.roon.RoonScanSummary)
    fun roonScan(): com.musicd.migrate.roon.RoonScanSummary?

    // ---------------------------------------------------------- match cache

    /**
     * A cached MISS (toId null in a row that EXISTS) is a real answer and must
     * be returned as one — hence the nullable wrapper rather than a nullable
     * String. Treating "we looked and found nothing" as "we have not looked"
     * makes every re-run pay again for exactly the tracks that are slowest,
     * because a miss costs the full fallback search.
     */
    fun cachedMatch(fromService: String, fromId: String, toService: String, kind: String):
        CachedMatch?

    fun cacheMatch(fromService: String, fromId: String, toService: String, kind: String,
                   toId: String?, method: String)

    fun clearMatchCache()
    fun matchCacheSize(): Int

    // ----------------------------------------------------------------- jobs

    fun createJob(id: String, direction: String, options: String, dryRun: Boolean)
    fun updateProgress(id: String, progressJson: String)
    fun finishJob(id: String, status: String, error: String?)
    fun job(id: String): JobRow?
    fun jobs(limit: Int = 25): List<JobRow>
    fun markOrphansInterrupted(): Int
    fun addItems(jobId: String, items: List<JobItem>)
    fun items(jobId: String, status: String? = null): List<JobItem>
    fun itemCounts(jobId: String): Map<String, Int>
    fun deleteJob(id: String)
    fun close()
}

data class CachedMatch(val toId: String?, val method: String)

/**
 * One scanned Roon album.
 *
 * Keyed by a hash of its normalised title and artist rather than by Roon's own
 * item_key: those are SESSION-SCOPED and meaningless once the browse session
 * is re-navigated, and a stored one would drill into whatever has since taken
 * that key. `position` is the album's offset in Roon's alphabetically-stable
 * album list, kept as a hint for finding it again — checked against the title
 * before it is used, so a stale hint can only cost a slower lookup, never the
 * wrong album.
 *
 * `trackCount` is null until something drills in. A ten thousand album library
 * is a hundred load calls to list; drilling every one for a count is twenty
 * thousand.
 */
data class RoonAlbumRow(
    val albumKey: String,
    val title: String,
    val artist: String,
    val position: Int,
    val imageKey: String? = null,
    val trackCount: Int? = null
)

data class JobRow(
    val id: String,
    val created: Long,
    val finished: Long?,
    val direction: String,
    val status: String,
    val dryRun: Boolean,
    val optionsJson: String,
    val progressJson: String,
    val error: String?
)

data class JobItem(
    val kind: String,
    val sourceId: String,
    val sourceLabel: String,
    val status: String,
    val container: String? = null,
    val targetId: String? = null,
    val method: String? = null,
    val note: String? = null
)
