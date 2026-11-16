package com.musicd.migrate

/**
 * The track-listing check: the evidence that decides an album match when
 * there is no barcode. Split out of Match.kt, which delegates to it — the
 * public names (Match.tracklistCorroborates and friends) are unchanged.
 */
object Tracklist {

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
     */
    const val MIN_COVERAGE = 0.7

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
    /** A trailing live tag on a track title: "(Live)", "[Live at X]",
     *  " - Live in Tokyo". */
    private val LIVE_TAG = Regex(
        """\s*(?:[(\[]\s*live\b[^)\]]*[)\]]|-\s*live\b[^()\[\]]*)\s*$""",
        RegexOption.IGNORE_CASE)

    /**
     * A live album's tracks with the live tag taken off — on both sides — but
     * only when EVERY track the service lists carries one.
     *
     * Services title each track of a live record "Statesboro Blues (Live)";
     * the owner's tags say "Statesboro Blues". The listing check treats
     * "(Live)" as a different performance, which is right in general, and so
     * found 0 of 7 tracks in common for "At Fillmore East" — a record whose
     * TITLE matched exactly and which is live from start to finish on both
     * sides. 52 albums of a real library were refused that way.
     *
     * On the owner's decision (0.5.1), and narrow on purpose: only for an
     * exact album-title match ([corroborates]' `exactTitle`), and only when
     * the tag is UNIFORM. A studio album against a live one under a different
     * title — "(Live)" added to the album name — still goes through the
     * needsListing path untouched, where "one live" is not "one" and the
     * coverage fails exactly where it should.
     */
    fun withoutUniformLive(want: List<Track>, cand: List<Track>): Pair<List<Track>, List<Track>> {
        if (cand.isEmpty() || !cand.all { LIVE_TAG.containsMatchIn(it.title) }) return want to cand
        fun strip(l: List<Track>) = l.map { it.copy(title = LIVE_TAG.replace(it.title, "")) }
        return strip(want) to strip(cand)
    }

    /** The fewest tracks a shorter edition may have, and the least of the
     *  owner's album it may cover. See [isShorterEdition]. */
    const val SHORTER_EDITION_MIN_TRACKS = 5
    const val SHORTER_EDITION_MIN_COVERAGE = 1.0 / 3

    /**
     * The owner has the deluxe, the service only the standard edition: EVERY
     * one of the service's tracks is on the owner's album, but that is under
     * [MIN_COVERAGE] of what the owner has. "Train" — 19 tracks owned, 13 on
     * Qobuz, all 13 of them among the 19.
     *
     * On the owner's decision (0.5.2), after 26 such albums of a real library
     * were refused. It accepts a different EDITION, never a different record:
     * a record whose tracks are all on yours is not somebody else's. The
     * floors keep it to that: five tracks, so a single or a two-track promo
     * cannot be "all of its tracks", and a third of the owner's album, so a
     * five-track EP of a thirty-track album with the same name is still
     * refused. The lowest a real one went was 11 of 30.
     */
    private fun isShorterEdition(a: Match.Agreement) =
        a.candCount >= SHORTER_EDITION_MIN_TRACKS && a.shared == a.candCount &&
            a.coverage >= SHORTER_EDITION_MIN_COVERAGE

    fun agreement(wantTitles: List<Track>?, candTitles: List<Track>?): Match.Agreement {
        val want = trackTitleSet(wantTitles)
        val cand = trackTitleSet(candTitles)
        if (want.isEmpty() || cand.isEmpty()) {
            return Match.Agreement(0.0, 0, want.size, cand.size)
        }
        val shared = want.count { cand.contains(it) }
        return Match.Agreement(shared.toDouble() / want.size, shared, want.size, cand.size)
    }

    /**
     * The album tier for an album with NO BARCODE.
     *
     * A barcode is decisive, and that is what the `upc` tier is. Without one,
     * title plus artist is not decisive for an album any more than it is for
     * a track. "Greatest
     * Hits" by almost anybody is several different records; a live album and a
     * studio album share a name often enough; a covers band files under a name
     * that normalises to the same string.
     *
     * So the album's own TRACK LISTING is made to carry the decision. It is
     * the one piece of independent evidence such an album has, and it is a
     * strong one: two different records by the same artist with the same title
     * do not have the same eleven track titles.
     *
     * This is a GATE, not a ranker. [Match.matchAlbum] has already ordered the
     * candidates; this says yes or no to one of them, and a caller works down
     * the list.
     */
    fun corroborates(
        wantTitles: List<Track>?,
        candTitles: List<Track>?,
        minCoverage: Double = MIN_COVERAGE,
        /** The album titles matched EXACTLY: a "(Live)" on every one of the
         *  candidate's tracks may be ignored. See [withoutUniformLive]. */
        exactTitle: Boolean = false
    ): Match.Agreement {
        val (w, c) = if (exactTitle) withoutUniformLive(wantTitles.orEmpty(), candTitles.orEmpty())
                     else wantTitles.orEmpty() to candTitles.orEmpty()
        val a = agreement(w, c)
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
        if (a.coverage < minCoverage && isShorterEdition(a)) {
            return a.copy(ok = true, reason = "matched on title, artist and track listing: " +
                "theirs is a shorter edition, all ${a.candCount} of its tracks among your " +
                "${a.wantCount}")
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
}
