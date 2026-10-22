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

    // -- Version ------------------------------------------------------------

    @Test
    fun alignmentVersionIsTwo() {
        assertEquals(2, AiLyricAligner.ALIGNMENT_VERSION)
    }

    // -- Normalisation -----------------------------------------------------

    @Test
    fun normalisationDropsCasePunctuationAndExtraSpace() {
        // "hello" loses its doubled letter to the collapse both sides get.
        assertEquals("helo", normalizeToken("Hello,"))
        assertEquals("world", normalizeToken("  WORLD!  "))
        assertEquals("to many spaces", normalizeToken("too   many    spaces"))
        assertEquals("123", normalizeToken("(123)"))
    }

    @Test
    fun normalisationSplitsHyphenatedWordsIntoUnits() {
        // A hyphen is a separator: two sung units, not one merged word.
        // The first unit's own doubled letter collapses like any other.
        assertEquals("wel known", normalizeToken("well-known"))
        assertEquals("ah ah", normalizeToken("ah-ah"))
    }

    @Test
    fun normalisationDropsApostrophesEntirely() {
        assertEquals("dont", normalizeToken("don't"))
        assertEquals("dont", normalizeToken("DON’T"))
        // Hanging off an edge or inside, the mark goes either way.
        assertEquals("tis", normalizeToken("'tis"))
        assertEquals("di", normalizeToken("'di"))
        assertEquals("ngayoy", normalizeToken("ngayo'y"))
        assertEquals("rock n rol", normalizeToken("rock 'n' roll"))
    }

    @Test
    fun normalisationCollapsesRepeatedLetters() {
        assertEquals("oh", normalizeToken("ooh"))
        assertEquals("ngayon", normalizeToken("ngayoon"))
        assertEquals("helo", normalizeToken("hello"))
    }

    @Test
    fun normalisationOfPurePunctuationIsEmpty() {
        assertEquals("", normalizeToken("!!!"))
        assertEquals("", normalizeToken("—"))
        assertEquals("", normalizeToken("   "))
        assertEquals("", normalizeToken(""))
        // The engine's own handle on the same function agrees.
        assertEquals("helo", AiLyricAligner.normalizeToken("Hello!"))
    }

    // -- Exact alignment ---------------------------------------------------

    @Test
    fun exactAlignmentTransfersTokenStartsAndHoldsEnds() {
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
        // Starts are the tokens' measured starts, offset from the line
        // stamps so nothing here could be derived from the lines. Ends
        // are NOT the tokens' ends: each word holds to the next word's
        // start, and the last word of a line holds to the line's end.
        assertEquals(
            listOf(
                LyricWord(1_123, 1_600, "hello"),
                LyricWord(1_600, 2_100, "brave"),
                LyricWord(2_100, 4_000, "world"),
            ),
            out[0].words,
        )
        assertEquals(
            listOf(
                LyricWord(5_100, 5_600, "sing"),
                LyricWord(5_600, 5_900, "it"),
                LyricWord(5_900, 8_000, "loud"),
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
            token("goldin", 1_100, 1_500),
            token("hour", 1_600, 2_000),
            token("light", 2_100, 2_600),
            token("second", 5_100, 5_500),
            token("line", 5_600, 5_900),
            token("here", 6_000, 6_400),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        // The lyric keeps its own spelling; only the timing is borrowed.
        assertEquals(LyricWord(1_100, 1_600, "golden"), out[0].words[0])
        assertEquals(3, out[0].words.size)
    }

    @Test
    fun shortWordNearMatchDoesNotMatch() {
        val lines = listOf(
            line(1_000, "we go on now"),
            line(5_000, "stay with me tonight"),
        )
        val tokens = listOf(
            token("we", 1_100, 1_300),
            // One letter from "go", but at two letters that is a different
            // word, not a mishearing: "go" must stay unmatched.
            token("to", 1_400, 1_600),
            token("on", 1_700, 1_900),
            token("now", 2_000, 2_400),
            token("stay", 5_100, 5_400),
            token("with", 5_500, 5_800),
            token("me", 5_900, 6_100),
            token("tonight", 6_200, 6_800),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        // "go" has no timing of its own: it rides on "we", the anchor
        // before it, and no emitted word carries the "to" token's stamp.
        assertEquals(
            listOf(
                LyricWord(1_100, 1_700, "we go"),
                LyricWord(1_700, 2_000, "on"),
                LyricWord(2_000, 5_000, "now"),
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
            token("hello", 1_100, 1_500),
            // No token for "brave": it was not heard, so it gets no timing
            // of its own and merges back into "hello".
            token("new", 2_100, 2_400),
            token("world", 2_500, 3_100),
            token("second", 5_100, 5_500),
            token("verse", 5_600, 6_000),
            token("line", 6_100, 6_500),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        assertEquals(
            listOf(
                LyricWord(1_100, 2_100, "hello brave"),
                LyricWord(2_100, 2_500, "new"),
                LyricWord(2_500, 5_000, "world"),
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
            line(5_000, "sing it loud now"),
        )
        val tokens = listOf(
            token("golden", 1_100, 1_600),
            token("hour", 1_700, 2_100),
            token("light", 2_200, 2_700),
            token("sing", 5_100, 5_400),
            token("it", 5_500, 5_700),
            token("loud", 5_800, 6_200),
            token("now", 6_300, 6_600),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        // Both leading "oh"s have no anchor before them, so they extend
        // the first match's span backwards — its text, its timing.
        assertEquals(LyricWord(1_100, 1_700, "oh oh golden"), out[0].words.first())
        assertEquals(3, out[0].words.size)
    }

    // -- Per-line acceptance -------------------------------------------------

    @Test
    fun shortLinesAndTheFiftyPercentBoundaryPass() {
        val lines = listOf(
            // Two words, one anchor: exactly half, and a short line needs
            // only the one anchor.
            line(1_000, "moon river"),
            // Four words, two anchors: exactly half, and a longer line
            // needs its two anchors — both bars met on the boundary.
            line(5_000, "alpha bravo charlie delta"),
            // Three words, two anchors: past half on a single-anchor bar.
            line(9_000, "one two three"),
        )
        val tokens = listOf(
            token("moon", 1_100, 1_500),
            token("alpha", 5_100, 5_500),
            token("bravo", 5_600, 5_900),
            token("one", 9_100, 9_400),
            token("two", 9_500, 9_800),
        )
        val outcome = AiLyricAligner.alignDetailed(lines, tokens)
        assertEquals(3, outcome.passedLeadLines)
        assertEquals(3, outcome.leadLineCount)
        val out = checkNotNull(outcome.lines)
        // The unanchored half of each line merged into its anchors.
        assertEquals(listOf(LyricWord(1_100, 5_000, "moon river")), out[0].words)
        assertEquals(
            listOf(
                LyricWord(5_100, 5_600, "alpha"),
                LyricWord(5_600, 9_000, "bravo charlie delta"),
            ),
            out[1].words,
        )
    }

    @Test
    fun fiveWordLineFailsWithASingleAnchor() {
        val lines = listOf(
            line(1_000, "first line here now"),
            line(5_000, "alpha bravo charlie delta echo"),
        )
        val tokens = listOf(
            token("first", 1_100, 1_400),
            token("line", 1_500, 1_800),
            token("here", 1_900, 2_300),
            token("now", 2_400, 2_700),
            // One anchor of five words: under half, and under the two
            // anchors a line this long needs. The line fails.
            token("alpha", 5_100, 5_500),
        )
        val outcome = AiLyricAligner.alignDetailed(lines, tokens)
        assertNull(outcome.lines)
        assertEquals(1, outcome.passedLeadLines)
        assertEquals(2, outcome.leadLineCount)
        assertFalse(outcome.calibrationDiscarded)
    }

    @Test
    fun threeWordLineFailsWithASingleAnchor() {
        // A short line's anchor bar is one, but the half-the-words bar
        // still applies: one anchor of three words is a third, not half.
        val lines = listOf(line(1_000, "alpha bravo charlie"))
        val tokens = listOf(token("alpha", 1_100, 1_500))
        val outcome = AiLyricAligner.alignDetailed(lines, tokens)
        assertNull(outcome.lines)
        assertEquals(0, outcome.passedLeadLines)
        assertEquals(1, outcome.leadLineCount)
    }

    @Test
    fun singleWordLinePassesOnItsOneAnchor() {
        val solo = line(1_000, "hello")
        val lines = listOf(
            solo,
            line(5_000, "sing it loud"),
        )
        val tokens = listOf(
            token("hello", 1_100, 1_500),
            token("sing", 5_100, 5_400),
            token("it", 5_500, 5_700),
            token("loud", 5_800, 6_500),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        // One word, one anchor: half and the short-line bar both met.
        assertEquals(listOf(LyricWord(1_100, 5_000, "hello")), out[0].words)
        assertTrue(out[0].wordsAiMeasured)
    }

    // -- Every line or discard ------------------------------------------------

    @Test
    fun oneFailingLineDiscardsTheWholeSong() {
        val lines = listOf(
            line(1_000, "first line here"),
            line(5_000, "alpha beta gamma delta"),
            line(9_000, "last line now"),
        )
        val tokens = listOf(
            token("first", 1_100, 1_400),
            token("line", 1_500, 1_800),
            token("here", 1_900, 2_300),
            // Only "alpha" of the middle line is heard: the line fails,
            // and a song is every line word-synced or not measured at all.
            token("alpha", 5_100, 5_400),
            token("zzz", 5_500, 5_800),
            token("qqq", 5_900, 6_200),
            token("last", 9_100, 9_400),
            token("line", 9_500, 9_800),
            token("now", 9_900, 10_300),
        )
        val outcome = AiLyricAligner.alignDetailed(lines, tokens)
        assertNull(outcome.lines)
        // The report still says how close the song came.
        assertEquals(2, outcome.passedLeadLines)
        assertEquals(3, outcome.leadLineCount)
        assertNull(AiLyricAligner.align(lines, tokens))
    }

    @Test
    fun gapLinesPassThroughAndDoNotCount() {
        val gap = LyricLine(timeMs = 3_000, text = "")
        val lines = listOf(
            line(1_000, "first line here"),
            gap,
            line(5_000, "last line now"),
        )
        val tokens = listOf(
            token("first", 1_100, 1_400),
            token("line", 1_500, 1_800),
            token("here", 1_900, 2_300),
            token("last", 5_100, 5_400),
            token("line", 5_500, 5_800),
            token("now", 5_900, 6_300),
        )
        val outcome = AiLyricAligner.alignDetailed(lines, tokens)
        assertEquals(2, outcome.leadLineCount)
        assertEquals(2, outcome.passedLeadLines)
        val out = checkNotNull(outcome.lines)
        assertEquals(3, out.size)
        assertEquals(gap, out[1])
    }

    // -- Line windows -----------------------------------------------------------

    @Test
    fun repeatedChorusWordsTakeTokensFromTheirOwnWindow() {
        val chorus = "sing the chorus line"
        val lines = listOf(
            line(1_000, chorus),
            line(6_000, "we go on"),
            line(11_000, chorus),
            line(16_000, "we go on"),
        )
        val tokens = listOf(
            token("sing", 1_100, 1_400),
            token("the", 1_500, 1_700),
            token("chorus", 1_800, 2_300),
            token("line", 2_400, 2_800),
            token("we", 6_100, 6_300),
            token("go", 6_400, 6_600),
            token("on", 6_700, 7_000),
            token("sing", 11_100, 11_400),
            token("the", 11_500, 11_700),
            token("chorus", 11_800, 12_300),
            token("line", 12_400, 12_800),
            token("we", 16_100, 16_300),
            token("go", 16_400, 16_600),
            token("on", 16_700, 17_000),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        // Identical words, two windows: each chorus is timed by the
        // tokens sung inside its own window, never the other chorus's.
        assertEquals(listOf(1_100L, 1_500L, 1_800L, 2_400L), out[0].words.map { it.startMs })
        assertEquals(listOf(11_100L, 11_500L, 11_800L, 12_400L), out[2].words.map { it.startMs })
    }

    @Test
    fun tokenOutsideEveryWindowIsUnused() {
        val lines = listOf(
            line(5_000, "hello brave world"),
            line(10_000, "sing it loud", sungUntilMs = 13_000),
        )
        val tokens = listOf(
            // Before the first window opens and after the last one shuts:
            // heard, but not any line's to borrow.
            token("ghost", 100, 400),
            token("hello", 5_100, 5_500),
            token("brave", 5_600, 6_000),
            token("world", 6_100, 6_600),
            token("sing", 10_100, 10_500),
            token("it", 10_600, 10_900),
            token("loud", 11_000, 11_800),
            token("ghost", 60_000, 60_400),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        val starts = out.flatMap { it.words }.map { it.startMs }
        assertEquals(listOf(5_100L, 5_600L, 6_100L, 10_100L, 10_600L, 11_000L), starts)
    }

    @Test
    fun tokenConsumedByOneLineCannotTimeTheNext() {
        val lines = listOf(
            line(1_000, "go home"),
            line(5_000, "home again soon"),
        )
        val tokens = listOf(
            token("go", 1_100, 1_300),
            // The one "home": its midpoint sits inside both lines'
            // widened windows, but the first line is processed first and
            // consumes it.
            token("home", 4_800, 5_200),
            token("again", 5_700, 6_100),
            token("soon", 6_200, 6_600),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        assertEquals(4_800L, out[0].words[1].startMs)
        // The second line's "home" has no token left: it merges forward
        // into "again", and no word of the line starts at 4800.
        assertEquals(LyricWord(5_700, 6_200, "home again"), out[1].words.first())
        assertTrue(out[1].words.none { it.startMs == 4_800L })
    }

    // -- Calibration -------------------------------------------------------------

    @Test
    fun uniformOffsetIsCalibratedOut() {
        // Every token sits a steady two seconds after the place its
        // word's character position in the line predicts — a different
        // edit's padding. Calibration should measure those two seconds
        // and take them back out, so the words land in their lines.
        val lines = listOf(
            line(10_000, "alpha bravo charlie delta", sungUntilMs = 19_000),
            line(20_000, "echo foxtrot golf hotel", sungUntilMs = 30_000),
        )
        val tokens = listOf(
            token("alpha", 12_000, 12_400),
            token("bravo", 14_400, 14_800),
            token("charlie", 16_800, 17_400),
            token("delta", 20_000, 20_600),
            token("echo", 22_200, 22_600),
            token("foxtrot", 24_200, 24_600),
            token("golf", 27_700, 28_100),
            token("hotel", 29_800, 30_400),
        )
        val outcome = AiLyricAligner.alignDetailed(lines, tokens)
        assertFalse(outcome.calibrationDiscarded)
        assertEquals(2_000L, outcome.offsetMs)
        val out = checkNotNull(outcome.lines)
        assertEquals(
            listOf(
                LyricWord(10_000, 12_400, "alpha"),
                LyricWord(12_400, 14_800, "bravo"),
                LyricWord(14_800, 18_000, "charlie"),
                LyricWord(18_000, 19_000, "delta"),
            ),
            out[0].words,
        )
        assertEquals(
            listOf(20_200L, 22_200L, 25_700L, 27_800L),
            out[1].words.map { it.startMs },
        )
        assertEquals(30_000L, out[1].words.last().endMs)
    }

    @Test
    fun scatteredResidualsDiscardCalibration() {
        // The long words' tokens are scattered seconds either side of
        // where their lines predict them: no one offset explains this
        // transcription, so the attempt is discarded whole.
        val lines = listOf(
            line(10_000, "alpha bravo charlie delta"),
            line(20_000, "foxtrot golf hotel india", sungUntilMs = 30_000),
        )
        val tokens = listOf(
            token("foxtrot", 15_000, 15_400),
            token("alpha", 10_000, 10_400),
            token("charlie", 12_300, 12_700),
            token("bravo", 14_900, 15_300),
            token("delta", 23_000, 23_400),
            token("hotel", 26_400, 26_800),
            token("india", 28_400, 28_800),
        )
        val outcome = AiLyricAligner.alignDetailed(lines, tokens)
        assertTrue(outcome.calibrationDiscarded)
        assertNull(outcome.lines)
        assertEquals(0, outcome.passedLeadLines)
        assertEquals(2, outcome.leadLineCount)
        assertNull(AiLyricAligner.align(lines, tokens))
    }

    @Test
    fun tooFewAnchorsMeansNoCalibrationAndNoDiscard() {
        // Three long-word anchors, wildly scattered — but three is below
        // the four-anchor bar, so nothing is shifted and nothing is
        // discarded on their say-so.
        val lines = listOf(
            line(10_000, "alpha bravo charlie", sungUntilMs = 20_000),
        )
        val tokens = listOf(
            token("alpha", 10_000, 10_400),
            token("bravo", 17_400, 17_800),
            token("charlie", 9_000, 9_400),
        )
        val outcome = AiLyricAligner.alignDetailed(lines, tokens)
        assertFalse(outcome.calibrationDiscarded)
        assertEquals(0L, outcome.offsetMs)
        // The line still aligns on the anchors inside its own window.
        checkNotNull(outcome.lines)
    }

    // -- Word ends hold -------------------------------------------------------------

    @Test
    fun wordEndsHoldToNextStartAndLastWordToLineEnd() {
        val lines = listOf(
            // The stated end runs past the next line's stamp: the window
            // caps it, so the last word ends at the window's end.
            line(1_000, "hello brave world", sungUntilMs = 9_000),
            line(5_000, "sing it loud", sungUntilMs = 8_000),
        )
        val tokens = listOf(
            token("hello", 1_123, 1_456),
            token("brave", 1_600, 2_000),
            token("world", 2_100, 2_300),
            token("sing", 5_100, 5_500),
            token("it", 5_600, 5_800),
            token("loud", 5_900, 6_800),
        )
        val tokenEnds = tokens.map { it.endMs }.toSet()
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        for (words in out.map { it.words }) {
            // Every interior end is exactly the next word's start...
            for (index in 0 until words.lastIndex) {
                assertEquals(words[index + 1].startMs, words[index].endMs)
            }
            // ...and no emitted end is any token's recognition end.
            assertTrue(words.none { it.endMs in tokenEnds })
            assertTrue(words.all { it.endMs > it.startMs })
        }
        // The last words end at their lines' display ends.
        assertEquals(5_000L, out[0].words.last().endMs)
        assertEquals(8_000L, out[1].words.last().endMs)
    }

    // -- Tagalog shapes ---------------------------------------------------------------

    @Test
    fun tagalogApostrophesAlign() {
        val lines = listOf(
            line(1_000, "'di kita iiwan ngayon"),
            line(5_000, "ngayo'y akin ka"),
        )
        val tokens = listOf(
            // The transcription writes neither apostrophe; the lyric
            // writes both. Dropped marks make them the same words.
            token("di", 1_100, 1_300),
            token("kita", 1_400, 1_800),
            token("iiwan", 1_900, 2_400),
            token("ngayon", 2_500, 3_100),
            token("ngayoy", 5_100, 5_600),
            token("akin", 5_700, 6_100),
            token("ka", 6_200, 6_400),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        assertEquals(LyricWord(1_100, 1_400, "'di"), out[0].words.first())
        assertEquals(LyricWord(5_100, 5_700, "ngayo'y"), out[1].words.first())
    }

    @Test
    fun doubledLettersAlignAcrossSpellings() {
        val lines = listOf(
            line(1_000, "ngayoon ooh mahal"),
            line(5_000, "sing it loud"),
        )
        val tokens = listOf(
            // Held vowels written short by the transcription, long by
            // the lyric: the collapse on both sides meets in the middle.
            token("ngayon", 1_100, 1_700),
            token("oh", 1_800, 2_100),
            token("mahal", 2_200, 2_800),
            token("sing", 5_100, 5_400),
            token("it", 5_500, 5_700),
            token("loud", 5_800, 6_400),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        assertEquals(
            listOf(
                LyricWord(1_100, 1_800, "ngayoon"),
                LyricWord(1_800, 2_200, "ooh"),
                LyricWord(2_200, 5_000, "mahal"),
            ),
            out[0].words,
        )
    }

    @Test
    fun hyphenatedVocalisationAlignsPerUnit() {
        val lines = listOf(
            line(1_000, "ah-ah ah-ah tayo"),
            line(5_000, "sing it loud"),
        )
        val tokens = listOf(
            token("ah", 1_100, 1_300),
            token("ah", 1_400, 1_600),
            token("ah", 1_700, 1_900),
            token("ah", 2_000, 2_200),
            token("tayo", 2_300, 2_800),
            token("sing", 5_100, 5_400),
            token("it", 5_500, 5_700),
            token("loud", 5_800, 6_500),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        // Each written word counts once and emits once, timed from its
        // earliest matched unit — even though it took two tokens to sing.
        assertEquals(
            listOf(
                LyricWord(1_100, 1_700, "ah-ah"),
                LyricWord(1_700, 2_300, "ah-ah"),
                LyricWord(2_300, 5_000, "tayo"),
            ),
            out[0].words,
        )
    }

    // -- Background lines ----------------------------------------------------

    @Test
    fun backgroundEchoGetsWordsFromItsOwnUnconsumedTokens() {
        val background = line(12_000, "echo echo", sungUntilMs = 13_500)
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
            // The echo's own tokens, sung after the lead's: not consumed
            // by the lead, so the echo may draw on them.
            token("echo", 12_100, 12_500),
            token("echo", 12_800, 13_200),
        )
        val out = checkNotNull(AiLyricAligner.align(listOf(parent), tokens))
        assertEquals(
            listOf(
                LyricWord(10_100, 10_600, "sing"),
                LyricWord(10_600, 11_000, "it"),
                LyricWord(11_000, 14_000, "loud"),
            ),
            out[0].words,
        )
        val alignedBackground = checkNotNull(out[0].background)
        assertEquals(
            listOf(
                LyricWord(12_100, 12_800, "echo"),
                LyricWord(12_800, 13_500, "echo"),
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
            token("second", 5_100, 5_500),
            token("line", 5_600, 5_900),
            token("here", 6_000, 6_400),
        )
        val out = checkNotNull(AiLyricAligner.align(lines, tokens))
        assertEquals(
            listOf(
                LyricWord(1_600, 2_200, "hello"),
                LyricWord(2_200, 2_700, "brave"),
                LyricWord(2_700, 5_000, "world"),
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
        val outcome = AiLyricAligner.alignDetailed(gaps, tokens)
        assertEquals(0, outcome.leadLineCount)
        assertEquals(0, outcome.passedLeadLines)
        assertFalse(outcome.calibrationDiscarded)
    }
}
