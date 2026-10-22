package com.music.sonariq.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [LyricLine.revealedChars]: the highlight must pause (hold) between
 * words rather than creeping forward through the silence — Apple Music rests
 * on the finished word, so each word lands distinctly.
 */
class LyricLineRevealedCharsTest {

    private fun line(vararg words: LyricWord): LyricLine {
        val text = words.joinToString(" ") { it.text }
        return LyricLine(timeMs = 0L, text = text, words = words.toList())
    }

    @Test
    fun `highlight holds at word end through the pause before next word`() {
        // "ah ah ah" with gaps: word1 0-500, gap, word2 1000-1500, gap, word3 2000-2500.
        val l = line(
            LyricWord(0L, 500L, "ah"),
            LyricWord(1000L, 1500L, "ah"),
            LyricWord(2000L, 2500L, "ah"),
        )
        // Mid first word: partial reveal.
        val midFirst = l.revealedChars(250L)
        assertTrue(midFirst > 0f && midFirst < 2f, "mid-word should be partial, was $midFirst")
        // In the pause after the first word: highlight holds at end of "ah" (2 chars),
        // it must NOT creep forward toward the next word.
        assertEquals(2f, l.revealedChars(750L), "pause should hold at word end")
        assertEquals(2f, l.revealedChars(999L), "pause should hold at word end")
        // Second word starts: highlight moves to it.
        assertTrue(l.revealedChars(1001L) > 2f, "next word should start revealing")
        // Pause before third word holds at end of second word ("ah ah" = 5 chars).
        assertEquals(5f, l.revealedChars(1750L), "pause should hold at second word end")
    }

    @Test
    fun `repeated words each light up individually`() {
        val l = line(
            LyricWord(0L, 300L, "ah"),
            LyricWord(600L, 900L, "ah"),
            LyricWord(1200L, 1500L, "ah"),
        )
        // Before anything: nothing revealed.
        assertEquals(0f, l.revealedChars(-1L))
        // After first word done, in pause: exactly "ah" revealed.
        assertEquals(2f, l.revealedChars(450L))
        // After second word done, in pause: "ah ah" revealed.
        assertEquals(5f, l.revealedChars(1050L))
        // After all done: full line.
        assertEquals(8f, l.revealedChars(1600L))
    }

    @Test
    fun `syllable timings pace the reveal within a word`() {
        // "enough" split the way a TTML provider splits it: "e" sung over
        // 31839-31996, "nough" held over 31996-32529. The reveal must follow
        // the syllables, not a linear wipe across the whole word.
        val l = LyricLine(
            timeMs = 31_839,
            text = "enough",
            words = listOf(
                LyricWord(
                    31_839, 32_529, "enough",
                    listOf(
                        LyricSyllable(31_839, 31_996, 0, 1),
                        LyricSyllable(31_996, 32_529, 1, 6),
                    ),
                ),
            ),
        )
        // Mid first syllable: half of "e" is sung. A whole-word linear wipe
        // would already claim ~0.68 chars here, so this pins the syllable pace.
        assertEquals(0.5f, l.revealedChars(31_917), 0.01f)
        // Exactly on the syllable boundary: exactly "e" revealed.
        assertEquals(1f, l.revealedChars(31_996))
        // Mid second syllable: "e" plus half of "nough".
        assertEquals(3.5f, l.revealedChars(32_262), 0.01f)
        // The sweep crosses one span per syllable.
        assertEquals(listOf(0..0, 1..5), l.sweepSpans)
    }

    @Test
    fun `line without words lights whole at its stamp`() {
        val l = LyricLine(timeMs = 1000L, text = "plain line")
        assertEquals(0f, l.revealedChars(999L))
        assertEquals(10f, l.revealedChars(1000L))
    }
}
