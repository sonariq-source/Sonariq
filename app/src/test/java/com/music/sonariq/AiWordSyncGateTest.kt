package com.music.sonariq

import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.lyrics.LyricWord
import com.music.sonariq.data.lyrics.LyricsRepository
import com.music.sonariq.data.lyrics.LyricsSource
import com.music.sonariq.download.aiWordSyncGateAllows
import com.music.sonariq.download.isAiWordSyncCandidate
import org.junit.Assert.assertFalse
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
}
