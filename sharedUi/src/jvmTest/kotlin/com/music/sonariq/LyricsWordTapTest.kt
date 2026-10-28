package com.music.sonariq

import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.lyrics.LyricWord
import com.music.sonariq.ui.player.wordAtCharOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsWordTapTest {
    private val line = LyricLine(
        timeMs = 10_000L,
        text = "hello brave new world",
        words = listOf(
            LyricWord(startMs = 10_000L, endMs = 10_400L, text = "hello"),
            LyricWord(startMs = 10_500L, endMs = 10_900L, text = "brave"),
            LyricWord(startMs = 11_000L, endMs = 11_300L, text = "new"),
            LyricWord(startMs = 11_400L, endMs = 11_900L, text = "world"),
        ),
    )

    @Test
    fun `tap inside a word picks that word`() {
        assertEquals(10_000L, wordAtCharOffset(line, 0)?.startMs)
        assertEquals(10_000L, wordAtCharOffset(line, 4)?.startMs)
        assertEquals(10_500L, wordAtCharOffset(line, 8)?.startMs)
        assertEquals(11_400L, wordAtCharOffset(line, 20)?.startMs)
    }

    @Test
    fun `tap on the gap between words picks the next word`() {
        // Character 5 is the space after "hello"; 11 is the space after "brave".
        assertEquals(10_500L, wordAtCharOffset(line, 5)?.startMs)
        assertEquals(11_000L, wordAtCharOffset(line, 11)?.startMs)
    }

    @Test
    fun `tap past the end picks the last word`() {
        assertEquals(11_400L, wordAtCharOffset(line, 21)?.startMs)
        assertEquals(11_400L, wordAtCharOffset(line, 500)?.startMs)
    }

    @Test
    fun `repeated words resolve to their own occurrence`() {
        val repeated = LyricLine(
            timeMs = 0L,
            text = "na na na",
            words = listOf(
                LyricWord(startMs = 0L, endMs = 300L, text = "na"),
                LyricWord(startMs = 400L, endMs = 700L, text = "na"),
                LyricWord(startMs = 800L, endMs = 1_100L, text = "na"),
            ),
        )
        assertEquals(0L, wordAtCharOffset(repeated, 1)?.startMs)
        assertEquals(400L, wordAtCharOffset(repeated, 4)?.startMs)
        assertEquals(800L, wordAtCharOffset(repeated, 7)?.startMs)
    }

    @Test
    fun `line without word timings answers nothing`() {
        val plain = LyricLine(timeMs = 1_000L, text = "just a line")
        assertNull(wordAtCharOffset(plain, 3))
    }

    @Test
    fun `estimated words never answer`() {
        val estimated = line.copy(wordsEstimated = true)
        assertNull(wordAtCharOffset(estimated, 2))
    }

    @Test
    fun `ai measured words answer like provider words`() {
        val measured = line.copy(wordsAiMeasured = true)
        assertEquals(10_500L, wordAtCharOffset(measured, 8)?.startMs)
    }
}
