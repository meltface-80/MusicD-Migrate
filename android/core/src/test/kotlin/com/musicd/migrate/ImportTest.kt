package com.musicd.migrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A library FILE imported into Qobuz favourites through the real migration
 * engine and the real Match rules — the only path the app now has.
 *
 * The target is a FakeService standing in for Qobuz: it implements the same
 * MusicTarget interface, and its album search behaves like a literal word
 * search, as the real one does.
 */
class ImportTest : MigrationFixtures() {

    private fun lib(text: String) = CsvLibrary.parse(text.byteInputStream(Charsets.UTF_8), "x.csv")

    private fun import(file: CsvLibrary, qobuz: FakeService, store: Store = MemoryStore(),
                       options: MigrationOptions = NOTHING.copy(doAlbums = true)): Run {
        store.createJob("imp", "csv-to-qobuz", "{}", options.dryRun)
        val counts = Migration(file, qobuz, store, "imp", options).run()
        return Run(counts, store, store.items("imp"))
    }

    @Test fun `an album with a barcode is found by it and favourited`() {
        val file = lib("artist,album,barcode\nMetallica,Master Of Puppets,075992736121\n")
        val qobuz = FakeService("qobuz", catalogueAlbums = mutableListOf(
            Album("q1", "075992736121", "Master Of Puppets (Remastered)",
                listOf("Metallica"), 8)))
        val r = import(file, qobuz)
        assertEquals(1, r.counts["matched"])
        assertEquals("upc", r.items[0].method)
        assertEquals(listOf("q1"), qobuz.writtenAlbums.toList())
    }

    @Test fun `an album with no barcode is decided by its track listing`() {
        val file = lib("""album artist,album,track,title
            |Miles Davis,Kind Of Blue,1,So What
            |Miles Davis,Kind Of Blue,2,Freddie Freeloader
            |Miles Davis,Kind Of Blue,3,Blue In Green
            |""".trimMargin())
        val qobuz = FakeService("qobuz", catalogueAlbums = mutableListOf(
            Album("right", "", "Kind Of Blue", listOf("Miles Davis"), 3),
            Album("wrong", "", "Kind Of Blue (Live)", listOf("Miles Davis"), 9)))
        qobuz.albumTrackListings["right"] = listing("So What", "Freddie Freeloader",
            "Blue in Green")
        qobuz.albumTrackListings["wrong"] = listing("Intro", "So What (Live)")
        val r = import(file, qobuz)
        assertEquals(r.items.toString(), 1, r.counts["matched"])
        assertEquals("exact+tracklist", r.items[0].method)
        assertEquals(listOf("right"), qobuz.writtenAlbums.toList())
    }

    @Test fun `a listing that disagrees is refused, so the same name is not enough`() {
        val file = lib("artist,album,title\nSomebody,Greatest Hits,Song A\n" +
            "Somebody,Greatest Hits,Song B\nSomebody,Greatest Hits,Song C\n")
        val qobuz = FakeService("qobuz", catalogueAlbums = mutableListOf(
            Album("other", "", "Greatest Hits", listOf("Somebody"), 3)))
        qobuz.albumTrackListings["other"] = listing("Other 1", "Other 2", "Song A")
        val r = import(file, qobuz)
        assertEquals(null, r.counts["matched"])
        assertEquals(1, r.counts["unmatched"])
        assertTrue(r.items[0].note!!, r.items[0].note!!.contains("track listing does not agree"))
        assertEquals(0, qobuz.writtenAlbums.size)
    }

    @Test fun `an album with no barcode and no listing is refused with the reason, in amber`() {
        val file = lib("artist,album\nSomebody,Greatest Hits\n")
        val qobuz = FakeService("qobuz", catalogueAlbums = mutableListOf(
            Album("q", "", "Greatest Hits", listOf("Somebody"), 12)))
        val r = import(file, qobuz)
        assertEquals("unmatched, not failed: the file lacks evidence, nothing broke",
            1, r.counts["unmatched"])
        assertEquals(null, r.counts["failed"])
        assertEquals(CsvLibrary.NO_EVIDENCE, r.items[0].note)
        assertEquals("not searched for at all", 0, qobuz.searchCount.get())
        assertEquals(0, qobuz.writtenAlbums.size)
    }

    @Test fun `sixty albums with no evidence do not trip the broken-check breaker`() {
        // Without unmatchable, each of these would be asked for a listing,
        // get nothing, and count as an album that could not be CHECKED —
        // and after fifty the run stops with "the track-listing check is
        // failing", which would be untrue: the file simply has no listings.
        val sb = StringBuilder("artist,album\n")
        for (i in 1..60) sb.append("Artist $i,Album $i\n")
        val r = import(lib(sb.toString()), FakeService("qobuz"))
        assertEquals(60, r.counts["unmatched"])
    }

    @Test fun `a file album whose barcode Qobuz lacks, with no listing, is refused in amber`() {
        val file = lib("artist,album,barcode\nMetallica,Master Of Puppets,075992736121\n")
        val qobuz = FakeService("qobuz", catalogueAlbums = mutableListOf(
            Album("q1", "", "Master Of Puppets", listOf("Metallica"), 8)))
        val r = import(file, qobuz)
        assertEquals(null, r.counts["matched"])
        assertEquals("a refusal, not a broken check", null, r.counts["failed"])
        assertTrue(r.items[0].note!!, r.items[0].note!!.contains("its barcode is not on qobuz"))
        assertEquals(0, qobuz.writtenAlbums.size)
    }

    @Test fun `an album already in Qobuz favourites is not searched for again`() {
        val file = lib("artist,album,barcode\nMetallica,Master Of Puppets,075992736121\n")
        val qobuz = FakeService("qobuz", libAlbums = mutableListOf(
            Album("q1", "075992736121", "Master Of Puppets", listOf("Metallica"), 8)))
        val r = import(file, qobuz)
        assertEquals(1, r.counts["already"])
        assertEquals(0, qobuz.searchCount.get())
    }

    @Test fun `a preview finds everything and writes nothing`() {
        val file = lib("artist,album,barcode\nMetallica,Master Of Puppets,075992736121\n")
        val qobuz = FakeService("qobuz", catalogueAlbums = mutableListOf(
            Album("q1", "075992736121", "Master Of Puppets", listOf("Metallica"), 8)))
        val r = import(file, qobuz, options = NOTHING.copy(doAlbums = true, dryRun = true))
        assertEquals(1, r.counts["matched"])
        assertEquals(0, qobuz.writtenAlbums.size)
    }
}
