package com.music.sonariq.data.lyrics

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricWordInterpolationTest {

    private fun line(timeMs: Long, text: String, sungUntilMs: Long? = null) =
        LyricLine(timeMs = timeMs, text = text, sungUntilMs = sungUntilMs)

    @Test
    fun `words split the window by character count`() {
        val out = listOf(line(1000, "hello world"), line(4000, "next")).withInterpolatedWordTimings()
        val words = out[0].words
        assertEquals(2, words.size)
        assertEquals(1000L, words[0].startMs)
        assertEquals(2500L, words[0].endMs)
        assertEquals(2500L, words[1].startMs)
        assertEquals(4000L, words[1].endMs)
        assertEquals("hello", words[0].text)
        assertEquals("world", words[1].text)
        assertTrue(out[0].wordsEstimated)
    }

    @Test
    fun `longer words get more time`() {
        val out = listOf(line(1000, "a bcdef"), line(2200, "x")).withInterpolatedWordTimings()
        val words = out[0].words
        // 1 of 6 chars -> 200 of 1200 ms; 5 of 6 -> the remaining 1000.
        assertEquals(1000L, words[0].startMs)
        assertEquals(1200L, words[0].endMs)
        assertEquals(1200L, words[1].startMs)
        assertEquals(2200L, words[1].endMs)
    }

    @Test
    fun `word boundaries are contiguous and the last word lands on the window end`() {
        val out = listOf(
            line(1000, "one two three four"),
            line(9000, "end"),
        ).withInterpolatedWordTimings()
        val words = out[0].words
        assertEquals(4, words.size)
        for (i in 0 until words.size - 1) {
            assertEquals(words[i].endMs, words[i + 1].startMs, "gap between word $i and ${i + 1}")
        }
        assertEquals(9000L, words.last().endMs)
    }

    @Test
    fun `single word takes the whole window`() {
        val out = listOf(line(2000, "yeah"), line(5000, "x")).withInterpolatedWordTimings()
        val words = out[0].words
        assertEquals(1, words.size)
        assertEquals(2000L, words[0].startMs)
        assertEquals(5000L, words[0].endMs)
        assertTrue(out[0].wordsEstimated)
    }

    @Test
    fun `stated line end beats the next line stamp`() {
        val out = listOf(
            line(1000, "short line", sungUntilMs = 1800),
            line(9000, "later"),
        ).withInterpolatedWordTimings()
        assertEquals(1800L, out[0].words.last().endMs)
    }

    @Test
    fun `blank lines are left alone`() {
        val out = listOf(line(1000, ""), line(2000, "   "), line(3000, "sung"))
            .withInterpolatedWordTimings()
        assertTrue(out[0].words.isEmpty())
        assertTrue(out[1].words.isEmpty())
        assertFalse(out[0].wordsEstimated)
        assertFalse(out[1].wordsEstimated)
        assertTrue(out[2].words.isNotEmpty())
    }

    @Test
    fun `lines that already have words are untouched`() {
        val measured = LyricWord(1000, 1500, "real")
        val before = LyricLine(timeMs = 1000, text = "real words", words = listOf(measured))
        val out = listOf(before, line(4000, "plain line")).withInterpolatedWordTimings()
        assertEquals(listOf(measured), out[0].words)
        assertFalse(out[0].wordsEstimated)
        assertTrue(out[1].wordsEstimated)
    }

    @Test
    fun `plain unstamped lyrics are untouched`() {
        val lines = listOf(LyricLine(0, "verse one"), LyricLine(0, "verse two"))
        val out = lines.withInterpolatedWordTimings()
        assertTrue(out.all { it.words.isEmpty() && !it.wordsEstimated })
    }

    @Test
    fun `section headers are not sung and not interpolated`() {
        val out = listOf(line(1000, "[Chorus]"), line(2000, "real line"), line(5000, "x"))
            .withInterpolatedWordTimings()
        assertTrue(out[0].words.isEmpty())
        assertFalse(out[0].wordsEstimated)
        assertTrue(out[1].words.isNotEmpty())
    }

    @Test
    fun `last line without an end learns the pace from its siblings`() {
        // "aa bb" (5 chars incl. space) over 4000 ms -> 800 ms/char.
        // "cccc" (4 chars) -> 3200 ms window.
        val out = listOf(line(1000, "aa bb"), line(5000, "cccc")).withInterpolatedWordTimings()
        val words = out[1].words
        assertEquals(1, words.size)
        assertEquals(5000L, words[0].startMs)
        assertEquals(8200L, words[0].endMs)
        assertTrue(out[1].wordsEstimated)
    }

    @Test
    fun `background vocals are interpolated inside their own stamps`() {
        val bg = LyricLine(timeMs = 1500, text = "ooh yeah")
        val out = listOf(
            LyricLine(timeMs = 1000, text = "lead line here", background = bg),
            line(8000, "next"),
        ).withInterpolatedWordTimings()
        val bgWords = out[0].background!!.words
        assertEquals(2, bgWords.size)
        assertEquals(1500L, bgWords[0].startMs)
        assertTrue(out[0].background!!.wordsEstimated)
        assertTrue(out[0].wordsEstimated)
    }

    @Test
    fun `all estimated lines report estimated word sync`() {
        val lines = listOf(line(1000, "aa bb"), line(4000, "cc"))
            .withInterpolatedWordTimings()
        assertEquals(LyricTiming.WORD_SYNCED_ESTIMATED, lines.lyricTiming())
    }

    @Test
    fun `any measured word outranks estimated ones`() {
        val measured = LyricLine(
            timeMs = 1000, text = "real",
            words = listOf(LyricWord(1000, 1500, "real")),
        )
        val lines = listOf(measured, line(4000, "plain line"), line(7000, "x"))
            .withInterpolatedWordTimings()
        assertEquals(LyricTiming.WORD_SYNCED, lines.lyricTiming())
        assertFalse(lines[0].wordsEstimated)
        assertTrue(lines[1].wordsEstimated)
    }

    @Test
    fun `line synced and plain buckets are unchanged`() {
        assertEquals(
            LyricTiming.LINE_SYNCED,
            listOf(line(1000, ""), line(2000, "")).lyricTiming(),
        )
        assertEquals(
            LyricTiming.PLAIN,
            listOf(LyricLine(0, "a"), LyricLine(0, "b")).lyricTiming(),
        )
    }

    @Test
    fun `estimated flag survives a cache round trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val line = line(1000, "aa bb").copy(
            words = listOf(LyricWord(1000, 2000, "aa")),
            wordsEstimated = true,
        )
        val back = json.decodeFromString<LyricLine>(json.encodeToString(line))
        assertTrue(back.wordsEstimated)
        assertEquals(line.words, back.words)
    }

    @Test
    fun `old cache payloads without the flag still decode`() {
        val json = Json { ignoreUnknownKeys = true }
        val back = json.decodeFromString<LyricLine>(
            """{"timeMs":1000,"text":"hello"}""",
        )
        assertFalse(back.wordsEstimated)
        assertEquals("hello", back.text)
    }

    @Test
    fun `line before a gap is capped at the learned pace`() {
        // Dense verse teaches ~100 ms/char; the third line's 14 s window to
        // the next stamp is capped instead of stretching its words across
        // the instrumental break.
        val verse = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // 30 chars
        val out = listOf(
            line(1000, verse),
            line(4000, verse.replace('a', 'b')),
            line(7000, verse.replace('a', 'c')),
            line(21000, "next"),
        ).withInterpolatedWordTimings()
        val words = out[2].words
        // pace = 100 ms/char -> cap = 30 * 100 * 1.5 = 4500 ms.
        assertEquals(7000L + 4500L, words.last().endMs)
        assertTrue(words.last().endMs < 21000L, "words must not reach the next stamp")
        for (i in 0 until words.size - 1) {
            assertEquals(words[i].endMs, words[i + 1].startMs, "gap between word $i and ${i + 1}")
        }
    }

    @Test
    fun `stated end equal to the next stamp is treated as a stamp`() {
        // Some providers copy the next stamp into sungUntilMs; that is an
        // echo, not a measured duration, so the cap still applies.
        val verse = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // 30 chars
        val out = listOf(
            line(1000, verse),
            line(4000, verse.replace('a', 'b')),
            line(7000, verse.replace('a', 'c'), sungUntilMs = 21000),
            line(21000, "next"),
        ).withInterpolatedWordTimings()
        assertEquals(7000L + 4500L, out[2].words.last().endMs)
        assertTrue(out[2].wordsEstimated)
    }

    @Test
    fun `genuine stated end is never capped`() {
        val verse = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // 30 chars
        val out = listOf(
            line(1000, verse),
            line(4000, "held note here", sungUntilMs = 12000),
            line(20000, "next"),
        ).withInterpolatedWordTimings()
        assertEquals(12000L, out[1].words.last().endMs)
    }

    @Test
    fun `median pace ignores a gap-stretched outlier`() {
        // Two dense lines at 100 ms/char and one stretched across a gap: the
        // median stays at the verse pace, so the window-less last line learns
        // the singing speed rather than the silence.
        val verse = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // 30 chars
        val out = listOf(
            line(1000, verse),
            line(4000, verse.replace('a', 'b')),
            line(7000, verse.replace('a', 'c')),
            line(21000, "dddd"),
        ).withInterpolatedWordTimings()
        // pace = 100 ms/char -> "dddd" wants 400 ms, floored at the minimum.
        assertEquals(21000L + 500L, out[3].words.last().endMs)
        assertTrue(out[3].wordsEstimated)
    }

    @Test
    fun `sparse song windows hit the absolute backstop`() {
        // Every line 30 s apart: the median pace is inflated, so the absolute
        // cap keeps any single estimated line-sing within bounds.
        val verse = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // 30 chars
        val out = listOf(
            line(1000, verse),
            line(31000, verse.replace('a', 'b')),
            line(61000, "next"),
        ).withInterpolatedWordTimings()
        assertEquals(1000L + 8000L, out[0].words.last().endMs)
    }
}
