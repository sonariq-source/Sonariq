package com.music.sonariq.data.lyrics

/**
 * How precisely lyrics are timed.
 *
 * The player picks its animation off this: word-synced lines light up a word
 * at a time, line-synced ones lift whole, plain ones just draw. Stored with a
 * cached result so a cache hit reads exactly like the lookup that produced it.
 */
enum class LyricTiming {
    WORD_SYNCED,

    /**
     * Word timings exist, but they were interpolated from line windows
     * ([withInterpolatedWordTimings]) rather than measured by a provider.
     * Renders like [WORD_SYNCED]; reports itself honestly everywhere else.
     */
    WORD_SYNCED_ESTIMATED,
    LINE_SYNCED,
    PLAIN
}

/**
 * Which bucket these lines fall into — the same predicates
 * [LyricsRepository.lyrics] already uses to pick its winner.
 *
 * Measured word timings outrank interpolated ones: a result earns WORD_SYNCED
 * on any single genuinely measured word, and only a result with *nothing*
 * measured settles for WORD_SYNCED_ESTIMATED.
 */
fun List<LyricLine>.lyricTiming(): LyricTiming {
    if (any { it.isWordSynced && !it.wordsEstimated }) return LyricTiming.WORD_SYNCED
    if (any { it.isWordSynced }) return LyricTiming.WORD_SYNCED_ESTIMATED
    if (any { it.timeMs > 0 }) return LyricTiming.LINE_SYNCED
    return LyricTiming.PLAIN
}
