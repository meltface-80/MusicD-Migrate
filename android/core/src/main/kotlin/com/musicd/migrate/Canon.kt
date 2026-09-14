package com.musicd.migrate

import java.text.Normalizer

/*
 * Canon.kt — turning two services' idea of a name into one comparable string.
 *
 * THE KOTLIN TWIN OF lib/canon.js, and it is deliberately a twin: the same
 * rules, the same edition-word list, the same refusals, and the tests in
 * CanonTest.kt are the JavaScript tests translated line for line. When one
 * side gains a rule the other has to, or the APK and the container will
 * quietly disagree about which tracks migrate — and the APK is the half nobody
 * would think to check.
 *
 * Everything here is pure: no network, no Android, no account.
 */
object Canon {

    /**
     * Lowercase, unaccented, punctuation-free, single-spaced.
     *
     * NFKD then dropping the combining marks is what makes "Björk" and "Bjork"
     * the same string. Both services send accented names and they do not
     * always agree on the accent.
     */
    fun canon(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val folded = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
        val sb = StringBuilder(folded.length)
        for (ch in folded) {
            when {
                // Combining diacritical marks: U+0300..U+036F.
                ch.code in 0x300..0x36F -> {}
                // A typographic apostrophe is a different code point from a
                // typewriter one and both appear. Dropped rather than mapped,
                // so "don't" and "dont" agree — which they must.
                ch == '\'' || ch == '‘' || ch == '’' || ch == 'ʼ' -> {}
                ch == '&' -> sb.append(" and ")
                ch in 'a'..'z' || ch in '0'..'9' -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(WHITESPACE, " ")
    }

    private val WHITESPACE = Regex("\\s+")

    /*
     * The words that mark an EDITION rather than a different recording.
     *
     * "remaster" is here and "live" is NOT, deliberately. A remaster is the
     * same performance and migrating to it is right; a live version is a
     * different performance, and accepting one for the other is the failure
     * this whole file is shaped to avoid. Same for "acoustic", "demo",
     * "remix", "instrumental", "edit" and "version".
     */
    val EDITION_WORDS = listOf(
        "remaster", "remastered", "remasterised", "remasterized",
        "deluxe", "deluxe edition", "expanded", "expanded edition",
        "special edition", "anniversary edition", "bonus track",
        "bonus track version", "explicit", "explicit version",
        "album version", "original mix", "mono", "stereo",
        "digital remaster", "reissue", "re issue"
    )

    // "(2011 Remaster)", "[Remastered]", " - 2011 Remaster", " - Deluxe Edition"
    private val SUFFIX = Regex("""\s*(?:[(\[][^)\]]*[)\]]|-\s+[^-]*)\s*$""")
    private val YEAR_EDITION = Regex("""^\d{4} (remaster|mix|version)$""")

    /**
     * The title with any trailing EDITION suffix removed.
     *
     * Only a suffix that actually names an edition is taken. "Paranoid Android
     * (Live)" keeps its suffix, because the suffix is the whole difference
     * between that and the studio recording.
     *
     * Repeats, because both shapes occur together: "Song (feat. X) - 2011
     * Remaster" has two suffixes and removing one leaves the other.
     */
    fun stripVersion(title: String?): String {
        var out = (title ?: "").trim()
        repeat(4) {
            val m = SUFFIX.find(out) ?: return out
            val inner = canon(m.value.trim(' ', '(', '[', '-', ')', ']'))
            if (inner.isEmpty()) return out
            val isEdition = EDITION_WORDS.any { inner == it || inner.contains(it) } ||
                YEAR_EDITION.matches(inner) ||
                inner.startsWith("feat ") || inner.startsWith("featuring ") ||
                inner.startsWith("with ")
            if (!isEdition) return out
            val next = out.substring(0, out.length - m.value.length).trim()
            // Stripping must never empty the title: a track genuinely called
            // "(Remastered)" would otherwise match everything.
            if (next.isEmpty()) return out
            out = next
        }
        return out
    }

    private val SPLIT_PRIMARY =
        Regex("""\s+(?:feat\.?|ft\.?|featuring|with|vs\.?|&)\s+|[,;]|/""", RegexOption.IGNORE_CASE)
    private val SPLIT_ALL =
        Regex("""\s+(?:feat\.?|ft\.?|featuring|with|vs\.?)\s+|[,;]|\s+&\s+|/""",
            RegexOption.IGNORE_CASE)

    /**
     * The artist a track is filed under, with collaborators dropped.
     *
     * Spotify returns an ARRAY of artists and Qobuz returns one `performer`
     * string that may already contain "feat.", so "Calvin Harris" and "Calvin
     * Harris, Rihanna" are routinely the two services' answer for one track.
     */
    fun primaryArtist(s: String?): String {
        val raw = s ?: ""
        val cut = SPLIT_PRIMARY.split(raw).firstOrNull()
        return canon(if (cut.isNullOrBlank()) raw else cut)
    }

    /** Every artist named, canonicalised. Order is not meaningful. */
    fun artistSet(value: List<String>?): Set<String> {
        val out = LinkedHashSet<String>()
        for (v in value ?: emptyList()) {
            for (part in SPLIT_ALL.split(v)) {
                val c = canon(part)
                if (c.isNotEmpty()) out.add(c)
            }
        }
        return out
    }

    fun artistSet(value: String?): Set<String> = artistSet(listOf(value ?: ""))

    /**
     * Sørensen-Dice over character bigrams, 0..1.
     *
     * Used only to RANK candidates that already passed a gate, never to let
     * one through. A similarity threshold on its own is how "Yesterday"
     * matches "Yesterdays".
     */
    fun similarity(a: String?, b: String?): Double {
        val x = a ?: ""
        val y = b ?: ""
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.length < 2 || y.length < 2) return 0.0
        val grams = HashMap<String, Int>()
        for (i in 0 until x.length - 1) {
            val g = x.substring(i, i + 2)
            grams[g] = (grams[g] ?: 0) + 1
        }
        var hits = 0
        for (i in 0 until y.length - 1) {
            val g = y.substring(i, i + 2)
            val n = grams[g] ?: 0
            if (n > 0) { grams[g] = n - 1; hits++ }
        }
        return (2.0 * hits) / ((x.length - 1) + (y.length - 1))
    }

    /** How much of [set] is also in [other], 0..1. Empty on either side is 0. */
    fun overlap(set: Set<String>, other: Set<String>): Double {
        if (set.isEmpty() || other.isEmpty()) return 0.0
        val hits = set.count { other.contains(it) }
        return hits.toDouble() / minOf(set.size, other.size)
    }
}
