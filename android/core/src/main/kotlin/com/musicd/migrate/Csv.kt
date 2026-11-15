package com.musicd.migrate

import java.io.Reader

/*
 * Csv.kt — reading a library export somebody else's software wrote.
 *
 * The file comes from wherever: Mandarin's own export, a spreadsheet, a tag
 * editor, another music app. So this is lenient about the FORM — comma,
 * semicolon or tab separated, quoted or not, a byte-order mark or none, CRLF
 * or LF, header names spelt however that software spells them — and strict
 * about nothing that decides a match. Deciding is Match's job, unchanged.
 */

/** A file that cannot be read as a library, with the reason to show. */
class CsvError(message: String) : RuntimeException(message)

/** What a column means, whatever the file calls it. */
enum class CsvField { ALBUM, ALBUM_ARTIST, ARTIST, TITLE, TRACK_NO, DISC, BARCODE, TRACK_COUNT, YEAR }

object Csv {

    /**
     * Header names, normalised (lowercase, letters and digits only), and what
     * each one means. Built from what common exporters write; a column not
     * named here is IGNORED and listed as such in the summary, never guessed.
     *
     * "track" is deliberately missing: it means the track NUMBER in some
     * exports and the track TITLE in others. [mapHeader] decides it from the
     * other columns present.
     */
    val ALIASES: Map<String, CsvField> = buildMap {
        for (h in listOf("album", "albumtitle", "albumname", "release", "releasetitle",
            "releasename")) put(h, CsvField.ALBUM)
        for (h in listOf("albumartist", "albumartists", "releaseartist", "band"))
            put(h, CsvField.ALBUM_ARTIST)
        for (h in listOf("artist", "artists", "artistname", "trackartist", "performer"))
            put(h, CsvField.ARTIST)
        for (h in listOf("title", "tracktitle", "trackname", "song", "songtitle", "name"))
            put(h, CsvField.TITLE)
        for (h in listOf("tracknumber", "trackno", "tracknum", "trackn", "no", "number"))
            put(h, CsvField.TRACK_NO)
        for (h in listOf("disc", "discnumber", "discno", "disk", "disknumber", "cd"))
            put(h, CsvField.DISC)
        for (h in listOf("barcode", "upc", "ean", "gtin", "upcean", "upcbarcode"))
            put(h, CsvField.BARCODE)
        for (h in listOf("tracks", "trackcount", "numtracks", "totaltracks", "tracktotal"))
            put(h, CsvField.TRACK_COUNT)
        for (h in listOf("year", "date", "releasedate", "originalyear")) put(h, CsvField.YEAR)
    }

    fun norm(header: String): String = header.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Which column holds which field. The first column naming a field wins;
     * a later duplicate is ignored rather than merged.
     *
     * A bare "track" column is the TITLE when nothing else names the title,
     * and the track NUMBER when something does.
     */
    fun mapHeader(header: List<String>): Map<CsvField, Int> {
        val out = LinkedHashMap<CsvField, Int>()
        header.forEachIndexed { i, h -> ALIASES[norm(h)]?.let { out.putIfAbsent(it, i) } }
        val track = header.indexOfFirst { norm(it) == "track" }
        if (track >= 0) {
            if (!out.containsKey(CsvField.TITLE)) out[CsvField.TITLE] = track
            else out.putIfAbsent(CsvField.TRACK_NO, track)
        }
        return out
    }

    /**
     * The separator, from the header line: whichever of comma, semicolon and
     * tab occurs most often outside quotes. A spreadsheet saving "CSV" in a
     * locale with a decimal comma writes semicolons, and treating that as one
     * giant column would report a library with nothing in it.
     */
    fun detectDelimiter(headerLine: String): Char {
        var inQuotes = false
        val counts = HashMap<Char, Int>()
        for (c in headerLine) {
            if (c == '"') inQuotes = !inQuotes
            else if (!inQuotes && (c == ',' || c == ';' || c == '\t')) {
                counts[c] = (counts[c] ?: 0) + 1
            }
        }
        return counts.maxByOrNull { it.value }?.key ?: ','
    }

    /**
     * RFC 4180 records from [reader], one list of cells per call to [onRecord].
     *
     * Streamed, a record at a time, so a file of a hundred thousand tracks is
     * never held as one string next to its parsed form — the app's heap is
     * 256MB and has run out once already on a library walk. Quotes may span
     * lines; "" inside quotes is a quote; a stray quote inside an unquoted
     * cell is kept as text rather than failing the file.
     */
    fun read(reader: Reader, delimiter: Char, onRecord: (List<String>) -> Unit) {
        val cell = StringBuilder()
        var record = ArrayList<String>()
        var inQuotes = false
        var cellStarted = false
        var pending = -2 // -2: nothing pushed back
        var any = false

        fun endCell() { record.add(cell.toString()); cell.setLength(0); cellStarted = false }
        fun endRecord() {
            endCell()
            // A blank line is not a record of one empty cell.
            if (!(record.size == 1 && record[0].isEmpty())) onRecord(record)
            record = ArrayList()
        }

        while (true) {
            val r = if (pending != -2) pending.also { pending = -2 } else reader.read()
            if (r < 0) break
            any = true
            val c = r.toChar()
            if (inQuotes) {
                if (c == '"') {
                    val next = reader.read()
                    if (next == '"'.code) cell.append('"')
                    else { inQuotes = false; pending = next }
                } else cell.append(c)
                continue
            }
            when {
                c == '"' && !cellStarted -> { inQuotes = true; cellStarted = true }
                c == delimiter -> endCell()
                c == '\r' -> {
                    val next = reader.read()
                    if (next != '\n'.code) pending = next
                    endRecord()
                }
                c == '\n' -> endRecord()
                else -> { cell.append(c); cellStarted = true }
            }
        }
        if (any && (cell.isNotEmpty() || record.isNotEmpty())) endRecord()
    }
}
