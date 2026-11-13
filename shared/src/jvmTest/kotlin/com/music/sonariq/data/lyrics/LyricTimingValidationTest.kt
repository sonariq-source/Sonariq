package com.music.sonariq.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [withValidWordTimings]: the rules a word timing must satisfy to be
 * shown at all — no negative stamps, no backwards spans, no words out
 * of sung order, nothing starting past the track's end — and the
 * degradation when one breaks them: the line keeps its text and loses
 * its timings, never the other way round.
 */
class LyricTimingValidationTest {

    private fun word(text: String, startMs: Long, endMs: Long) =
        LyricWord(startMs = startMs, endMs = endMs, text = text)

    @Test
    fun validTimingsPassThroughUnchanged() {
        val lines = listOf(
            LyricLine(
                timeMs = 10_000,
                text = "hello world",
                words = listOf(word("hello", 10_000, 10_800), word("world", 10_900, 11_600)),
                sungUntilMs = 11_600,
            ),
            LyricLine(timeMs = 20_000, text = "next line"),
        )
        assertSame(lines, lines.withValidWordTimings(180_000))
        assertSame(lines, lines.withValidWordTimings())
    }

    @Test
    fun aNegativeWordStartDropsTheLinesWords() {
        val lines = listOf(
            LyricLine(
                timeMs = 0,
                text = "hello world",
                words = listOf(word("hello", -500, 300), word("world", 400, 900)),
            ),
        )
        val cleaned = lines.withValidWordTimings(180_000)
        assertEquals("hello world", cleaned.single().text)
        assertTrue(cleaned.single().words.isEmpty())
    }

    @Test
    fun outOfOrderWordsAreDropped() {
        val lines = listOf(
            LyricLine(
                timeMs = 5_000,
                text = "world hello",
                words = listOf(word("world", 5_000, 5_800), word("hello", 3_000, 3_800)),
            ),
        )
        assertTrue(lines.withValidWordTimings(180_000).single().words.isEmpty())
    }

    @Test
    fun aWordEndingWhereItStartsIsDropped() {
        val lines = listOf(
            LyricLine(
                timeMs = 5_000,
                text = "hello",
                words = listOf(word("hello", 5_000, 5_000)),
            ),
        )
        assertTrue(lines.withValidWordTimings(180_000).single().words.isEmpty())
    }

    @Test
    fun aWordStartingPastTheTracksEndIsDropped() {
        val lines = listOf(
            LyricLine(
                timeMs = 12_000,
                text = "too late",
                words = listOf(word("too", 12_000, 12_500), word("late", 12_600, 13_000)),
            ),
        )
        assertTrue(lines.withValidWordTimings(10_000).single().words.isEmpty())
        // Without a known duration there is no end to start past.
        assertEquals(2, lines.withValidWordTimings().single().words.size)
    }

    @Test
    fun aWordRunningPastTheTracksEndIsClampedNotDropped() {
        val lines = listOf(
            LyricLine(
                timeMs = 8_000,
                text = "held note",
                words = listOf(word("held", 8_000, 8_700), word("note", 8_800, 12_000)),
            ),
        )
        val cleaned = lines.withValidWordTimings(10_000).single()
        assertEquals(2, cleaned.words.size)
        assertEquals(10_000, cleaned.words.last().endMs)
    }

    @Test
    fun aNegativeLineStampIsHeldAtZero() {
        val lines = listOf(LyricLine(timeMs = -1_000, text = "early"))
        assertEquals(0, lines.withValidWordTimings(180_000).single().timeMs)
    }

    @Test
    fun aStatedEndIsHeldInsideTheTrack() {
        val lines = listOf(
            LyricLine(timeMs = 170_000, text = "final", sungUntilMs = 200_000),
        )
        assertEquals(180_000, lines.withValidWordTimings(180_000).single().sungUntilMs)
    }

    @Test
    fun aStatedEndBeforeTheLineStartsIsDropped() {
        val lines = listOf(
            LyricLine(timeMs = 50_000, text = "broken", sungUntilMs = 40_000),
        )
        assertNull(lines.withValidWordTimings(180_000).single().sungUntilMs)
    }

    @Test
    fun aSyllableOutsideItsWordCostsTheSplitNotTheWord() {
        val lines = listOf(
            LyricLine(
                timeMs = 1_000,
                text = "hello",
                words = listOf(
                    LyricWord(
                        startMs = 1_000,
                        endMs = 2_000,
                        text = "hello",
                        syllables = listOf(
                            LyricSyllable(startMs = 1_000, endMs = 1_500, startChar = 0, endChar = 2),
                            LyricSyllable(startMs = 1_500, endMs = 2_500, startChar = 2, endChar = 5),
                        ),
                    ),
                ),
            ),
        )
        val cleaned = lines.withValidWordTimings(180_000).single()
        assertEquals(1, cleaned.words.size)
        assertTrue(cleaned.words.single().syllables.isEmpty())
    }

    @Test
    fun backgroundWordsAreValidatedIndependently() {
        val background = LyricLine(
            timeMs = 5_000,
            text = "echo",
            words = listOf(word("echo", 5_800, 5_000)),
        )
        val lines = listOf(
            LyricLine(
                timeMs = 5_000,
                text = "lead line",
                words = listOf(word("lead", 5_000, 5_600), word("line", 5_700, 6_400)),
                background = background,
            ),
        )
        val cleaned = lines.withValidWordTimings(180_000).single()
        assertEquals(2, cleaned.words.size)
        assertTrue(cleaned.background!!.words.isEmpty())
        assertEquals("echo", cleaned.background!!.text)
    }

    @Test
    fun droppingWordsClearsTheMeasuredFlags() {
        val lines = listOf(
            LyricLine(
                timeMs = 5_000,
                text = "hello",
                words = listOf(word("hello", -1, 500)),
                wordsAiMeasured = true,
            ),
        )
        val cleaned = lines.withValidWordTimings(180_000).single()
        assertTrue(cleaned.words.isEmpty())
        assertTrue(!cleaned.wordsAiMeasured && !cleaned.wordsEstimated)
        assertEquals(LyricTiming.LINE_SYNCED, listOf(cleaned).lyricTiming())
    }

    @Test
    fun aRepairKeepsTheTranscribedMark() {
        // The line start is negative and gets clamped, so the line is
        // rebuilt by the sanitizer — and the rebuild must carry the
        // mark, or the surviving timings would silently re-price as a
        // provider's own word sync.
        val lines = listOf(
            LyricLine(
                timeMs = -500,
                text = "hello world",
                words = listOf(word("hello", 100, 600), word("world", 700, 1_200)),
                sungUntilMs = 1_200,
                wordsAiTranscribed = true,
            ),
        )
        val cleaned = lines.withValidWordTimings(180_000).single()
        assertEquals(0, cleaned.timeMs)
        assertEquals(2, cleaned.words.size)
        assertTrue(cleaned.wordsAiTranscribed)
        assertEquals(
            LyricTiming.WORD_SYNCED_AI_TRANSCRIBED,
            listOf(cleaned).lyricTiming(),
        )
    }

    @Test
    fun droppingWordsClearsTheTranscribedMark() {
        val lines = listOf(
            LyricLine(
                timeMs = 5_000,
                text = "hello",
                words = listOf(word("hello", -1, 500)),
                wordsAiTranscribed = true,
            ),
        )
        val cleaned = lines.withValidWordTimings(180_000).single()
        assertTrue(cleaned.words.isEmpty())
        assertTrue(!cleaned.wordsAiTranscribed)
    }
}
