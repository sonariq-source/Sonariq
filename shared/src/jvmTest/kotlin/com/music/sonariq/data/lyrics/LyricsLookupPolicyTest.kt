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
            timing == LyricTiming.WORD_SYNCED_AI_MEASURED -> listOf(
                LyricLine(
                    timeMs = 5_000,
                    text = "verse",
                    words = listOf(LyricWord(5_000, 5_600, "verse")),
                    sungUntilMs = 8_000,
                    wordsAiMeasured = true,
                ),
            )
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

    /**
     * A result from an explicit [source], with syllable timings on its word
     * when [withSyllables] — the two facts [LyricsLookupPolicy.tierOf] ranks
     * on, which the source-blind [result] helper above cannot express.
     */
    private fun sourcedResult(
        source: LyricsSource,
        timing: LyricTiming,
        withSyllables: Boolean = false,
    ): LyricsRepository.Result {
        val lines = when (timing) {
            LyricTiming.WORD_SYNCED, LyricTiming.WORD_SYNCED_ESTIMATED,
            LyricTiming.WORD_SYNCED_AI_MEASURED -> listOf(
                LyricLine(
                    timeMs = 5_000,
                    text = "verse",
                    words = listOf(
                        LyricWord(
                            startMs = 5_000,
                            endMs = 5_600,
                            text = "verse",
                            syllables = if (withSyllables) {
                                listOf(
                                    LyricSyllable(5_000, 5_300, 0, 2),
                                    LyricSyllable(5_300, 5_600, 2, 5),
                                )
                            } else {
                                emptyList()
                            },
                        ),
                    ),
                    sungUntilMs = 8_000,
                    wordsEstimated = timing == LyricTiming.WORD_SYNCED_ESTIMATED,
                    wordsAiMeasured = timing == LyricTiming.WORD_SYNCED_AI_MEASURED,
                ),
            )
            LyricTiming.LINE_SYNCED -> listOf(
                LyricLine(5_000, "verse", sungUntilMs = 8_000),
            )
            LyricTiming.PLAIN -> listOf(LyricLine(0, "verse"))
        }
        return LyricsRepository.Result(source, lines, timing)
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
    fun ttmlSourcesAreExactlyTheTtmlCarriers() {
        assertEquals(
            setOf(
                LyricsSource.LRC_RED,
                LyricsSource.BINI_LYRICS,
                LyricsSource.BETTER_LYRICS,
                LyricsSource.BETTER_LYRICS_PORTATO,
                LyricsSource.PAXSENIX,
                LyricsSource.PAXSENIX_SPOTIFY,
                LyricsSource.PAXSENIX_MUSIXMATCH,
                LyricsSource.UNISON,
            ),
            LyricsLookupPolicy.TTML_SOURCES,
        )
    }

    @Test
    fun tierOrdersTtmlSyllableThenTtmlWordThenOtherWordThenLine() {
        val ttmlSyllable = sourcedResult(
            LyricsSource.LRC_RED, LyricTiming.WORD_SYNCED, withSyllables = true,
        )
        val ttmlWord = sourcedResult(LyricsSource.LRC_RED, LyricTiming.WORD_SYNCED)
        val otherSyllable = sourcedResult(
            LyricsSource.LYRICS_PLUS, LyricTiming.WORD_SYNCED, withSyllables = true,
        )
        val otherWord = sourcedResult(LyricsSource.LYRICS_PLUS, LyricTiming.WORD_SYNCED)
        val line = sourcedResult(LyricsSource.LRCLIB, LyricTiming.LINE_SYNCED)

        assertEquals(0, LyricsLookupPolicy.tierOf(ttmlSyllable))
        assertEquals(1, LyricsLookupPolicy.tierOf(ttmlWord))
        assertEquals(2, LyricsLookupPolicy.tierOf(otherSyllable))
        assertEquals(3, LyricsLookupPolicy.tierOf(otherWord))
        assertEquals(5, LyricsLookupPolicy.tierOf(line))
        assertTrue(
            LyricsLookupPolicy.tierOf(ttmlSyllable) < LyricsLookupPolicy.tierOf(ttmlWord),
        )
        assertTrue(
            LyricsLookupPolicy.tierOf(ttmlWord) < LyricsLookupPolicy.tierOf(otherSyllable),
        )
        assertTrue(
            LyricsLookupPolicy.tierOf(otherSyllable) < LyricsLookupPolicy.tierOf(otherWord),
        )
        assertTrue(
            LyricsLookupPolicy.tierOf(otherWord) < LyricsLookupPolicy.tierOf(line),
        )
    }

    @Test
    fun tierTreatsEstimatedAndPlainAsProvisional() {
        val estimated = sourcedResult(
            LyricsSource.LYRICS_PLUS, LyricTiming.WORD_SYNCED_ESTIMATED,
        )
        val plain = sourcedResult(LyricsSource.GENIUS, LyricTiming.PLAIN)
        val line = sourcedResult(LyricsSource.LRCLIB, LyricTiming.LINE_SYNCED)

        assertEquals(5, LyricsLookupPolicy.tierOf(estimated))
        assertEquals(5, LyricsLookupPolicy.tierOf(plain))
        assertEquals(5, LyricsLookupPolicy.tierOf(line))
        assertTrue(LyricsLookupPolicy.isProvisional(estimated))
        assertFalse(LyricsLookupPolicy.isFinal(estimated))
        assertTrue(LyricsLookupPolicy.isProvisional(line))
        assertTrue(LyricsLookupPolicy.isProvisional(plain))
        assertFalse(LyricsLookupPolicy.isFinal(line))
        assertFalse(LyricsLookupPolicy.isFinal(plain))
    }

    @Test
    fun tierPlacesAiMeasuredBetweenProviderWordSyncAndLineSync() {
        val ai = sourcedResult(
            LyricsSource.LRCLIB, LyricTiming.WORD_SYNCED_AI_MEASURED,
        )
        val otherWord = sourcedResult(LyricsSource.LYRICS_PLUS, LyricTiming.WORD_SYNCED)
        val line = sourcedResult(LyricsSource.LRCLIB, LyricTiming.LINE_SYNCED)

        // Tier 4 of its own: above line sync, below every provider tier.
        assertEquals(4, LyricsLookupPolicy.tierOf(ai))
        assertTrue(LyricsLookupPolicy.tierOf(otherWord) < LyricsLookupPolicy.tierOf(ai))
        assertTrue(LyricsLookupPolicy.tierOf(ai) < LyricsLookupPolicy.tierOf(line))
        // Real measurements worth showing — but provisional, so the hunt
        // for provider timings continues past them.
        assertTrue(LyricsLookupPolicy.isProvisional(ai))
        assertFalse(LyricsLookupPolicy.isFinal(ai))
    }

    @Test
    fun lyricTimingClassifiesAiMeasuredLines() {
        val aiLines = listOf(
            LyricLine(
                timeMs = 5_000,
                text = "verse",
                words = listOf(LyricWord(5_000, 5_600, "verse")),
                wordsAiMeasured = true,
            ),
        )
        assertEquals(LyricTiming.WORD_SYNCED_AI_MEASURED, aiLines.lyricTiming())

        // One provider-measured line outranks the AI-measured ones.
        val mixed = aiLines + LyricLine(
            timeMs = 9_000,
            text = "chorus",
            words = listOf(LyricWord(9_000, 9_600, "chorus")),
        )
        assertEquals(LyricTiming.WORD_SYNCED, mixed.lyricTiming())

        // A line flagged both estimated and AI-measured reports estimated:
        // the AI tier is only for words that are nothing but measured.
        val both = listOf(
            LyricLine(
                timeMs = 5_000,
                text = "verse",
                words = listOf(LyricWord(5_000, 5_600, "verse")),
                wordsEstimated = true,
                wordsAiMeasured = true,
            ),
        )
        assertEquals(LyricTiming.WORD_SYNCED_ESTIMATED, both.lyricTiming())
    }

    @Test
    fun everyWordSyncedResultIsFinal() {
        val wordSyncedResults = listOf(
            sourcedResult(
                LyricsSource.LRC_RED, LyricTiming.WORD_SYNCED, withSyllables = true,
            ),
            sourcedResult(LyricsSource.BINI_LYRICS, LyricTiming.WORD_SYNCED),
            sourcedResult(
                LyricsSource.LYRICS_PLUS, LyricTiming.WORD_SYNCED, withSyllables = true,
            ),
            sourcedResult(LyricsSource.KUGOU, LyricTiming.WORD_SYNCED),
        )
        for (wordSynced in wordSyncedResults) {
            assertTrue(LyricsLookupPolicy.isFinal(wordSynced), "$wordSynced should be final")
            assertFalse(
                LyricsLookupPolicy.isProvisional(wordSynced),
                "$wordSynced should not be provisional",
            )
        }
    }

    @Test
    fun timingRankTreatsEstimatedAsLineLevel() {
        // Estimated word timings are not real word sync: they rank with line
        // sync, below true word sync and above plain text. AI-measured
        // timings rank between the two: real measurements, but not a
        // provider's.
        assertEquals(3, LyricsLookupPolicy.timingRank(LyricTiming.WORD_SYNCED))
        assertEquals(2, LyricsLookupPolicy.timingRank(LyricTiming.WORD_SYNCED_AI_MEASURED))
        assertEquals(1, LyricsLookupPolicy.timingRank(LyricTiming.WORD_SYNCED_ESTIMATED))
        assertEquals(1, LyricsLookupPolicy.timingRank(LyricTiming.LINE_SYNCED))
        assertEquals(0, LyricsLookupPolicy.timingRank(LyricTiming.PLAIN))
        assertTrue(
            LyricsLookupPolicy.timingRank(LyricTiming.WORD_SYNCED) >
                LyricsLookupPolicy.timingRank(LyricTiming.WORD_SYNCED_AI_MEASURED),
        )
        assertTrue(
            LyricsLookupPolicy.timingRank(LyricTiming.WORD_SYNCED_AI_MEASURED) >
                LyricsLookupPolicy.timingRank(LyricTiming.LINE_SYNCED),
        )
        assertEquals(
            LyricsLookupPolicy.timingRank(LyricTiming.LINE_SYNCED),
            LyricsLookupPolicy.timingRank(LyricTiming.WORD_SYNCED_ESTIMATED),
        )
        assertTrue(
            LyricsLookupPolicy.timingRank(LyricTiming.WORD_SYNCED) >
                LyricsLookupPolicy.timingRank(LyricTiming.WORD_SYNCED_ESTIMATED),
        )
        assertTrue(
            LyricsLookupPolicy.timingRank(LyricTiming.WORD_SYNCED_ESTIMATED) >
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
    fun upgradeFollowsTierBeforeTimingRank() {
        val line = sourcedResult(LyricsSource.LRCLIB, LyricTiming.LINE_SYNCED)
        val ttmlWord = sourcedResult(LyricsSource.LRC_RED, LyricTiming.WORD_SYNCED)
        val otherWord = sourcedResult(LyricsSource.LYRICS_PLUS, LyricTiming.WORD_SYNCED)
        val ttmlSyllable = sourcedResult(
            LyricsSource.LRC_RED, LyricTiming.WORD_SYNCED, withSyllables = true,
        )
        // A line-synced result upgrades to a TTML word-synced one.
        assertTrue(LyricsLookupPolicy.isUpgrade(line, ttmlWord))
        // Same timing, worse tier: a TTML word result is never replaced by an
        // other-source word result.
        assertFalse(LyricsLookupPolicy.isUpgrade(ttmlWord, otherWord))
        // An other-source word result upgrades to a TTML syllable one.
        assertTrue(LyricsLookupPolicy.isUpgrade(otherWord, ttmlSyllable))
    }

    @Test
    fun aiResultUpgradesLineButYieldsToProviderWordSync() {
        val line = sourcedResult(LyricsSource.LRCLIB, LyricTiming.LINE_SYNCED)
        val estimated = sourcedResult(
            LyricsSource.LYRICS_PLUS, LyricTiming.WORD_SYNCED_ESTIMATED,
        )
        val ai = sourcedResult(
            LyricsSource.LRCLIB, LyricTiming.WORD_SYNCED_AI_MEASURED,
        )
        val providerWord = sourcedResult(LyricsSource.LYRICS_PLUS, LyricTiming.WORD_SYNCED)
        val otherLine = sourcedResult(LyricsSource.LRCLIB, LyricTiming.LINE_SYNCED)

        // An AI-measured result upgrades a line-synced (or estimated) one.
        assertTrue(LyricsLookupPolicy.isUpgrade(line, ai))
        assertTrue(LyricsLookupPolicy.isUpgrade(estimated, ai))
        // A provider word-synced result upgrades an AI-measured one: the
        // AI timings were only ever keeping the seat warm.
        assertTrue(LyricsLookupPolicy.isUpgrade(ai, providerWord))
        // And nothing weaker takes the seat back.
        assertFalse(LyricsLookupPolicy.isUpgrade(ai, otherLine))
        assertFalse(LyricsLookupPolicy.isUpgrade(ai, estimated))
        assertFalse(LyricsLookupPolicy.isUpgrade(ai, ai))
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
    fun deadlineWinnerPrefersWordResultOverEarlierLineResult() {
        val line = sourcedResult(LyricsSource.LRCLIB, LyricTiming.LINE_SYNCED)
        val otherWord = sourcedResult(LyricsSource.LYRICS_PLUS, LyricTiming.WORD_SYNCED)
        val picked = LyricsLookupPolicy.pickDeadlineWinner(
            completedInOrder = listOf(line, otherWord),
            prioritizeSyllableSync = true,
        )
        assertEquals(otherWord, picked)
    }

    @Test
    fun deadlineWinnerPrefersSyllableResultOverWordResult() {
        val ttmlWord = sourcedResult(LyricsSource.LRC_RED, LyricTiming.WORD_SYNCED)
        val ttmlSyllable = sourcedResult(
            LyricsSource.LRC_RED, LyricTiming.WORD_SYNCED, withSyllables = true,
        )
        val picked = LyricsLookupPolicy.pickDeadlineWinner(
            completedInOrder = listOf(ttmlWord, ttmlSyllable),
            prioritizeSyllableSync = true,
        )
        assertEquals(ttmlSyllable, picked)
    }

    @Test
    fun deadlineWinnerSurfacesLineOnlyResultAsProvisional() {
        val line = sourcedResult(LyricsSource.LRCLIB, LyricTiming.LINE_SYNCED)
        val picked = LyricsLookupPolicy.pickDeadlineWinner(
            completedInOrder = listOf(line),
            prioritizeSyllableSync = true,
        )
        assertEquals(line, picked)
        // It still surfaces for instant display — it is just not final, so the
        // hunt for real word sync may continue past it.
        val winner = checkNotNull(picked)
        assertTrue(LyricsLookupPolicy.isProvisional(winner))
        assertFalse(LyricsLookupPolicy.isFinal(winner))
    }

    @Test
    fun deadlineWinnerPrefersAiResultOverLineResult() {
        val line = sourcedResult(LyricsSource.LRCLIB, LyricTiming.LINE_SYNCED)
        val ai = sourcedResult(
            LyricsSource.LRCLIB, LyricTiming.WORD_SYNCED_AI_MEASURED,
        )
        val picked = LyricsLookupPolicy.pickDeadlineWinner(
            completedInOrder = listOf(line, ai),
            prioritizeSyllableSync = true,
        )
        assertEquals(ai, picked)
        // It surfaces for instant display — but stays provisional, so the
        // race for provider timings continues past the deadline.
        val winner = checkNotNull(picked)
        assertTrue(LyricsLookupPolicy.isProvisional(winner))
        assertFalse(LyricsLookupPolicy.isFinal(winner))
    }

    @Test
    fun deadlineWinnerKeepsPriorityOrderWithinTierWhenNotPrioritizing() {
        // Plain and line-synced results share tier 5, so within the tier the
        // priority (completion) order decides — there is no timed shortcut.
        val plain = result(LyricTiming.PLAIN)
        val line = result(LyricTiming.LINE_SYNCED)
        val picked = LyricsLookupPolicy.pickDeadlineWinner(
            completedInOrder = listOf(plain, line, null),
            prioritizeSyllableSync = false,
        )
        assertEquals(plain, picked)
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

    @Test
    fun withoutEstimatedWordsStripsEstimatedWordsAndClearsTheFlag() {
        val estimated = LyricLine(
            timeMs = 5_000,
            text = "verse",
            words = listOf(LyricWord(5_000, 5_600, "verse")),
            sungUntilMs = 8_000,
            background = LyricLine(
                timeMs = 5_500,
                text = "(ooh)",
                words = listOf(LyricWord(5_500, 6_100, "(ooh)")),
                wordsEstimated = true,
            ),
            wordsEstimated = true,
        )
        val measured = LyricLine(
            timeMs = 9_000,
            text = "chorus",
            words = listOf(LyricWord(9_000, 9_600, "chorus")),
            sungUntilMs = 12_000,
        )

        val cleaned = listOf(estimated, measured).withoutEstimatedWords()

        // The estimated line loses its invented words — including the nested
        // background line's — and the flag that marked them as estimated.
        assertTrue(cleaned[0].words.isEmpty())
        assertFalse(cleaned[0].wordsEstimated)
        val background = checkNotNull(cleaned[0].background)
        assertTrue(background.words.isEmpty())
        assertFalse(background.wordsEstimated)
        // A genuinely measured line passes through untouched.
        assertEquals(measured, cleaned[1])
        // On its own, the stripped line honestly reports line sync.
        assertEquals(
            LyricTiming.LINE_SYNCED,
            listOf(estimated).withoutEstimatedWords().lyricTiming(),
        )
    }
}
