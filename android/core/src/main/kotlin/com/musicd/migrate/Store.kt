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
