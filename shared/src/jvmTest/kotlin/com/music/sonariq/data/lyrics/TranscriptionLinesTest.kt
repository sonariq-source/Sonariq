package com.music.sonariq.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TranscriptionLines]: the lines a transcription's hearing becomes.
 * The one promise every test here leans on: a word's span in the line
 * is the span the transcription measured — never moved, stretched or
 * averaged — and a hearing that is too thin or too clumped to be the
 * song makes no lines at all.
 */
class TranscriptionLinesTest {

    private fun word(text: String, startMs: Long, endMs: Long) =
        TranscribedWord(text, startMs, endMs)

    private fun segment(startMs: Long, endMs: Long) =
        TranscribedSegment("segment", startMs, endMs)

    /** Ten words sung evenly from 1s to 11s: a hearing that passes the gates. */
    private fun sungWords(): List<TranscribedWord> =
        (0 until 10).map { i ->
            word("w$i", 1_000L + i * 1_000L, 1_500L + i * 1_000L)
        }

    @Test
    fun segmentsGroupWordsIntoLinesAndTimingsArePreservedExactly() {
        val words = listOf(
            word("hello", 1_000, 1_400),
            word("there", 1_500, 1_900),
            word("wide", 2_000, 2_400),
            word("world", 2_500, 3_000),
            word("sing", 6_000, 6_400),
            word("it", 6_500, 6_700),
            word("again", 6_800, 7_300),
            word("now", 7_400, 7_900),
        )
        val segments = listOf(segment(0, 4_000), segment(5_000, 9_000))
        // Span 1s..7.9s = 6.9s against a 20s track: past the fifth.
        val lines = TranscriptionLines.build(words, segments, durationMs = 20_000)

        assertNotNull(lines)
        assertEquals(2, lines.size)
        val first = lines[0]
        assertEquals("hello there wide world", first.text)
        assertEquals(1_000, first.timeMs)
        assertEquals(3_000, first.sungUntilMs)
        // Every word keeps the exact span the transcription measured.
        assertEquals(
            listOf(1_000L to 1_400L, 1_500L to 1_900L, 2_000L to 2_400L, 2_500L to 3_000L),
            first.words.map { it.startMs to it.endMs },
        )
        assertEquals(listOf("hello", "there", "wide", "world"), first.words.map { it.text })
        assertEquals("sing it again now", lines[1].text)
        // The lines carry the transcription's mark, and price as such.
        assertTrue(lines.all { it.wordsAiTranscribed })
        assertEquals(LyricTiming.WORD_SYNCED_AI_TRANSCRIBED, lines.lyricTiming())
    }

    @Test
    fun pausesGroupWordsWhenThereAreNoSegments() {
        val words = mutableListOf<TranscribedWord>()
        // Four words, a 2.5s silence, four more: two sung lines.
        for (i in 0 until 4) words += word("a$i", 1_000L + i * 600L, 1_400L + i * 600L)
        for (i in 0 until 4) words += word("b$i", 6_000L + i * 600L, 6_400L + i * 600L)
        val lines = TranscriptionLines.build(words, emptyList(), durationMs = 20_000)
        assertNotNull(lines)
        assertEquals(2, lines.size)
        assertEquals("a0 a1 a2 a3", lines[0].text)
        assertEquals("b0 b1 b2 b3", lines[1].text)
    }

    @Test
    fun longRunsAreCappedPerLine() {
        val words = (0 until 15).map { i ->
            word("w$i", 1_000L + i * 300L, 1_200L + i * 300L)
        }
        val lines = TranscriptionLines.build(words, emptyList(), durationMs = 20_000)
        assertNotNull(lines)
        assertEquals(listOf(12, 3), lines.map { it.words.size })
    }

    @Test
    fun blockScriptWordsJoinWithoutSpaces() {
        val words = listOf(
            word("纯", 1_000, 1_300),
            word("音", 1_350, 1_600),
            word("乐", 1_650, 2_000),
            word("响", 2_100, 2_400),
            word("起", 2_500, 2_800),
            word("来", 2_900, 3_200),
            word("唱", 6_000, 6_400),
            word("吧", 6_500, 6_900),
        )
        val lines = TranscriptionLines.build(words, emptyList(), durationMs = 20_000)
        assertNotNull(lines)
        assertEquals("纯音乐响起来", lines[0].text)
    }

    @Test
    fun closingPunctuationAttachesToTheWordBeforeIt() {
        val words = mutableListOf(
            word("hello", 1_000, 1_400),
            word(",", 1_450, 1_500),
            word("world", 1_600, 2_000),
        )
        for (i in 3 until 10) {
            words += word("w$i", 500L + i * 500L, 900L + i * 500L)
        }
        val lines = TranscriptionLines.build(words, emptyList(), durationMs = 20_000)
        assertNotNull(lines)
        assertEquals(1, lines.size)
        assertTrue(lines[0].text.startsWith("hello, world"), lines[0].text)
    }

    @Test
    fun tooFewWordsMakeNoLines() {
        val words = (0 until 5).map { i ->
            word("w$i", 1_000L + i * 1_000L, 1_500L + i * 1_000L)
        }
        assertNull(TranscriptionLines.build(words, emptyList(), durationMs = 20_000))
    }

    @Test
    fun aClumpedHearingMakesNoLines() {
        // Plenty of words, all inside the first two seconds of a long
        // track: a noise burst or a mis-hearing, not the song.
        val words = (0 until 20).map { i ->
            word("w$i", 1_000L + i * 50L, 1_040L + i * 50L)
        }
        assertNull(TranscriptionLines.build(words, emptyList(), durationMs = 180_000))
        // The same clump with the length unknown fails the floor too.
        assertNull(TranscriptionLines.build(words, emptyList(), durationMs = 0L))
    }

    @Test
    fun degenerateWordsAreDroppedBeforeTheGates() {
        val words = sungWords().toMutableList()
        // Backwards span, negative start, blank text: all unusable.
        words += word("bad", 5_000, 4_000)
        words += word("bad", -100, 100)
        words += word("   ", 3_000, 3_500)
        val lines = TranscriptionLines.build(words, emptyList(), durationMs = 30_000)
        assertNotNull(lines)
        val allWords = lines.flatMap { it.words }
        assertEquals(10, allWords.size)
        assertTrue(allWords.none { it.text == "bad" || it.text.isBlank() })
        // Arrival order does not matter; start order does.
        assertEquals(allWords.map { it.startMs }.sorted(), allWords.map { it.startMs })
    }
}
