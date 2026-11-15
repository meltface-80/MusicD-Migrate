package com.musicd.migrate

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest

/*
 * CsvLibrary.kt — a library export (Mandarin's, or any other software's) as a
 * migration SOURCE, so its albums can be found on Qobuz and favourited there.
 *
 * Two shapes are accepted, told apart by whether the file has a title column:
 *
 *   ONE ROW PER TRACK  album, album artist, title, track/disc numbers, and a
 *                      barcode if the tags have one. Rows are grouped into
 *                      albums, and each album carries its own TRACK LISTING —
 *                      which is what lets a barcode-less album be matched at
 *                      all (see Tracklist.kt). The recommended shape.
 *   ONE ROW PER ALBUM  album, artist, barcode. Only albums with a barcode can
 *                      be matched: an album with neither a barcode nor a
 *                      listing is [unmatchable], and the report says so rather
 *                      than letting title and artist decide.
 *
 * Nothing here decides a match. It hands the engine the same Album and Track
 * shapes a streaming service would, and Match applies the same rules.
 */

data class CsvAlbum(val album: Album, val tracks: List<Track>)

data class CsvSummary(
    val fileName: String,
    /** "tracks" or "albums" — which shape the file was read as. */
    val shape: String,
    val rows: Int,
    val albums: Int,
    val tracks: Int,
    val withBarcode: Int,
    val withListing: Int,
    val unmatchable: Int,
    val skippedRows: Int,
    val columns: List<String>,
    val ignoredColumns: List<String>,
    val warnings: List<String>
) {
    fun toJson(): String = buildString {
        append("{\"fileName\":").append(jsonQuote(fileName))
        append(",\"shape\":").append(jsonQuote(shape))
        append(",\"rows\":").append(rows).append(",\"albums\":").append(albums)
        append(",\"tracks\":").append(tracks).append(",\"withBarcode\":").append(withBarcode)
        append(",\"withListing\":").append(withListing)
        append(",\"unmatchable\":").append(unmatchable)
        append(",\"skippedRows\":").append(skippedRows)
        append(",\"columns\":").append(columns.joinToString(",", "[", "]") { jsonQuote(it) })
        append(",\"ignoredColumns\":")
            .append(ignoredColumns.joinToString(",", "[", "]") { jsonQuote(it) })
        append(",\"warnings\":").append(warnings.joinToString(",", "[", "]") { jsonQuote(it) })
        append("}")
    }
}

class CsvLibrary(albums: List<CsvAlbum>, val summary: CsvSummary) : MusicSource {

    override val serviceName = "csv"
    override val accountId = ""

    private val byId = albums.associateBy { it.album.id }
    private val ordered = albums.map { it.album }

    override fun me() = Account("", summary.fileName)
    override fun playlists(): List<Playlist> = emptyList()
    override fun playlistTracks(playlistId: String): List<Track> = emptyList()
    override fun savedTracks(): List<Track> = emptyList()
    override fun savedAlbums(): List<Album> = ordered
    override fun followedArtists(): List<Artist> = emptyList()
    override fun albumDetail(albumId: String): Album? = byId[albumId]?.album
    override fun albumTracks(albumId: String): List<Track> = byId[albumId]?.tracks.orEmpty()

    override fun unmatchable(album: Album): String? =
        if (album.upc.isEmpty() && byId[album.id]?.tracks.isNullOrEmpty()) NO_EVIDENCE else null

    companion object {
        const val NO_EVIDENCE = "the file gives no barcode and no track listing for it, and " +
            "an album title and artist alone are not decisive — the same name is too often a " +
            "different record. Export one row per track, or include the barcode."

        /** Past this the file is not a library export, or not one this phone can hold. */
        const val MAX_ROWS = 400_000

        /**
         * Read a library file.
         *
         * @throws CsvError when it cannot be read as a library at all: no
         *   header, no album column, no artist column. A row it cannot use is
         *   SKIPPED and counted instead, so one bad line does not lose a
         *   ten-thousand-album file.
         */
        fun parse(input: InputStream, fileName: String): CsvLibrary {
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16)
            reader.mark(1 shl 16)
            val first = reader.readLine() ?: throw CsvError("That file is empty.")
            reader.reset()
            if (first.firstOrNull() == '﻿') reader.skip(1)
            val delimiter = Csv.detectDelimiter(first)

            var header: List<String>? = null
            var cols: Map<CsvField, Int> = emptyMap()
            val builder = Builder()
            Csv.read(reader, delimiter) { record ->
                if (header == null) {
                    header = record.map { it.trim() }
                    cols = Csv.mapHeader(header!!)
                    requireColumns(cols, header!!)
                } else {
                    if (builder.rows >= MAX_ROWS) {
                        throw CsvError("That file has more than $MAX_ROWS rows, which is more " +
                            "than this app will hold. Split it and import the parts.")
                    }
                    builder.add(record, cols)
                }
            }
            val h = header ?: throw CsvError("That file is empty.")
            if (builder.rows == 0) throw CsvError("That file has a header and no rows.")
            return builder.build(fileName, h, cols)
        }

        private fun requireColumns(cols: Map<CsvField, Int>, header: List<String>) {
            val seen = header.joinToString(", ") { "\"$it\"" }.take(300)
            if (CsvField.ALBUM !in cols) {
                throw CsvError("No column names the album. Expected one called album, album " +
                    "title or release — this file has $seen.")
            }
            if (CsvField.ALBUM_ARTIST !in cols && CsvField.ARTIST !in cols) {
                throw CsvError("No column names the artist. Expected one called album artist " +
                    "or artist — this file has $seen.")
            }
        }

        /**
         * A barcode as digits, or "" when there is none worth searching for.
         * A spreadsheet that opened the file turns 075992736121 into
         * 7.59927E+10, which is not a barcode any more: [mangled] says so,
         * so the summary can say why barcodes went missing.
         */
        internal fun barcode(raw: String, mangled: () -> Unit): String {
            val v = raw.trim()
            if (v.isEmpty()) return ""
            if (Regex("""^\d+(\.\d+)?[eE]\+?\d+$""").matches(v)) { mangled(); return "" }
            val d = v.filter { it.isDigit() }
            return if (d.length in 8..14) d else ""
        }

        internal fun sha(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it) }
    }

    /** Rows in, albums out. One instance per file. */
    private class Builder {
        private class Group(val title: String, val artist: String) {
            val trackRows = ArrayList<Triple<Int, Int, String>>() // disc, number, title
            val trackArtists = HashMap<String, Int>()
            var albumArtist = ""
            var upc = ""
            var trackCount: Int? = null
            var year: Int? = null
            var order = 0
        }

        var rows = 0
        private var skipped = 0
        private var mangled = 0
        private var conflictingBarcodes = 0
        private val groups = LinkedHashMap<String, Group>()

        private fun cell(record: List<String>, cols: Map<CsvField, Int>, f: CsvField): String =
            cols[f]?.let { record.getOrNull(it) }?.trim().orEmpty()

        fun add(record: List<String>, cols: Map<CsvField, Int>) {
            rows++
            val album = cell(record, cols, CsvField.ALBUM)
            val albumArtist = cell(record, cols, CsvField.ALBUM_ARTIST)
            val artist = cell(record, cols, CsvField.ARTIST)
            val credited = albumArtist.ifEmpty { artist }
            if (album.isEmpty() || credited.isEmpty()) { skipped++; return }

            val key = Canon.canon(album) + "|" + Canon.canon(credited)
            val g = groups.getOrPut(key) { Group(album, credited).also { it.order = groups.size } }
            if (albumArtist.isNotEmpty()) g.albumArtist = albumArtist
            if (artist.isNotEmpty()) g.trackArtists[artist] = (g.trackArtists[artist] ?: 0) + 1

            val upc = barcode(cell(record, cols, CsvField.BARCODE)) { mangled++ }
            if (upc.isNotEmpty()) {
                if (g.upc.isEmpty()) g.upc = upc
                else if (g.upc.trimStart('0') != upc.trimStart('0')) conflictingBarcodes++
            }
            cell(record, cols, CsvField.YEAR).take(4).toIntOrNull()
                ?.takeIf { it > 1000 }?.let { g.year = g.year ?: it }
            cell(record, cols, CsvField.TRACK_COUNT).toIntOrNull()
                ?.takeIf { it > 0 }?.let { g.trackCount = it }

            val title = cell(record, cols, CsvField.TITLE)
            if (CsvField.TITLE in cols && title.isNotEmpty()) {
                val disc = cell(record, cols, CsvField.DISC).substringBefore('/')
                    .trim().toIntOrNull() ?: 1
                val no = cell(record, cols, CsvField.TRACK_NO).substringBefore('/')
                    .trim().toIntOrNull() ?: Int.MAX_VALUE
                g.trackRows.add(Triple(disc, no, title))
            }
        }

        fun build(fileName: String, header: List<String>, cols: Map<CsvField, Int>): CsvLibrary {
            val shape = if (CsvField.TITLE in cols) "tracks" else "albums"
            val albums = groups.values.sortedBy { it.order }.map { g ->
                // Sorted by disc and number; rows with no number keep file order.
                val listing = g.trackRows.withIndex()
                    .sortedWith(compareBy({ it.value.first }, { it.value.second }, { it.index }))
                    .map { it.value.third }
                val artist = g.albumArtist.ifEmpty {
                    g.trackArtists.maxByOrNull { it.value }?.key ?: g.artist
                }
                // The id carries the EVIDENCE, not just the name: a match
                // cached for a barcode-less row must not be reused when a
                // richer export of the same album turns up with a barcode or
                // a listing — the refusal would outlive what caused it.
                val id = "csv-" + sha(Canon.canon(g.title) + "|" + Canon.canon(artist) + "|" +
                    g.upc + "|" + listing.joinToString("\u0001") { Canon.canon(it) })
                val tracks = listing.mapIndexed { i, t ->
                    Track(id = "$id-${i + 1}", title = t, artists = listOf(artist), album = g.title)
                }
                CsvAlbum(Album(id = id, upc = g.upc, title = g.title, artists = listOf(artist),
                    trackCount = if (tracks.isNotEmpty()) tracks.size else g.trackCount,
                    year = g.year), tracks)
            }
            val used = cols.values.toSet()
            val warnings = ArrayList<String>()
            if (skipped > 0) warnings.add("$skipped row${if (skipped == 1) "" else "s"} had no " +
                "album or no artist and were skipped.")
            if (mangled > 0) warnings.add("$mangled barcode${if (mangled == 1) "" else "s"} " +
                "looked like a spreadsheet had turned them into numbers (7.6E+11) and were " +
                "ignored. Export again without opening the file in a spreadsheet.")
            if (conflictingBarcodes > 0) warnings.add("$conflictingBarcodes row" +
                "${if (conflictingBarcodes == 1) "" else "s"} gave a different barcode from the " +
                "rest of the album; the first one was used.")
            if (shape == "albums") warnings.add("This file has one row per album, so only " +
                "albums with a barcode can be matched. An export with one row per track lets " +
                "the others be checked by their track listing.")
            val summary = CsvSummary(
                fileName = fileName, shape = shape, rows = rows, albums = albums.size,
                tracks = albums.sumOf { it.tracks.size },
                withBarcode = albums.count { it.album.upc.isNotEmpty() },
                withListing = albums.count { it.tracks.isNotEmpty() },
                unmatchable = albums.count { it.album.upc.isEmpty() && it.tracks.isEmpty() },
                skippedRows = skipped,
                columns = cols.entries.sortedBy { it.value }.map { header[it.value] },
                ignoredColumns = header.filterIndexed { i, h -> i !in used && h.isNotBlank() },
                warnings = warnings)
            return CsvLibrary(albums, summary)
        }
    }
}
