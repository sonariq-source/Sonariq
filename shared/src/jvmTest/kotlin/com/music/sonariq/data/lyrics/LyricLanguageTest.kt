package com.music.sonariq.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LyricLanguageTest {

    private fun lines(vararg texts: String): List<LyricLine> =
        texts.mapIndexed { index, text -> LyricLine(timeMs = (index + 1) * 1_000L, text = text) }

    @Test
    fun tagalogLyricsAreDetected() {
        // ang, ko, ay, sa, iyo: five Tagalog stopword hits, no English.
        val detected = LyricLanguageDetector.detect(
            lines("Ang puso ko ay para sa iyo", "Mahal kita nang lubos"),
        )
        assertEquals("tl", detected)
    }

    @Test
    fun englishLyricsAreDetected() {
        // the, time, has, and, you, will, go: seven English hits, no Tagalog.
        val detected = LyricLanguageDetector.detect(
            lines("The time has come and you will go"),
        )
        assertEquals("en", detected)
    }

    @Test
    fun caseAndPunctuationDoNotHideStopwords() {
        // The same normalisation the aligner uses: case and punctuation
        // are stripped before counting, so these still read as ang/ko/sa.
        val detected = LyricLanguageDetector.detect(lines("ANG, KO, SA!"))
        assertEquals("tl", detected)
    }

    @Test
    fun tooFewHitsReturnsNull() {
        // Two Tagalog hits (ang, ng) are below the three-hit bar.
        assertNull(LyricLanguageDetector.detect(lines("Ang ganda ng umaga")))
        // No stopwords at all on either list.
        assertNull(LyricLanguageDetector.detect(lines("Hello beautiful world tonight")))
        assertNull(LyricLanguageDetector.detect(emptyList()))
    }

    @Test
    fun tiedHitsReturnNull() {
        // Three against three: enough hits, but no strict winner.
        assertNull(LyricLanguageDetector.detect(lines("ang ko sa the and of")))
    }

    @Test
    fun strictWinnerWinsEvenWithOtherLanguagePresent() {
        // English 3 (the, and, you) against Tagalog 2 (ang, ko).
        assertEquals("en", LyricLanguageDetector.detect(lines("the and you ang ko")))
        // Tagalog 4 (ang, ko, sa, ng) against English 1 (the).
        assertEquals("tl", LyricLanguageDetector.detect(lines("ang ko sa ng the")))
    }

    @Test
    fun hitsCountWithRepetitionAcrossLines() {
        // "ko" sung on three lines is three hits, not one.
        val detected = LyricLanguageDetector.detect(
            lines("Puso ko", "Buhay ko", "Lahat ko"),
        )
        assertEquals("tl", detected)
    }
}
