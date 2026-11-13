package com.music.sonariq.data.lyrics

/**
 * Lyric lines built out of a transcription of the track's own audio —
 * the AI lyrics path, for a track no provider had words for.
 *
 * The alignment ([AiLyricAligner]) borrows a transcription's timings
 * for a provider's text and throws the transcription's words away.
 * That is the right trade where a provider's text exists. Where none
 * does — every source missed, or answered with a placeholder (see
 * [PlaceholderLyrics]) — the choice is between the transcription's
 * hearing and no lyrics at all, and this is the hearing, taken on
 * honest terms:
 *
 *  - Every word on every line is a word the transcription reported,
 *    with the start and end *it* measured. Nothing is interpolated,
 *    spread evenly, or filled in: a word the service did not hear is
 *    not here, and a gap in the hearing is a gap in the lines.
 *  - The text is the hearing's own, mishearings and all. It is never
 *    corrected against the track's title, its artist, or what the
 *    words "should" be — the moment a line is edited toward what a
 *    model expects to hear, it stops being a transcription.
 *  - The result is marked for what it is, on every line
 *    ([LyricLine.wordsAiTranscribed]) and in its timing
 *    ([LyricTiming.WORD_SYNCED_AI_TRANSCRIBED]), so it ranks below
 *    every provider result and below AI-measured provider text, stays
 *    provisional, and is replaced the moment a provider answers.
 *
 * Grouping is the only judgement exercised here, and it changes no
 * timing: words are grouped into lines at the transcription's own
 * segment boundaries where it offered them, and at the natural pauses
 * in the singing where it did not — a breath between phrases is a
 * line break in every lyric sheet ever written. Within a segment, a
 * pause just as long breaks the line too, and a runaway segment is
 * capped at a readable line length; a break moves no word.
 *
 * A hearing is not always a song. The gates at the head of [build]
 * refuse the ones that cannot honestly be shown: too few words to be
 * a lyric at all, or a hearing clumped into one corner of the track —
 * the chorus caught and the verses missed — which shown as "the
 * lyrics" would be a fragment wearing the whole song's name. A
 * refused hearing builds nothing, and the track keeps its honest
 * no-lyrics state.
 *
 * Pure: no I/O, no clock. The caller sanitises what is built here
 * through the same [withValidWordTimings] every provider result
 * passes through, and stores it through
 * [LyricsRepository.storeAiTranscription], whose guards are the
 * final word on where it may land.
 */
object TranscriptionLines {

    /** The fewest heard words that can honestly be called a song's lyrics. */
    const val MIN_WORDS = 8

    /**
     * How much of the track the hearing must span, as a share — the
     * span from the first word's start to the last word's end against
     * the track's duration, judged as span × [MIN_SPAN_SHARE_DENOMINATOR]
     * ≥ duration. A fifth is deliberately undemanding: intros and
     * outros are real, and a ballad's words may honestly occupy a
     * minority of its runtime. What it refuses is the fragment — a
     * hearing that caught one passage and nothing else.
     */
    const val MIN_SPAN_SHARE_DENOMINATOR = 5L

    /** The span a hearing must cover when the track's duration is unknown. */
    const val MIN_SPAN_MS = 10_000L

    /** A silence between two words at least this long is a line break. */
    const val LINE_PAUSE_MS = 800L

    /** The most words one built line carries; past it, the line breaks anyway. */
    const val MAX_WORDS_PER_LINE = 12

    /**
     * The lines a transcription's hearing makes, or null when the
     * hearing fails the gates above and must not be shown.
     *
     * [words] and [segments] are one transcription's output (see
     * [Transcription]); [durationMs] is the track's length, or 0 where
     * it is not known — the span gate then falls back to
     * [MIN_SPAN_MS] rather than a share. Unusable tokens — blank text,
     * a span that does not run forward, a start before the song — are
     * dropped before anything is judged, as the client's parser drops
     * them; the survivors are taken in start order whatever order they
     * arrived in.
     */
    fun build(
        words: List<TranscribedWord>,
        segments: List<TranscribedSegment>,
        durationMs: Long,
    ): List<LyricLine>? {
        val usable = words
            .filter { it.text.isNotBlank() && it.endMs > it.startMs && it.startMs >= 0L }
            .sortedBy { it.startMs }
        if (usable.size < MIN_WORDS) return null
        val firstStartMs = usable.first().startMs
        val lastEndMs = usable.maxOf { it.endMs }
        val spanMs = lastEndMs - firstStartMs
        if (durationMs > 0L) {
            if (spanMs * MIN_SPAN_SHARE_DENOMINATOR < durationMs) return null
        } else if (spanMs < MIN_SPAN_MS) {
            return null
        }

        val orderedSegments = segments
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
        // Which segment each word falls in: the last one that started
        // at or before it, or -1 before the first segment (and for
        // every word, when the answer carried no segments at all).
        fun segmentIndexOf(word: TranscribedWord): Int {
            var index = -1
            for ((i, segment) in orderedSegments.withIndex()) {
                if (segment.startMs <= word.startMs) index = i else break
            }
            return index
        }

        val lines = mutableListOf<LyricLine>()
        var current = mutableListOf<TranscribedWord>()
        var currentSegment = -1
        fun closeLine() {
            if (current.isEmpty()) return
            lines += lineOf(current)
            current = mutableListOf()
        }
        for (word in usable) {
            val segment = segmentIndexOf(word)
            val previous = current.lastOrNull()
            if (previous != null &&
                (segment != currentSegment ||
                    word.startMs - previous.endMs >= LINE_PAUSE_MS ||
                    current.size >= MAX_WORDS_PER_LINE)
            ) {
                closeLine()
            }
            if (current.isEmpty()) currentSegment = segment
            current += word
        }
        closeLine()
        return lines.takeIf { it.isNotEmpty() }
    }

    /** One line from its heard words, in order. */
    private fun lineOf(words: List<TranscribedWord>): LyricLine = LyricLine(
        timeMs = words.first().startMs,
        text = joinWords(words),
        words = words.map { LyricWord(startMs = it.startMs, endMs = it.endMs, text = it.text) },
        sungUntilMs = words.last().endMs,
        wordsAiTranscribed = true,
    )

    /**
     * The line's text, written out of its words: the words in order,
     * joined the way they were sung rather than the way tokens are
     * stored. A word that opens with closing punctuation belongs to
     * the word before it and takes no space; a word after an opening
     * bracket takes none either; and where either side of the join is
     * a block-script character (Han, kana, Hangul — scripts written
     * without spaces, whose transcriptions arrive a character or a
     * block at a time) there is no space to take. Everything else is
     * joined with a single space. Only spacing is decided here — the
     * characters are the hearing's own, untouched.
     */
    private fun joinWords(words: List<TranscribedWord>): String {
        val out = StringBuilder()
        for (word in words) {
            if (out.isEmpty()) {
                out.append(word.text)
                continue
            }
            val previous = out.last()
            val next = word.text.first()
            val separated = next !in CLOSING_PUNCTUATION &&
                previous !in OPENING_PUNCTUATION &&
                !previous.isBlockScript() && !next.isBlockScript()
            if (separated) out.append(' ')
            out.append(word.text)
        }
        return out.toString()
    }

    /** Punctuation that closes onto the word before it. */
    private val CLOSING_PUNCTUATION = setOf(
        '.', ',', '!', '?', ';', ':', '%', ')', ']', '}', '»',
        '。', '，', '、', '！', '？', '；', '：', '）', '】',
    )

    /** Punctuation that opens onto the word after it. */
    private val OPENING_PUNCTUATION = setOf('(', '[', '{', '«', '（', '【')

    /** The block scripts, in the ranges [LyricLine] lays out as blocks. */
    private fun Char.isBlockScript(): Boolean =
        this in '一'..'鿿' || this in '぀'..'ゟ' ||
            this in '゠'..'ヿ' || this in '가'..'힯'
}
