package com.music.sonariq.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiLyricAlignerTest {

    private fun line(
        timeMs: Long,
        text: String,
        sungUntilMs: Long? = null,
        background: LyricLine? = null,
    ) = LyricLine(
        timeMs = timeMs,
        text = text,
        sungUntilMs = sungUntilMs,
        background = background,
    )

    private fun token(text: String, startMs: Long, endMs: Long) =
        TranscribedWord(text, startMs, endMs)

    // -- Normalisation -----------------------------------------------------

    @Test
    fun normalisationDropsCasePunctuationAndExtraSpace() {
        assertEquals("hello", normalizeToken("Hello,"))
        assertEquals("world", normalizeToken("  WORLD!  "))
        assertEquals("too many spaces", normalizeToken("too   many    spaces"))
        // A hyphen is punctuation: the word compares whole, as one token.
        assertEquals("wellknown", normalizeToken("well-known"))
        assertEquals("123", normalizeToken("(123)"))
    }

    @Test
    fun normalisationKeepsApostrophesInsideWordsOnly() {
        assertEquals("don't", normalizeToken("don't"))
        assertEquals("don't", normalizeToken("DON’T"))
        // Hanging off an edge, the mark is decoration and goes.
        assertEquals("tis", normalizeToken("'tis"))
        assertEquals("rock n roll", normalizeToken("rock 'n' roll"))
    }

    @Test
    fun normalisationOfPurePunctuationIsEmpty() {
        assertEquals("", normalizeToken("!!!"))
        assertEquals("", normalizeToken("—"))
        assertEquals("", normalizeToken("   "))
        assertEquals("", normalizeToken(""))
        // The engine's own handle on the same function agrees.
        assertEquals("hello", AiLyricAligner.normalizeToken("Hello!"))
    }

    // -- Exact alignment ---------------------------------------------------

    @Test
    fun exactAlignmentTransfersTokenTimes() {
        val lines = listOf(
            line(1_000, "hello brave world", sungUntilMs = 4_000),
            line(5_000, "sing it loud", sungUntilMs = 8_000),
        )
        val tokens = listOf(
            token("hello", 1_123, 1_456),
            token("brave", 1_600, 2_000),
            token("world", 2_100, 2_900),
            token("sing", 5_100, 5_500),
            token("it", 5_600, 5_800),
            token("loud", 5_900, 6_800),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        // Each word's time is its token's, exactly — the token stamps are
        // deliberately offset from the line stamps, so nothing here could
        // have been derived from the lines themselves.
        assertEquals(
            listOf(
                LyricWord(1_123, 1_456, "hello"),
                LyricWord(1_600, 2_000, "brave"),
                LyricWord(2_100, 2_900, "world"),
            ),
            out[0].words,
        )
        assertEquals(
            listOf(
                LyricWord(5_100, 5_500, "sing"),
                LyricWord(5_600, 5_800, "it"),
                LyricWord(5_900, 6_800, "loud"),
            ),
            out[1].words,
        )
        assertTrue(out.all { it.wordsAiMeasured })
        assertFalse(out.any { it.wordsEstimated })
        assertEquals(LyricTiming.WORD_SYNCED_AI_MEASURED, out.lyricTiming())
    }

    @Test
    fun nearMatchWithinOneLetterMatches() {
        val lines = listOf(
            line(1_000, "golden hour light"),
            line(5_000, "second line here"),
        )
        val tokens = listOf(
            // One letter wrong in a six-letter word: a mishearing, matched.
            token("goldin", 100, 500),
            token("hour", 600, 900),
            token("light", 1_000, 1_400),
            token("second", 2_000, 2_400),
            token("line", 2_500, 2_800),
            token("here", 2_900, 3_300),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        // The lyric keeps its own spelling; only the timing is borrowed.
        assertEquals(LyricWord(100, 500, "golden"), out[0].words[0])
        assertEquals(3, out[0].words.size)
    }

    @Test
    fun shortWordNearMatchDoesNotMatch() {
        val lines = listOf(
            line(1_000, "we go on now"),
            line(5_000, "stay with me tonight"),
        )
        val tokens = listOf(
            token("we", 100, 200),
            // One letter from "go", but at two letters that is a different
            // word, not a mishearing: "go" must stay unmatched.
            token("to", 300, 400),
            token("on", 500, 600),
            token("now", 700, 1_000),
            token("stay", 2_000, 2_300),
            token("with", 2_400, 2_700),
            token("me", 2_800, 2_900),
            token("tonight", 3_000, 3_600),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        // "go" has no timing of its own: it rides on "we", the anchor
        // before it, and no emitted word carries the "to" token's stamp.
        assertEquals(
            listOf(
                LyricWord(100, 200, "we go"),
                LyricWord(500, 600, "on"),
                LyricWord(700, 1_000, "now"),
            ),
            out[0].words,
        )
    }

    // -- Merging -----------------------------------------------------------

    @Test
    fun unmatchedInteriorWordMergesIntoPreviousSpan() {
        val lines = listOf(
            line(1_000, "hello brave new world"),
            line(5_000, "second verse line"),
        )
        val tokens = listOf(
            token("hello", 1_000, 1_400),
            // No token for "brave": it was not heard, so it gets no timing
            // of its own and merges back into "hello".
            token("new", 2_000, 2_300),
            token("world", 2_400, 3_000),
            token("second", 4_000, 4_400),
            token("verse", 4_500, 4_900),
            token("line", 5_000, 5_400),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        assertEquals(
            listOf(
                LyricWord(1_000, 1_400, "hello brave"),
                LyricWord(2_000, 2_300, "new"),
                LyricWord(2_400, 3_000, "world"),
            ),
            out[0].words,
        )
        // The merged texts are still findable in the line, in order.
        assertEquals(
            out[0].words.map { it.text },
            out[0].wordSpans.map { out[0].text.substring(it) },
        )
    }

    @Test
    fun leadingUnmatchedWordsMergeForwardIntoFirstMatch() {
        val lines = listOf(
            line(1_000, "oh oh golden hour light"),
            line(5_000, "plain second line"),
        )
        val tokens = listOf(
            token("golden", 1_000, 1_500),
            token("hour", 1_600, 2_000),
            token("light", 2_100, 2_600),
            token("plain", 4_000, 4_300),
            token("second", 4_400, 4_800),
            token("line", 4_900, 5_300),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        // Both leading "oh"s have no anchor before them, so they extend
        // the first match's span backwards — its text, its timing.
        assertEquals(LyricWord(1_000, 1_500, "oh oh golden"), out[0].words.first())
        assertEquals(3, out[0].words.size)
    }

    // -- Line acceptance ---------------------------------------------------

    @Test
    fun lineBelowSixtyPercentStaysUnchanged() {
        val failing = line(5_000, "alpha beta gamma delta")
        val lines = listOf(
            line(1_000, "first line here"),
            failing,
            line(9_000, "last line now"),
        )
        val tokens = listOf(
            token("first", 1_100, 1_400),
            token("line", 1_500, 1_800),
            token("here", 1_900, 2_300),
            // Only "alpha" of the middle line is heard: 1 of 4 is below
            // the 60% bar, so the line keeps its honest line sync.
            token("alpha", 5_100, 5_400),
            token("zzz", 5_500, 5_800),
            token("qqq", 5_900, 6_200),
            token("last", 9_100, 9_400),
            token("line", 9_500, 9_800),
            token("now", 9_900, 10_300),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        assertEquals(failing, out[1])
        assertTrue(out[1].words.isEmpty())
        assertFalse(out[1].wordsAiMeasured)
        assertTrue(out[0].words.isNotEmpty())
        assertTrue(out[2].words.isNotEmpty())
    }

    @Test
    fun lineWithSingleAnchorStaysUnchanged() {
        val solo = line(1_000, "hello")
        val lines = listOf(
            solo,
            line(5_000, "second line here"),
            line(9_000, "third line now"),
        )
        val tokens = listOf(
            token("hello", 1_100, 1_500),
            token("second", 5_100, 5_400),
            token("line", 5_500, 5_800),
            token("here", 5_900, 6_300),
            token("third", 9_100, 9_400),
            token("line", 9_500, 9_800),
            token("now", 9_900, 10_300),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        // The one-word line matched its only word — 100% of it — but a
        // single anchor is not enough to sing a line by.
        assertEquals(solo, out[0])
        assertTrue(out[0].words.isEmpty())
    }

    // -- Song acceptance ---------------------------------------------------

    @Test
    fun songAtOrBelowMajorityReturnsNull() {
        val lines = listOf(
            line(1_000, "hello brave world"),
            line(5_000, "alpha beta gamma"),
        )
        val tokens = listOf(
            token("hello", 1_100, 1_400),
            token("brave", 1_500, 1_900),
            token("world", 2_000, 2_600),
            token("omega", 5_100, 5_500),
        )
        // One of two lines passing is exactly half, not more than half:
        // the whole attempt is discarded, passing line and all.
        assertNull(AiLyricAligner.align(lines, tokens))
    }

    @Test
    fun songAboveMajorityReturnsLines() {
        val lines = listOf(
            line(1_000, "hello brave world"),
            line(5_000, "sing it loud"),
        )
        val tokens = listOf(
            token("hello", 1_100, 1_400),
            token("brave", 1_500, 1_900),
            token("world", 2_000, 2_600),
            token("sing", 5_100, 5_400),
            token("it", 5_500, 5_700),
            token("loud", 5_800, 6_500),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        assertEquals(lines.size, out.size)
        assertTrue(out.all { it.wordsAiMeasured && it.words.isNotEmpty() })
    }

    @Test
    fun gapLinesPassThroughAndDoNotCountInTheRatio() {
        val gap = LyricLine(timeMs = 3_000, text = "")
        val lines = listOf(
            line(1_000, "first line here"),
            gap,
            line(5_000, "alpha beta gamma delta"),
            line(9_000, "last line now"),
        )
        val tokens = listOf(
            token("first", 1_100, 1_400),
            token("line", 1_500, 1_800),
            token("here", 1_900, 2_300),
            token("alpha", 5_100, 5_400),
            token("last", 9_100, 9_400),
            token("line", 9_500, 9_800),
            token("now", 9_900, 10_300),
        )
        // Two of the three sung lines pass. Counted with the gap that
        // would be two of four — not a majority — so a result at all
        // proves the gap stayed out of the ratio.
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        assertEquals(4, out.size)
        assertEquals(gap, out[1])
    }

    // -- Background lines ----------------------------------------------------

    @Test
    fun backgroundEchoGetsWordsFromWindowTokens() {
        val background = line(12_000, "it loud", sungUntilMs = 13_500)
        val parent = line(
            10_000,
            "sing it loud",
            sungUntilMs = 14_000,
            background = background,
        )
        val tokens = listOf(
            // An early "loud", well before the echo's window: the window
            // keeps it out of the echo's reach.
            token("loud", 9_000, 9_500),
            token("sing", 10_100, 10_500),
            token("it", 10_600, 10_900),
            token("loud", 11_000, 11_800),
        )
        val out = checkNotNull(AiLyricAligner.align(listOf(parent), tokens))
        assertEquals(
            listOf(
                LyricWord(10_100, 10_500, "sing"),
                LyricWord(10_600, 10_900, "it"),
                LyricWord(11_000, 11_800, "loud"),
            ),
            out[0].words,
        )
        val alignedBackground = checkNotNull(out[0].background)
        assertEquals(
            listOf(
                LyricWord(10_600, 10_900, "it"),
                LyricWord(11_000, 11_800, "loud"),
            ),
            alignedBackground.words,
        )
        assertTrue(alignedBackground.wordsAiMeasured)
    }

    @Test
    fun backgroundHeardOnlyOutsideItsWindowStaysUnchanged() {
        val background = line(12_000, "far away", sungUntilMs = 13_500)
        val parent = line(
            10_000,
            "sing it loud",
            sungUntilMs = 14_000,
            background = background,
        )
        val tokens = listOf(
            token("sing", 10_100, 10_500),
            token("it", 10_600, 10_900),
            token("loud", 11_000, 11_800),
            // The echo's words, but sung half a minute later — outside
            // the window around the echo's own stamp, so not its timings.
            token("far", 40_000, 40_400),
            token("away", 40_500, 41_000),
        )
        val out = checkNotNull(AiLyricAligner.align(listOf(parent), tokens))
        // The parent still passes on its own words; the background's
        // failure costs it nothing.
        assertTrue(out[0].words.isNotEmpty())
        assertEquals(background, out[0].background)
        assertTrue(checkNotNull(out[0].background).words.isEmpty())
    }

    // -- Degenerate inputs ---------------------------------------------------

    @Test
    fun invalidAndUnmatchableTokensAreIgnoredSafely() {
        val lines = listOf(
            line(1_000, "hello brave world"),
            line(5_000, "second line here"),
        )
        val tokens = listOf(
            // A span that does not run forwards carries no measurement.
            token("hello", 1_500, 1_500),
            token("hello", 1_600, 2_000),
            // Normalises to nothing: unmatchable, skipped.
            token("!!!", 1_700, 1_800),
            token("brave", 2_100, 2_000),
            token("brave", 2_200, 2_600),
            token("world", 2_700, 3_200),
            token("second", 5_000, 5_400),
            token("line", 5_500, 5_800),
            token("here", 5_900, 6_300),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        assertEquals(
            listOf(
                LyricWord(1_600, 2_000, "hello"),
                LyricWord(2_200, 2_600, "brave"),
                LyricWord(2_700, 3_200, "world"),
            ),
            out[0].words,
        )
    }

    @Test
    fun emptyInputsReturnNull() {
        val lines = listOf(line(1_000, "hello brave world"))
        val tokens = listOf(token("hello", 1_100, 1_400))
        assertNull(AiLyricAligner.align(emptyList(), tokens))
        assertNull(AiLyricAligner.align(lines, emptyList()))
        assertNull(AiLyricAligner.align(emptyList(), emptyList()))
        // Nothing but gaps: no sung lines, nothing to measure onto.
        val gaps = listOf(LyricLine(1_000, ""), LyricLine(2_000, "   "))
        assertNull(AiLyricAligner.align(gaps, tokens))
    }
}
