package com.music.sonariq.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [LyricsValidation]: the classification of a lyrics result, and the one
 * rejection it drives — word timings a track's own duration proves
 * belong to a different recording are stripped before they can be
 * shown, while the song's text stays, honestly line-timed.
 *
 * The track in most of these is three minutes long; the spans are
 * chosen against [LyricsLookupPolicy]'s fit tolerances (10s of
 * overshoot, 60s of trailing silence), not near their edges.
 */
class LyricsValidationTest {

    private val durationMs = 180_000L

    private fun word(text: String, startMs: Long, endMs: Long) =
        LyricWord(startMs = startMs, endMs = endMs, text = text)

    /** A word-timed song sung from 10s to [lastEndMs], in two lines. */
    private fun wordSong(
        lastEndMs: Long,
        aiMeasured: Boolean = false,
        estimated: Boolean = false,
    ): List<LyricLine> = listOf(
        LyricLine(
            timeMs = 10_000,
            text = "first line here",
            words = listOf(
                word("first", 10_000, 10_800),
                word("line", 10_900, 11_500),
                word("here", 11_600, 12_400),
            ),
            wordsAiMeasured = aiMeasured,
            wordsEstimated = estimated,
        ),
        LyricLine(
            timeMs = 170_000,
            text = "final line",
            words = listOf(
                word("final", 170_000, 171_000),
                word("line", 171_100, lastEndMs),
            ),
            wordsAiMeasured = aiMeasured,
            wordsEstimated = estimated,
        ),
    )

    private fun resultOf(lines: List<LyricLine>): LyricsRepository.Result =
        LyricsRepository.Result(LyricsSource.LRCLIB, lines, lines.lyricTiming())

    @Test
    fun correctWordSyncedLyricsAreVerified() {
        val result = resultOf(wordSong(lastEndMs = 175_000))
        assertEquals(
            LyricsVerdict.VERIFIED_WORD_SYNC,
            LyricsValidation.classify(result, durationMs),
        )
        // And a verified result is never altered by the rejection pass.
        with(LyricsValidation) {
            assertSame(result, result.withoutUnfitWordTimings(durationMs))
        }
    }

    @Test
    fun wordTimingsFromALongerRecordingAreUnverifiedAndStripped() {
        // The span runs 20s past the end of the track being played:
        // these timings were measured against a longer edit.
        val result = resultOf(wordSong(lastEndMs = 200_000))
        assertEquals(
            LyricsVerdict.UNVERIFIED,
            LyricsValidation.classify(result, durationMs),
        )
        val judged = with(LyricsValidation) {
            result.withoutUnfitWordTimings(durationMs)
        }
        // The words of the song stay; the other recording's timings go.
        assertEquals(listOf("first line here", "final line"), judged.lines.map { it.text })
        assertTrue(judged.lines.all { it.words.isEmpty() })
        assertEquals(LyricTiming.LINE_SYNCED, judged.timing)
    }

    @Test
    fun aiMeasuredTimingsThatFitAreAiMeasured() {
        val result = resultOf(wordSong(lastEndMs = 175_000, aiMeasured = true))
        assertEquals(LyricTiming.WORD_SYNCED_AI_MEASURED, result.timing)
        assertEquals(
            LyricsVerdict.AI_MEASURED,
            LyricsValidation.classify(result, durationMs),
        )
        with(LyricsValidation) {
            assertSame(result, result.withoutUnfitWordTimings(durationMs))
        }
    }

    @Test
    fun aiMeasuredTimingsThatCannotFitAreUnverified() {
        // The aligner measured against *an* audio; if that audio was a
        // different edit, the fit verdict is what catches it.
        val result = resultOf(wordSong(lastEndMs = 240_000, aiMeasured = true))
        assertEquals(
            LyricsVerdict.UNVERIFIED,
            LyricsValidation.classify(result, durationMs),
        )
        val judged = with(LyricsValidation) {
            result.withoutUnfitWordTimings(durationMs)
        }
        assertTrue(judged.lines.all { it.words.isEmpty() })
    }

    @Test
    fun estimatedTimingsAreNeverVerified() {
        val result = resultOf(wordSong(lastEndMs = 175_000, estimated = true))
        assertEquals(LyricTiming.WORD_SYNCED_ESTIMATED, result.timing)
        assertEquals(
            LyricsVerdict.UNVERIFIED,
            LyricsValidation.classify(result, durationMs),
        )
    }

    @Test
    fun lineSyncedLyricsClassifyAsLineSynced() {
        val lines = listOf(
            LyricLine(timeMs = 10_000, text = "first line here"),
            LyricLine(timeMs = 170_000, text = "final line", sungUntilMs = 175_000),
        )
        val result = resultOf(lines)
        assertEquals(LyricTiming.LINE_SYNCED, result.timing)
        assertEquals(
            LyricsVerdict.LINE_SYNCED,
            LyricsValidation.classify(result, durationMs),
        )
    }

    @Test
    fun lineTimingsFromAShorterRecordingAreUnverifiedButKept() {
        // The stated end leaves 80s of track after the singing stops:
        // a shorter edit's stamps. The verdict is unverified — and the
        // rejection pass leaves the result alone, because it rejects
        // word timings, not text.
        val lines = listOf(
            LyricLine(timeMs = 10_000, text = "first line here"),
            LyricLine(timeMs = 90_000, text = "final line", sungUntilMs = 100_000),
        )
        val result = resultOf(lines)
        assertEquals(
            LyricsVerdict.UNVERIFIED,
            LyricsValidation.classify(result, durationMs),
        )
        with(LyricsValidation) {
            assertSame(result, result.withoutUnfitWordTimings(durationMs))
        }
    }

    @Test
    fun plainTextIsAVerifiedProviderResult() {
        val lines = listOf(
            LyricLine(timeMs = 0, text = "first line here"),
            LyricLine(timeMs = 0, text = "final line"),
        )
        val result = resultOf(lines)
        assertEquals(LyricTiming.PLAIN, result.timing)
        assertEquals(
            LyricsVerdict.VERIFIED_PROVIDER,
            LyricsValidation.classify(result, durationMs),
        )
    }

    @Test
    fun noResultIsNoLyrics() {
        assertEquals(
            LyricsVerdict.NO_LYRICS,
            LyricsValidation.classify(null, durationMs),
        )
    }

    @Test
    fun anUnknownDurationCondemnsNothing() {
        // Without the track's length there is no evidence either way:
        // the same span that fails at 180s is unjudged at 0, and the
        // rejection pass leaves the result exactly as it was.
        val result = resultOf(wordSong(lastEndMs = 200_000))
        assertEquals(
            LyricsVerdict.VERIFIED_WORD_SYNC,
            LyricsValidation.classify(result, 0L),
        )
        with(LyricsValidation) {
            assertSame(result, result.withoutUnfitWordTimings(0L))
        }
    }
}
