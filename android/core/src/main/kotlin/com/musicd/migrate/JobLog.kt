package com.musicd.migrate

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A run's bookkeeping: the report rows, the counts, and the progress the page
 * polls. One per job, shared by [Migration] and [Clearance] so the two cannot
 * disagree about how a row is counted or how often progress is written.
 */
class JobLog(private val store: Store, private val jobId: String) {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val pending = java.util.Collections.synchronizedList(ArrayList<JobItem>())

    val searches = AtomicInteger(0)
    val cacheHits = AtomicInteger(0)
    val rateLimits = AtomicInteger(0)

    @Volatile var progress = Progress()
        private set
    @Volatile private var lastProgressAt = 0L

    fun countsMap(): Map<String, Int> = counts.mapValues { it.value.get() }

    /** Add to a count with no row of its own, such as "written". */
    fun count(status: String, n: Int) {
        counts.getOrPut(status) { AtomicInteger(0) }.addAndGet(n)
    }

    /** Progress is written to the store and polled by the page. Throttled
     *  because a per-track write is a disk write per track. */
    fun report(
        phase: String = progress.phase, step: String = progress.step,
        label: String = progress.label, done: Int = progress.done, total: Int = progress.total,
        /** Write it out whatever the throttle says. See Migration.noteRateLimit:
         *  a notice nothing overwrites for thirty seconds must not be the one
         *  the throttle drops. */
        force: Boolean = false
    ) {
        progress = Progress(phase, step, label, done, total, countsMap(),
            searches.get(), cacheHits.get(), rateLimits.get())
        val now = System.currentTimeMillis()
        val terminal = phase == "done" || phase == "failed" || phase == "cancelled"
        if (force || terminal || now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
            lastProgressAt = now
            store.updateProgress(jobId, progress.toJson())
        }
    }

    /**
     * @param countAs how many things this row stands for. One, normally. A
     *   failed BATCH write is the exception: it is a single row accounting for
     *   every id in the batch, and counting it as one understates the damage
     *   by forty-nine.
     */
    fun record(item: JobItem, countAs: Int = 1) {
        pending.add(item)
        count(item.status, countAs)
        // Flushed in batches: the job survives a crash to within a hundred
        // rows, which is the right trade for a job re-runnable by design.
        if (pending.size >= 100) flush()
    }

    fun flush() {
        synchronized(pending) {
            if (pending.isEmpty()) return
            store.addItems(jobId, ArrayList(pending))
            pending.clear()
        }
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 400L
    }
}
