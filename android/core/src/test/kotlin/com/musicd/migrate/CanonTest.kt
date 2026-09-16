package com.musicd.migrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JavaScript canon tests (test/unit/canon.test.js), translated.
 *
 * Kept line for line on purpose: this file and that one are the contract
 * between the container and the APK, and a rule that changes on one side and
 * not the other should fail here rather than migrate a different set of tracks
 * on a phone.
 */
class CanonTest {

    @Test fun `folds case accents punctuation and ampersands`() {
        assertEquals("bjork", Canon.canon("Björk"))
        assertEquals("dont stop", Canon.canon("Don't Stop"))
        assertEquals("dont stop", Canon.canon("Don’t Stop"))
        assertEquals("simon and garfunkel", Canon.canon("Simon & Garfunkel"))
        assertEquals("a b", Canon.canon("  A   B  "))
        assertEquals("", Canon.canon(null))
    }

    @Test fun `strips edition suffixes`() {
        assertEquals("Blue Monday", Canon.stripVersion("Blue Monday - 2016 Remaster"))
        assertEquals("Blue Monday", Canon.stripVersion("Blue Monday (Remastered)"))
        assertEquals("Rumours", Canon.stripVersion("Rumours [Deluxe Edition]"))
        assertEquals("Song", Canon.stripVersion("Song (feat. Someone) - 2011 Remaster"))
    }

    @Test fun `the edition words added from a real library strip, their neighbours do not`() {
        // Added on the owner's decision: each is the same performances in a
        // different pressing, which is what "deluxe" already is.
        assertEquals("Coming On Strong", Canon.stripVersion("Coming On Strong (Bonus Edition)"))
        assertEquals("Bummed", Canon.stripVersion("Bummed (Collector's Edition)"))
        assertEquals("Death to False Metal",
            Canon.stripVersion("Death to False Metal (International Version)"))
        assertEquals("Babylon", Canon.stripVersion("Babylon (U.S. Version)"))
        assertEquals("Emotional Rescue", Canon.stripVersion("Emotional Rescue (2009 Re-Mastered)"))
        assertEquals("Something", Canon.stripVersion("Something (Re-Mastered)"))

        // Asked about and deliberately refused: different RECORDINGS, or a
        // different record outright.
        assertEquals("Pearls Of Passion (Extended Version)",
            Canon.stripVersion("Pearls Of Passion (Extended Version)"))
        assertEquals("Blue Lines (Remixes)", Canon.stripVersion("Blue Lines (Remixes)"))
        assertEquals("At Play (DJ Mix)", Canon.stripVersion("At Play (DJ Mix)"))
        assertEquals("Aftersun (EP)", Canon.stripVersion("Aftersun (EP)"))

        // And the reason the remaster form is a pattern rather than a word in
        // the list: the list is matched as a SUBSTRING, so "re master" in it
        // would also strip a pre-master, a studio stage and not an edition.
        assertEquals("Something (Pre-Master)", Canon.stripVersion("Something (Pre-Master)"))
        assertEquals("Something (Pre-Mastered)", Canon.stripVersion("Something (Pre-Mastered)"))
    }

    @Test fun `keeps suffixes that name a different recording`() {
        // The whole safety property: these are not editions.
        assertEquals("Paranoid Android (Live)", Canon.stripVersion("Paranoid Android (Live)"))
        assertEquals("Song (Acoustic)", Canon.stripVersion("Song (Acoustic)"))
        assertEquals("Song (Radio Edit)", Canon.stripVersion("Song (Radio Edit)"))
        assertEquals("Song - Extended Mix", Canon.stripVersion("Song - Extended Mix"))
        assertEquals("Song (Someone Remix)", Canon.stripVersion("Song (Someone Remix)"))
    }

    @Test fun `never empties the title`() {
        assertEquals("(Remastered)", Canon.stripVersion("(Remastered)"))
    }

    @Test fun `primaryArtist takes the lead credit`() {
        assertEquals("calvin harris", Canon.primaryArtist("Calvin Harris feat. Rihanna"))
        assertEquals("calvin harris", Canon.primaryArtist("Calvin Harris, Rihanna"))
        assertEquals("jay z", Canon.primaryArtist("Jay-Z & Kanye West"))
        assertEquals("portishead", Canon.primaryArtist("Portishead"))
    }

    @Test fun `artistSet holds the parts AND the name exactly as written`() {
        // Both, deliberately. `overlap` needs one shared entry, so carrying
        // the whole string alongside the pieces lets two spellings of the
        // same name agree -- see the next test, which is 42 albums of a real
        // library -- without admitting an artist the string never named.
        val feat = Canon.artistSet("Calvin Harris feat. Rihanna")
        assertTrue(feat.contains("calvin harris"))
        assertTrue(feat.contains("rihanna"))
        assertTrue("the credit as written", feat.contains("calvin harris feat rihanna"))
        assertFalse(feat.contains("drake"))

        assertEquals(listOf("calvin harris", "rihanna"),
            Canon.artistSet(listOf("Calvin Harris", "Rihanna")).toList())
    }

    @Test fun `one name written two ways still agrees with itself`() {
        // 42 albums of a real library were refused for this: Roon writes
        // "Siouxsie and the Banshees", the service writes "Siouxsie & The
        // Banshees", canon turns "&" into "and" so the two ARE the same
        // string -- but the split ran first and cut the service's name into
        // "Siouxsie" and "The Banshees", leaving nothing to agree with.
        for ((ours, theirs) in listOf(
            "Siouxsie and the Banshees" to "Siouxsie & The Banshees",
            "Derek and the Dominos" to "Derek & The Dominos",
            "King Gizzard and the Lizard Wizard" to "King Gizzard & The Lizard Wizard",
            "To Die For" to "To/Die/For"
        )) {
            assertTrue("$ours must agree with $theirs",
                Canon.overlap(Canon.artistSet(ours), Canon.artistSet(theirs)) > 0.0)
        }
    }

    @Test fun `a leading article and an ensemble word are not different artists`() {
        fun same(a: String, b: String) =
            Canon.overlap(Canon.artistSet(a), Canon.artistSet(b)) > 0.0
        assertTrue("an article", same("The Modern Jazz Quartet", "Modern Jazz Quartet"))
        assertTrue(same("A Certain Ratio", "Certain Ratio"))
        assertTrue("how jazz bills a leader", same("Vijay Iyer Trio", "Vijay Iyer"))
        assertTrue(same("The Dave Brubeck Quartet", "Dave Brubeck"))

        // The guard that matters: a tribute act IS somebody else.
        assertFalse(same("Portishead", "Portishead Tribute"))
        assertFalse(same("Metallica", "Apocalyptica"))
        assertFalse("a different band of the same name", same("Nirvana", "Nirvana UK"))
    }

    @Test fun `a band whose own name contains an ampersand or slash is not cut in half`() {
        // 88 albums of a real library came back "not found" because the
        // splitter searched for "AC" and compared "Belle".
        assertEquals(listOf("AC/DC"), Canon.splitArtists("AC/DC"))
        assertEquals(listOf("Belle & Sebastian"), Canon.splitArtists("Belle & Sebastian"))
        assertEquals(listOf("Earth, Wind & Fire"), Canon.splitArtists("Earth, Wind & Fire"))
        assertEquals(listOf("Siouxsie & The Banshees"),
            Canon.splitArtists("Siouxsie & The Banshees"))

        // And it still splits what is genuinely a list.
        assertEquals(listOf("Carla Bley", "Steve Swallow", "Andy Sheppard"),
            Canon.splitArtists("Carla Bley/Steve Swallow/Andy Sheppard"))
        assertEquals(listOf("Miles Davis", "John Coltrane"),
            Canon.splitArtists("Miles Davis, John Coltrane"))
        assertEquals(listOf("Vincent Peirani", "Emile Parisien"),
            Canon.splitArtists("Vincent Peirani & Emile Parisien"))
        assertEquals(listOf("Terence Blanchard", "the E-Collective"),
            Canon.splitArtists("Terence Blanchard featuring the E-Collective"))
        assertEquals(emptyList<String>(), Canon.splitArtists("/"))
    }

    @Test fun `similarity is 1 for equal and 0 for empty`() {
        assertEquals(1.0, Canon.similarity("abc", "abc"), 0.0001)
        assertEquals(0.0, Canon.similarity("", "abc"), 0.0001)
        assertTrue(Canon.similarity("rumours", "rumors") > 0.5)
        assertTrue(Canon.similarity("rumours", "nevermind") < 0.2)
    }

    @Test fun `overlap measures the smaller set`() {
        assertEquals(1.0, Canon.overlap(setOf("a"), setOf("a", "b")), 0.0001)
        assertEquals(0.0, Canon.overlap(setOf("a"), setOf("b")), 0.0001)
        assertEquals(0.0, Canon.overlap(emptySet(), setOf("a")), 0.0001)
    }
}
