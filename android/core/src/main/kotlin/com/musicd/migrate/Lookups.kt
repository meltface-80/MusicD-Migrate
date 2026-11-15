package com.musicd.migrate

import java.util.concurrent.atomic.AtomicInteger

/**
 * Lookups.kt — finding one source item on the target: the cache first, then
 * at most two searches, then (for an album with no barcode) its track listing.
 *
 * Split out of Migration.kt so that file stays a readable size; nothing here
 * decides what a match IS — that is Match.kt — only how the evidence is
 * gathered and when a refusal is cached.
 */
class Lookups(
    private val source: MusicSource,
    private val target: MusicTarget,
    private val store: Store,
    private val options: MigrationOptions,
    private val log: JobLog
) {
    private val sourceName = source.serviceName
    private val targetName = target.serviceName

    /** Searches that came back at all, empty or not. Disarms the search
     *  breaker in Migration for good once it is above zero. */
    val searchOk = AtomicInteger(0)

    /** Albums whose listing agreed. Disarms the unreadable breaker likewise. */
    val corroborated = AtomicInteger(0)

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

    data class Resolved(
        val id: String?,
        val method: String?,
        val reason: String,
        /**
         * Nothing was matched because a READ FAILED, not because the thing is
         * not there. The row is reported as `failed` rather than `unmatched`,
         * and the refusal is not cached.
         */
        val problem: Boolean = false,
        /**
         * The failed read was a SEARCH. Counted against its own circuit
         * breaker rather than the corroboration one — see noteSearchFailure.
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
            log.cacheHits.incrementAndGet()
            return if (c.toId != null) Resolved(c.toId, c.method, "from the match cache")
                   else Resolved(null, null, c.method)
        }

        var result: Match.Result? = null
        // A search that could not be MADE is kept apart from one that came
        // back empty, and only counts if nothing matched in the end.
        var searchError: String? = null

        if (t.isrc.isNotEmpty()) {
            log.searches.incrementAndGet()
            val got = trySearch { target.searchByIsrc(t.isrc) }
            if (got.error != null) searchError = got.error else searchOk.incrementAndGet()
            result = Match.matchTrack(got.results, t, options.strict, options.toleranceMs)
        }

        if ((result == null || !result.matched) && !options.strict) {
            // Searched WITHOUT the edition suffix and with the lead artist
            // only. Searching for "Blue Monday - 2016 Remaster" by "New Order,
            // Someone" finds nothing on a service that calls it "Blue Monday"
            // by "New Order", and that is the largest source of false misses.
            log.searches.incrementAndGet()
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

    fun resolveAlbum(a: Album): Resolved {
        if (a.id.isEmpty()) return Resolved(null, null, "the source returned no usable album")
        store.cachedMatch(sourceName, a.id, targetName, "album")?.let { c ->
            log.cacheHits.incrementAndGet()
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
            log.searches.incrementAndGet()
            val got = trySearch { target.searchByUpc(want.upc) }
            if (got.error != null) searchError = got.error else searchOk.incrementAndGet()
            r = Match.matchAlbum(got.results, want, options.strict)
        }

        // No barcode, or the barcode found nothing: fall back to title and
        // artist with the edition suffix removed, so "Master Of Puppets
        // (Remastered)" searches for what Spotify calls "Master of Puppets".
        if ((r == null || !r.matched) && !options.strict) {
            log.searches.incrementAndGet()
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
            log.cacheHits.incrementAndGet()
            return if (c.toId != null) Resolved(c.toId, c.method, "from the match cache")
                   else Resolved(null, null, c.method)
        }
        log.searches.incrementAndGet()
        val got = trySearch { target.searchArtists(a.name) }
        if (got.error == null) searchOk.incrementAndGet()
        val r = Match.matchArtist(got.results, a)
        val id = r.artist?.id
        if (id == null && got.error != null) return searchFailed(got.error)
        store.cacheMatch(sourceName, a.id, targetName, "artist", id,
            if (id != null) r.method ?: "" else r.reason)
        return if (id != null) Resolved(id, r.method, r.reason) else Resolved(null, null, r.reason)
    }

    companion object {
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
         */
        fun searchArtist(artists: List<String>?): String =
            Canon.artistNames(artists?.firstOrNull() ?: "").firstOrNull() ?: ""

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
         */
        private const val CORROBORATE_CANDIDATES = 2

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
    }
}
