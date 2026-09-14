package com.musicd.migrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JavaScript match tests (test/unit/match.test.js), translated. */
class MatchTest {

    private fun t(
        id: String = "x", title: String = "Song", artists: List<String> = listOf("Band"),
        album: String = "Rec", durationMs: Long? = 200000, isrc: String = ""
    ) = Track(id = id, isrc = isrc, title = title, artists = artists, album = album,
        durationMs = durationMs)

    @Test fun `normIsrc keeps 12 chars and rejects anything else`() {
        assertEquals("GBAYE0601498", Match.normIsrc("gb-aye-06-01498"))
        assertEquals("GBAYE0601498", Match.normIsrc("GBAYE0601498"))
        assertEquals("", Match.normIsrc("TOOSHORT"))
        assertEquals("", Match.normIsrc(null))
    }

    // ----------------------------------------------------------- tier: isrc

    @Test fun `an ISRC match wins with no duration corroboration at all`() {
        val r = Match.matchTrack(
            listOf(t(id = "a", isrc = "GBAYE0601498", title = "Utterly Different",
                durationMs = 1)),
            t(isrc = "gb-aye-06-01498"))
        assertEquals("isrc", r.method)
        assertEquals("a", r.track!!.id)
    }

    @Test fun `several ISRC hits pick deterministically preferring the same album`() {
        val cands = listOf(
            t(id = "zzz", isrc = "GBAYE0601498", album = "Greatest Hits"),
            t(id = "aaa", isrc = "GBAYE0601498", album = "Rec"))
        val want = t(isrc = "GBAYE0601498", album = "Rec")
        assertEquals("aaa", Match.matchTrack(cands, want).track!!.id)
        assertEquals("aaa", Match.matchTrack(cands.reversed(), want).track!!.id)
    }

    @Test fun `a missing ISRC on the other side falls through, it does not refuse`() {
        assertEquals("exact",
            Match.matchTrack(listOf(t(id = "a")), t(isrc = "GBAYE0601498")).method)
    }

    // ---------------------------------------------------------- tier: exact

    @Test fun `title artist and duration agreeing is an exact match`() {
        val r = Match.matchTrack(listOf(t(id = "a", durationMs = 201500)), t())
        assertEquals("exact", r.method)
        assertTrue(r.reason.contains("title, artist and duration"))
    }

    @Test fun `artists that overlap only on the lead credit still match`() {
        assertEquals("exact", Match.matchTrack(
            listOf(t(id = "a", artists = listOf("Calvin Harris", "Rihanna"))),
            t(artists = listOf("Calvin Harris"))).method)
    }

    // ---------------------------------------------------------- tier: close

    @Test fun `an edition suffix is matched through with the tighter gate`() {
        assertEquals("close", Match.matchTrack(
            listOf(t(id = "a", title = "Song - 2011 Remaster", durationMs = 202000)),
            t(title = "Song")).method)
    }

    @Test fun `a stripped-title match outside the tighter gate is refused`() {
        // 4s apart: inside the 5s exact tolerance, outside the 3s close one.
        assertNull(Match.matchTrack(
            listOf(t(id = "a", title = "Song - 2011 Remaster", durationMs = 204000)),
            t(title = "Song")).method)
    }

    // ------------------------------------------------------------ refusals

    @Test fun `a live version is never accepted for the studio recording`() {
        assertNull("a live take must not match the studio cut", Match.matchTrack(
            listOf(t(id = "a", title = "Song (Live)", durationMs = 200000)),
            t(title = "Song")).method)
    }

    @Test fun `a radio edit is refused on duration`() {
        val r = Match.matchTrack(
            listOf(t(id = "a", title = "Song", durationMs = 180000)),
            t(title = "Song", durationMs = 420000))
        assertNull(r.method)
        assertTrue(r.reason.contains("different recording"))
    }

    @Test fun `a cover by another artist is refused and says so`() {
        val r = Match.matchTrack(listOf(t(id = "a", artists = listOf("Some Covers Band"))),
            t(artists = listOf("Band")))
        assertNull(r.method)
        assertTrue(r.reason.contains("cover"))
    }

    @Test fun `no duration on the wanted track refuses every title tier`() {
        val r = Match.matchTrack(listOf(t(id = "a")), t(durationMs = null))
        assertNull(r.method)
        assertTrue(r.reason.contains("no duration"))
    }

    @Test fun `an empty candidate list refuses`() {
        assertNull(Match.matchTrack(emptyList(), t()).method)
        assertNull(Match.matchTrack(null, t()).method)
    }

    @Test fun `strict mode accepts only ISRC`() {
        assertNull(Match.matchTrack(listOf(t(id = "a")), t(), strict = true).method)
        assertEquals("isrc", Match.matchTrack(
            listOf(t(id = "a", isrc = "GBAYE0601498")), t(isrc = "GBAYE0601498"),
            strict = true).method)
    }

    @Test fun `the refusal reason distinguishes absent from wrong-length`() {
        assertTrue(Match.matchTrack(listOf(t(id = "a", title = "Something Else")),
            t(title = "Song")).reason.contains("nothing called"))
        assertTrue(Match.matchTrack(listOf(t(id = "a", durationMs = 400000)), t())
            .reason.contains("400s there and 200s here"))
    }

    // -------------------------------------------------------------- albums

    @Test fun `albums match on barcode first`() {
        assertEquals("upc", Match.matchAlbum(
            listOf(Album(id = "a", upc = "0075992736121", title = "Nope",
                artists = listOf("Other"))),
            Album(id = "q", upc = "75992736121", title = "Rumours",
                artists = listOf("Fleetwood Mac"))).method)
    }

    @Test fun `a deluxe edition is not accepted for the standard one`() {
        assertNull(Match.matchAlbum(
            listOf(Album(id = "a", title = "Rumours (Deluxe Edition)",
                artists = listOf("Fleetwood Mac"), trackCount = 34)),
            Album(id = "q", title = "Rumours", artists = listOf("Fleetwood Mac"),
                trackCount = 11)).method)
    }

    @Test fun `an exact album title matches even when track counts are unknown`() {
        assertEquals("exact", Match.matchAlbum(
            listOf(Album(id = "a", title = "Rumours", artists = listOf("Fleetwood Mac"))),
            Album(id = "q", title = "Rumours", artists = listOf("Fleetwood Mac"))).method)
    }

    // ------------------------------------------------------------- artists

    @Test fun `artists match on an exact name only`() {
        assertEquals("name", Match.matchArtist(
            listOf(Artist("a", "Portishead")), Artist("q", "portishead")).method)
        assertNull(Match.matchArtist(
            listOf(Artist("a", "Portishead Tribute")), Artist("q", "Portishead")).method)
    }

    // --------------------------------------------------- the tracklist tier
    //
    // The gate that makes a barcode-less album match -- a Roon album --
    // decisive. The JavaScript tests for the same rules are in
    // test/unit/match.test.js; these two have to agree or the APK and the
    // container migrate different records.

    private fun tl(vararg titles: String): List<Track> =
        titles.mapIndexed { i, t -> Track("t$i", "", t, emptyList(), "", null) }

    @Test fun `a remastered edition's tracks agree with a local rip's`() {
        // Spotify writes "So What - Remastered"; a rip just says "So What".
        // Without stripping the suffix the two listings share nothing and the
        // album the user owns is refused.
        val mine = tl("So What", "Freddie Freeloader", "Blue In Green",
                      "All Blues", "Flamenco Sketches")
        val theirs = tl("So What - Remastered", "Freddie Freeloader - Remastered",
                        "Blue In Green - Remastered", "All Blues - Remastered",
                        "Flamenco Sketches - Remastered")
        val c = Match.tracklistCorroborates(mine, theirs)
        assertTrue(c.ok)
        assertEquals(1.0, c.coverage, 0.0001)
        assertTrue(c.reason.contains("all 5 track titles"))
    }

    @Test fun `coverage is of what the user owns, not what the candidate holds`() {
        val mine = tl("A", "B", "C", "D")
        val deluxe = tl("A", "B", "C", "D", "E", "F", "G", "H")
        val a = Match.tracklistAgreement(mine, deluxe)
        assertEquals("a deluxe edition contains all of the standard", 1.0, a.coverage, 0.0001)
        assertEquals(4, a.wantCount)
        assertEquals(8, a.candCount)
        assertTrue(Match.tracklistCorroborates(mine, deluxe).ok)

        val back = Match.tracklistCorroborates(deluxe, mine)
        assertEquals(0.5, back.coverage, 0.0001)
        assertFalse("half of a record is not that record", back.ok)
    }

    @Test fun `a different record with the same name is refused, with the numbers`() {
        val c = Match.tracklistCorroborates(tl("One", "Two", "Three", "Four"),
                                            tl("Nine", "Ten", "Eleven", "One"))
        assertFalse(c.ok)
        assertTrue(c.reason.contains("1 of your 4 tracks on its 4"))
        assertTrue(c.reason.contains("different record with the same name"))
    }

    @Test fun `the tracklist threshold is where it says it is`() {
        val ten = tl("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
        val seven = tl("a", "b", "c", "d", "e", "f", "g")
        val six = tl("a", "b", "c", "d", "e", "f")
        assertEquals(0.7, Match.TRACKLIST_MIN_COVERAGE, 0.0001)
        assertTrue("7 of 10 passes", Match.tracklistCorroborates(ten, seven).ok)
        assertFalse("6 of 10 does not", Match.tracklistCorroborates(ten, six).ok)
        assertTrue(Match.tracklistCorroborates(ten, six, 0.6).ok)
    }

    @Test fun `could not check and checked-and-disagrees are different refusals`() {
        val fromSource = Match.tracklistCorroborates(emptyList(), tl("a", "b"))
        assertFalse(fromSource.ok)
        assertTrue(fromSource.reason.contains("from the source"))

        val fromTarget = Match.tracklistCorroborates(tl("a", "b"), emptyList())
        assertFalse(fromTarget.ok)
        assertTrue(fromTarget.reason.contains("other service would not list"))

        assertNotEquals(fromSource.reason, fromTarget.reason)
    }

    @Test fun `duplicate track titles do not inflate the agreement`() {
        val a = Match.tracklistAgreement(tl("Intro", "Intro", "Theme", "Coda"),
                                          tl("Intro", "Intro", "Intro", "Intro"))
        assertEquals("three distinct titles, not four", 3, a.wantCount)
        assertEquals(1, a.shared)
    }

    @Test fun `a title that normalises to nothing is not a track`() {
        val a = Match.tracklistAgreement(tl("", "   ", "Real Track"), tl("Real Track"))
        assertEquals(1, a.wantCount)
        assertEquals(1.0, a.coverage, 0.0001)
    }

    @Test fun `matchAlbum hands back everything that passed its gates`() {
        val want = Album("q", "", "The Record", listOf("Band"), null)
        val r = Match.matchAlbum(listOf(
            Album("b", "", "The Record", listOf("Band"), null),
            Album("a", "", "The Record", listOf("Band"), null),
            Album("no", "", "Something Else", listOf("Band"), null)), want)
        assertEquals("in score order, and a caller with no barcode works down it",
            listOf("a", "b"), r.shortlist.map { it.id })
    }
}
