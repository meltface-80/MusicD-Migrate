package com.musicd.migrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Reading library files: the CSV reader itself, recognising columns, and
 * turning rows into albums. Whether an album then MATCHES is Match's business
 * and is tested in ImportTest through the real engine.
 */
class CsvTest {

    private fun records(text: String, delimiter: Char = ','): List<List<String>> {
        val out = ArrayList<List<String>>()
        Csv.read(text.reader(), delimiter) { out.add(it) }
        return out
    }

    private fun lib(text: String, name: String = "x.csv") =
        CsvLibrary.parse(text.byteInputStream(Charsets.UTF_8), name)

    // ------------------------------------------------------------ the reader

    @Test fun `quotes, doubled quotes, commas and newlines inside a cell survive`() {
        val r = records("a,b,c\r\n\"one, two\",\"say \"\"hi\"\"\",\"line1\nline2\"\r\nx,,z")
        assertEquals(listOf("a", "b", "c"), r[0])
        assertEquals(listOf("one, two", "say \"hi\"", "line1\nline2"), r[1])
        assertEquals("an empty cell is kept, not swallowed", listOf("x", "", "z"), r[2])
        assertEquals(3, r.size)
    }

    @Test fun `blank lines are not records and a missing final newline loses nothing`() {
        assertEquals(listOf(listOf("a"), listOf("b")), records("a\n\n\nb"))
    }

    @Test fun `the separator is detected from the header`() {
        assertEquals(';', Csv.detectDelimiter("Album;Artist;\"Title, with comma\""))
        assertEquals('\t', Csv.detectDelimiter("album\tartist\ttitle"))
        assertEquals(',', Csv.detectDelimiter("album,artist"))
    }

    @Test fun `a semicolon file from a spreadsheet reads as columns, not one long cell`() {
        val l = lib("Album Artist;Album;Title\nMetallica;Master Of Puppets;Battery\n")
        assertEquals(1, l.summary.albums)
        assertEquals("Master Of Puppets", l.savedAlbums()[0].title)
    }

    @Test fun `a byte-order mark does not hide the first column`() {
        val l = lib("﻿album,artist,title\nRec,Band,Song\n")
        assertEquals(1, l.summary.albums)
        assertEquals("Rec", l.savedAlbums()[0].title)
    }

    // --------------------------------------------------------------- columns

    @Test fun `header names are recognised however the exporting software spells them`() {
        val m = Csv.mapHeader(listOf("Album Artist", "ALBUM_TITLE", "Track Title", "Track #",
            "Disc Number", "UPC", "Year", "Comment"))
        assertEquals(0, m[CsvField.ALBUM_ARTIST])
        assertEquals(1, m[CsvField.ALBUM])
        assertEquals(2, m[CsvField.TITLE])
        assertEquals(4, m[CsvField.DISC])
        assertEquals(5, m[CsvField.BARCODE])
        assertEquals(6, m[CsvField.YEAR])
        assertTrue("an unknown column is ignored, never guessed", 7 !in m.values)
    }

    @Test fun `a bare track column is the title alone and the number beside a title`() {
        assertEquals(1, Csv.mapHeader(listOf("album", "track", "artist"))[CsvField.TITLE])
        val both = Csv.mapHeader(listOf("album", "track", "title", "artist"))
        assertEquals(2, both[CsvField.TITLE])
        assertEquals(1, both[CsvField.TRACK_NO])
    }

    @Test fun `a file with no album or no artist column says so, naming its columns`() {
        for ((csv, want) in listOf("artist,title\nA,B\n" to "No column names the album",
                                   "album,title\nA,B\n" to "No column names the artist")) {
            try {
                lib(csv); fail("should refuse: $csv")
            } catch (e: CsvError) {
                assertTrue(e.message!!, e.message!!.contains(want))
                assertTrue("it quotes what the file has: ${e.message}",
                    e.message!!.contains("\"title\""))
            }
        }
    }

    @Test fun `an empty file and a header-only file are refused, not read as no albums`() {
        for (csv in listOf("", "album,artist\n")) {
            try { lib(csv); fail("should refuse: '$csv'") } catch (e: CsvError) { }
        }
    }

    // ---------------------------------------------------------------- albums

    @Test fun `track rows become albums with their listing in disc and track order`() {
        val l = lib("""album artist,album,disc,track,title,artist,barcode
            |Pink Floyd,The Wall,2,1,Hey You,Pink Floyd,
            |Pink Floyd,The Wall,1,2,The Thin Ice,Pink Floyd,0077774604428
            |Pink Floyd,The Wall,1,1,In the Flesh?,Pink Floyd,0077774604428
            |Miles Davis,Kind of Blue,1,1,So What,Miles Davis,
            |""".trimMargin())
        assertEquals("tracks", l.summary.shape)
        assertEquals(2, l.summary.albums)
        val wall = l.savedAlbums().first { it.title == "The Wall" }
        assertEquals("the barcode from any row of the album", "0077774604428", wall.upc)
        assertEquals(3, wall.trackCount)
        assertEquals(listOf("In the Flesh?", "The Thin Ice", "Hey You"),
            l.albumTracks(wall.id).map { it.title })
        assertEquals(1, l.summary.withBarcode)
        assertEquals(2, l.summary.withListing)
        assertEquals(0, l.summary.unmatchable)
    }

    @Test fun `album rows are read as albums, and the ones with no barcode are unmatchable`() {
        val l = lib("artist,album,upc,tracks\nMetallica,Master Of Puppets,075992736121,8\n" +
            "Somebody,Greatest Hits,,12\n")
        assertEquals("albums", l.summary.shape)
        val mop = l.savedAlbums().first { it.title == "Master Of Puppets" }
        val hits = l.savedAlbums().first { it.title == "Greatest Hits" }
        assertEquals(8, mop.trackCount)
        assertEquals(null, l.unmatchable(mop))
        assertEquals(CsvLibrary.NO_EVIDENCE, l.unmatchable(hits))
        assertEquals(1, l.summary.unmatchable)
        assertTrue("the summary says why", l.summary.warnings.any { it.contains("one row per album") })
    }

    @Test fun `a barcode a spreadsheet turned into a number is ignored and reported`() {
        val l = lib("artist,album,barcode\nMetallica,Master Of Puppets,7.59927E+10\n")
        assertEquals("", l.savedAlbums()[0].upc)
        assertTrue(l.summary.warnings.joinToString(), l.summary.warnings.any { it.contains("E+11") })
    }

    @Test fun `rows without an album or an artist are skipped and counted`() {
        val l = lib("artist,album,title\nA,Rec,Song\n,Rec,Song2\nA,,Song3\n")
        assertEquals(1, l.summary.albums)
        assertEquals(2, l.summary.skippedRows)
        assertEquals(3, l.summary.rows)
    }

    @Test fun `columns used and ignored are both reported`() {
        val l = lib("album,artist,title,rating,comment\nRec,A,Song,5,nice\n")
        assertEquals(listOf("album", "artist", "title"), l.summary.columns)
        assertEquals(listOf("rating", "comment"), l.summary.ignoredColumns)
    }

    @Test fun `an album's id changes with its evidence, so a cached refusal cannot outlive it`() {
        // A barcode-less, listing-less row is refused; if the same album later
        // arrives WITH a listing or a barcode, it must not reuse that refusal
        // from the match cache.
        val bare = lib("artist,album\nA,Rec\n").savedAlbums()[0].id
        val again = lib("artist,album\nA,Rec\n").savedAlbums()[0].id
        val withBarcode = lib("artist,album,upc\nA,Rec,075992736121\n").savedAlbums()[0].id
        val withListing = lib("artist,album,title\nA,Rec,Song\n").savedAlbums()[0].id
        assertEquals("the same evidence is the same album", bare, again)
        assertNotEquals(bare, withBarcode)
        assertNotEquals(bare, withListing)
        assertTrue(bare.startsWith("csv-"))
    }

    @Test fun `library notes in an album title are left out, real titles are not`() {
        // From a real export, where these titles made the search come back empty.
        val cleaned = mapOf(
            "Miracle Pill (12 tracks)" to "Miracle Pill",
            "101 [Disc B]" to "101",
            "Should God Forget- A Retrospective (Disc 1)" to "Should God Forget- A Retrospective",
            "50 Years - Don't Stop Disc 1" to "50 Years - Don't Stop",
            "Blur (Disc One)" to "Blur",
            "Back in Black (2003)" to "Back in Black",
            "The Complete Wooden Nickel Recordings (1 of 2) (2005 Remaster)" to
                "The Complete Wooden Nickel Recordings (2005 Remaster)",
            "Songs Of A Lost World (1)" to "Songs Of A Lost World",
            "Who Can You Trust_" to "Who Can You Trust",
            "Late Night Tales_ Hot Chip" to "Late Night Tales Hot Chip",
            "Alter Bridge (2026) (2)" to "Alter Bridge",
            "Humanz (Japanese Edition) [CD-02] (2017)" to "Humanz (Japanese Edition)",
            "Damned Damned Damned CD4" to "Damned Damned Damned")
        for ((raw, want) in cleaned) assertEquals(raw, want, CsvLibrary.cleanTitle(raw))
        // Facts stay: these are part of what the record IS.
        for (t in listOf("A Sort of Homecoming (Live)", "2112", "1999", "Volume 2", "Nymph_o",
                "AE_LIVE 2016/2018",
                "This Is Your Bloody Valentine [Mini Album]", "Antennas To Hell (Special Edition)",
                "Disc", "(2003)")) {
            assertEquals(t, t, CsvLibrary.cleanTitle(t))
        }
    }

    @Test fun `the discs of one album become one album with one listing`() {
        val l = lib("album artist,album,disc,track,title\n" +
            "Fleetwood Mac,50 Years - Don't Stop Disc 1,1,1,Dreams\n" +
            "Fleetwood Mac,50 Years - Don't Stop Disc 2,2,1,Tusk\n")
        assertEquals(1, l.summary.albums)
        val a = l.savedAlbums()[0]
        assertEquals("50 Years - Don't Stop", a.title)
        assertEquals(listOf("Dreams", "Tusk"), l.albumTracks(a.id).map { it.title })
        assertTrue(l.summary.warnings.joinToString(), l.summary.warnings.any { it.contains("library notes") })
    }

    @Test fun `a large track-per-row file parses quickly`() {
        val sb = StringBuilder("album artist,album,track,title,barcode\n")
        for (a in 1..10_000) for (t in 1..12) sb.append("Artist $a,Album $a,$t,Song $t,\n")
        val started = System.currentTimeMillis()
        val l = lib(sb.toString())
        assertEquals(10_000, l.summary.albums)
        assertEquals(120_000, l.summary.tracks)
        assertTrue("took ${System.currentTimeMillis() - started}ms",
            System.currentTimeMillis() - started < 10_000)
    }
}
