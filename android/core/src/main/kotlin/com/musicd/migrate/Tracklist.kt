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
        minCoverage: Double = MIN_COVERAGE
    ): Match.Agreement {
        val a = agreement(wantTitles, candTitles)
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
}
