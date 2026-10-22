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
     * rather than measured by a provider. The interpolator is gone; this
     * value now exists only so legacy cached or downloaded data carrying
     * estimated timings is recognised and stripped by the repository —
     * see [withoutEstimatedWords] — instead of being shown as word sync.
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

/**
 * These lines with every estimated word timing taken back out.
 *
 * Any line whose [LyricLine.wordsEstimated] is set — including a nested
 * background line, which carries its own words and its own flag — is copied
 * with its words emptied and the flag cleared, so what remains is the honest
 * line sync the estimate was derived from. Lines with measured word timings,
 * and lines with no words at all, are untouched, and a list with nothing
 * estimated in it is returned as-is. No timings are created here: this only
 * removes ones that were never measured.
 */
fun List<LyricLine>.withoutEstimatedWords(): List<LyricLine> {
    fun LyricLine.hasEstimatedWords(): Boolean =
        wordsEstimated || background?.hasEstimatedWords() == true

    fun LyricLine.stripped(): LyricLine {
        val strippedBackground = background?.stripped()
        val own = if (wordsEstimated) {
            copy(words = emptyList(), wordsEstimated = false)
        } else {
            this
        }
        return if (strippedBackground === background) {
            own
        } else {
            own.copy(background = strippedBackground)
        }
    }

    if (none { it.hasEstimatedWords() }) return this
    return map { it.stripped() }
}
