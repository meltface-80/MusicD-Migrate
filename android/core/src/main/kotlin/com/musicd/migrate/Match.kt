package com.musicd.migrate

/*
 * Match.kt — which track on the other service is this track.
 *
 * Three tiers, fixed tolerances, and a refusal with a reason wherever the
 * evidence is not decisive. MatchTest.kt pins every one of them.
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
         * The title tier refused, but a candidate differs only by a trailing
         * tag — and the TRACK LISTING may still decide it.
         *
         * Never a match by itself. [shortlist] carries the candidates and the
         * engine only puts them to the listing when the user has that check
         * on, so with it off this flag changes nothing.
         */
        val needsListing: Boolean = false,
        /**
         * The refusal came from a READ that failed, not from evidence.
         *
         * "We looked and it is not there" is an answer and is cached. "We
         * could not look" is not, and a caller must not cache it or count it
         * as a miss -- see Migration.resolveAlbum. 0.2.0 shipped with a
         * broken corroboration read and every album came back "not found",
         * which read as a library that is not on the other service rather
         * than as a thing that was broken.
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

        data class Scored(val c: Album, val method: String, val score: Double,
                          val tag: String = "")
        val scored = ArrayList<Scored>()

        // What was actually there, for the refusal to quote. A report row
        // saying only "no album called that" invites the entirely reasonable
        // objection "but I own it, it is definitely on there" -- and the
        // answer is usually visible in what DID come back: the same record
        // under a longer title, or a different act with the same album name.
        // Candidates the title tier will not pass on its own, held for the
        // track listing to decide. See TitleTags.addedTag.
        data class ByListing(val c: Album, val sim: Double)
        val byListing = ArrayList<ByListing>()

        var nearestByArtist: Album? = null
        var nearestByArtistSim = -1.0
        var nearest: Album? = null
        var nearestSim = -1.0

        for (c in list) {
            val cTitle = Canon.canon(c.title)
            if (cTitle.isEmpty()) continue
            val artistOverlap = Canon.overlap(wantArtists, Canon.artistSet(c.artists))
            val samePrimary = wantPrimary == Canon.primaryArtist(c.artists.firstOrNull() ?: "")
            val sim = Canon.similarity(cTitle, wantTitle)
            if (sim > nearestSim) { nearest = c; nearestSim = sim }
            if (artistOverlap == 0.0 && !samePrimary) continue
            if (sim > nearestByArtistSim) { nearestByArtist = c; nearestByArtistSim = sim }

            val exact = cTitle == wantTitle
            val close = !exact && Canon.canon(Canon.stripVersion(c.title)) == wantStripped
            // A tag on either side that only repeats the title's own words or
            // the artist's name. See TitleTags.redundantTag: the redundancy is what
            // makes it safe.
            val tag = if (exact || close) ""
                else TitleTags.redundantTag(c.title, want.title, want.artists)
                    .ifEmpty { TitleTags.redundantTag(want.title, c.title, want.artists) }

            // A title that differs by a tag which DOES say something --
            // "(Live)" on a title that never mentions live, a venue and a
            // date. Never a match on its own: it goes on a separate list
            // that only the TRACK LISTING can promote, so with corroboration
            // off this changes nothing at all. See needsListing on Result.
            if (!exact && !close && tag.isEmpty()) {
                if (TitleTags.addedTag(c.title, want.title).isNotEmpty()) {
                    byListing.add(ByListing(c, sim))
                }
                continue
            }

            // A stripped-title match whose track count disagrees is the
            // standard edition being offered for the deluxe, or the reverse.
            // On its own that is not the record the user owns — but it may
            // CONTAIN it ("War Child" against "War Child (2002 Remaster)"
            // with four bonus tracks), and the track listing measures exactly
            // that. So it is not a match here; it goes to the listing, like a
            // title with an added tag. On the owner's decision (0.5.1), after
            // 190 albums of a real library were refused this way before the
            // listing ever saw them. With the listing check off nothing
            // changes: it is still refused.
            if (close && want.trackCount != null && c.trackCount != null &&
                c.trackCount != want.trackCount) {
                byListing.add(ByListing(c, sim))
                continue
            }

            val countScore = if (want.trackCount != null && c.trackCount != null)
                (if (c.trackCount == want.trackCount) 1.0 else 0.0) else 0.5
            scored.add(Scored(c, if (exact) "exact" else "close",
                (if (exact) 0.8 else 0.6) + 0.12 * countScore + 0.08 * artistOverlap, tag))
        }

        if (scored.isEmpty()) {
            // Nothing decisive on the title, but something whose only
            // difference is a trailing tag. The refusal is UNCHANGED -- this
            // is still not a match -- and the shortlist rides along so the
            // engine can put the track listing to it when the user has that
            // check on. matchAlbum never promotes these itself: a title and
            // an artist are not decisive evidence, which is the rule this
            // whole file exists to hold.
            if (byListing.isNotEmpty()) {
                val ordered = byListing.sortedWith(
                    compareByDescending<ByListing> { it.sim }.thenBy { it.c.id })
                return Result(
                    reason = "the closest that artist has is \"${ordered.first().c.title}\", " +
                        "which is not the same record as \"${want.title}\"",
                    needsListing = true,
                    shortlist = ordered.map { it.c })
            }
            // Three different refusals on purpose, because they ask for three
            // different things from the user: fix the tag, accept a different
            // edition, or accept that it is not there.
            nearestByArtist?.let {
                return Result(reason = "the closest that artist has is \"${it.title}\", " +
                    "which is not the same record as \"${want.title}\"")
            }
            nearest?.let {
                val theirs = it.artists.filter { a -> a.isNotEmpty() }.joinToString(", ")
                val ours = want.artists.filter { a -> a.isNotEmpty() }.joinToString(", ")
                return Result(reason = "something is called \"${it.title}\" there, but by " +
                    (if (theirs.isEmpty()) "somebody else" else theirs) + " — not by " +
                    (if (ours.isEmpty()) "that artist" else ours))
            }
            return Result(reason =
                "no album called \"${want.title}\" by that artist on the other service")
        }
        val ordered = scored.sortedWith(
            compareByDescending<Scored> { it.score }.thenBy { it.c.id })
        val top = ordered.first()
        return Result(album = top.c, method = top.method, score = top.score,
            reason = if (top.tag.isNotEmpty())
                    "matched on album title and artist, ignoring \"${top.tag}\" \u2014 " +
                    "which only repeats what the title or the artist already says"
                else if (top.method == "exact") "matched on album title and artist"
                else "matched on album title without its edition suffix",
            shortlist = ordered.map { it.c })
    }

    /** See [Tracklist.MIN_COVERAGE]. */
    const val TRACKLIST_MIN_COVERAGE = Tracklist.MIN_COVERAGE

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

    /** See [Tracklist.agreement]. */
    fun tracklistAgreement(wantTitles: List<Track>?, candTitles: List<Track>?): Agreement =
        Tracklist.agreement(wantTitles, candTitles)

    /** The gate for a barcode-less album. See [Tracklist.corroborates]. */
    fun tracklistCorroborates(
        wantTitles: List<Track>?,
        candTitles: List<Track>?,
        minCoverage: Double = TRACKLIST_MIN_COVERAGE,
        exactTitle: Boolean = false
    ): Agreement = Tracklist.corroborates(wantTitles, candTitles, minCoverage, exactTitle)

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
