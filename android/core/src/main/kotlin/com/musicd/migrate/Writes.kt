package com.musicd.migrate

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Writing matched ids AS THEY ACCUMULATE, and the worker pool the lookups run
 * on. Split out of Migration.kt; see [Writes.WRITE_BATCH] for why writing
 * as we go is a correctness fix rather than an optimisation.
 */
class Writes(private val dryRun: Boolean, private val log: JobLog) {

    /**
     * Somewhere to put matched ids that writes them AS THEY ACCUMULATE.
     *
     * See [WRITE_BATCH] for why this is a correctness fix rather than an
     * optimisation, and why it costs no extra requests.
     */
    inner class Writer(
        private val what: String,
        private val fn: (List<String>) -> Unit
    ) {
        private val held = ArrayList<String>()

        fun add(id: String) {
            // The batch is taken out UNDER THE LOCK, before the write, so two
            // workers cannot carry off the same ids.
            val batch = synchronized(held) {
                held.add(id)
                if (held.size < WRITE_BATCH) return
                ArrayList(held).also { held.clear() }
            }
            write(what, batch, fn, quiet = true)
        }

        fun flush(quiet: Boolean = false) {
            while (true) {
                val batch = synchronized(held) {
                    if (held.isEmpty()) return
                    val take = minOf(held.size, WRITE_BATCH)
                    ArrayList(held.subList(0, take)).also {
                        repeat(take) { _ -> held.removeAt(0) }
                    }
                }
                write(what, batch, fn, quiet)
            }
        }
    }

    private val writers = java.util.Collections.synchronizedList(ArrayList<Writer>())

    fun writer(what: String, fn: (List<String>) -> Unit): Writer =
        Writer(what, fn).also { writers.add(it) }

    /**
     * Write whatever is still held, whatever else has gone wrong.
     *
     * Called from the run's catch as well as its happy path: the whole point
     * is that a run which stops early keeps what it matched. Each writer is
     * tried even if an earlier one throws, because a failed album write is no
     * reason to strand the artists too.
     */
    fun flushAll() {
        for (w in ArrayList(writers)) {
            // write() already records a failed row; this guard only stops one
            // writer's failure from stranding the others.
            runCatching { w.flush(quiet = true) }
        }
    }

    /** @param quiet leave the phase alone — an incremental flush must not
     *   relabel a matching run as "writing". */
    private fun write(
        what: String, ids: List<String>, fn: (List<String>) -> Unit, quiet: Boolean = false
    ) {
        if (dryRun || ids.isEmpty()) return
        if (!quiet) log.report(phase = "writing", label = "Saving ${ids.size} $what")
        try {
            fn(ids)
            log.count("written", ids.size)
        } catch (e: Exception) {
            // The whole batch failed, so every id in it is unwritten. One row
            // rather than one per id, because the endpoint does not say which
            // of the fifty it objected to — but it counts as all of them,
            // which is the true number unwritten.
            log.record(JobItem("write", what, what, "failed",
                note = "saving ${ids.size} $what failed: ${e.message}"), ids.size)
        }
    }

    companion object {
        /**
         * How many matched ids are held before they are written.
         *
         * The endpoint maximum on both services for a library write, which is
         * what makes writing as we go FREE: a flush of fifty makes exactly
         * the request that batch would have made in one write at the end, and
         * both clients chunk at the same number.
         *
         * Writing only at the end was the bug. A run that was cancelled,
         * tripped a circuit breaker, hit Android's six-hour foreground cap or
         * simply had its process killed wrote NOTHING, however many albums it
         * had matched — and into Spotify a ten thousand album library takes
         * hours against a rate-limited search, so any interruption in those
         * hours saved nothing at all. Into Qobuz the phase finished and the
         * write happened, which is why only one direction looked broken.
         */
        const val WRITE_BATCH = 50
    }
}

/**
 * A small worker pool. `fn` gets the item and its index, so callers that
 * care about order can write results into a slot.
 *
 * Low concurrency on purpose: both services rate limit, and the 429
 * backoff costs far more than the extra threads save.
 */
fun <T> eachWithConcurrency(concurrency: Int, list: List<T>, fn: (T, Int) -> Unit) {
    if (list.isEmpty()) return
    val n = concurrency.coerceIn(1, 8).coerceAtMost(list.size)
    if (n == 1) {
        list.forEachIndexed { i, item -> fn(item, i) }
        return
    }
    val pool = Executors.newFixedThreadPool(n) { r ->
        Thread(r, "migrate-worker").apply { isDaemon = true }
    }
    val next = AtomicInteger(0)
    val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
    try {
        repeat(n) {
            pool.execute {
                while (failure.get() == null) {
                    val i = next.getAndIncrement()
                    if (i >= list.size) return@execute
                    try {
                        fn(list[i], i)
                    } catch (e: Throwable) {
                        // The FIRST failure wins and the rest stop. A
                        // cancel or an expired sign-in has to reach the
                        // caller, and swallowing it here would leave the
                        // run reporting misses for a library it never
                        // actually searched.
                        failure.compareAndSet(null, e)
                        return@execute
                    }
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(12, TimeUnit.HOURS)
    } finally {
        pool.shutdownNow()
    }
    failure.get()?.let { throw it }
}
