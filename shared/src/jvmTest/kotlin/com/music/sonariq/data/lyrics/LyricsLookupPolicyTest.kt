package com.music.sonariq.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsLookupPolicyTest {

    private fun result(
        timing: LyricTiming,
        wordSynced: Boolean = timing == LyricTiming.WORD_SYNCED,
    ): LyricsRepository.Result {
        val lines = when {
            wordSynced -> listOf(
                LyricLine(
                    timeMs = 5_000,
                    text = "verse",
                    words = listOf(LyricWord(5_000, 5_600, "verse")),
                    sungUntilMs = 8_000,
                ),
            )
            timing == LyricTiming.LINE_SYNCED -> listOf(
                LyricLine(5_000, "verse", sungUntilMs = 8_000),
            )
            else -> listOf(LyricLine(0, "verse"))
        }
        return LyricsRepository.Result(LyricsSource.LRCLIB, lines, timing)
    }

    @Test
    fun negativeFreshWithinTtl() {
        val missedAt = 1_000_000L
        assertTrue(LyricsLookupPolicy.isNegativeFresh(missedAt, missedAt))
        assertTrue(
            LyricsLookupPolicy.isNegativeFresh(
                missedAt,
                missedAt + LyricsLookupPolicy.NEGATIVE_TTL_MS - 1,
            ),
        )
    }

    @Test
    fun negativeStaleAtAndAfterTtl() {
        val missedAt = 1_000_000L
        assertFalse(
            LyricsLookupPolicy.isNegativeFresh(
                missedAt,
                missedAt + LyricsLookupPolicy.NEGATIVE_TTL_MS,
            ),
        )
        assertFalse(
            LyricsLookupPolicy.isNegativeFresh(
                missedAt,
                missedAt + LyricsLookupPolicy.NEGATIVE_TTL_MS * 3,
            ),
        )
    }

    @Test
    fun negativeNeverFreshForBogusTimestamps() {
        assertFalse(LyricsLookupPolicy.isNegativeFresh(0L, 1_000_000L))
        assertFalse(LyricsLookupPolicy.isNegativeFresh(-5L, 1_000_000L))
        // Clock moved backwards: age is negative, not fresh.
        assertFalse(LyricsLookupPolicy.isNegativeFresh(2_000_000L, 1_000_000L))
    }

    @Test
    fun negativeTtlIsSevenDays() {
        assertEquals(7L * 24 * 60 * 60 * 1000, LyricsLookupPolicy.NEGATIVE_TTL_MS)
    }

    @Test
    fun timingRankOrdersTrueWordSyncFirst() {
        assertTrue(
            LyricsLookupPolicy.timingRank(LyricTiming.WORD_SYNCED) >
                LyricsLookupPolicy.timingRank(LyricTiming.WORD_SYNCED_ESTIMATED),
        )
        assertTrue(
            LyricsLookupPolicy.timingRank(LyricTiming.WORD_SYNCED_ESTIMATED) >
                LyricsLookupPolicy.timingRank(LyricTiming.LINE_SYNCED),
        )
        assertTrue(
            LyricsLookupPolicy.timingRank(LyricTiming.LINE_SYNCED) >
                LyricsLookupPolicy.timingRank(LyricTiming.PLAIN),
        )
    }

    @Test
    fun upgradeOnlyForStrictlyBetterTiming() {
        val line = result(LyricTiming.LINE_SYNCED)
        val word = result(LyricTiming.WORD_SYNCED)
        val otherLine = result(LyricTiming.LINE_SYNCED)
        assertTrue(LyricsLookupPolicy.isUpgrade(line, word))
        assertTrue(LyricsLookupPolicy.isUpgrade(null, line))
        assertFalse(LyricsLookupPolicy.isUpgrade(word, line))
        assertFalse(LyricsLookupPolicy.isUpgrade(line, otherLine))
        assertFalse(LyricsLookupPolicy.isUpgrade(line, null))
    }

    @Test
    fun deadlineWinnerPrefersWordSynced() {
        val line = result(LyricTiming.LINE_SYNCED)
        val word = result(LyricTiming.WORD_SYNCED)
        val picked = LyricsLookupPolicy.pickDeadlineWinner(
            completedInOrder = listOf(line, word),
            prioritizeSyllableSync = true,
        )
        assertEquals(word, picked)
    }

    @Test
    fun deadlineWinnerTakesFirstTimedWhenNotPrioritizing() {
        val plain = result(LyricTiming.PLAIN)
        val line = result(LyricTiming.LINE_SYNCED)
        val picked = LyricsLookupPolicy.pickDeadlineWinner(
            completedInOrder = listOf(plain, line, null),
            prioritizeSyllableSync = false,
        )
        assertEquals(line, picked)
    }

    @Test
    fun deadlineWinnerFallsBackToFirstResultWhenPrioritizing() {
        val plain = result(LyricTiming.PLAIN)
        val line = result(LyricTiming.LINE_SYNCED)
        // Prioritizing syllable sync: no timed shortcut, so the first
        // completed result in priority order is the fallback.
        val picked = LyricsLookupPolicy.pickDeadlineWinner(
            completedInOrder = listOf(plain, line),
            prioritizeSyllableSync = true,
        )
        assertEquals(plain, picked)
    }

    @Test
    fun deadlineWinnerSkipsMissesAndKeepsFallback() {
        val fallback = result(LyricTiming.LINE_SYNCED)
        assertEquals(
            fallback,
            LyricsLookupPolicy.pickDeadlineWinner(
                completedInOrder = listOf(null, null),
                prioritizeSyllableSync = false,
                fallback = fallback,
            ),
        )
        assertNull(
            LyricsLookupPolicy.pickDeadlineWinner(
                completedInOrder = listOf(null, null),
                prioritizeSyllableSync = false,
            ),
        )
        assertNull(
            LyricsLookupPolicy.pickDeadlineWinner(
                completedInOrder = emptyList(),
                prioritizeSyllableSync = false,
            ),
        )
    }
}
