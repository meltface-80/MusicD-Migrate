package com.musicd.migrate

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/*
 * Migration.kt — moving a library from one service to the other.
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

class Migration(
    /** Read-only is enough for a source. See Model.kt. */
    private val source: MusicSource,
    private val target: MusicTarget,
    private val store: Store,
    private val jobId: String,
    private val options: MigrationOptions
) : RunningJob {
    private val sourceName = source.serviceName
    private val targetName = target.serviceName

    private val cancelled = AtomicBoolean(false)
    private val unreadable = AtomicInteger(0)
    private val searchFails = AtomicInteger(0)

    private val log = JobLog(store, jobId)
    private val lookups = Lookups(source, target, store, options, log)
    private val writes = Writes(options.dryRun, log)

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
     */
    override fun noteRateLimit(ms: Long) {
        val n = log.rateLimits.incrementAndGet()
        // force: the throttle would otherwise drop this, and the label it
        // dropped is the only thing on the page that explains a run that is
        // about to sit still for thirty seconds. A test caught exactly that.
        log.report(label = "$targetName is rate limiting this app — waiting " +
            "${ms / 1000}s ($n so far)", force = true)
    }

    override fun cancel() { cancelled.set(true) }

    /**
     * An album could not be CHECKED. Count it, and stop the run if the check
     * itself is plainly broken.
     *
     * @see UNREADABLE_LIMIT
     */
    private fun noteUnreadable(reason: String) {
        val n = unreadable.incrementAndGet()
        if (lookups.corroborated.get() > 0 || n < UNREADABLE_LIMIT) return
        throw BrokenRead(
            "Stopped after $n albums could not be checked and not one could: " +
            reason + ". This is the track-listing check failing, not your " +
            "library — carrying on would have searched for thousands of albums " +
            "and reported every one of them as not found. Nothing already " +
            "matched has been lost. Turning off \"check the track listing\" " +
            "will migrate on title and artist alone, which is weaker evidence.")
    }

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
        if (lookups.searchOk.get() > 0 || n < UNREADABLE_LIMIT) return
        throw BrokenRead(
            "Stopped after $n searches failed and not one succeeded: " + reason +
            ". Carrying on would have reported your whole library as missing " +
            "from $targetName. Nothing already matched has been lost, and none " +
            "of these was cached — re-running picks up where this left off.")
    }

    private fun checkCancelled() { if (cancelled.get()) throw Cancelled() }

    /**
     * On the way out of a run that did not finish: save the rows, and KEEP
     * WHAT WAS MATCHED.
     *
     * A run that stops early — cancelled, a circuit breaker, a dead sign-in,
     * Android's foreground cap — has already found real matches, and
     * stranding them is what made a long run into Spotify look as though it
     * had done nothing at all. An Error gets the same treatment: the write is
     * a few requests and the alternative is losing hours of matching.
     */
    private fun stopping() {
        log.flush()
        runCatching { writes.flushAll() }
    }

    // --------------------------------------------------------------------- run

    fun run(): Map<String, Int> {
        try {
            log.report(phase = "reading", label = "Reading your libraries")
            val existing = Existing.read(target, options) { checkCancelled() }
            checkCancelled()

            if (options.doTracks) migrateSavedTracks(existing)
            if (options.doAlbums) migrateSavedAlbums(existing)
            if (options.doArtists) migrateArtists(existing)
            if (options.doPlaylists) migratePlaylists()

            log.flush()
            log.report(phase = "done", label = "Finished")
            return log.countsMap()
        } catch (e: Cancelled) {
            stopping()
            log.report(phase = "cancelled", label = "Cancelled")
            throw e
        } catch (e: Exception) {
            stopping()
            log.report(phase = "failed", label = e.message ?: "Failed")
            throw e
        } catch (t: Throwable) {
            // An Error is not an Exception. Letting one past here would leave
            // the progress saying "matching" forever while the process died
            // under it. The rows recorded so far are flushed either way.
            stopping()
            log.report(phase = "failed", label = "the app hit a " + t.javaClass.simpleName)
            throw t
        }
    }

    // ----------------------------------------------------------- saved tracks

    private fun migrateSavedTracks(existing: Existing) {
        val src = source.savedTracks()
        checkCancelled()
        log.report(phase = "matching", step = "tracks", total = src.size, done = 0,
            label = "Favourite tracks — ${src.size} to check")

        val w = writes.writer("favourite tracks") { target.saveTracks(it) }
        val done = AtomicInteger(0)
        eachWithConcurrency(options.concurrency, src) { t, _ ->
            checkCancelled()
            val label = trackLabel(t)
            if ((t.isrc.isNotEmpty() && existing.trackIsrc.contains(t.isrc.uppercase())) ||
                existing.trackKey.contains(trackKey(t))) {
                log.record(JobItem("track", t.id, label, "already",
                    note = "already a favourite there"))
            } else {
                val r = lookups.resolveTrack(t)
                if (r.id != null) {
                    w.add(r.id)
                    log.record(JobItem("track", t.id, label, "matched", targetId = r.id,
                        method = r.method, note = r.reason))
                } else {
                    // Red rather than amber when the lookup could not be
                    // MADE, exactly as for an album. Recorded first, then
                    // counted, so the row that stops the run is in the report
                    // that says why.
                    log.record(JobItem("track", t.id, label,
                        if (r.problem) "failed" else "unmatched", note = r.reason))
                    if (r.searchFailure) noteSearchFailure(r.reason)
                }
            }
            log.report(done = done.incrementAndGet(), label = "Favourite tracks — $label")
        }

        w.flush()
    }

    // ----------------------------------------------------------- saved albums

    private fun migrateSavedAlbums(existing: Existing) {
        val src = source.savedAlbums()
        checkCancelled()
        log.report(phase = "matching", step = "albums", total = src.size, done = 0,
            label = "Favourite albums — ${src.size} to check")

        val w = writes.writer("favourite albums") { target.saveAlbums(it) }
        val done = AtomicInteger(0)
        eachWithConcurrency(options.concurrency, src) { a, _ ->
            checkCancelled()
            val label = albumLabel(a)
            if ((a.upc.isNotEmpty() && existing.albumUpc.contains(digits(a.upc))) ||
                existing.albumKey.contains(albumKey(a))) {
                log.record(JobItem("album", a.id, label, "already",
                    note = "already a favourite there"))
            } else if (options.corroborate && source.unmatchable(a) != null) {
                // Not enough evidence in the source to try: see unmatchable.
                log.record(JobItem("album", a.id, label, "unmatched",
                    note = source.unmatchable(a)))
            } else {
                val r = lookups.resolveAlbum(a)
                if (r.id != null) {
                    w.add(r.id)
                    log.record(JobItem("album", a.id, label, "matched", targetId = r.id,
                        method = r.method, note = r.reason))
                } else {
                    // `failed`, not `unmatched`, when the lookup could not be
                    // MADE. It renders red rather than amber, and that
                    // difference is the whole point: 0.2.0 shipped with a
                    // broken corroboration read, every album came back "not
                    // found", and it read as a library that is not on the
                    // other service rather than as a thing that was broken.
                    log.record(JobItem("album", a.id, label,
                        if (r.problem) "failed" else "unmatched", note = r.reason))
                    // Recorded first, then counted: if this is the one that
                    // stops the run, its row has to be in the report that
                    // says why.
                    if (r.searchFailure) noteSearchFailure(r.reason)
                    else if (r.problem) noteUnreadable(r.reason)
                }
            }
            log.report(done = done.incrementAndGet(), label = "Favourite albums — $label")
        }

        w.flush()
    }

    // ---------------------------------------------------------------- artists

    private fun migrateArtists(existing: Existing) {
        val src = source.followedArtists()
        checkCancelled()
        log.report(phase = "matching", step = "artists", total = src.size, done = 0,
            label = "Artists — ${src.size} to check")

        val w = writes.writer("artists") { target.followArtists(it) }
        val done = AtomicInteger(0)
        eachWithConcurrency(options.concurrency, src) { a, _ ->
            checkCancelled()
            if (existing.artistKey.contains(Canon.canon(a.name))) {
                log.record(JobItem("artist", a.id, a.name, "already", note = "already followed there"))
            } else {
                val r = lookups.resolveArtist(a)
                if (r.id != null) {
                    w.add(r.id)
                    log.record(JobItem("artist", a.id, a.name, "matched", targetId = r.id,
                        method = r.method, note = r.reason))
                } else {
                    log.record(JobItem("artist", a.id, a.name,
                        if (r.problem) "failed" else "unmatched", note = r.reason))
                    if (r.searchFailure) noteSearchFailure(r.reason)
                }
            }
            log.report(done = done.incrementAndGet(), label = "Artists — ${a.name}")
        }

        w.flush()
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
                log.record(JobItem("playlist", pl.id, pl.name, "skipped",
                    targetId = existingPl.id,
                    note = "a playlist called \"$targetName\" is already there"))
                continue
            }

            val tracks = source.playlistTracks(pl.id)
            log.report(phase = "matching", step = "playlist", total = tracks.size, done = 0,
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
            eachWithConcurrency(options.concurrency, tracks) { t, i ->
                checkCancelled()
                if (t.skip != null) {
                    log.record(JobItem("track", "local:$i", t.title, "skipped",
                        container = pl.name,
                        note = "a ${t.skip}, which cannot be migrated"))
                } else {
                    val r = lookups.resolveTrack(t)
                    if (r.id != null) {
                        resolved[i] = r.id
                        log.record(JobItem("track", t.id, trackLabel(t), "matched",
                            container = pl.name, targetId = r.id, method = r.method,
                            note = r.reason))
                    } else {
                        log.record(JobItem("track", t.id, trackLabel(t), "unmatched",
                            container = pl.name, note = r.reason))
                    }
                }
                log.report(done = done.incrementAndGet())
            }

            // De-duplicated WITHIN the playlist too: two source tracks can
            // legitimately resolve to one destination track (the album cut and
            // the single, same ISRC), and adding it twice is a duplicate the
            // user did not have.
            val unique = resolved.filterNotNull().filter { !present.contains(it) }.distinct()

            if (options.dryRun) {
                log.record(JobItem("playlist", pl.id, pl.name, "matched",
                    note = "would ${if (existingPl != null) "add to" else "create"} " +
                        "\"$targetName\" with ${unique.size} " +
                        "track${if (unique.size == 1) "" else "s"}"))
                continue
            }

            if (unique.isEmpty()) {
                log.record(JobItem("playlist", pl.id, pl.name, "already",
                    targetId = existingPl?.id,
                    note = if (existingPl != null) "every track was already in it"
                           else "nothing in it could be matched, so it was not created"))
                continue
            }

            log.report(phase = "writing", label = "Writing $targetName")
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
                            "by Mandarin Migration.", false)
                    destId = made.id
                    note = "created with ${unique.size} " +
                        "track${if (unique.size == 1) "" else "s"}"
                }
                target.addToPlaylist(destId, unique)
                log.count("written", unique.size)
                log.record(JobItem("playlist", pl.id, pl.name, "matched", targetId = destId,
                    note = note))
            } catch (e: Exception) {
                log.record(JobItem("playlist", pl.id, pl.name, "failed", note = e.message))
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

    companion object {
        /** The one artist to put in a search query. See [Lookups.searchArtist]. */
        fun searchArtist(artists: List<String>?): String = Lookups.searchArtist(artists)

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
         */
        const val UNREADABLE_LIMIT = 50

        /** Matched ids held before they are written. See [Writes.WRITE_BATCH]. */
        const val WRITE_BATCH = Writes.WRITE_BATCH

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
