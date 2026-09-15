package com.musicd.migrate

/*
 * Match.kt — which track on the other service is this track.
 *
 * THE KOTLIN TWIN OF lib/match.js: same three tiers, same tolerances, same
 * refusals, same wording in the reasons. MatchTest.kt is the JavaScript test
 * file translated, so a rule that changes on one side and not the other fails
 * a test rather than quietly making the APK migrate a different set of tracks
 * than the container does.
 *
 * This is the only code in the app that can be wrong in a way nobody notices.
 * A failed migration is obvious and recoverable; a migration that quietly put
 * the karaoke version, the radio edit or a covers-band recording into
 * someone's playlist looks like it worked. So:
 *
 *     WHERE THE EVIDENCE IS NOT DECISIVE, MATCH NOTHING.
 */
object Match {

    /**
     * How far two services' durations for the same recording may sit apart.
     *
     * Within one service ±2s is generous. ACROSS services it is not: they
     * encode from different deliveries and trim silence differently, and 3-4
     * second gaps for an identical recording are ordinary. Five seconds admits
     * those and still excludes what matters — a radio edit is 60-90s shorter,
     * a live take minutes longer.
     */
    const val DEFAULT_TOLERANCE_MS = 5000L

    /**
     * The tighter gate for a title that only matched after its suffix was
     * removed. At that point the title has stopped being independent evidence,
     * so the duration is carrying the decision alone.
     */
    const val CLOSE_TOLERANCE_MS = 3000L

    data class Result(
        val track: Track? = null,
        val album: Album? = null,
        val artist: Artist? = null,
        val method: String? = null,
        val score: Double = 0.0,
        val reason: String,
        /**
         * Every album that passed the title and artist gates, best first.
         *
         * A caller with no barcode to go on corroborates these against the
         * album's track listing rather than taking the top one on trust --
         * see [tracklistCorroborates].
         */
        val shortlist: List<Album> = emptyList(),
        /**
         * The refusal came from a READ that failed, not from evidence.
         *
         * "We looked and it is not there" is an answer and is cached. "We
         * could not look" is not, and a caller must not cache it or count it
         * as a miss -- see Migration.resolveAlbum. 0.2.0 shipped with a
         * broken corroboration read and every album came back "not found",
         * which read as a library that is not on the other service rather
         * than as a thing that was broken.
         *
         * Kept in step with `unreadable` in lib/match.js by hand.
         */
        val unreadable: Boolean = false
    ) {
        val matched: Boolean get() = method != null
        /** Whichever id was matched, or null. */
        val id: String? get() = track?.id ?: album?.id ?: artist?.id
    }

    /** ISRCs are 12 characters, case- and punctuation-insensitive in the wild. */
    fun normIsrc(v: String?): String {
        val s = (v ?: "").uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        return if (s.length == 12) s else ""
    }

    fun matchTrack(
        candidates: List<Track>?,
        want: Track,
        strict: Boolean = false,
        toleranceMs: Long = DEFAULT_TOLERANCE_MS
    ): Result {
        val tol = toleranceMs
        val closeTol = minOf(tol, CLOSE_TOLERANCE_MS)
        val list = candidates.orEmpty()
        if (list.isEmpty()) return Result(reason = "the search returned nothing")

        // ------------------------------------------------------------ tier 1
        val wantIsrc = normIsrc(want.isrc)
        if (wantIsrc.isNotEmpty()) {
            val hits = list.filter { normIsrc(it.isrc) == wantIsrc }
            if (hits.isNotEmpty()) {
                // Several hits is normal and harmless: the same recording on
                // the album, the single and three compilations, all carrying
                // the one ISRC. Any is correct — but the choice has to be
                // DETERMINISTIC or two runs disagree and the cache thrashes.
                return Result(track = pickStable(hits, want), method = "isrc", score = 1.0,
                    reason = "matched on ISRC $wantIsrc")
            }
            // No ISRC hit is not a refusal. Qobuz omits the code on plenty of
            // older releases, so falling through to the title tiers is right.
            // Missing data on one side is not evidence of a different
            // recording.
        }

        if (strict) {
            return Result(reason = if (wantIsrc.isNotEmpty())
                "no track with ISRC $wantIsrc on the other service (strict matching is on)"
            else
                "this track has no ISRC, and strict matching only accepts ISRC matches")
        }

        // -------------------------------------------------- tiers 2 and 3
        val wantTitle = Canon.canon(want.title)
        if (wantTitle.isEmpty()) return Result(reason = "the track has no title to match on")

        val wantStripped = Canon.canon(Canon.stripVersion(want.title))
        val wantArtists = Canon.artistSet(want.artists)
        val wantPrimary = Canon.primaryArtist(want.artists.firstOrNull() ?: "")
        val wantMs = want.durationMs?.takeIf { it > 0 }
        val wantAlbum = Canon.canon(want.album)

        if (wantMs == null) {
            // Without a duration, a title-and-artist agreement is two facts
            // where the tier needs three — and the two it has are exactly the
            // two a cover, a re-recording and a live take also satisfy. This
            // is the refusal that does the most work in the whole file.
            return Result(reason = "no duration for this track, so a title match cannot be checked")
        }

        data class Scored(val c: Track, val method: String, val score: Double, val gap: Long)
        val scored = ArrayList<Scored>()

        for (c in list) {
            val cTitle = Canon.canon(c.title)
            if (cTitle.isEmpty()) continue
            val cMs = c.durationMs?.takeIf { it > 0 } ?: continue

            val cArtists = Canon.artistSet(c.artists)
            val cPrimary = Canon.primaryArtist(c.artists.firstOrNull() ?: "")
            val artistOverlap = Canon.overlap(wantArtists, cArtists)
            val samePrimary = wantPrimary.isNotEmpty() && wantPrimary == cPrimary
            // Either the full credits intersect, or the lead artist is the
            // same person. Both are needed as alternatives: Spotify credits
            // every featured artist where Qobuz names one.
            if (artistOverlap == 0.0 && !samePrimary) continue

            val gap = kotlin.math.abs(cMs - wantMs)
            val cStripped = Canon.canon(Canon.stripVersion(c.title))

            val method = when {
                cTitle == wantTitle && gap <= tol -> "exact"
                cStripped == wantStripped && gap <= closeTol -> "close"
                else -> null
            } ?: continue

            // Ranking only, never admission — everything here passed a gate.
            val durationScore = 1.0 - minOf(gap.toDouble() / maxOf(tol, 1L).toDouble(), 1.0)
            val albumScore = if (wantAlbum.isNotEmpty() && c.album.isNotEmpty())
                Canon.similarity(wantAlbum, Canon.canon(c.album)) else 0.0
            val base = if (method == "exact") 0.8 else 0.6
            scored.add(Scored(c, method,
                base + 0.10 * durationScore + 0.06 * artistOverlap + 0.04 * albumScore, gap))
        }

        if (scored.isEmpty()) {
            return Result(reason = explainNoTier(list, want, wantTitle, wantStripped,
                wantArtists, wantPrimary, wantMs, tol))
        }

        val top = scored.sortedWith(
            compareByDescending<Scored> { it.score }
                .thenBy { it.gap }
                .thenBy { it.c.id }
        ).first()

        val seconds = (top.gap / 100L) / 10.0
        return Result(track = top.c, method = top.method, score = top.score,
            reason = if (top.method == "exact")
                "matched on title, artist and duration (${seconds}s apart)"
            else
                "matched on title without its edition suffix, artist and duration " +
                    "(${seconds}s apart)")
    }

    /**
     * Why nothing passed — named precisely, because the three ways this fails
     * need three different things from the user. "Title is there, duration is
     * not" means they own a different edition and can fix it by hand. "The
     * title is not there at all" means nothing will fix it.
     */
    private fun explainNoTier(
        list: List<Track>, want: Track, wantTitle: String, wantStripped: String,
        wantArtists: Set<String>, wantPrimary: String, wantMs: Long, tol: Long
    ): String {
        val titled = list.filter {
            Canon.canon(it.title) == wantTitle ||
                Canon.canon(Canon.stripVersion(it.title)) == wantStripped
        }
        if (titled.isEmpty()) {
            return "nothing called \"${want.title}\" by that artist on the other service"
        }
        val byArtist = titled.filter {
            Canon.overlap(wantArtists, Canon.artistSet(it.artists)) > 0.0 ||
                Canon.primaryArtist(it.artists.firstOrNull() ?: "") == wantPrimary
        }
        if (byArtist.isEmpty()) {
            return "\"${want.title}\" is there but credited to a different artist — " +
                "likely a cover, so it was not taken"
        }
        val lens = byArtist.joinToString(" / ") {
            it.durationMs?.let { ms -> "${Math.round(ms / 1000.0)}s" } ?: "?"
        }
        return "\"${want.title}\" is $lens there and ${Math.round(wantMs / 1000.0)}s here — " +
            "more than ${tol / 1000}s apart, so a different recording"
    }

    /**
     * Albums, by UPC then by title and artist.
     *
     * Same rule as tracks with one difference: an album has no duration, so
     * the corroborating fact is the TRACK COUNT. A deluxe edition and a
     * standard edition share a title and an artist and differ in exactly that.
     */
    fun matchAlbum(candidates: List<Album>?, want: Album, strict: Boolean = false): Result {
        val list = candidates.orEmpty()
        if (list.isEmpty()) return Result(reason = "the search returned nothing")

        val wantUpc = digits(want.upc)
        if (wantUpc.isNotEmpty()) {
            val hit = list.firstOrNull { digits(it.upc) == wantUpc }
            if (hit != null) {
                return Result(album = hit, method = "upc", score = 1.0,
                    reason = "matched on barcode $wantUpc")
            }
        }
        if (strict) return Result(reason = "no album with that barcode (strict matching is on)")

        val wantTitle = Canon.canon(want.title)
        if (wantTitle.isEmpty()) return Result(reason = "the album has no title to match on")
        val wantStripped = Canon.canon(Canon.stripVersion(want.title))
        val wantArtists = Canon.artistSet(want.artists)
        val wantPrimary = Canon.primaryArtist(want.artists.firstOrNull() ?: "")

        data class Scored(val c: Album, val method: String, val score: Double)
        val scored = ArrayList<Scored>()

        for (c in list) {
            val cTitle = Canon.canon(c.title)
            if (cTitle.isEmpty()) continue
            val artistOverlap = Canon.overlap(wantArtists, Canon.artistSet(c.artists))
            val samePrimary = wantPrimary == Canon.primaryArtist(c.artists.firstOrNull() ?: "")
            if (artistOverlap == 0.0 && !samePrimary) continue

            val exact = cTitle == wantTitle
            val close = !exact && Canon.canon(Canon.stripVersion(c.title)) == wantStripped
            if (!exact && !close) continue

            // A stripped-title match whose track count disagrees is the
            // standard edition being offered for the deluxe, or the reverse.
            // The user asked for the record they own.
            if (close && want.trackCount != null && c.trackCount != null &&
                c.trackCount != want.trackCount) continue

            val countScore = if (want.trackCount != null && c.trackCount != null)
                (if (c.trackCount == want.trackCount) 1.0 else 0.0) else 0.5
            scored.add(Scored(c, if (exact) "exact" else "close",
                (if (exact) 0.8 else 0.6) + 0.12 * countScore + 0.08 * artistOverlap))
        }

        if (scored.isEmpty()) {
            return Result(reason =
                "no album called \"${want.title}\" by that artist on the other service")
        }
        val ordered = scored.sortedWith(
            compareByDescending<Scored> { it.score }.thenBy { it.c.id })
        val top = ordered.first()
        return Result(album = top.c, method = top.method, score = top.score,
            reason = if (top.method == "exact") "matched on album title and artist"
                     else "matched on album title without its edition suffix",
            shortlist = ordered.map { it.c })
    }

    /**
     * How much of a source album's track listing has to be present on a
     * candidate before a barcode-less album match is allowed.
     *
     * 0.7, and the number is a judgement about what the two failure modes
     * cost. Too high refuses a record the user owns because one track is
     * titled differently or a bonus track is missing from their rip -- one
     * line in a report they can act on. Too low accepts a different record
     * with the same name, which looks like it worked. Seven tracks in ten
     * agreeing is not something two different records do; three in ten
     * routinely is, for a compilation or a live set.
     *
     * Kept in step with TRACKLIST_MIN_COVERAGE in lib/match.js by hand.
     */
    const val TRACKLIST_MIN_COVERAGE = 0.7

    data class Agreement(
        val coverage: Double,
        val shared: Int,
        val wantCount: Int,
        val candCount: Int,
        val ok: Boolean = false,
        val reason: String = "",
        /** See [Result.unreadable]: the check could not be MADE. */
        val unreadable: Boolean = false
    )

    private fun trackTitleSet(titles: List<Track>?): Set<String> {
        val out = LinkedHashSet<String>()
        for (t in titles.orEmpty()) {
            val c = Canon.canon(Canon.stripVersion(t.title))
            if (c.isNotEmpty()) out.add(c)
        }
        return out
    }

    /**
     * How much of a source album's track listing turns up on a candidate.
     *
     * Titles are compared canonicalised AND with their edition suffix removed,
     * because a remastered edition on Spotify writes its tracks as "So What -
     * Remastered" where a local rip just says "So What". Comparing the raw
     * strings would find nothing on exactly the albums this exists for.
     *
     * Direction matters: the fraction is of what the USER OWNS that is
     * present, not of what the candidate holds. A deluxe edition with eleven
     * bonus tracks still contains all of the standard edition, and refusing it
     * for being bigger is not the job -- reporting the sizes is.
     */
    fun tracklistAgreement(wantTitles: List<Track>?, candTitles: List<Track>?): Agreement {
        val want = trackTitleSet(wantTitles)
        val cand = trackTitleSet(candTitles)
        if (want.isEmpty() || cand.isEmpty()) {
            return Agreement(0.0, 0, want.size, cand.size)
        }
        val shared = want.count { cand.contains(it) }
        return Agreement(shared.toDouble() / want.size, shared, want.size, cand.size)
    }

    /**
     * The album tier for a source with NO BARCODE -- Roon.
     *
     * Spotify and Qobuz both hand over a barcode, which is decisive, and that
     * is what the `upc` tier is. A Roon library hands over nothing of the
     * kind: a browse row is a title and an artist, and title plus artist is
     * not decisive for an album any more than it is for a track. "Greatest
     * Hits" by almost anybody is several different records; a live album and a
     * studio album share a name often enough; a covers band files under a name
     * that normalises to the same string.
     *
     * So the album's own TRACK LISTING is made to carry the decision. It is
     * the one piece of independent evidence a Roon album has, and it is a
     * strong one: two different records by the same artist with the same title
     * do not have the same eleven track titles.
     *
     * This is a GATE, not a ranker. [matchAlbum] has already ordered the
     * candidates; this says yes or no to one of them, and a caller works down
     * the list.
     */
    fun tracklistCorroborates(
        wantTitles: List<Track>?,
        candTitles: List<Track>?,
        minCoverage: Double = TRACKLIST_MIN_COVERAGE
    ): Agreement {
        val a = tracklistAgreement(wantTitles, candTitles)
        // The two "could not check" cases are kept apart from "checked and it
        // disagrees", because they call for different action from the user:
        // one is something to look into, the other is a record that is not
        // there. They are also flagged `unreadable`, so the engine can count
        // them as a FAILURE rather than as a miss -- and, crucially, not
        // cache them. See Migration.corroborate.
        if (a.wantCount == 0) {
            return a.copy(ok = false, unreadable = true, reason =
                "could not read this album's track listing from the source, so there " +
                "was nothing to corroborate a title-and-artist match with")
        }
        if (a.candCount == 0) {
            return a.copy(ok = false, unreadable = true, reason =
                "the other service would not list that album's tracks, so the match " +
                "could not be corroborated")
        }
        if (a.coverage < minCoverage) {
            return a.copy(ok = false, reason =
                "an album of that name is there but its track listing does not agree: " +
                "${a.shared} of your ${a.wantCount} tracks on its ${a.candCount}. " +
                "Probably a different record with the same name")
        }
        return a.copy(ok = true, reason =
            if (a.candCount == a.wantCount)
                "matched on title, artist and all ${a.wantCount} track titles"
            else "matched on title, artist and track listing (${a.shared} of your " +
                "${a.wantCount} tracks, on an edition of ${a.candCount})")
    }

    /**
     * Artists, by name.
     *
     * There is no corroborating fact available at all — no duration, no
     * barcode, no track count — so this tier is exact-name-only and there is
     * deliberately no fuzzy fallback. Following the wrong "John Williams" is
     * the cost of one.
     */
    fun matchArtist(candidates: List<Artist>?, want: Artist): Result {
        val list = candidates.orEmpty()
        if (list.isEmpty()) return Result(reason = "the search returned nothing")
        val wantName = Canon.canon(want.name)
        if (wantName.isEmpty()) return Result(reason = "the artist has no name to match on")

        val hits = list.filter { Canon.canon(it.name) == wantName }
        if (hits.isEmpty()) {
            return Result(reason = "no artist called \"${want.name}\" on the other service")
        }
        // Several artists share a name exactly — "Nirvana" is three bands.
        // Whichever the service ranked first is its own popularity answer.
        return Result(artist = hits.first(), method = "name", score = 1.0,
            reason = if (hits.size > 1)
                "matched on name (${hits.size} artists share it — took the most popular)"
            else "matched on name")
    }

    /** Deterministic choice among equally-correct candidates — see matchTrack. */
    private fun pickStable(hits: List<Track>, want: Track): Track {
        val wantAlbum = Canon.canon(want.album)
        return hits.sortedWith(
            compareByDescending<Track> {
                if (wantAlbum.isEmpty()) 0.0 else Canon.similarity(wantAlbum, Canon.canon(it.album))
            }.thenBy { it.id }
        ).first()
    }

    private fun digits(s: String?) = (s ?: "").filter { it.isDigit() }.trimStart('0')
}
