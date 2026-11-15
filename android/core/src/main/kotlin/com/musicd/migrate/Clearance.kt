package com.musicd.migrate

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/*
 * Clearance.kt — emptying a library: favourite tracks, favourite albums,
 * followed artists and playlists, on one service.
 *
 * THE ONE THING IN THIS APP THAT CANNOT BE UNDONE. A migration only ever adds;
 * this only ever removes, and neither service has a recycle bin. So it is
 * built the other way round from everything else here — the dangerous path is
 * the one that has to be asked for, three times over:
 *
 * 1. A PREVIEW IS THE DEFAULT. A dry run reads everything it would remove and
 *    lists it, item by item, with each item's id, and removes nothing. That
 *    list — downloadable as a CSV — is also the only record of what was there
 *    once it is gone, so the page tells the user to keep it.
 * 2. A REAL RUN NEEDS THE SERVICE'S NAME TYPED. Enforced by MigrateApi on the
 *    server, not only by the page, so a stray request cannot empty a library.
 * 3. A PLAYLIST SOMEBODY ELSE MADE IS LEFT ALONE unless the user ticks the box
 *    that says otherwise. Its row says why it was kept.
 *
 * Every item gets a row: `listed` in a preview, `removed` or `failed` in a
 * real run, `skipped` with the reason when it was deliberately kept. A batch
 * that fails marks every id in it failed — the rule for writes everywhere in
 * this app — and the run carries on, unless no batch at all has worked, which
 * is the removal itself being broken.
 */

data class ClearOptions(
    val doTracks: Boolean = false,
    val doAlbums: Boolean = false,
    val doArtists: Boolean = false,
    val doPlaylists: Boolean = false,
    /** Unfollow playlists somebody else made as well. Off unless asked. */
    val includeOthersPlaylists: Boolean = false,
    /** ON unless the caller says otherwise: the safe answer to a missing field. */
    val dryRun: Boolean = true
)

class Clearance(
    private val target: MusicTarget,
    private val store: Store,
    private val jobId: String,
    private val options: ClearOptions
) : RunningJob {

    private val service = target.serviceName
    private val serviceLabel = service.replaceFirstChar { it.uppercase() }

    private val cancelled = AtomicBoolean(false)
    private val okBatches = AtomicInteger(0)
    private val failedBatches = AtomicInteger(0)
    private val log = JobLog(store, jobId)

    override fun cancel() { cancelled.set(true) }

    /** The same notice a migration shows, and for the same reason: a run held
     *  by the service must not look like a run that has hung. */
    override fun noteRateLimit(ms: Long) {
        val n = log.rateLimits.incrementAndGet()
        log.report(label = "$service is rate limiting this app — waiting " +
            "${ms / 1000}s ($n so far)", force = true)
    }

    private fun checkCancelled() { if (cancelled.get()) throw Cancelled() }

    // --------------------------------------------------------------------- run

    fun run(): Map<String, Int> {
        try {
            log.report(phase = "reading", label = "Reading your $serviceLabel library")
            if (options.doTracks) {
                clearKind("tracks", "track", "favourite tracks", TRACK_BATCH,
                    target.savedTracks().map { it.id to Migration.trackLabel(it) }) {
                    target.unsaveTracks(it)
                }
            }
            if (options.doAlbums) {
                clearKind("albums", "album", "favourite albums", ALBUM_BATCH,
                    target.savedAlbums().map { it.id to Migration.albumLabel(it) }) {
                    target.unsaveAlbums(it)
                }
            }
            if (options.doArtists) {
                clearKind("artists", "artist", "followed artists", ARTIST_BATCH,
                    target.followedArtists().map { it.id to it.name }) {
                    target.unfollowArtists(it)
                }
            }
            if (options.doPlaylists) clearPlaylists()

            log.flush()
            log.report(phase = "done",
                label = if (options.dryRun) "Preview finished — nothing was removed"
                        else "Finished")
            return log.countsMap()
        } catch (e: Cancelled) {
            log.flush()
            log.report(phase = "cancelled", label = "Cancelled")
            throw e
        } catch (e: Exception) {
            log.flush()
            log.report(phase = "failed", label = e.message ?: "Failed")
            throw e
        } catch (t: Throwable) {
            // An Error is not an Exception — see MigrateApi.runJob. The rows
            // already recorded are the record of what is gone, so they are
            // saved whatever happens.
            log.flush()
            log.report(phase = "failed", label = "the app hit a " + t.javaClass.simpleName)
            throw t
        }
    }

    /**
     * One kind of favourite: list it, then (unless previewing) remove it in
     * batches, recording a row per item.
     *
     * Read BEFORE anything is removed, and removed from that snapshot rather
     * than by re-reading page one until it is empty: a re-read loop that hit a
     * service which acknowledges a removal and keeps listing the item would
     * never end, and offsets shift under a list that is shrinking.
     */
    private fun clearKind(
        step: String, rowKind: String, what: String, batchSize: Int,
        items: List<Pair<String, String>>, remove: (List<String>) -> Unit
    ) {
        checkCancelled()
        log.report(phase = if (options.dryRun) "reading" else "removing", step = step,
            total = items.size, done = 0,
            label = "${what.replaceFirstChar { it.uppercase() }} — ${items.size} " +
                if (options.dryRun) "to list" else "to remove")

        val usable = ArrayList<Pair<String, String>>()
        for ((id, label) in items) {
            if (id.isEmpty()) {
                log.record(JobItem(rowKind, "-", label, "skipped",
                    note = "$serviceLabel gave no id for it, so it cannot be removed"))
            } else {
                usable.add(id to label)
            }
        }

        var done = items.size - usable.size
        for (batch in usable.chunked(batchSize)) {
            checkCancelled()
            if (options.dryRun) {
                for ((id, label) in batch) {
                    log.record(JobItem(rowKind, id, label, "listed",
                        note = "would be removed from your $what"))
                }
            } else {
                val ids = batch.map { it.first }
                val error = attempt { remove(ids) }
                for ((id, label) in batch) {
                    log.record(if (error == null)
                        JobItem(rowKind, id, label, "removed",
                            note = "removed from your $what")
                    else
                        JobItem(rowKind, id, label, "failed",
                            note = "removing it failed: $error"))
                }
                if (error != null) noteFailedBatch(error)
            }
            done += batch.size
            log.report(done = done)
        }
    }

    /**
     * Playlists, one call each — neither service removes them in bulk.
     *
     * Ownership decides what happens, so it has to be KNOWN. A playlist whose
     * owner the service did not report is left alone, and if the account
     * itself cannot be identified nothing is touched at all: deleting
     * somebody else's playlist is refused anyway, and unfollowing one the user
     * made would not remove it — guessing gets it wrong either way.
     */
    private fun clearPlaylists() {
        checkCancelled()
        val me = accountId()
        val all = target.playlists()
        log.report(phase = if (options.dryRun) "reading" else "removing", step = "playlists",
            total = all.size, done = 0,
            label = "Playlists — ${all.size} in your library")

        if (me.isEmpty()) {
            log.record(JobItem("playlist", "-", "$serviceLabel playlists", "failed",
                note = "$serviceLabel would not say which account this is, so there is no " +
                    "telling your playlists from the ones you follow. None was touched."))
            return
        }

        for ((i, pl) in all.withIndex()) {
            checkCancelled()
            val label = pl.name.ifEmpty { "(untitled)" } +
                " (${pl.trackCount} track${if (pl.trackCount == 1) "" else "s"})"
            val owned = pl.ownerId == me
            when {
                pl.id.isEmpty() -> log.record(JobItem("playlist", "-", label, "skipped",
                    note = "$serviceLabel gave no id for it, so it cannot be removed"))

                pl.ownerId.isEmpty() -> log.record(JobItem("playlist", pl.id, label, "skipped",
                    note = "$serviceLabel did not say who owns it, so it was left alone"))

                !owned && !options.includeOthersPlaylists ->
                    log.record(JobItem("playlist", pl.id, label, "skipped",
                        note = "a playlist you follow rather than made — kept. Tick " +
                            "\"Also unfollow playlists you don't own\" to remove these too"))

                options.dryRun -> log.record(JobItem("playlist", pl.id, label, "listed",
                    method = if (owned) "delete" else "unfollow",
                    note = if (owned) "would be deleted" else "would be unfollowed"))

                else -> {
                    val error = attempt { target.removePlaylist(pl.id, owned) }
                    if (error == null) {
                        log.record(JobItem("playlist", pl.id, label, "removed",
                            method = if (owned) "delete" else "unfollow",
                            note = if (owned) "deleted" else "unfollowed"))
                    } else {
                        log.record(JobItem("playlist", pl.id, label, "failed",
                            note = "removing it failed: $error"))
                        noteFailedBatch(error)
                    }
                }
            }
            log.report(done = i + 1, label = "Playlists — $label")
        }
    }

    /** Who is signed in, asking once if the client does not know yet. */
    private fun accountId(): String {
        if (target.accountId.isNotEmpty()) return target.accountId
        return try {
            target.me().id
        } catch (e: AuthError) {
            throw e
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Run one removal, returning the service's complaint or null.
     *
     * A dead sign-in, a cancel and a rate limit the service would not lift all
     * STOP the run rather than becoming thousands of red rows: none of them is
     * about the items, and the rest of the list would fail for the same
     * reason. Everything already removed is in the report.
     */
    private fun attempt(fn: () -> Unit): String? =
        try {
            fn()
            okBatches.incrementAndGet()
            null
        } catch (e: AuthError) {
            throw e
        } catch (e: RateLimitError) {
            throw e
        } catch (e: Cancelled) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }

    /**
     * The removal's own circuit breaker. A few failures among many successes
     * are the items; [FAILED_BATCH_LIMIT] failures with not one success is the
     * removal itself being refused, and carrying on would only paint the whole
     * library red.
     */
    private fun noteFailedBatch(reason: String) {
        val n = failedBatches.incrementAndGet()
        if (okBatches.get() > 0 || n < FAILED_BATCH_LIMIT) return
        throw BrokenRead("Stopped after $n removals failed and not one worked: $reason. " +
            "Nothing was removed; the report lists what was tried.")
    }

    companion object {
        /**
         * Ids per removal request, by kind.
         *
         * The service's maximum, and the client chunks at the same numbers, so one batch here is exactly one request and a
         * failed batch names exactly the ids that were not removed. (Albums
         * were twenty while Spotify, which takes no more per DELETE, was
         * supported.)
         */
        const val TRACK_BATCH = 50
        const val ALBUM_BATCH = 50
        const val ARTIST_BATCH = 50

        /** See [noteFailedBatch]. */
        const val FAILED_BATCH_LIMIT = 3
    }
}
