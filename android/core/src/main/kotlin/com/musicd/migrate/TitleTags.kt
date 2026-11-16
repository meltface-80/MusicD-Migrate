package com.musicd.migrate

/**
 * The trailing tags on an album title — "(Live In Tokyo)", "- Remastered",
 * ": Stan Getz" — and whether one adds a fact or only repeats one. Used by
 * Match.matchAlbum; split out of Match.kt.
 */
internal object TitleTags {

    // A trailing "(...)" or "[...]".
    private val BRACKET_TAIL = Regex("""\s*[(\[][^)\]]*[)\]]\s*$""")

    /**
     * The trailing tags a title might have, SHORTEST first.
     *
     * Shortest first matters: "Always Let Me Go - Live In Tokyo (Live In
     * Tokyo)" has two, and taking the longest leaves "Always Let Me Go",
     * which is not the record anybody owns.
     */
    private fun tails(title: String): List<String> {
        val out = ArrayList<String>()
        BRACKET_TAIL.find(title)?.let { out.add(it.value) }
        for (sep in listOf(" - ", ": ")) {
            val at = title.lastIndexOf(sep)
            if (at > 0) out.add(title.substring(at))
        }
        return out.sortedBy { it.length }
    }

    /** A trailing "(Original)" or a catalogue number in brackets of any kind:
     *  "(395 136-2)", "[COOKCD646]", "(AMAN2510PR)", "(00946 397342 2 1)". */
    private val SEARCH_NOISE = Regex(
        """\s*[(\[{]\s*(?:original|[a-z]{0,6}-?\d[\d\s\-.]*[a-z]{0,3})\s*[)\]}]\s*$""",
        RegexOption.IGNORE_CASE)

    /**
     * The title without a trailing "(Original)" or catalogue number, or null
     * if it has neither — what to search for when the title as written came
     * back EMPTY.
     *
     * On the owner's decision (0.5.2): 65 empty searches in a real library
     * were "Siamese Dream (Original)", "Solitude Standing (395 136-2)" and the
     * like, notes from the owner's tagging that no service puts in a title.
     * This changes what is SEARCHED, never what is accepted: the caller only
     * asks again with the track-listing check on, and anything it finds has
     * to pass that check like any other title match. Deliberately NOT any
     * trailing tag: without "(Live)" a search finds the studio record, and a
     * live album's untagged track titles would agree with it.
     */
    fun searchNoise(title: String): String? {
        var t = title
        while (true) {
            val m = SEARCH_NOISE.find(t) ?: break
            // "(Original)" itself, or a catalogue number with at least three
            // digits: "(2)" and "(CD1)" are library notes CsvLibrary handles.
            if (!m.value.contains("original", ignoreCase = true) &&
                m.value.count { it.isDigit() } < 3) break
            val next = t.substring(0, m.range.first).trimEnd()
            if (next.isBlank()) break
            t = next
        }
        return if (t != title) t else null
    }

    private val TAG_EDGES = Regex("""^[\s(\[\-:]+|[)\]\s]+$""")

    /**
     * The tag one title has and the other does not, or "" if that is not the
     * only difference between them.
     *
     * The counterpart of [redundantTag]: this one finds the tags that DO say
     * something. "Rio" against "Rio (Live)" returns "(Live)"; "Greatest Hits"
     * against "Greatest Hits: Volume 2" returns ": Volume 2".
     *
     * A match on this alone would be a title-and-artist match, which this
     * file refuses on principle: "Greatest Hits" by almost anybody is several
     * records. So the caller holds these back for the TRACK LISTING to
     * decide, which is the one piece of independent evidence a barcode-less
     * album has.
     */
    fun addedTag(candTitle: String?, wantTitle: String?): String {
        for ((long, short) in listOf(candTitle to wantTitle, wantTitle to candTitle)) {
            val raw = long ?: ""
            for (tail in tails(raw)) {
                val head = Canon.canon(raw.substring(0, raw.length - tail.length))
                if (head.isEmpty()) continue
                if (head == Canon.canon(short ?: "") ||
                    head == Canon.canon(Canon.stripVersion(short ?: ""))) {
                    val inner = Canon.canon(TAG_EDGES.replace(tail, ""))
                    if (inner.isNotEmpty()) return tail.trim()
                }
            }
        }
        return ""
    }

    /**
     * A trailing tag that adds NOTHING the other side's title and artist did
     * not already say -- the tag text if so, "" if not.
     *
     * Not a relaxation of the title gate, an observation about information.
     * Two real examples, from a 9,635-album library:
     *
     *     owns "Always Let Me Go - Live In Tokyo"
     *     they have "Always Let Me Go - Live In Tokyo (Live In Tokyo)"
     *
     *     owns "33 Hits"                     by Nina Simone
     *     they have "33 Hits (Nina Simone)"  by Nina Simone
     *
     * Neither tag says anything new: the first repeats the title's own words,
     * the second repeats the artist whose gate this candidate has already
     * passed. A tag that DOES add something -- "(Live)" on a title that never
     * mentions live, "Vol. 2", "(Remixes)" -- fails the test and the album is
     * still refused, which is the whole reason this can be allowed at all.
     *
     * Either side may carry the tag: services append "(Live In Tokyo)" and
     * Roon rips append ": Stan Getz".
     */
    fun redundantTag(a: String?, b: String?, artists: List<String>?): String {
        val known = HashSet<String>()
        for (w in Canon.canon(b ?: "").split(" ")) if (w.isNotEmpty()) known.add(w)
        for (name in Canon.artistSet(artists)) {
            for (w in name.split(" ")) if (w.isNotEmpty()) known.add(w)
        }
        val raw = a ?: ""
        for (tail in tails(raw)) {
            val head = Canon.canon(raw.substring(0, raw.length - tail.length))
            if (head.isEmpty()) continue
            if (head != Canon.canon(b ?: "") &&
                head != Canon.canon(Canon.stripVersion(b ?: ""))) continue
            val inner = Canon.canon(TAG_EDGES.replace(tail, ""))
            if (inner.isEmpty()) continue
            if (inner.split(" ").all { it.isEmpty() || known.contains(it) }) return tail.trim()
        }
        return ""
    }
}
