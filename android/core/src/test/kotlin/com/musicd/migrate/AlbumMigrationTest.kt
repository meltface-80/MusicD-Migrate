package com.musicd.migrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The migration engine's albums: barcodes, the track-listing check, and
 *  how an album is searched for. */
class AlbumMigrationTest : MigrationFixtures() {

    // ----------------------------------------------------------------------
    // Regression: albums were matched by barcode in name only.

    @Test fun `an album is looked up by barcode before anything else`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb()))
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            // A title the text search could never find, and a track count that
            // would fail the close-tier gate. Only the barcode can match this.
            Album("sal", "075992736121", "Meisterwerk der Marionetten",
                listOf("Metallica"), 12)))

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(listOf("sal"), target.writtenAlbums)
        assertEquals(1, r.counts["matched"])
        assertEquals("the barcode tier must be what fired", "upc", r.items[0].method)
        assertEquals(1, target.upcSearchCount.get())
    }

    @Test fun `the edition that differs only by a suffix is found by barcode`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(title = "Master Of Puppets (Remastered)", trackCount = 8)))
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "075992736121", "Master of Puppets", listOf("Metallica"), 10)))

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals("a barcode match beats the track-count gate", 1, r.counts["matched"])
        assertEquals(listOf("sal"), target.writtenAlbums)
    }

    @Test fun `no barcode falls back to title, artist and the track listing`() {
        val titles = listing("Battery", "Master of Puppets", "The Thing That Should Not Be")
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb(upc = "")))
        source.albumTrackListings["qal"] = titles
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Master Of Puppets", listOf("Metallica"), 3)))
        // As Spotify writes a remastered edition's tracks.
        target.albumTrackListings["sal"] =
            listing("Battery - Remastered", "Master of Puppets - Remastered",
                    "The Thing That Should Not Be - Remastered")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["matched"])
        assertEquals("with no barcode, the title tier alone is not decisive evidence",
            "exact+tracklist", r.items[0].method)
        assertEquals("no barcode search was wasted", 0, target.upcSearchCount.get())
    }

    @Test fun `a barcode-less album with a different track listing is refused, with a reason`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(title = "Greatest Hits", upc = "", trackCount = null)))
        source.albumTrackListings["qal"] = listing("One", "Two", "Three", "Four")
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Greatest Hits", listOf("Metallica"), null)))
        target.albumTrackListings["sal"] = listing("Nine", "Ten", "Eleven", "One")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["unmatched"])
        assertTrue("the report says what was wrong with it: ${r.items[0].note}",
            r.items[0].note.orEmpty().contains("1 of your 4 tracks"))
    }

    @Test fun `corroboration works down the shortlist rather than trusting the top one`() {
        val titles = listing("Aaa", "Bbb", "Ccc", "Ddd")
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(title = "The Record", upc = "", trackCount = null)))
        source.albumTrackListings["qal"] = titles
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("wrong", "", "The Record", listOf("Metallica"), null),
            Album("right", "", "The Record", listOf("Metallica"), null)))
        target.albumTrackListings["wrong"] = listing("Zzz", "Yyy")
        target.albumTrackListings["right"] = titles

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["matched"])
        assertEquals(listOf("right"), target.writtenAlbums)
    }

    @Test fun `corroboration is bounded and does not read the whole search result`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(title = "The Record", upc = "", trackCount = null)))
        source.albumTrackListings["qal"] = listing("Aaa", "Bbb")
        val target = FakeService("spotify", catalogueAlbums = (1..4).map {
            Album("c$it", "", "The Record", listOf("Metallica"), null)
        }.toMutableList())
        for (i in 1..4) target.albumTrackListings["c$i"] = listing("No", "Nope")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["unmatched"])
        assertEquals("two candidates, and no more", 2, target.albumTrackCalls.get())
    }

    @Test fun `a deluxe edition is accepted for the standard one the user owns`() {
        // The coverage is measured against WHAT THE USER OWNS, not against
        // what the candidate holds: a deluxe edition contains all of the
        // standard plus bonus tracks, and measuring the other way round
        // refuses a record that is plainly the right one.
        val mine = listing("Aaa", "Bbb", "Ccc", "Ddd")
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(title = "The Record", upc = "", trackCount = null)))
        source.albumTrackListings["qal"] = mine
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("deluxe", "", "The Record", listOf("Metallica"), 8)))
        target.albumTrackListings["deluxe"] =
            listing("Aaa", "Bbb", "Ccc", "Ddd", "Eee", "Fff", "Ggg", "Hhh")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["matched"])
        assertEquals(listOf("deluxe"), target.writtenAlbums)
        assertTrue("which edition was taken is in the report: ${r.items[0].note}",
            r.items[0].note.orEmpty().contains("on an edition of 8"))
    }

    @Test fun `the source album's own length ranks the standard edition above the deluxe`() {
        // A Roon album arrives with no track count at all, so the listing read
        // for corroboration is also what supplies it. Without that the two
        // editions tie and the tie-break is alphabetical on an opaque id: a
        // coin toss between the record the user owns and a different edition.
        val mine = listing("Aaa", "Bbb", "Ccc", "Ddd")
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(title = "The Record", upc = "", trackCount = null)))
        source.albumTrackListings["qal"] = mine
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            // Named so an alphabetical tie-break would pick the wrong one.
            Album("a-deluxe", "", "The Record", listOf("Metallica"), 8),
            Album("z-standard", "", "The Record", listOf("Metallica"), 4)))
        target.albumTrackListings["a-deluxe"] =
            listing("Aaa", "Bbb", "Ccc", "Ddd", "E", "F", "G", "H")
        target.albumTrackListings["z-standard"] = mine

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["matched"])
        assertEquals("the edition with the same number of tracks is the one they own",
            listOf("z-standard"), target.writtenAlbums)
    }

    @Test fun `corroboration can be turned off, and then the title tier decides alone`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb(upc = "")))
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Master Of Puppets", listOf("Metallica"), 8)))

        val r = run(source, target, NOTHING.copy(doAlbums = true, corroborate = false))
        assertEquals(1, r.counts["matched"])
        assertEquals("exact", r.items[0].method)
        assertEquals("and nothing extra was read", 0, target.albumTrackCalls.get())
    }

    @Test fun `a listing that cannot be read is a FAILURE, not a record that is not there`() {
        // 0.2.0 shipped with a broken corroboration read. Every album came
        // back "not found" -- amber, indistinguishable from a library that is
        // genuinely not on the other service -- and the cause took a live run
        // and a screenshot to find. A check that could not be MADE is red.
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb(upc = "")))
        source.albumTracksFails = true
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Master Of Puppets", listOf("Metallica"), 8)))
        target.albumTrackListings["sal"] = listing("Battery")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals("red, so it cannot be mistaken for a miss", 1, r.counts["failed"])
        assertNull(r.counts["unmatched"])
        assertTrue(r.items[0].note.orEmpty().contains("track listing from the source"))

        // And it is NOT cached. "We looked and found nothing" is a real answer
        // worth keeping; "we could not look" is not, and caching it would keep
        // being reused after the thing that broke was fixed.
        assertNull("a failed read must be retried on the next run, not remembered as a miss",
            r.store.cachedMatch("qobuz", "qal", "spotify", "album"))
    }

    @Test fun `the other service refusing to list an album is also a failure`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(alb(upc = "")))
        source.albumTrackListings["qal"] = listing("Battery", "Leper Messiah")
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Master Of Puppets", listOf("Metallica"), 8)))
        // No listing for "sal" at all: the other side would not say.

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["failed"])
        assertTrue(r.items[0].note.orEmpty().contains("would not list that album's tracks"))
    }

    @Test fun `a listing that disagrees is still an ordinary miss, and is cached`() {
        // The other side of it: a record that is genuinely a different record
        // is amber and IS remembered, or every re-run pays for it again.
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            alb(title = "Greatest Hits", upc = "", trackCount = null)))
        source.albumTrackListings["qal"] = listing("One", "Two", "Three", "Four")
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Greatest Hits", listOf("Metallica"), null)))
        target.albumTrackListings["sal"] = listing("Nine", "Ten", "Eleven", "One")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(1, r.counts["unmatched"])
        assertNull(r.counts["failed"])
        val cached = r.store.cachedMatch("qobuz", "qal", "spotify", "album")
        assertTrue("a real miss is remembered", cached != null && cached.toId == null)
    }

    // ------------------------------------------ how an album is searched for

    @Test fun `several artists glued into one string are cut down to the first`() {
        // Roon writes an album's artists as one slash-joined string, and a
        // query naming all three finds nothing on either service. Measured on
        // a real 9,514-album library: 457 of the 1,273 albums whose search
        // came back EMPTY had an artist string naming more than one person,
        // against 2.0% of the 4,639 that matched.
        assertEquals("Carla Bley",
            Migration.searchArtist(listOf("Carla Bley/Steve Swallow/Andy Sheppard")))
        assertEquals("Vincent Peirani",
            Migration.searchArtist(listOf("Vincent Peirani & Emile Parisien")))
        assertEquals("Miles Davis", Migration.searchArtist(listOf("Miles Davis, John Coltrane")))
        assertEquals("Terence Blanchard",
            Migration.searchArtist(listOf("Terence Blanchard featuring the E-Collective")))
    }

    @Test fun `a second artist in the list is a different artist, not a phrasing`() {
        // artists[0] is split again because it may be several names glued
        // together. The REST of the list is left alone: "Little Boots" is not
        // a rephrasing of "Hot Chip".
        assertEquals("Hot Chip", Migration.searchArtist(listOf("Hot Chip", "Little Boots")))
    }

    @Test fun `searchArtist is defined on the awkward inputs a real library holds`() {
        assertEquals("", Migration.searchArtist(emptyList()))
        assertEquals("", Migration.searchArtist(null))
        assertEquals("", Migration.searchArtist(listOf("")))
        assertEquals("", Migration.searchArtist(listOf("/")))
        assertEquals("left as it is -- the gate will refuse it, and that is right",
            "Unknown Artist", Migration.searchArtist(listOf("Unknown Artist")))
    }

    @Test fun `an album whose artist string names three people is found anyway`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            Album("qal", "", "Andando el Tiempo",
                listOf("Carla Bley/Steve Swallow/Andy Sheppard"), 8)))
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sal", "", "Andando el Tiempo",
                listOf("Carla Bley", "Steve Swallow", "Andy Sheppard"), 8)))

        val r = run(source, target, NOTHING.copy(doAlbums = true, corroborate = false))
        assertEquals(r.items.firstOrNull()?.note, 1, r.counts["matched"])
        assertEquals("the query named one artist, not three",
            "Carla Bley", target.albumQueriesSeen[0].second)
        assertEquals("and it still costs exactly one search", 1, target.searchCount.get())
    }

    // ------------------------------------------- 0.5.1, from a real preview

    @Test fun `an edition with bonus tracks is decided by the listing, not refused on count`() {
        // "War Child" (10 tracks) against "War Child (2002 Remaster)" (14):
        // refused on the count before the listing was ever read. 190 albums
        // of a real library went that way.
        val mine = listing("Bungle In The Jungle", "Ladies", "Back-Door Angels")
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            Album("qal", "", "War Child", listOf("Jethro Tull"), 3)))
        source.albumTrackListings["qal"] = mine
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("rem", "", "War Child (2002 Remaster)", listOf("Jethro Tull"), 5)))
        target.albumTrackListings["rem"] = listing("Bungle In The Jungle", "Ladies",
            "Back-Door Angels", "Paradise Steakhouse", "Sealion II")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(r.items.toString(), 1, r.counts["matched"])
        assertEquals("tracklist", r.items[0].method)

        // With the listing check off, a count mismatch is still refused.
        val off = run(source, FakeService("spotify", catalogueAlbums = target.catalogueAlbums),
            NOTHING.copy(doAlbums = true, corroborate = false))
        assertEquals(null, off.counts["matched"])
    }

    @Test fun `a live album whose every track the service tags Live matches on an exact title`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            Album("qal", "", "At Fillmore East", listOf("The Allman Brothers Band"), 3)))
        source.albumTrackListings["qal"] = listing("Statesboro Blues", "Stormy Monday",
            "Whipping Post")
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("afe", "", "At Fillmore East", listOf("The Allman Brothers Band"), 3)))
        target.albumTrackListings["afe"] = listing("Statesboro Blues (Live)",
            "Stormy Monday (Live at Fillmore East, 1971)", "Whipping Post - Live")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(r.items.toString(), 1, r.counts["matched"])
        assertEquals("exact+tracklist", r.items[0].method)
    }

    @Test fun `a Live tag on only some tracks is still a different performance`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            Album("qal", "", "At Fillmore East", listOf("The Allman Brothers Band"), 3)))
        source.albumTrackListings["qal"] = listing("Statesboro Blues", "Stormy Monday",
            "Whipping Post", "Hot 'Lanta")
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("afe", "", "At Fillmore East", listOf("The Allman Brothers Band"), 4)))
        // Three of four tagged Live: stripping those three would make 3 of 4
        // (75%) agree and pass. Not uniform, so nothing may be stripped.
        target.albumTrackListings["afe"] = listing("Statesboro Blues (Live)",
            "Stormy Monday (Live)", "Whipping Post (Live)", "Bonus studio take")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals("not uniform, so nothing is ignored", null, r.counts["matched"])
    }

    @Test fun `a uniform Live is not ignored when the album title itself differs by Live`() {
        // The needsListing path: "Kind Of Blue" against "Kind Of Blue (Live)".
        // There the live tags are the evidence that it is another record.
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            Album("qal", "", "Kind Of Blue", listOf("Miles Davis"), 2)))
        source.albumTrackListings["qal"] = listing("So What", "Freddie Freeloader")
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("live", "", "Kind Of Blue (Live)", listOf("Miles Davis"), 2)))
        target.albumTrackListings["live"] = listing("So What (Live)", "Freddie Freeloader (Live)")

        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(null, r.counts["matched"])
    }

    // ------------------------------------------- 0.5.2, from a real preview

    private fun siamese(): Pair<FakeService, FakeService> {
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            Album("qal", "", "Siamese Dream (Original)", listOf("The Smashing Pumpkins"), 3)))
        source.albumTrackListings["qal"] = listing("Cherub Rock", "Quiet", "Today")
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("sd", "", "Siamese Dream", listOf("The Smashing Pumpkins"), 3)))
        return source to target
    }

    @Test fun `an empty search is asked again without (Original), and the listing decides`() {
        val (source, target) = siamese()
        target.albumTrackListings["sd"] = listing("Cherub Rock", "Quiet", "Today")
        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(r.items.toString(), 1, r.counts["matched"])
        assertEquals("exact+tracklist", r.items[0].method)
        assertEquals(listOf("Siamese Dream (Original)", "Siamese Dream"),
            target.albumQueriesSeen.map { it.first })
    }

    @Test fun `a record found by the retried search still has to agree track by track`() {
        val (source, target) = siamese()
        target.albumTrackListings["sd"] = listing("Other", "Songs", "Entirely")
        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(null, r.counts["matched"])
        // And with the check off nothing is asked again: there would be
        // nothing to decide it.
        val (s2, t2) = siamese()
        val off = run(s2, t2, NOTHING.copy(doAlbums = true, corroborate = false))
        assertEquals(null, off.counts["matched"])
        assertEquals(1, t2.albumQueriesSeen.size)
    }

    @Test fun `KoЯn's album is found on the service under Korn`() {
        val source = FakeService("qobuz", libAlbums = mutableListOf(
            Album("qal", "", "Issues", listOf("KoЯn"), 3)))
        source.albumTrackListings["qal"] = listing("Dead", "Falling Away from Me", "Trash")
        val target = FakeService("spotify", catalogueAlbums = mutableListOf(
            Album("iss", "", "Issues", listOf("Korn"), 3)))
        target.albumTrackListings["iss"] = listing("Dead", "Falling Away from Me", "Trash")
        // The search was never the problem -- the real report quoted Korn's
        // albums back -- only the artist comparison was.
        val r = run(source, target, NOTHING.copy(doAlbums = true))
        assertEquals(r.items.toString(), 1, r.counts["matched"])
    }
}
