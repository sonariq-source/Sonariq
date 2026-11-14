package com.music.sonariq.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The word sweep at its exact edges: before the first word, on a word's
 * first and last millisecond, past the end of the line, and across a
 * rapid-fire line — the positions the compensated playhead must land
 * on precisely, with nothing revealed early and nothing held back late.
 */
class LyricLineWordBoundaryTest {

    // "go now": "go" over 1000-1400, "now" over 1600-2200.
    private val line = LyricLine(
        timeMs = 1_000,
        text = "go now",
        words = listOf(
            LyricWord(1_000, 1_400, "go"),
            LyricWord(1_600, 2_200, "now"),
        ),
    )

    @Test
    fun `before the first word nothing is revealed`() {
        assertEquals(0f, line.revealedChars(0L))
        assertEquals(0f, line.revealedChars(999L))
    }

    @Test
    fun `exactly at a word start the word begins`() {
        assertEquals(0f, line.revealedChars(1_000L))
        assertTrue(line.revealedChars(1_001L) > 0f)
        // The second word begins at its own first character (index 3).
        assertEquals(3f, line.revealedChars(1_600L))
    }

    @Test
    fun `exactly at a word end the word is fully revealed`() {
        assertEquals(2f, line.revealedChars(1_400L))
        assertEquals(6f, line.revealedChars(2_200L))
    }

    @Test
    fun `after the last word the whole line stays revealed`() {
        assertEquals(6f, line.revealedChars(2_201L))
        assertEquals(6f, line.revealedChars(600_000L))
    }

    @Test
    fun `a rapid line reveals monotonically and lands on every word`() {
        // Ten 90 ms words back to back — patter, where a sweep that
        // skips or stalls shows at once.
        val words = (0 until 10).map { i ->
            LyricWord(i * 100L, i * 100L + 90L, "w$i")
        }
        val rapid = LyricLine(
            timeMs = 0L,
            text = words.joinToString(" ") { it.text },
            words = words,
        )
        var previous = 0f
        var position = 0L
        while (position <= 1_000L) {
            val revealed = rapid.revealedChars(position)
            assertTrue(
                revealed >= previous,
                "sweep went back at $position: $revealed < $previous",
            )
            previous = revealed
            position += 10L
        }
        // Each word's end reveals exactly through that word.
        for (i in words.indices) {
            val endOfWord = rapid.revealedChars(words[i].endMs)
            val expected = words.take(i + 1).sumOf { it.text.length } + i
            assertEquals(expected.toFloat(), endOfWord, "word $i")
        }
        assertEquals(rapid.text.length.toFloat(), rapid.revealedChars(1_000L))
    }

    @Test
    fun `a long held word fills steadily across its whole span`() {
        val held = LyricLine(
            timeMs = 0L,
            text = "stay",
            words = listOf(LyricWord(0L, 4_000L, "stay")),
        )
        assertEquals(0f, held.revealedChars(0L))
        assertEquals(2f, held.revealedChars(2_000L), 0.01f)
        assertEquals(4f, held.revealedChars(4_000L))
    }
}
