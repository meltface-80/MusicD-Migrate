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
        "digital remaster", "reissue", "re issue",
        // Added on the owner's decision after two real reports, each one the
        // same performances in a different pressing -- the same family as
        // "deluxe", "expanded" and "anniversary edition" above:
        //   "(Bonus Edition)"          Coming On Strong
        //   "(Collector's Edition)"    Bummed
        //   "(International Version)"  Death to False Metal
        //   "(U.S. Version)"           Babylon   -- canon writes "u s version"
        //   "(2009 Re-Mastered)"       Emotional Rescue  -- see REMASTER
        // What was asked for and deliberately NOT added: "extended version",
        // "(Remixes)", "(DJ Mix)", "(Acoustic)", "Vol. 2" and "(EP)". The
        // first four are different RECORDINGS, "Vol. 2" is a different record
        // outright, and an artist can have both an EP and an album of one
        // name.
        "bonus edition", "collectors edition", "international version",
        "u s version"
    )

    // "(2011 Remaster)", "[Remastered]", " - 2011 Remaster", " - Deluxe Edition"
    private val SUFFIX = Regex("""\s*(?:[(\[][^)\]]*[)\]]|-\s+[^-]*)\s*$""")
    private val YEAR_EDITION = Regex("""^\d{4} (remaster|mix|version)$""")

    /**
     * "(Re-Mastered)", "(2009 Re-Mastered)", "(Remastering)" -- a remaster
     * whose spelling the word list does not carry.
     *
     * ANCHORED, and that is the point: [EDITION_WORDS] is matched as a
     * substring, so putting "re master" in the list would also strip
     * "(Pre-Master)", which is a studio stage and not an edition. This form
     * cannot.
     */
    private val REMASTER = Regex("""^(?:\d{4} )?re ?master(?:ed|ing)?$""")

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
                REMASTER.matches(inner) ||
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

    /**
     * A collaboration marker always separates artists. A list punctuation
     * mark only sometimes does -- see [splitArtists].
     */
    private val COLLAB =
        Regex("""\s+(?:feat\.?|ft\.?|featuring|with|vs\.?)\s+""", RegexOption.IGNORE_CASE)
    private val LIST_PUNCT = Regex("""\s*[,;/]\s*|\s+&\s+""")
    private val HAS_ALNUM = Regex("[a-zA-Z0-9]")

    /**
     * Split a string that may name several artists -- but not through a NAME.
     *
     * "Carla Bley/Steve Swallow/Andy Sheppard" is three people. "AC/DC" is
     * one band, "Belle & Sebastian" is one band, and "Earth, Wind & Fire" is
     * one band with both a comma and an ampersand in its name. Splitting
     * those leaves "AC", "Belle" and "Earth", which match nothing and search
     * for nothing.
     *
     * The rule: a comma, semicolon, slash or ampersand separates artists only
     * when EVERY segment it produces is more than one word. Two people are
     * "Vincent Peirani & Emile Parisien" -- first name and last name, on both
     * sides. A band with punctuation in its name almost always has a one-word
     * piece: AC, DC, Belle, Fire, Die, For.
     *
     * Measured on a real 9,635-album library: 88 albums were refused because
     * a band's own name had been cut in half this way.
     *
     * Kept in step with splitArtists in lib/canon.js by hand.
     */
    fun splitArtists(value: String?): List<String> {
        val out = ArrayList<String>()
        for (piece in COLLAB.split(value ?: "")) {
            val parts = LIST_PUNCT.split(piece).map { it.trim() }.filter { it.isNotEmpty() }
            val separates = parts.size > 1 && parts.all { it.split(Regex("\\s+")).size > 1 }
            // A piece with no letters or digits in it is punctuation, not an
            // artist.
            if (separates) out.addAll(parts.filter { HAS_ALNUM.containsMatchIn(it) })
            else if (HAS_ALNUM.containsMatchIn(piece)) out.add(piece.trim())
        }
        return out
    }

    /**
     * The artists a string names, each AS WRITTEN, in order.
     *
     * The same split as [artistSet], but keeping the original spelling,
     * because this one feeds a SEARCH QUERY rather than a comparison. Roon
     * hands over an album's artists as one string joined with slashes --
     * "Carla Bley/Steve Swallow/Andy Sheppard" -- and a query naming all
     * three finds nothing on either service. Measured on a real 9,514-album
     * Roon library: 457 of the 1,273 albums whose search came back EMPTY had
     * an artist string naming more than one person, against 2.0% of the 4,639
     * that matched.
     *
     * Kept in step with artistNames in lib/canon.js by hand.
     */
    fun artistNames(value: String?): List<String> {
        val out = ArrayList<String>()
        for (part in splitArtists(value)) {
            val t = part.trim()
            if (t.isNotEmpty() && !out.contains(t)) out.add(t)
        }
        return out
    }

    /** A leading "The"/"A"/"Los" -- never the difference between two acts. */
    private val LEADING_ARTICLE = Regex("^(?:the|a|an|los|las|les)\\s+")

    /**
     * Words that name an ENSEMBLE rather than a different act: "Vijay Iyer"
     * and "Vijay Iyer Trio" are the same artist billed two ways, and jazz
     * does this constantly. Deliberately short, and deliberately without
     * "tribute", "covers", "band" or "project": a tribute act IS somebody
     * else, and that is the mistake this whole file exists to avoid.
     *
     * Kept in step with ENSEMBLE_WORDS in lib/canon.js by hand.
     */
    val ENSEMBLE_WORDS = listOf("trio", "quartet", "quintet", "sextet", "septet",
        "octet", "orchestra", "ensemble", "quartett", "big band")

    /**
     * Every FORM of every artist a value names, canonicalised.
     *
     * Every form, not just every name, and that is the point. [overlap] needs
     * one shared entry, so listing the name as written alongside the pieces
     * it might be made of lets two spellings of the same thing agree without
     * letting a different artist in. Each entry is still a name that appears
     * in the string.
     *
     * The forms are: the whole string; each artist it splits into; and each
     * of those without a leading article or a trailing ensemble word.
     *
     * The whole string matters most, and 42 albums of a real library are why:
     * Roon writes "Siouxsie and the Banshees", the service writes "Siouxsie &
     * The Banshees", canon turns "&" into "and" so the two are the SAME
     * string -- but the split ran first, cut the service's name into
     * "Siouxsie" and "The Banshees", and nothing was left to agree with. Same
     * for "To/Die/For" against "To Die For".
     */
    fun artistSet(value: List<String>?): Set<String> {
        val out = LinkedHashSet<String>()
        fun add(x: String?) {
            val c = canon(x ?: "")
            if (c.isNotEmpty()) out.add(c)
        }
        for (v in value ?: emptyList()) {
            add(v)                                    // the name exactly as written
            for (name in artistNames(v)) {
                add(name)
                val bare = LEADING_ARTICLE.replace(canon(name), "")
                if (bare.isNotEmpty()) add(bare)
                for (w in ENSEMBLE_WORDS) {
                    if (bare.endsWith(" $w")) add(bare.dropLast(w.length + 1).trim())
                }
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
