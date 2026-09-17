package com.musicd.migrate

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/*
 * Migration.kt — moving a library from one service to the other. The Kotlin
 * twin of lib/migrate.js, and MigrationTest.kt is that file's test suite
 * translated, so the APK and the container behave identically or something
 * fails.
 *
 * ONE CODE PATH, BOTH DIRECTIONS. This class never asks which service it is
 * talking to: SpotifyClient and QobuzClient both implement MusicTarget, so
 * "Qobuz to Spotify" and "Spotify to Qobuz" are the same run with the two
 * swapped.
 *
 * THE THREE PROMISES
 *
 * 1. It never invents a match. Everything goes through Match, which refuses
 *    where the evidence is not decisive, and a refusal is REPORTED rather than
 *    swallowed.
 * 2. It is safe to run twice. Favourites are idempotent because both services'
 *    save endpoints are; playlists are because a second run finds the playlist
 *    it made and adds only what is missing.
 * 3. It never writes anything in a dry run — while doing every read, search
 *    and match, so the preview is the real answer rather than an estimate.
 */

class Cancelled : RuntimeException("Cancelled")

/**
 * The corroboration read is broken, so the run was stopped.
 *
 * Its own type so a caller can tell it from an ordinary failure. The twin of
 * `e.brokenRead` in lib/migrate.js.
 */
class BrokenRead(message: String) : RuntimeException(message)

data class MigrationOptions(
    /** null = all of the user's own; a list = exactly these; empty = none. */
    val playlistIds: List<String>? = null,
    val doPlaylists: Boolean = true,
    val doAlbums: Boolean = true,
    val doArtists: Boolean = true,
    val doTracks: Boolean = true,
    val dryRun: Boolean = false,
    val strict: Boolean = false,
    val toleranceMs: Long = Match.DEFAULT_TOLERANCE_MS,
    /** "add-missing" | "create-new" | "skip" */
    val onExisting: String = "add-missing",
    val includeOthersPlaylists: Boolean = false,
    val playlistSuffix: String = "",
    val concurrency: Int = 4,
    /**
     * Check a barcode-less album match against the album's own track listing.
     *
     * On by default: without a barcode, title and artist are not decisive
     * evidence, and this is the app that refuses where the evidence is not
     * decisive. Costs up to two extra reads per album.
     */
    val corroborate: Boolean = true
)

data class Progress(
    val phase: String = "starting",
    val step: String = "",
    val label: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val counts: Map<String, Int> = emptyMap(),
    val searches: Int = 0,
    val cacheHits: Int = 0,
    /** How many times a service has held the run. The page's spelling, which
     *  is the authority; lib/migrate.js emits the same name. */
    val rateLimits: Int = 0
) {
    fun toJson(): String = buildString {
        append("{")
        append("\"phase\":").append(jsonQuote(phase)).append(",")
        append("\"step\":").append(jsonQuote(step)).append(",")
        append("\"label\":").append(jsonQuote(label)).append(",")
        append("\"done\":").append(done).append(",")
        append("\"total\":").append(total).append(",")
        append("\"searches\":").append(searches).append(",")
        append("\"cacheHits\":").append(cacheHits).append(",")
        append("\"rateLimits\":").append(rateLimits).append(",")
        append("\"counts\":{")
        append(counts.entries.joinToString(",") { jsonQuote(it.key) + ":" + it.value })
        append("}}")
    }
}

class Migration(
    /** Read-only is enough for a source; Roon is one. See Model.kt. */
    private val source: MusicSource,
    private val target: MusicTarget,
    private val store: Store,
    private val jobId: String,
    private val options: MigrationOptions
) {
    private val sourceName = source.serviceName
    private val targetName = target.serviceName

    private val cancelled = AtomicBoolean(false)
    private val counts = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    private val pending = java.util.Collections.synchronizedList(ArrayList<JobItem>())
    private val searches = AtomicInteger(0)
    private val cacheHits = AtomicInteger(0)
    private val unreadable = AtomicInteger(0)
    private val corroborated = AtomicInteger(0)
    private val searchFails = AtomicInteger(0)
    private val searchOk = AtomicInteger(0)
    private val rateLimits = AtomicInteger(0)

    @Volatile private var progress = Progress()
    @Volatile private var lastProgressAt = 0L

    /**
     * A service has told the run to wait, and the page must say so.
     *
     * "Roon to Spotify seems unresponsive" was this, invisible: the progress
     * label only changes when an album FINISHES, so a run whose every search
     * is being held for thirty seconds shows a counter that does not move and
     * no reason at all. [SpotifyClient.onRateLimit] existed on both clients,
     * in both languages, and was passed by nothing but the tests — dead code
     * in production, which is why a throttled run and a hung one looked
     * identical.
     *
     * Reported as a label rather than an error: the run is working as intended
     * and will finish. Nothing overwrites the label until something completes,
     * which is exactly the case where the user needs to read it.
     *
     * Kept in step with noteRateLimit in lib/migrate.js by hand.
     */
    fun noteRateLimit(ms: Long) {
        val n = rateLimits.incrementAndGet()
        // force: the throttle would otherwise drop this, and the label it
        // dropped is the only thing on the page that explains a run that is
        // about to sit still for thirty seconds. A test caught exactly that.
        report(label = "$targetName is rate limiting this app — waiting " +
            "${ms / 1000}s ($n so far)", force = true)
    }

    fun cancel() { cancelled.set(true) }

    /**
     * An album could not be CHECKED. Count it, and stop the run if the check
     * itself is plainly broken.
     *
     * @see UNREADABLE_LIMIT
     */
    private fun noteUnreadable(reason: String) {
        val n = unreadable.incrementAndGet()
        if (corroborated.get() > 0 || n < UNREADABLE_LIMIT) return
        throw BrokenRead(
            "Stopped after $n albums could not be checked and not one could: " +
            reason + ". This is the track-listing check failing, not your " +
            "library — carrying on would have searched for thousands of albums " +
            "and reported every one of them as not found. Nothing already " +
            "matched has been lost. Turning off \"check the track listing\" " +
            "will migrate on title and artist alone, which is weaker evidence.")
    }

    /**
     * A search could not be made, so this item has no answer either way.
     *
     * Red rather than amber, and NOT cached — the two halves of the same rule
     * that governs a failed corroboration read. The reason quotes the
     * service's own words ("Spotify is rate limiting this app and did not let
     * up"), which is the difference between a user who waits and re-runs and
     * a user who concludes their records are not on the service.
     */
    private fun searchFailed(message: String) = Resolved(
        null, null, "could not search $targetName: $message",
        problem = true, searchFailure = true)

    /**
     * Record that a search failed, and stop the run if searches never work.
     *
     * The same circuit breaker as [UNREADABLE_LIMIT] and deliberately a
     * SEPARATE pair of counters from the corroboration one: if searches work
     * and the listing check is broken, that one must still fire, and sharing
     * a "something worked" flag would disarm both.
     *
     * This is what "Roon to Spotify seems unresponsive" turned out to be.
     * Nine thousand albums against a rate-limited search endpoint, every one
     * waiting out its backoff and then being recorded as a miss, is a run that
     * takes days to report a library as absent. Stopping on the fiftieth, with
     * nothing having worked, says so in the first minute instead.
     */
    private fun noteSearchFailure(reason: String) {
        val n = searchFails.incrementAndGet()
        if (searchOk.get() > 0 || n < UNREADABLE_LIMIT) return
        throw BrokenRead(
            "Stopped after $n searches failed and not one succeeded: " + reason +
            ". Carrying on would have reported your whole library as missing " +
            "from $targetName. Nothing already matched has been lost, and none " +
            "of these was cached — re-running picks up where this left off.")
    }

    private fun checkCancelled() { if (cancelled.get()) throw Cancelled() }

    private fun countsMap(): Map<String, Int> = counts.mapValues { it.value.get() }

    /** Progress is written to the store and polled by the page. Throttled
     *  because a per-track write is a disk write per track. */
    private fun report(
        phase: String = progress.phase, step: String = progress.step,
        label: String = progress.label, done: Int = progress.done, total: Int = progress.total,
        /** Write it out whatever the throttle says. See noteRateLimit: a
         *  notice nothing overwrites for thirty seconds must not be the one
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
    private fun record(item: JobItem, countAs: Int = 1) {
        pending.add(item)
        counts.getOrPut(item.status) { AtomicInteger(0) }.addAndGet(countAs)
        // Flushed in batches: the job survives a crash to within a hundred
        // rows, which is the right trade for a job re-runnable by design.
        if (pending.size >= 100) flush()
    }

    private fun flush() {
        synchronized(pending) {
            if (pending.isEmpty()) return
            store.addItems(jobId, ArrayList(pending))
            pending.clear()
        }
    }

    // --------------------------------------------------------------------- run

    fun run(): Map<String, Int> {
        try {
            report(phase = "reading", label = "Reading your libraries")
            val existing = readExisting()
            checkCancelled()

            if (options.doTracks && !refuseUnsupported("tracks", "track")) {
                migrateSavedTracks(existing)
            }
            if (options.doAlbums && !refuseUnsupported("albums", "album")) {
                migrateSavedAlbums(existing)
            }
            if (options.doArtists && !refuseUnsupported("artists", "artist")) {
                migrateArtists(existing)
            }
            if (options.doPlaylists && !refuseUnsupported("playlists", "playlist")) {
                migratePlaylists()
            }

            flush()
            report(phase = "done", label = "Finished")
            return countsMap()
        } catch (e: Cancelled) {
            flush()
            report(phase = "cancelled", label = "Cancelled")
            throw e
        } catch (e: Exception) {
            flush()
            report(phase = "failed", label = e.message ?: "Failed")
            throw e
        } catch (t: Throwable) {
            // An Error is not an Exception. Letting one past here would leave
            // the progress saying "matching" forever while the process died
            // under it. The rows recorded so far are flushed either way.
            flush()
            report(phase = "failed", label = "the app hit a " + t.javaClass.simpleName)
            throw t
        }
    }

    private class Existing {
        val trackIsrc = HashSet<String>()
        val trackKey = HashSet<String>()
        val albumUpc = HashSet<String>()
        val albumKey = HashSet<String>()
        val artistKey = HashSet<String>()
    }

    /**
     * What the destination already has.
     *
     * Indexed by ISRC and by a canonical "title|artist" key, because the same
     * recording carries different ids on the two services and the id is the
     * one thing that cannot be compared. Only the kinds actually being
     * migrated are read — a playlists-only run has no reason to page someone's
     * 4,000 saved tracks.
     */
    private fun readExisting(): Existing {
        val out = Existing()
        if (options.doTracks) {
            for (t in target.savedTracks()) {
                if (t.isrc.isNotEmpty()) out.trackIsrc.add(t.isrc.uppercase())
                out.trackKey.add(trackKey(t))
            }
        }
        checkCancelled()
        if (options.doAlbums) {
            for (a in target.savedAlbums()) {
                if (a.upc.isNotEmpty()) out.albumUpc.add(digits(a.upc))
                out.albumKey.add(albumKey(a))
            }
        }
        checkCancelled()
        if (options.doArtists) {
            for (a in target.followedArtists()) out.artistKey.add(Canon.canon(a.name))
        }
        return out
    }

    // ----------------------------------------------------------- saved tracks

    private fun migrateSavedTracks(existing: Existing) {
        val src = source.savedTracks()
        checkCancelled()
        report(phase = "matching", step = "tracks", total = src.size, done = 0,
            label = "Favourite tracks — ${src.size} to check")

        val toWrite = java.util.Collections.synchronizedList(ArrayList<String>())
        val done = AtomicInteger(0)
        eachWithConcurrency(src) { t, _ ->
            checkCancelled()
            val label = trackLabel(t)
            if ((t.isrc.isNotEmpty() && existing.trackIsrc.contains(t.isrc.uppercase())) ||
                existing.trackKey.contains(trackKey(t))) {
                record(JobItem("track", t.id, label, "already",
                    note = "already a favourite there"))
            } else {
                val r = resolveTrack(t)
                if (r.id != null) {
                    toWrite.add(r.id)
                    record(JobItem("track", t.id, label, "matched", targetId = r.id,
                        method = r.method, note = r.reason))
                } else {
                    // Red rather than amber when the lookup could not be
                    // MADE, exactly as for an album. Recorded first, then
                    // counted, so the row that stops the run is in the report
                    // that says why.
                    record(JobItem("track", t.id, label,
                        if (r.problem) "failed" else "unmatched", note = r.reason))
                    if (r.searchFailure) noteSearchFailure(r.reason)
                }
            }
            report(done = done.incrementAndGet(), label = "Favourite tracks — $label")
        }

        write("favourite tracks", toWrite) { target.saveTracks(it) }
    }

    // ----------------------------------------------------------- saved albums

    private fun migrateSavedAlbums(existing: Existing) {
        val src = source.savedAlbums()
        checkCancelled()
        report(phase = "matching", step = "albums", total = src.size, done = 0,
            label = "Favourite albums — ${src.size} to check")

        val toWrite = java.util.Collections.synchronizedList(ArrayList<String>())
        val done = AtomicInteger(0)
        eachWithConcurrency(src) { a, _ ->
            checkCancelled()
            val label = albumLabel(a)
            if ((a.upc.isNotEmpty() && existing.albumUpc.contains(digits(a.upc))) ||
                existing.albumKey.contains(albumKey(a))) {
                record(JobItem("album", a.id, label, "already",
                    note = "already a favourite there"))
            } else {
                val r = resolveAlbum(a)
                if (r.id != null) {
                    toWrite.add(r.id)
                    record(JobItem("album", a.id, label, "matched", targetId = r.id,
                        method = r.method, note = r.reason))
                } else {
                    // `failed`, not `unmatched`, when the lookup could not be
                    // MADE. It renders red rather than amber, and that
                    // difference is the whole point: 0.2.0 shipped with a
                    // broken corroboration read, every album came back "not
                    // found", and it read as a library that is not on the
                    // other service rather than as a thing that was broken.
                    record(JobItem("album", a.id, label,
                        if (r.problem) "failed" else "unmatched", note = r.reason))
                    // Recorded first, then counted: if this is the one that
                    // stops the run, its row has to be in the report that
                    // says why.
                    if (r.searchFailure) noteSearchFailure(r.reason)
                    else if (r.problem) noteUnreadable(r.reason)
                }
            }
            report(done = done.incrementAndGet(), label = "Favourite albums — $label")
        }

        write("favourite albums", toWrite) { target.saveAlbums(it) }
    }

    // ---------------------------------------------------------------- artists

    private fun migrateArtists(existing: Existing) {
        val src = source.followedArtists()
        checkCancelled()
        report(phase = "matching", step = "artists", total = src.size, done = 0,
            label = "Artists — ${src.size} to check")

        val toWrite = java.util.Collections.synchronizedList(ArrayList<String>())
        val done = AtomicInteger(0)
        eachWithConcurrency(src) { a, _ ->
            checkCancelled()
            if (existing.artistKey.contains(Canon.canon(a.name))) {
                record(JobItem("artist", a.id, a.name, "already", note = "already followed there"))
            } else {
                val r = resolveArtist(a)
                if (r.id != null) {
                    toWrite.add(r.id)
                    record(JobItem("artist", a.id, a.name, "matched", targetId = r.id,
                        method = r.method, note = r.reason))
                } else {
                    record(JobItem("artist", a.id, a.name,
                        if (r.problem) "failed" else "unmatched", note = r.reason))
                    if (r.searchFailure) noteSearchFailure(r.reason)
                }
            }
            report(done = done.incrementAndGet(), label = "Artists — ${a.name}")
        }

        write("artists", toWrite) { target.followArtists(it) }
    }

    // -------------------------------------------------------------- playlists

    private fun migratePlaylists() {
        val all = source.playlists()
        val wanted = if (options.playlistIds != null) {
            // The user named them. No ownership check is wanted or needed.
            all.filter { options.playlistIds.contains(it.id) }
        } else {
            // Filtering to the user's OWN playlists needs to know who they
            // are, and a client built straight from a stored session has never
            // called me(). When the stored id was empty, `ownerId == ""` was
            // false for every playlist, EVERY ONE was filtered out, and the
            // migration reported nothing while looking like it had simply
            // found nothing to do.
            val me = sourceAccountId()
            all.filter {
                options.includeOthersPlaylists || it.ownerId.isEmpty() ||
                    // Still unknown: the service would not say. There is then
                    // no way to tell an owned playlist from a followed one,
                    // and including them is the useful failure — silently
                    // migrating nothing is not.
                    me.isEmpty() || it.ownerId == me
            }
        }

        // Read once, before the loop: doing this per playlist would be one
        // full paged read per playlist.
        val byName = target.playlists().associateBy { Canon.canon(it.name) }

        for ((pi, pl) in wanted.withIndex()) {
            checkCancelled()
            val targetName = pl.name.ifEmpty { "Playlist" } + options.playlistSuffix
            val existingPl = byName[Canon.canon(targetName)]

            if (existingPl != null && options.onExisting == "skip") {
                record(JobItem("playlist", pl.id, pl.name, "skipped",
                    targetId = existingPl.id,
                    note = "a playlist called \"$targetName\" is already there"))
                continue
            }

            val tracks = source.playlistTracks(pl.id)
            report(phase = "matching", step = "playlist", total = tracks.size, done = 0,
                label = "Playlist ${pi + 1} of ${wanted.size}: ${pl.name} " +
                    "(${tracks.size} tracks)")

            // Already in the destination playlist, when reusing it. Only then:
            // for a brand-new playlist this is a request that can only return
            // nothing.
            val present = HashSet<String>()
            if (existingPl != null && options.onExisting == "add-missing") {
                for (t in target.playlistTracks(existingPl.id)) present.add(t.id)
            }

            // Positions are preserved even though lookups run concurrently:
            // results are written back into a slot and read in order
            // afterwards. Pushing as they land would shuffle every playlist
            // into completion order.
            val resolved = arrayOfNulls<String>(tracks.size)
            val done = AtomicInteger(0)
            eachWithConcurrency(tracks) { t, i ->
                checkCancelled()
                if (t.skip != null) {
                    record(JobItem("track", "local:$i", t.title, "skipped",
                        container = pl.name,
                        note = "a ${t.skip}, which cannot be migrated"))
                } else {
                    val r = resolveTrack(t)
                    if (r.id != null) {
                        resolved[i] = r.id
                        record(JobItem("track", t.id, trackLabel(t), "matched",
                            container = pl.name, targetId = r.id, method = r.method,
                            note = r.reason))
                    } else {
                        record(JobItem("track", t.id, trackLabel(t), "unmatched",
                            container = pl.name, note = r.reason))
                    }
                }
                report(done = done.incrementAndGet())
            }

            // De-duplicated WITHIN the playlist too: two source tracks can
            // legitimately resolve to one destination track (the album cut and
            // the single, same ISRC), and adding it twice is a duplicate the
            // user did not have.
            val unique = resolved.filterNotNull().filter { !present.contains(it) }.distinct()

            if (options.dryRun) {
                record(JobItem("playlist", pl.id, pl.name, "matched",
                    note = "would ${if (existingPl != null) "add to" else "create"} " +
                        "\"$targetName\" with ${unique.size} " +
                        "track${if (unique.size == 1) "" else "s"}"))
                continue
            }

            if (unique.isEmpty()) {
                record(JobItem("playlist", pl.id, pl.name, "already",
                    targetId = existingPl?.id,
                    note = if (existingPl != null) "every track was already in it"
                           else "nothing in it could be matched, so it was not created"))
                continue
            }

            report(phase = "writing", label = "Writing $targetName")
            try {
                val destId: String
                val note: String
                if (existingPl != null && options.onExisting == "add-missing") {
                    destId = existingPl.id
                    note = "added ${unique.size} track${if (unique.size == 1) "" else "s"} " +
                        "to the existing \"$targetName\""
                } else {
                    val made = target.createPlaylist(targetName,
                        "Migrated from ${sourceName.replaceFirstChar { it.uppercase() }} " +
                            "by MusicD Migrate.", false)
                    destId = made.id
                    note = "created with ${unique.size} " +
                        "track${if (unique.size == 1) "" else "s"}"
                }
                target.addToPlaylist(destId, unique)
                counts.getOrPut("written") { AtomicInteger(0) }.addAndGet(unique.size)
                record(JobItem("playlist", pl.id, pl.name, "matched", targetId = destId,
                    note = note))
            } catch (e: Exception) {
                record(JobItem("playlist", pl.id, pl.name, "failed", note = e.message))
            }
        }
    }

    /**
     * Who the source account is, asking the service once if it does not know.
     *
     * Cached: a failure is worth one request per migration, not one per
     * playlist.
     */
    private var cachedAccountId: String? = null

    private fun sourceAccountId(): String {
        cachedAccountId?.let { return it }
        var id = source.accountId
        if (id.isEmpty()) {
            id = try {
                source.me().id
            } catch (e: AuthError) {
                throw e
            } catch (e: Exception) {
                ""
            }
        }
        cachedAccountId = id
        return id
    }

    // ------------------------------------------------------------------ lookups

    data class Resolved(
        val id: String?,
        val method: String?,
        val reason: String,
        /**
         * Nothing was matched because a READ FAILED, not because the thing is
         * not there. The row is reported as `failed` rather than `unmatched`,
         * and the refusal is not cached.
         *
         * Kept in step with `problem` in lib/migrate.js by hand.
         */
        val problem: Boolean = false,
        /**
         * The failed read was a SEARCH. Counted against its own circuit
         * breaker rather than the corroboration one — see noteSearchFailure.
         *
         * Kept in step with `searchFailure` in lib/migrate.js by hand.
         */
        val searchFailure: Boolean = false
    )

    /** What a search came back with, and why it did not come back at all. */
    class Searched<T>(val results: List<T>, val error: String?)

    /**
     * The lookup, and the reason a migration takes minutes rather than hours:
     * a chance to answer with no network request at all, then at most two
     * searches.
     */
    fun resolveTrack(t: Track): Resolved {
        if (t.id.isEmpty()) return Resolved(null, null, "the source returned no usable track")

        store.cachedMatch(sourceName, t.id, targetName, "track")?.let { c ->
            cacheHits.incrementAndGet()
            return if (c.toId != null) Resolved(c.toId, c.method, "from the match cache")
                   else Resolved(null, null, c.method)
        }

        var result: Match.Result? = null
        // A search that could not be MADE is kept apart from one that came
        // back empty, and only counts if nothing matched in the end.
        var searchError: String? = null

        if (t.isrc.isNotEmpty()) {
            searches.incrementAndGet()
            val got = trySearch { target.searchByIsrc(t.isrc) }
            if (got.error != null) searchError = got.error else searchOk.incrementAndGet()
            result = Match.matchTrack(got.results, t, options.strict, options.toleranceMs)
        }

        if ((result == null || !result.matched) && !options.strict) {
            // Searched WITHOUT the edition suffix and with the lead artist
            // only. Searching for "Blue Monday - 2016 Remaster" by "New Order,
            // Someone" finds nothing on a service that calls it "Blue Monday"
            // by "New Order", and that is the largest source of false misses.
            searches.incrementAndGet()
            val got = trySearch {
                target.searchTracks(Canon.stripVersion(t.title), t.artists.firstOrNull() ?: "")
            }
            if (got.error != null) searchError = got.error else searchOk.incrementAndGet()
            val r2 = Match.matchTrack(got.results, t, options.strict, options.toleranceMs)
            // Keep whichever matched, otherwise whichever refusal is more
            // informative — the ISRC one says only "the search returned
            // nothing", which tells the user nothing they can act on.
            result = if (r2.matched) r2 else (if (result?.matched == true) result else r2)
        }

        val id = result?.track?.id
        if (id == null && searchError != null) return searchFailed(searchError)
        store.cacheMatch(sourceName, t.id, targetName, "track", id,
            if (id != null) result?.method ?: "" else result?.reason ?: "not found")
        return if (id != null) Resolved(id, result?.method, result?.reason ?: "")
               else Resolved(null, null, result?.reason ?: "not found")
    }

    /**
     * Some sources cannot offer some kinds of thing at all, and say so.
     *
     * The twin of refuseUnsupported in lib/migrate.js. Roon is why: a Roon
     * track carries no length, so it can never be matched safely, and neither
     * can a Roon playlist. Returning an empty list would finish the run green
     * having migrated nothing; the reason is RECORDED instead, as a skipped
     * row in the report the user actually reads.
     */
    private fun refuseUnsupported(kind: String, rowKind: String): Boolean {
        val why = source.unsupported[kind] ?: return false
        record(JobItem(rowKind, "-", "$sourceName $kind", "skipped", note = why))
        return true
    }

    fun resolveAlbum(a: Album): Resolved {
        if (a.id.isEmpty()) return Resolved(null, null, "the source returned no usable album")
        store.cachedMatch(sourceName, a.id, targetName, "album")?.let { c ->
            cacheHits.incrementAndGet()
            return if (c.toId != null) Resolved(c.toId, c.method, "from the match cache")
                   else Resolved(null, null, c.method)
        }

        // The barcode first, exactly as a track's ISRC goes first.
        //
        // Fetched BEFORE searching, not after: an earlier version searched by
        // title and only then looked the barcode up, so the barcode was never
        // used to search for anything. Combined with Spotify's album search
        // returning no barcodes at all (SpotifyClient.searchByUpc), the whole
        // barcode tier was dead and every album fell through to the title
        // tiers — 114 of 195 albums reported "not found" on a real library.
        var want = a
        if (a.upc.isEmpty()) {
            safely { source.albumDetail(a.id) }?.let { d ->
                if (d.upc.isNotEmpty()) want = a.copy(upc = d.upc)
            }
        }

        // Still no barcode: the album's own track listing is the only
        // independent evidence there is, so read it now rather than after
        // searching. Doing it first is not a style choice -- the listing's
        // LENGTH is the album's track count, and matchAlbum uses the count to
        // rank the standard edition above the deluxe. Fetched after the
        // search, it would have ranked nothing.
        val corroborating = want.upc.isEmpty() && options.corroborate
        var wantTitles: List<Track> = emptyList()
        if (corroborating) {
            // An empty list here is not a reason to skip the check -- it IS
            // the check, and it fails with "could not read the track
            // listing". Treating a failed read as "do not corroborate" would
            // quietly restore the title-only match this tier replaces.
            wantTitles = safely { source.albumTracks(a.id) } ?: emptyList()
            if (wantTitles.isNotEmpty() && want.trackCount == null) {
                want = want.copy(trackCount = wantTitles.size)
            }
        }

        var r: Match.Result? = null
        // As in resolveTrack: "could not ask" is not "asked and got nothing".
        var searchError: String? = null
        if (want.upc.isNotEmpty()) {
            searches.incrementAndGet()
            val got = trySearch { target.searchByUpc(want.upc) }
            if (got.error != null) searchError = got.error else searchOk.incrementAndGet()
            r = Match.matchAlbum(got.results, want, options.strict)
        }

        // No barcode, or the barcode found nothing: fall back to title and
        // artist with the edition suffix removed, so "Master Of Puppets
        // (Remastered)" searches for what Spotify calls "Master of Puppets".
        if ((r == null || !r.matched) && !options.strict) {
            searches.incrementAndGet()
            val got = trySearch {
                target.searchAlbums(Canon.stripVersion(a.title), searchArtist(a.artists))
            }
            if (got.error != null) searchError = got.error else searchOk.incrementAndGet()
            val r2 = Match.matchAlbum(got.results, want, options.strict)
            // Keep whichever matched, else the more informative refusal.
            r = if (r2.matched) r2 else (if (r?.matched == true) r else r2)
        }
        if (r == null) r = Match.matchAlbum(emptyList(), want, options.strict)

        // A barcode match is decisive and is never second-guessed. A title
        // match without one is not, so it has to be corroborated or given up.
        //
        // `needsListing` is the other way round: matchAlbum found something
        // whose only difference is a trailing tag that says something --
        // "(Live)", a venue, "(Legacy Edition)" -- and refused it, because a
        // title and an artist are not decisive. Those never match on their
        // own; the listing is the only thing that can promote them, so they
        // are only put to it when the user has that check on.
        if ((r.matched || r.needsListing) && corroborating) r = corroborate(r, wantTitles)

        val id = r.album?.id

        // A refusal that came from a read FAILING is not cached. CLAUDE.md's
        // rule is "cache the misses", and a miss is "we looked and found
        // nothing" -- a real answer. "We could not look" is not an answer,
        // and caching it would keep being reused after the thing that broke
        // was fixed, which is how a bug outlives its own repair.
        if (id == null && r.unreadable) {
            return Resolved(null, null, r.reason, problem = true)
        }
        // Same rule for a search we could not make. Checked after
        // corroboration, because a failed barcode search does not matter once
        // the title search has found the record and the listing has agreed.
        if (id == null && searchError != null) return searchFailed(searchError)
        store.cacheMatch(sourceName, a.id, targetName, "album", id,
            if (id != null) r.method ?: "" else r.reason)
        return if (id != null) Resolved(id, r.method, r.reason) else Resolved(null, null, r.reason)
    }

    /**
     * Check a title-tier album match against the album's track listing.
     *
     * Works down matchAlbum's shortlist, which is already ordered, and takes
     * the first candidate whose listing agrees. Every candidate that does not
     * is remembered so the refusal can quote the closest one -- "an album of
     * that name is there but only 3 of your 11 tracks are on it" is something
     * a user can act on, and "not found" is not.
     */
    private fun corroborate(r: Match.Result, wantTitles: List<Track>): Match.Result {
        val shortlist = (if (r.shortlist.isNotEmpty()) r.shortlist else listOfNotNull(r.album))
            .take(CORROBORATE_CANDIDATES)
        var closest: Match.Agreement? = null
        for (cand in shortlist) {
            val tracks = safely { target.albumTracks(cand.id) } ?: emptyList()
            val check = Match.tracklistCorroborates(wantTitles, tracks)
            if (check.ok) {
                // One of these anywhere in the run proves the check works,
                // and disarms the circuit breaker for good. See
                // noteUnreadable.
                corroborated.incrementAndGet()
                return Match.Result(album = cand,
                    // A candidate the title tier refused is promoted by the
                    // listing alone, and the method says so rather than
                    // pretending the title agreed.
                    method = if (r.method == null) "tracklist" else r.method + "+tracklist",
                    score = r.score,
                    reason = if (r.needsListing)
                        "the title differs \u2014 theirs is \"${cand.title}\" \u2014 but " +
                        check.reason.removePrefix("matched on title, artist and ") +
                        " agree, which is the evidence that decides it"
                    else check.reason)
            }
            if (closest == null || check.coverage > closest.coverage) closest = check
        }
        // A candidate that only ever had a tag against it keeps the refusal
        // matchAlbum wrote: "the closest that artist has is X" says more than
        // "its track listing does not agree", because the title differed too.
        if (r.needsListing) {
            return Match.Result(unreadable = closest?.unreadable ?: false, reason = r.reason)
        }
        return Match.Result(
            // A check that could not be MADE is not the same as a record that
            // is not there. Passed up so the row is counted as failed rather
            // than unmatched, and so the refusal is not cached: see
            // resolveAlbum. A broken read cached as a miss would keep being
            // reused after the read was fixed.
            unreadable = closest?.unreadable ?: false,
            reason = closest?.reason
                ?: "no album of that name could be corroborated against its track listing")
    }

    fun resolveArtist(a: Artist): Resolved {
        if (a.id.isEmpty()) return Resolved(null, null, "the source returned no usable artist")
        store.cachedMatch(sourceName, a.id, targetName, "artist")?.let { c ->
            cacheHits.incrementAndGet()
            return if (c.toId != null) Resolved(c.toId, c.method, "from the match cache")
                   else Resolved(null, null, c.method)
        }
        searches.incrementAndGet()
        val got = trySearch { target.searchArtists(a.name) }
        if (got.error == null) searchOk.incrementAndGet()
        val r = Match.matchArtist(got.results, a)
        val id = r.artist?.id
        if (id == null && got.error != null) return searchFailed(got.error)
        store.cacheMatch(sourceName, a.id, targetName, "artist", id,
            if (id != null) r.method ?: "" else r.reason)
        return if (id != null) Resolved(id, r.method, r.reason) else Resolved(null, null, r.reason)
    }

    // ------------------------------------------------------------------ writing

    private fun write(what: String, ids: List<String>, fn: (List<String>) -> Unit) {
        if (options.dryRun || ids.isEmpty()) return
        report(phase = "writing", label = "Saving ${ids.size} $what")
        try {
            fn(ids)
            counts.getOrPut("written") { AtomicInteger(0) }.addAndGet(ids.size)
        } catch (e: Exception) {
            // The whole batch failed, so every id in it is unwritten. One row
            // rather than one per id, because the endpoint does not say which
            // of the fifty it objected to — but it counts as all of them,
            // which is the true number unwritten.
            record(JobItem("write", what, what, "failed",
                note = "saving ${ids.size} $what failed: ${e.message}"), ids.size)
        }
    }

    /**
     * A small worker pool. `fn` gets the item and its index, so callers that
     * care about order can write results into a slot.
     *
     * Low concurrency on purpose: both services rate limit, and the 429
     * backoff costs far more than the extra threads save.
     */
    private fun <T> eachWithConcurrency(list: List<T>, fn: (T, Int) -> Unit) {
        if (list.isEmpty()) return
        val n = options.concurrency.coerceIn(1, 8).coerceAtMost(list.size)
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

    companion object {
        private const val PROGRESS_INTERVAL_MS = 400L

        /**
         * How many shortlisted albums are checked against their track listing
         * before giving up on a barcode-less album.
         *
         * Two, and the bound is the point. Each candidate costs a read on the
         * other service, and a ten thousand album library at four candidates
         * each is forty thousand requests against a rate-limited API. The
         * shortlist is already ordered by title exactness, artist overlap and
         * track count, so the right album is first or second or it is a
         * different record.
         *
         * Kept in step with CORROBORATE_CANDIDATES in lib/migrate.js by hand.
         */
        private const val CORROBORATE_CANDIDATES = 2

        /**
         * The ONE artist name to put in a search query.
         *
         * `artists[0]` is the first-named artist by convention, but it can
         * itself be several names glued into one string: Roon writes an
         * album's artists as "Carla Bley/Steve Swallow/Andy Sheppard", and
         * Qobuz hands over a single `performer`. A query naming all three
         * finds nothing on either service -- 457 of the 1,273 empty searches
         * in a real 9,514-album library looked like this, against 2.0% of the
         * ones that matched.
         *
         * The matching gates were never the problem: [Canon.artistSet] and
         * [Canon.primaryArtist] both split on all of those separators, so a
         * candidate that came back was compared correctly. Only the query was
         * wrong, and nothing here changes what a candidate must prove.
         *
         * The rest of the list is deliberately left alone -- the second entry
         * is a genuinely different artist, not a better phrasing of the first.
         *
         * Kept in step with searchArtist in lib/migrate.js by hand.
         */
        fun searchArtist(artists: List<String>?): String =
            Canon.artistNames(artists?.firstOrNull() ?: "").firstOrNull() ?: ""

        /**
         * How many albums may fail to be CHECKED before the run gives up.
         *
         * Not a tuning knob — a circuit breaker. 0.2.0's corroboration read
         * was broken, and the run dutifully carried on: forty minutes, three
         * thousand searches against a rate-limited API, and a report of 2325
         * albums "not found" that was not about the library at all.
         *
         * CLAUDE.md already says a dead sign-in must stop the run rather than
         * be reported as four thousand misses. A read that fails every single
         * time is the same thing. Fifty with not ONE success is not bad luck
         * at the edges: it is the check itself being broken, and stopping says
         * so while the user is still watching.
         *
         * It cannot fire on a healthy run: one album corroborating anywhere
         * disarms it for good.
         *
         * Kept in step with UNREADABLE_LIMIT in lib/migrate.js by hand.
         */
        const val UNREADABLE_LIMIT = 50

        /**
         * Run something that talks to a service, treating a failure as "no
         * results".
         *
         * Only ever wrapped around a SEARCH. A search that errors means this
         * one track cannot be looked up, and the right answer is to report it
         * unmatched and carry on — failing the whole migration because one
         * lookup timed out would throw away an hour of work over one track.
         * Reads and writes are deliberately NOT wrapped: those failing means
         * something is actually wrong.
         */
        /**
         * A SEARCH, keeping "we asked and there is nothing" apart from "we
         * could not ask".
         *
         * `safely` collapses both into null, and every caller turned that
         * into `emptyList()` — which `matchTrack`/`matchAlbum` then report as
         * "the search returned nothing", an amber row that reads as "your
         * library is not on that service". Worse, the caller CACHED it, so a
         * refusal caused by a rate limit or a dropped connection outlived the
         * thing that caused it and the next run did not even retry.
         *
         * That is the same mistake 0.2.0 shipped one layer down, where a
         * broken corroboration read turned ~1300 albums amber: **a failed read
         * is missing data, not evidence.** So the message comes back beside
         * the (empty) results, and the caller turns it into a red `failed` row
         * that is never cached — but only if nothing matched anyway, because a
         * barcode search that failed matters not at all once the title search
         * has found the record.
         *
         * AuthError and Cancelled still pass through: a dead sign-in and a
         * cancellation must both stop the run.
         *
         * Kept in step with trySearch in lib/migrate.js by hand.
         */
        private fun <T> trySearch(fn: () -> List<T>): Searched<T> =
            try {
                Searched(fn(), null)
            } catch (e: AuthError) {
                throw e
            } catch (e: Cancelled) {
                throw e
            } catch (e: Exception) {
                Searched(emptyList(), e.message ?: e.javaClass.simpleName)
            }

        private fun <T> safely(fn: () -> T): T? =
            try {
                fn()
            } catch (e: AuthError) {
                throw e // a dead sign-in is not "no results"
            } catch (e: Cancelled) {
                throw e
            } catch (e: Exception) {
                null
            }

        fun trackKey(t: Track) =
            Canon.canon(Canon.stripVersion(t.title)) + "|" +
                Canon.primaryArtist(t.artists.firstOrNull() ?: "")

        fun albumKey(a: Album) =
            Canon.canon(Canon.stripVersion(a.title)) + "|" +
                Canon.primaryArtist(a.artists.firstOrNull() ?: "")

        fun trackLabel(t: Track) =
            if (t.artists.isEmpty()) t.title else "${t.title} — ${t.artists.joinToString(", ")}"

        fun albumLabel(a: Album) =
            if (a.artists.isEmpty()) a.title else "${a.title} — ${a.artists.joinToString(", ")}"

        fun digits(s: String) = s.filter { it.isDigit() }.trimStart('0')
    }
}
