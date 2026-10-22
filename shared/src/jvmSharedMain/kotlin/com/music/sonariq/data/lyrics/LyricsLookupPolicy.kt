package com.music.sonariq.data.lyrics

/**
 * The pure decisions behind the faster lyrics lookup, kept apart from
 * [LyricsRepository] so they can be tested without a network or a disk.
 *
 * Nothing here fetches, caches or invents anything: it only ranks results
 * that already passed the repository's matching, and ages negative-cache
 * entries. Word timings are never created here — a result is word-synced
 * only when its provider supplied true word timings, exactly as
 * [lyricTiming] already reports it.
 */
object LyricsLookupPolicy {

    /**
     * How long the player's lookup waits for the full priority race before
     * surfacing the best already-matched result. Straggler providers keep
     * racing in the background of the same lookup; if a better result lands
     * later it upgrades in place. Background prefetch lookups pass no
     * deadline and run to completion as before.
     */
    const val PLAYER_DEADLINE_MS = 6_000L

    /** How long a genuine all-providers miss is remembered before a re-race. */
    const val NEGATIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** The most tracks one list (Home shelf, detail page) warms at once. */
    const val LIST_PREFETCH_CAP = 40

    /**
     * Strength of a matched result for deadline and upgrade decisions.
     * True word timings outrank estimated ones, which outrank line sync,
     * which outranks plain text — the same order [lyricTiming] implies.
     */
    fun timingRank(timing: LyricTiming): Int = when (timing) {
        LyricTiming.WORD_SYNCED -> 3
        LyricTiming.WORD_SYNCED_ESTIMATED -> 2
        LyricTiming.LINE_SYNCED -> 1
        LyricTiming.PLAIN -> 0
    }

    /** True when [candidate] is strictly better timed than [current]. */
    fun isUpgrade(
        current: LyricsRepository.Result?,
        candidate: LyricsRepository.Result?,
    ): Boolean {
        candidate ?: return false
        current ?: return true
        return timingRank(candidate.timing) > timingRank(current.timing)
    }

    /**
     * The best of the results that have already completed, in race
     * (priority) order — what the deadline surfaces.
     *
     * Mirrors the race's own winner rules so the deadline can never surface
     * something the full race would have rejected: a word-synced result
     * wins outright; otherwise, when syllable sync is not being prioritized,
     * the first timed result in priority order wins; failing both, the
     * first result of any kind is the fallback. Null results (misses) are
     * skipped, and a result that has not completed yet is simply absent
     * from [completedInOrder] — never guessed at.
     */
    fun pickDeadlineWinner(
        completedInOrder: List<LyricsRepository.Result?>,
        prioritizeSyllableSync: Boolean,
        fallback: LyricsRepository.Result? = null,
    ): LyricsRepository.Result? {
        var best = fallback
        for (result in completedInOrder) {
            result ?: continue
            if (result.lines.any { it.isWordSynced }) return result
            if (!prioritizeSyllableSync && result.lines.any { it.timeMs > 0 }) return result
            if (best == null) best = result
        }
        return best
    }

    /** Whether a remembered miss at [missedAtMs] is still fresh at [nowMs]. */
    fun isNegativeFresh(
        missedAtMs: Long,
        nowMs: Long,
        ttlMs: Long = NEGATIVE_TTL_MS,
    ): Boolean {
        if (missedAtMs <= 0L) return false
        val age = nowMs - missedAtMs
        return age in 0 until ttlMs
    }
}
