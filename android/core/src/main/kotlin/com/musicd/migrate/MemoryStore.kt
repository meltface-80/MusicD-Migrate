package com.musicd.migrate

/**
 * The store the tests run against.
 *
 * Not a stub — it implements every rule the SQLite one does, including the
 * cached-miss distinction and the orphan sweep, because those are the parts
 * the migration engine's behaviour actually turns on. A test double that got
 * those wrong would let a real bug through.
 */
class MemoryStore : Store {
    private val settings = HashMap<String, String>()
    private val cache = HashMap<String, CachedMatch>()
    private val jobRows = LinkedHashMap<String, JobRow>()
    private val jobItems = HashMap<String, MutableList<JobItem>>()

    private fun key(a: String, b: String, c: String, d: String) = "$a|$b|$c|$d"

    override fun setting(key: String): String? = settings[key]
    override fun putSetting(key: String, value: String) { settings[key] = value }
    override fun deleteSetting(key: String) { settings.remove(key) }

    override fun cachedMatch(fromService: String, fromId: String, toService: String,
                             kind: String): CachedMatch? =
        cache[key(fromService, fromId, toService, kind)]

    override fun cacheMatch(fromService: String, fromId: String, toService: String,
                            kind: String, toId: String?, method: String) {
        cache[key(fromService, fromId, toService, kind)] = CachedMatch(toId, method)
    }

    override fun clearMatchCache() = cache.clear()
    override fun matchCacheSize() = cache.size

    override fun createJob(id: String, direction: String, options: String, dryRun: Boolean) {
        jobRows[id] = JobRow(id, System.currentTimeMillis(), null, direction, "running",
            dryRun, options, "{}", null)
        jobItems[id] = ArrayList()
    }

    override fun updateProgress(id: String, progressJson: String) {
        jobRows[id]?.let { jobRows[id] = it.copy(progressJson = progressJson) }
    }

    override fun finishJob(id: String, status: String, error: String?) {
        jobRows[id]?.let {
            jobRows[id] = it.copy(status = status, finished = System.currentTimeMillis(),
                error = error)
        }
    }

    override fun job(id: String) = jobRows[id]

    override fun jobs(limit: Int) = jobRows.values.sortedByDescending { it.created }.take(limit)

    override fun markOrphansInterrupted(): Int {
        var n = 0
        for ((id, row) in jobRows.toList()) {
            if (row.status == "running") {
                jobRows[id] = row.copy(status = "interrupted",
                    finished = System.currentTimeMillis(),
                    error = "The app stopped while this was running.")
                n++
            }
        }
        return n
    }

    override fun addItems(jobId: String, items: List<JobItem>) {
        jobItems.getOrPut(jobId) { ArrayList() }.addAll(items)
    }

    override fun items(jobId: String, status: String?): List<JobItem> {
        val all = jobItems[jobId].orEmpty()
        return if (status == null) all else all.filter { it.status == status }
    }

    override fun itemCounts(jobId: String): Map<String, Int> =
        jobItems[jobId].orEmpty().groupingBy { it.status }.eachCount()

    override fun deleteJob(id: String) {
        jobRows.remove(id)
        jobItems.remove(id)
    }

    override fun close() {}
}
