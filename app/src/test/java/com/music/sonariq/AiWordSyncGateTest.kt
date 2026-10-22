package com.music.sonariq

import com.music.sonariq.data.lyrics.AiLyricAligner
import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.lyrics.LyricWord
import com.music.sonariq.data.lyrics.LyricsRepository
import com.music.sonariq.data.lyrics.LyricsSource
import com.music.sonariq.download.aiWordSyncGateAllows
import com.music.sonariq.download.bestAiAttempt
import com.music.sonariq.download.isAiWordSyncCandidate
import com.music.sonariq.download.pickAiAttempt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure gates in front of an AI word-sync job — see `AiWordSync.kt`.
 *
 * The dormant behaviour is the point: a build whose transcription key is
 * blank (a fresh checkout or a fork, since the key is injected at build
 * time) starts no job for any track, so the feature behaves exactly as
 * though it did not exist. That is a pure decision over three booleans,
 * which is why it can be pinned down here without a device.
 */
class AiWordSyncGateTest {

    @Test
    fun `the gate opens only when enabled, keyed and not already running`() {
        assertTrue(aiWordSyncGateAllows(enabled = true, keyBlank = false, alreadyInFlight = false))
        assertFalse(aiWordSyncGateAllows(enabled = false, keyBlank = false, alreadyInFlight = false))
        assertFalse(aiWordSyncGateAllows(enabled = true, keyBlank = true, alreadyInFlight = false))
        assertFalse(aiWordSyncGateAllows(enabled = true, keyBlank = false, alreadyInFlight = true))
        assertFalse(aiWordSyncGateAllows(enabled = false, keyBlank = true, alreadyInFlight = true))
    }

    private fun result(lines: List<LyricLine>) =
        LyricsRepository.Result(LyricsSource.LRCLIB, lines)

    @Test
    fun `a line-synced result is a candidate`() {
        val lineOnly = result(
            listOf(LyricLine(timeMs = 4_000, text = "golden hour", sungUntilMs = 8_000)),
        )
        assertTrue(lineOnly.isAiWordSyncCandidate())
    }

    @Test
    fun `word-synced results are not candidates`() {
        val provider = result(
            listOf(
                LyricLine(
                    timeMs = 4_000,
                    text = "golden hour",
                    words = listOf(LyricWord(4_000, 4_600, "golden")),
                ),
            ),
        )
        assertFalse(provider.isAiWordSyncCandidate())

        val aiMeasured = result(
            listOf(
                LyricLine(
                    timeMs = 4_000,
                    text = "golden hour",
                    words = listOf(LyricWord(4_000, 4_600, "golden")),
                    wordsAiMeasured = true,
                ),
            ),
        )
        assertFalse(aiMeasured.isAiWordSyncCandidate())
    }

    @Test
    fun `a result with no sung lines is not a candidate`() {
        val gapsOnly = result(listOf(LyricLine(timeMs = 4_000, text = "")))
        assertFalse(gapsOnly.isAiWordSyncCandidate())
    }

    /**
     * One attempt's outcome, as [AiLyricAligner.alignDetailed] reports it:
     * [complete] carries the timed lines (granted only when every lead
     * line earned words); an incomplete attempt carries none, only how
     * many of its [total] lead lines passed.
     */
    private fun outcome(passed: Int, total: Int, complete: Boolean) =
        AiLyricAligner.AlignOutcome(
            lines = if (complete) {
                listOf(LyricLine(timeMs = 4_000, text = "golden hour", sungUntilMs = 8_000))
            } else {
                null
            },
            passedLeadLines = passed,
            leadLineCount = total,
            calibrationDiscarded = false,
            offsetMs = 0L,
        )

    @Test
    fun `a complete first attempt is the one stored`() {
        val first = outcome(passed = 8, total = 8, complete = true)
        val second = outcome(passed = 8, total = 8, complete = true)
        assertSame(first, pickAiAttempt(first, second))
        assertSame(first, pickAiAttempt(first, null))
    }

    @Test
    fun `a complete second attempt is stored when the first fell short`() {
        val first = outcome(passed = 5, total = 8, complete = false)
        val second = outcome(passed = 8, total = 8, complete = true)
        assertSame(second, pickAiAttempt(first, second))
        assertSame(second, pickAiAttempt(null, second))
    }

    @Test
    fun `two incomplete attempts store nothing`() {
        val first = outcome(passed = 5, total = 8, complete = false)
        val second = outcome(passed = 7, total = 8, complete = false)
        // The second got closer, and closer still earns no store: a
        // half-timed song is never written over its honest line sync.
        assertNull(pickAiAttempt(first, second))
        assertNull(pickAiAttempt(first, null))
        assertNull(pickAiAttempt(null, null))
    }

    @Test
    fun `the best attempt is the complete one, whichever it was`() {
        val incomplete = outcome(passed = 7, total = 8, complete = false)
        val complete = outcome(passed = 2, total = 2, complete = true)
        assertSame(complete, bestAiAttempt(incomplete, complete))
        assertSame(complete, bestAiAttempt(complete, incomplete))
    }

    @Test
    fun `between two incomplete attempts the best passed more lines`() {
        val first = outcome(passed = 3, total = 8, complete = false)
        val second = outcome(passed = 6, total = 8, complete = false)
        assertSame(second, bestAiAttempt(first, second))
        assertSame(second, bestAiAttempt(second, first))
        // A tie keeps the first attempt — the primary model's.
        val tied = outcome(passed = 3, total = 8, complete = false)
        assertSame(first, bestAiAttempt(first, tied))
        // And a missing attempt is never the best of the two.
        assertSame(first, bestAiAttempt(first, null))
        assertSame(first, bestAiAttempt(null, first))
        assertNull(bestAiAttempt(null, null))
    }
}
