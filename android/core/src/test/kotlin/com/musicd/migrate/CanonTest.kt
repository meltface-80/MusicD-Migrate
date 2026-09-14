package com.musicd.migrate

import org.junit.Assert.assertEquals
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

    @Test fun `artistSet splits a string and accepts a list`() {
        assertEquals(listOf("calvin harris", "rihanna"),
            Canon.artistSet("Calvin Harris feat. Rihanna").toList())
        assertEquals(listOf("calvin harris", "rihanna"),
            Canon.artistSet(listOf("Calvin Harris", "Rihanna")).toList())
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
