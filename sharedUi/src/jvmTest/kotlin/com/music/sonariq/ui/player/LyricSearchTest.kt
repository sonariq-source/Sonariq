package com.music.sonariq.ui.player

import com.music.sonariq.data.lyrics.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricSearchTest {

    private fun line(text: String, timeMs: Long = 0L) = LyricLine(timeMs = timeMs, text = text)

    private val lines = listOf(
        line("Hello from the other side", 1_000L),
        line("I must have called a thousand times", 5_000L),
        line("", 9_000L), // instrumental gap: stamped, but no words
        line("HELLO again in caps", 13_000L),
        line("Never mind, I'll find someone like you", 0L), // plain lyric: no timing
    )

    // ---- searchLyricLines ----

    @Test
    fun `search is case-insensitive`() {
        assertEquals(listOf(0, 3), searchLyricLines(lines, "hello"))
        assertEquals(listOf(0, 3), searchLyricLines(lines, "HELLO"))
        assertEquals(listOf(0, 3), searchLyricLines(lines, "HeLlO"))
    }

    @Test
    fun `search matches substrings and returns line indices`() {
        assertEquals(listOf(1), searchLyricLines(lines, "thousand"))
        assertEquals(listOf(4), searchLyricLines(lines, "someone like you"))
    }

    @Test
    fun `blank query matches nothing`() {
        assertTrue(searchLyricLines(lines, "").isEmpty())
        assertTrue(searchLyricLines(lines, "   ").isEmpty())
    }

    @Test
    fun `no matches returns empty list`() {
        assertTrue(searchLyricLines(lines, "xyzzy").isEmpty())
    }

    @Test
    fun `empty lines match nothing`() {
        assertTrue(searchLyricLines(emptyList(), "hello").isEmpty())
    }

    // ---- nextSearchMatch / prevSearchMatch ----

    @Test
    fun `next steps forward and wraps to the first match`() {
        val matches = listOf(0, 3, 4)
        assertEquals(1, nextSearchMatch(matches, 0))
        assertEquals(2, nextSearchMatch(matches, 1))
        assertEquals(0, nextSearchMatch(matches, 2))
    }

    @Test
    fun `prev steps backward and wraps to the last match`() {
        val matches = listOf(0, 3, 4)
        assertEquals(2, prevSearchMatch(matches, 0))
        assertEquals(0, prevSearchMatch(matches, 1))
        assertEquals(1, prevSearchMatch(matches, 2))
    }

    @Test
    fun `a single match steps to itself`() {
        val matches = listOf(2)
        assertEquals(0, nextSearchMatch(matches, 0))
        assertEquals(0, prevSearchMatch(matches, 0))
    }

    @Test
    fun `no matches yields minus one`() {
        assertEquals(-1, nextSearchMatch(emptyList(), 0))
        assertEquals(-1, prevSearchMatch(emptyList(), 0))
    }

    @Test
    fun `stepping from no selection lands on an end`() {
        val matches = listOf(0, 3, 4)
        assertEquals(0, nextSearchMatch(matches, -1))
        assertEquals(2, prevSearchMatch(matches, -1))
    }

    // ---- lyricLineSeekMs ----

    @Test
    fun `a timed line seeks to its stamp`() {
        assertEquals(1_000L, lyricLineSeekMs(lines[0]))
        assertEquals(13_000L, lyricLineSeekMs(lines[3]))
    }

    @Test
    fun `the first line seeks to its stamp, not to zero`() {
        assertEquals(1_000L, lyricLineSeekMs(lines.first()))
    }

    @Test
    fun `a stamped instrumental gap is seekable`() {
        assertEquals(9_000L, lyricLineSeekMs(lines[2]))
    }

    @Test
    fun `a line without timing is not seekable`() {
        assertNull(lyricLineSeekMs(lines[4]))
        assertNull(lyricLineSeekMs(line("untimed", 0L)))
    }

    @Test
    fun `a negative stamp is not seekable`() {
        assertNull(lyricLineSeekMs(line("odd", -50L)))
    }
}
