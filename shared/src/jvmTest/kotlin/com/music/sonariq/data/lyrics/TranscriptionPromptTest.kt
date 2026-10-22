package com.music.sonariq.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [buildTranscriptionPrompt]: the lyric text handed to the transcription
 * endpoint as context — the lines in order, blanks skipped, whole lines
 * only inside the budget, and null where there is nothing worth sending.
 */
class TranscriptionPromptTest {

    private fun lines(vararg texts: String): List<LyricLine> =
        texts.mapIndexed { index, text -> LyricLine(timeMs = (index + 1) * 1_000L, text = text) }

    @Test
    fun theBudgetIsTheBriefedThousandAndTwentyFour() {
        assertEquals(1024, TRANSCRIPTION_PROMPT_MAX_CHARS)
    }

    @Test
    fun linesAreJoinedInOrderWithNewlines() {
        val prompt = buildTranscriptionPrompt(
            lines("first line sung", "second line sung", "third line sung"),
        )
        assertEquals("first line sung\nsecond line sung\nthird line sung", prompt)
    }

    @Test
    fun aNormalShortSongReturnsItsFullTextUnchanged() {
        val texts = listOf(
            "Golden hour light on your face",
            "We were never in a hurry then",
            "And the chorus comes around again",
        )
        val prompt = buildTranscriptionPrompt(
            texts.mapIndexed { index, text -> LyricLine(timeMs = (index + 1) * 1_000L, text = text) },
        )
        // Under the budget nothing is dropped, trimmed or reordered.
        assertEquals(texts.joinToString("\n"), prompt)
    }

    @Test
    fun blankLinesAreSkipped() {
        val prompt = buildTranscriptionPrompt(
            lines("", "first real line", "   ", "\t", "second real line", ""),
        )
        // Instrumental stretches and whitespace-only lines carry no words
        // worth steering with, so they leave no gaps in the prompt either.
        assertEquals("first real line\nsecond real line", prompt)
    }

    @Test
    fun backgroundVocalsAreNotFoldedIntoThePrompt() {
        val lead = LyricLine(
            timeMs = 1_000L,
            text = "the lead line being sung",
            background = LyricLine(timeMs = 1_500L, text = "(ooh ooh)"),
        )
        // Only the lead line's text: the answering vocal is not the line.
        assertEquals("the lead line being sung", buildTranscriptionPrompt(listOf(lead)))
    }

    @Test
    fun truncationKeepsWholeLinesWithinTheBudgetAndDropsLaterLines() {
        val first = "a".repeat(600)
        val second = "b".repeat(423)
        val third = "c".repeat(10)
        val prompt = buildTranscriptionPrompt(lines(first, second, third))
        // 600 + newline + 423 lands exactly on the budget; the third line
        // would cross it, so it is dropped whole rather than cut short.
        assertEquals("$first\n$second", prompt)
        assertEquals(TRANSCRIPTION_PROMPT_MAX_CHARS, prompt?.length)
    }

    @Test
    fun aLineThatWouldCrossTheBudgetIsDroppedWhole() {
        val first = "a".repeat(600)
        val second = "b".repeat(500)
        val prompt = buildTranscriptionPrompt(lines(first, second))
        // 600 + newline + 500 is over the budget: the prompt is the first
        // line alone, not a first line plus a fragment of the second.
        assertEquals(first, prompt)
        assertTrue((prompt?.length ?: 0) <= TRANSCRIPTION_PROMPT_MAX_CHARS)
    }

    @Test
    fun aSingleOverLongFirstLineIsHardTruncatedToExactlyTheBudget() {
        val long = "x".repeat(TRANSCRIPTION_PROMPT_MAX_CHARS * 2)
        val prompt = buildTranscriptionPrompt(lines(long))
        // The one exception to whole lines only: with nothing else to
        // send, the line is cut to the budget rather than sending nothing.
        assertEquals(TRANSCRIPTION_PROMPT_MAX_CHARS, prompt?.length)
        assertEquals("x".repeat(TRANSCRIPTION_PROMPT_MAX_CHARS), prompt)
    }

    @Test
    fun anOverLongFirstNonBlankLineIsHardTruncatedEvenAfterBlanks() {
        val long = "y".repeat(TRANSCRIPTION_PROMPT_MAX_CHARS + 100)
        val prompt = buildTranscriptionPrompt(lines("", "   ", long, "a later line"))
        assertEquals("y".repeat(TRANSCRIPTION_PROMPT_MAX_CHARS), prompt)
    }

    @Test
    fun emptyInputReturnsNull() {
        assertNull(buildTranscriptionPrompt(emptyList()))
    }

    @Test
    fun allBlankInputReturnsNull() {
        // No non-blank text at all means no prompt part is worth sending.
        assertNull(buildTranscriptionPrompt(lines("", "   ", "\n", "\t")))
    }
}
