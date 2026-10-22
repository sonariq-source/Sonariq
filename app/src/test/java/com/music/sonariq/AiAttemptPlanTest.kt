package com.music.sonariq

import com.music.sonariq.data.lyrics.AiLyricAligner
import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.lyrics.LyricWord
import com.music.sonariq.data.lyrics.TranscriptionClient
import com.music.sonariq.download.AiAttemptSpec
import com.music.sonariq.download.aiAttemptPlan
import com.music.sonariq.download.bestAiAttempt
import com.music.sonariq.download.isAiWordSyncCandidate
import com.music.sonariq.download.pickAiAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure pieces of the four-attempt AI word-sync ladder — see
 * `AiWordSync.kt`.
 *
 * The ladder is the expensive decision in the feature: each rung is a
 * transcription call paid for in time and quota, so the order it runs
 * in, the steers each rung drops, and the rule that it stops at the
 * first complete attempt are all worth pinning down without a device.
 * The embedded-lines candidate check rides along here: it is the same
 * kind of pure gate, for lyrics read out of a file's tags rather than
 * out of a repository result.
 */
class AiAttemptPlanTest {

    @Test
    fun `the plan runs four rungs in order when a language was detected`() {
        assertEquals(
            listOf(
                AiAttemptSpec(
                    model = TranscriptionClient.DEFAULT_MODEL,
                    language = "tl",
                    withPrompt = true,
                ),
                AiAttemptSpec(
                    model = TranscriptionClient.DEFAULT_MODEL,
                    language = null,
                    withPrompt = true,
                ),
                AiAttemptSpec(
                    model = TranscriptionClient.ALTERNATE_MODEL,
                    language = "tl",
                    withPrompt = true,
                ),
                AiAttemptSpec(
                    model = TranscriptionClient.ALTERNATE_MODEL,
                    language = null,
                    withPrompt = false,
                ),
            ),
            aiAttemptPlan(detectedLanguage = "tl"),
        )
    }

    @Test
    fun `the plan dedupes to three rungs when no language was detected`() {
        val plan = aiAttemptPlan(detectedLanguage = null)
        // With no detected language the first two rungs are the same
        // call; the plan must not pay for it twice.
        assertEquals(3, plan.size)
        assertEquals(plan.size, plan.distinct().size)
        assertEquals(
            listOf(
                AiAttemptSpec(
                    model = TranscriptionClient.DEFAULT_MODEL,
                    language = null,
                    withPrompt = true,
                ),
                AiAttemptSpec(
                    model = TranscriptionClient.ALTERNATE_MODEL,
                    language = null,
                    withPrompt = true,
                ),
                AiAttemptSpec(
                    model = TranscriptionClient.ALTERNATE_MODEL,
                    language = null,
                    withPrompt = false,
                ),
            ),
            plan,
        )
    }

    /**
     * One attempt's outcome, as [AiLyricAligner.alignDetailed] reports
     * it: [complete] carries the timed lines (granted only when every
     * lead line earned words); an incomplete attempt carries none, only
     * how many of its [total] lead lines passed.
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
    fun `pick over a list takes the first complete attempt and ignores later ones`() {
        val incomplete = outcome(passed = 5, total = 8, complete = false)
        val firstComplete = outcome(passed = 8, total = 8, complete = true)
        val laterComplete = outcome(passed = 8, total = 8, complete = true)
        assertSame(
            firstComplete,
            pickAiAttempt(listOf(incomplete, firstComplete, laterComplete)),
        )
        assertSame(firstComplete, pickAiAttempt(listOf(null, firstComplete)))
    }

    @Test
    fun `pick over a list stores nothing when no attempt completed`() {
        val first = outcome(passed = 5, total = 8, complete = false)
        val second = outcome(passed = 7, total = 8, complete = false)
        assertNull(pickAiAttempt(listOf(first, null, second)))
        assertNull(pickAiAttempt(listOf(null, null)))
        assertNull(pickAiAttempt(emptyList()))
    }

    @Test
    fun `best over a list prefers a complete attempt over incomplete ones`() {
        val incomplete = outcome(passed = 7, total = 8, complete = false)
        val complete = outcome(passed = 2, total = 2, complete = true)
        assertSame(complete, bestAiAttempt(listOf(incomplete, complete)))
        assertSame(complete, bestAiAttempt(listOf(complete, incomplete)))
        // A later complete attempt never displaces an earlier one.
        val laterComplete = outcome(passed = 8, total = 8, complete = true)
        assertSame(complete, bestAiAttempt(listOf(complete, laterComplete)))
    }

    @Test
    fun `best over a list takes the most passed lines and keeps the earlier on a tie`() {
        val first = outcome(passed = 3, total = 8, complete = false)
        val closer = outcome(passed = 6, total = 8, complete = false)
        assertSame(closer, bestAiAttempt(listOf(first, closer)))
        assertSame(closer, bestAiAttempt(listOf(closer, first)))
        val tied = outcome(passed = 3, total = 8, complete = false)
        assertSame(first, bestAiAttempt(listOf(first, null, tied)))
        // And a missing attempt is never the best of the list.
        assertSame(first, bestAiAttempt(listOf(null, first)))
        assertNull(bestAiAttempt(listOf(null, null)))
        assertNull(bestAiAttempt(emptyList()))
    }

    @Test
    fun `line-only embedded lines are a candidate`() {
        val lines = listOf(
            LyricLine(timeMs = 4_000, text = "golden hour", sungUntilMs = 8_000),
        )
        assertTrue(lines.isAiWordSyncCandidate())
    }

    @Test
    fun `word-timed embedded lines are not a candidate, whoever timed them`() {
        val providerTimed = listOf(
            LyricLine(
                timeMs = 4_000,
                text = "golden hour",
                words = listOf(LyricWord(4_000, 4_600, "golden")),
            ),
        )
        assertFalse(providerTimed.isAiWordSyncCandidate())

        val aiMeasured = listOf(
            LyricLine(
                timeMs = 4_000,
                text = "golden hour",
                words = listOf(LyricWord(4_000, 4_600, "golden")),
                wordsAiMeasured = true,
            ),
        )
        assertFalse(aiMeasured.isAiWordSyncCandidate())
    }

    @Test
    fun `embedded lines with no sung text are not a candidate`() {
        val gapsOnly = listOf(LyricLine(timeMs = 4_000, text = ""))
        assertFalse(gapsOnly.isAiWordSyncCandidate())
        assertFalse(emptyList<LyricLine>().isAiWordSyncCandidate())
    }
}
