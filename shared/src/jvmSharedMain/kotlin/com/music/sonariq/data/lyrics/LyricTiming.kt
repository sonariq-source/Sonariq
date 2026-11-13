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
     * Word timings exist and were really measured — but measured from the
     * track's own audio by the AI alignment fallback (see [AiLyricAligner])
     * rather than supplied by a provider. They are measurements, not
     * interpolations, so the line lights up word by word exactly like
     * [WORD_SYNCED]; they rank below it because a provider's own timings
     * are the authority for its catalogue entry, and a result carrying
     * them stays provisional so the hunt for provider timings goes on and
     * replaces them when it lands.
     */
    WORD_SYNCED_AI_MEASURED,

    /**
     * Word timings exist and were really measured — and so were the
     * words themselves: no provider had lyrics for the track at all, so
     * a transcription of the track's own audio is standing in as the
     * lyric (see [TranscriptionLines]). The timings are measurements of
     * the actual singing, so the line lights up word by word exactly
     * like [WORD_SYNCED_AI_MEASURED]; but the *text* is a machine
     * hearing, mishearings and all, so a result carrying them ranks
     * below even an AI-measured one in the cache's store guards — a
     * provider's text, whenever one turns up, replaces a transcription
     * outright — and it stays provisional the same way, so the hunt
     * for provider lyrics goes on and replaces these when it lands.
     */
    WORD_SYNCED_AI_TRANSCRIBED,

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
 * Provider word timings outrank AI-measured ones, and both outrank
 * interpolated ones: a result earns WORD_SYNCED on any single
 * provider-measured word; failing that, WORD_SYNCED_AI_TRANSCRIBED on
 * any single word a transcription of the track's own audio supplied
 * (words *and* text heard from the audio — see
 * [LyricTiming.WORD_SYNCED_AI_TRANSCRIBED]); failing that,
 * WORD_SYNCED_AI_MEASURED on any single word the AI alignment fallback
 * measured from the track's own audio; and only a result with
 * *nothing* measured settles for WORD_SYNCED_ESTIMATED. AI timings are
 * real measurements from the track's audio, ranked below provider
 * timings and kept provisional (see [LyricsLookupPolicy.tierOf]).
 *
 * Only the top-level lines' own flags are read — a background line's words
 * never lift the result on their own, exactly as before.
 */
fun List<LyricLine>.lyricTiming(): LyricTiming {
    if (any {
            it.isWordSynced && !it.wordsEstimated && !it.wordsAiMeasured &&
                !it.wordsAiTranscribed
        }
    ) {
        return LyricTiming.WORD_SYNCED
    }
    if (any { it.isWordSynced && it.wordsAiTranscribed && !it.wordsEstimated }) {
        return LyricTiming.WORD_SYNCED_AI_TRANSCRIBED
    }
    if (any { it.isWordSynced && it.wordsAiMeasured && !it.wordsEstimated }) {
        return LyricTiming.WORD_SYNCED_AI_MEASURED
    }
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

/**
 * These lines with every word timing taken back out, whatever measured
 * them — a provider's, the AI alignment's, an estimate's.
 *
 * Any line carrying words — including a nested background line, which
 * carries its own — is copied with its words emptied and both measured
 * flags cleared, so what remains is the honest line sync underneath.
 * Lines with no words are untouched, and a list with no words anywhere
 * is returned as-is. No timings are created here: this only removes
 * ones a caller has judged cannot belong to the track they would be
 * shown against — see [LyricsValidation] — leaving the words of the
 * song itself in place, honestly line-timed rather than confidently
 * word-timed to a different recording.
 */
fun List<LyricLine>.withoutWordTimings(): List<LyricLine> {
    fun LyricLine.hasWords(): Boolean =
        words.isNotEmpty() || background?.hasWords() == true

    fun LyricLine.stripped(): LyricLine {
        val strippedBackground = background?.stripped()
        val own = if (words.isNotEmpty()) {
            copy(
                words = emptyList(),
                wordsEstimated = false,
                wordsAiMeasured = false,
                wordsAiTranscribed = false,
            )
        } else {
            this
        }
        return if (strippedBackground === background) {
            own
        } else {
            own.copy(background = strippedBackground)
        }
    }

    if (none { it.hasWords() }) return this
    return map { it.stripped() }
}

/**
 * These lines with their word timings made safe to show, or taken out
 * where they cannot be made safe.
 *
 * Word timings reach the parsers from files and services this app does
 * not control, and nothing downstream re-checks them: a word stamped
 * before the song starts, a line whose words run backwards, a span that
 * ends where it begins would each be drawn exactly as written. The
 * rules here are the ones a timing must satisfy to be a timing at all:
 *
 *  - No negative stamps: a line's own stamp is held at zero, and a word
 *    may not start before the song does.
 *  - Words arrive in the order they are sung: their starts do not run
 *    backwards against each other. (An end reaching past the *next*
 *    word's start is not a violation — a held word runs until the next
 *    begins throughout this app's own measured output.)
 *  - No word starts at or after the track's end, and where the track's
 *    length is known ([durationMs] > 0) a word's end is held at it: a
 *    provider rounding its last word a breath past the file is a
 *    rounding, not a different song.
 *  - A word whose end does not run past its start — a degenerate span
 *    some word-timed formats genuinely emit for a fragment — is
 *    repaired, not believed: it runs until the nearest honest bound
 *    (the next word's start, the line's stated end, the next line's
 *    stamp), and where no bound leaves it room the word alone is
 *    dropped. No span is ever invented past a bound the data states.
 *  - A line's stated end is held inside the track the same way, and
 *    dropped where it no longer runs past the line's own stamp.
 *  - A word's syllables must sit inside the word; one that does not
 *    costs the word its syllable split, never the word itself.
 *
 * A line with a word that breaks a rule it cannot be repaired under —
 * a negative start, an out-of-order start, a start past the track's
 * end — loses **all** its words, not just the offender's: those are
 * the marks of a timing track that was never this song's, and a
 * partial word set would leave the survivors' spans pointing at the
 * wrong places in the text. What remains is the line itself, honestly
 * line-timed. Lines whose words all satisfy the rules, and lines with
 * no words, pass through untouched — the same list instance, where
 * nothing changed anywhere.
 */
fun List<LyricLine>.withValidWordTimings(durationMs: Long = 0L): List<LyricLine> {
    fun LyricLine.sanitized(nextLineStartMs: Long?): LyricLine {
        val cleanBackground = background?.sanitized(nextLineStartMs)
        val cleanStart = maxOf(0L, timeMs)
        var cleanEnd = sungUntilMs
        if (cleanEnd != null) {
            if (durationMs > 0L) cleanEnd = minOf(cleanEnd, durationMs)
            if (cleanEnd <= cleanStart) cleanEnd = null
        }
        var cleanWords = words
        if (words.isNotEmpty()) {
            var previousStart = Long.MIN_VALUE
            var broken = false
            for (word in words) {
                if (word.startMs < 0L || word.startMs < previousStart ||
                    (durationMs > 0L && word.startMs >= durationMs)
                ) {
                    broken = true
                    break
                }
                previousStart = word.startMs
            }
            cleanWords = if (broken) {
                emptyList()
            } else {
                var changed = false
                val repaired = ArrayList<LyricWord>(words.size)
                words.forEachIndexed { index, word ->
                    var end = word.endMs
                    if (durationMs > 0L) end = minOf(end, durationMs)
                    if (end <= word.startMs) {
                        val bound = listOfNotNull(
                            words.getOrNull(index + 1)?.startMs,
                            cleanEnd,
                            nextLineStartMs,
                        ).filter { it > word.startMs }.minOrNull()
                        if (bound == null) {
                            // No honest bound leaves this word room:
                            // it alone is dropped.
                            changed = true
                            return@forEachIndexed
                        }
                        end = bound
                        if (durationMs > 0L) end = minOf(end, durationMs)
                    }
                    val syllables = if (
                        word.syllables.any {
                            it.endMs <= it.startMs || it.startMs < word.startMs || it.endMs > end
                        }
                    ) {
                        emptyList()
                    } else {
                        word.syllables
                    }
                    if (end == word.endMs && syllables === word.syllables) {
                        repaired += word
                    } else {
                        changed = true
                        repaired += word.copy(endMs = end, syllables = syllables)
                    }
                }
                if (changed) repaired else words
            }
        }
        val wordsDropped = words.isNotEmpty() && cleanWords.isEmpty()
        if (cleanStart == timeMs && cleanEnd == sungUntilMs &&
            cleanWords === words && cleanBackground === background
        ) {
            return this
        }
        return copy(
            timeMs = cleanStart,
            words = cleanWords,
            sungUntilMs = cleanEnd,
            background = cleanBackground,
            wordsEstimated = if (wordsDropped) false else wordsEstimated,
            wordsAiMeasured = if (wordsDropped) false else wordsAiMeasured,
            // Carried like the other two marks, and cleared with them
            // when the words it describes are the ones dropped: a
            // rebuild that silently lost the mark would re-price a
            // transcription's timings as a provider's own.
            wordsAiTranscribed = if (wordsDropped) false else wordsAiTranscribed,
        )
    }

    val sanitized = mapIndexed { index, line ->
        line.sanitized(getOrNull(index + 1)?.timeMs)
    }
    return if (sanitized.indices.all { sanitized[it] === this[it] }) this else sanitized
}

/**
 * These lines with every AI-measured word timing taken back out.
 *
 * The counterpart of [withoutEstimatedWords] for the other kind of word
 * timing that is not a provider's: any line whose
 * [LyricLine.wordsAiMeasured] is set — including a nested background line,
 * which carries its own words and its own flag — is copied with its words
 * emptied and the flag cleared, so what remains is the honest line sync
 * the measurement was derived from. Lines with provider word timings, and
 * lines with no words at all, are untouched, and a list with nothing
 * AI-measured in it is returned as-is.
 *
 * This exists to retire measurements made by an older alignment version:
 * [LyricsRepository] strips them on the way out of the disk cache when
 * the entry was stamped by a version [AiLyricAligner] has since moved
 * past, so a superseded measurement is never shown again and the track
 * is offered for a fresh measurement instead. No timings are created
 * here: this only removes ones a newer aligner no longer stands behind.
 */
fun List<LyricLine>.withoutAiMeasuredWords(): List<LyricLine> {
    fun LyricLine.hasAiMeasuredWords(): Boolean =
        wordsAiMeasured || background?.hasAiMeasuredWords() == true

    fun LyricLine.stripped(): LyricLine {
        val strippedBackground = background?.stripped()
        val own = if (wordsAiMeasured) {
            copy(words = emptyList(), wordsAiMeasured = false)
        } else {
            this
        }
        return if (strippedBackground === background) {
            own
        } else {
            own.copy(background = strippedBackground)
        }
    }

    if (none { it.hasAiMeasuredWords() }) return this
    return map { it.stripped() }
}
