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
    fun `line without words lights whole at its stamp`() {
        val l = LyricLine(timeMs = 1000L, text = "plain line")
        assertEquals(0f, l.revealedChars(999L))
        assertEquals(10f, l.revealedChars(1000L))
    }
}
