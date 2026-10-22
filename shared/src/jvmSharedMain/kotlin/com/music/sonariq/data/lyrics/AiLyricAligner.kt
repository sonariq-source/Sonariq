package com.music.sonariq.data.lyrics

/**
 * One word of a transcription of the track's own audio, with the stretch of
 * the song the transcription says it was sung over.
 *
 * The input to [AiLyricAligner]: a transcription service listened to the
 * track itself and reported what it heard, word by word. Nothing here is a
 * lyric — the words are whatever the transcription made of the singing,
 * mishearings and all — which is why the aligner's whole job is matching
 * this sequence against the known lyric text and borrowing only the
 * timestamps of the words that line up.
 */
data class TranscribedWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * The comparable form of one lyric word or transcribed token: lowercased
 * and trimmed, punctuation and symbols dropped, internal whitespace
 * collapsed to single spaces.
 *
 * Apostrophes inside a word survive — "don't" and "don’t" are the same
 * word sung the same way, and dropping the mark would make them compare
 * as "dont" against a provider's "don't" for no gain — while one hanging
 * off either end does not ("'tis" is "tis"). Everything else that is not
 * a letter, a digit or whitespace is dropped outright, hyphens included:
 * "well-known" compares as "wellknown", one word against one word, rather
 * than being split into two halves neither of which is a whole token.
 *
 * A word that normalises to nothing — pure punctuation, a dash marking a
 * pause — is unmatchable: it can never anchor a timing, only be carried
 * by a neighbouring anchor (see [AiLyricAligner]).
 */
internal fun normalizeToken(raw: String): String = normalizeTokenImpl(raw)

/** The work of [normalizeToken], shared with [AiLyricAligner.normalizeToken]. */
private fun normalizeTokenImpl(raw: String): String {
    val lowered = raw.lowercase().trim()
    val out = StringBuilder(lowered.length)
    for (index in lowered.indices) {
        val char = lowered[index]
        when {
            char.isLetterOrDigit() -> out.append(char)
            char == '\'' || char == '’' -> {
                // Kept only where it sits inside a word, between two
                // letters or digits; written back as the plain mark so the
                // straight and curly forms compare equal.
                val inside = index > 0 && lowered[index - 1].isLetterOrDigit() &&
                    index + 1 < lowered.length && lowered[index + 1].isLetterOrDigit()
                if (inside) out.append('\'')
            }
            char.isWhitespace() -> out.append(' ')
            // Anything else — commas, brackets, hyphens, symbols — is
            // dropped: it is spelling decoration, not part of the word
            // that was sung.
            else -> Unit
        }
    }
    // Collapse the runs of spaces the drops and the source spacing left
    // behind, and trim the ends in the same pass.
    return out.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * Word timings measured from the track's own audio, for songs no provider
 * could word-sync.
 *
 * A transcription of the track reports the words it heard with a start
 * and an end for each. This engine lines that sequence up against the
 * lyric text the lookup already has — a global sequence alignment over
 * the whole song — and a lyric word that matches a transcribed token
 * takes that token's timestamps. That is the only way a timing is ever
 * produced here: every emitted [LyricWord] carries one token's measured
 * start and end, copied, never stretched, split, averaged or spread over
 * its neighbours. Timings are never invented.
 *
 * A lyric word with no match has no honest timing of its own, so it is
 * merged into a matched neighbour's span instead: the emitted word's text
 * grows to cover it — the exact substring of the line, spaces and all —
 * while keeping the anchor's measured timing. Words before a line's
 * first match merge forward into that match; words after one merge back
 * into it. Because the emitted texts are contiguous substrings of the
 * line in order, [LyricLine.wordSpans] still locates every one of them.
 *
 * Weak evidence is refused, at three levels:
 *
 *  - A word matches exactly on its normalised form, or nearly — a
 *    Levenshtein distance of at most one between words of at least four
 *    characters, worth less than an exact match. Short words get no
 *    near matches: at two or three letters, one letter wrong is a
 *    different word, not a mishearing.
 *  - A line is only AI-synced when at least 60% of its words matched and
 *    it has at least two anchor matches. Failing lines come back
 *    unchanged, still honestly line-synced.
 *  - The song is only AI-synced when strictly more than half of its
 *    non-gap lead lines pass. Otherwise [align] returns null and the
 *    whole attempt is discarded — a transcription of a different edit,
 *    or a different song, must not half-time this one.
 *
 * Background lines are aligned separately, against only the tokens whose
 * start falls in a window around the background line's own stamp: an
 * echo is sung where it is sung, and letting it compete for the whole
 * song's tokens would have it stealing the lead's. A background that
 * fails its own acceptance simply stays unchanged — it never sinks its
 * parent line or the song.
 *
 * Everything here is pure and deterministic: no I/O, no clock, no
 * randomness, the same inputs giving the same timings on every run. The
 * results are real measurements but not a provider's, so passing lines
 * are flagged [LyricLine.wordsAiMeasured] and stay provisional in
 * [LyricsLookupPolicy] — provider timings replace them whenever the hunt
 * later lands some.
 */
object AiLyricAligner {

    /** [normalizeToken], for callers who address the engine as a whole. */
    internal fun normalizeToken(raw: String): String = normalizeTokenImpl(raw)

    /**
     * These [lines] with word timings measured onto them from [tokens],
     * or null when the song as a whole did not earn them — see the class
     * doc for the acceptance rules. Lines that fail their own acceptance
     * keep their own text untimed, and gap lines pass through untouched;
     * background lines are judged separately either way, as the class doc
     * describes. The list is otherwise the input, same lines in the same
     * order.
     */
    fun align(lines: List<LyricLine>, tokens: List<TranscribedWord>): List<LyricLine>? {
        // Tokens that say nothing usable: a span that does not run
        // forwards carries no measurement, and a token that normalises
        // to nothing can never match. Both are ignored, not repaired.
        val usable = tokens.mapNotNull { token ->
            if (token.endMs <= token.startMs) return@mapNotNull null
            val normalized = normalizeTokenImpl(token.text)
            if (normalized.isEmpty()) null else TimedToken(token, normalized)
        }
        if (usable.isEmpty()) return null

        // The lead lines that count: gap (blank) lines are instrumental
        // stretches, passed through untouched and kept out of the ratio.
        val leadIndices = lines.indices.filter { lines[it].text.isNotBlank() }
        if (leadIndices.isEmpty()) return null

        // Flatten every lead line's words into one song-long sequence:
        // the alignment is global, so a chorus word can only take a token
        // in its own turn, never one an earlier verse already used.
        val spansByLine = HashMap<Int, List<WordSpan>>()
        val flatNorms = ArrayList<String>()
        val flatLine = ArrayList<Int>()
        val flatWord = ArrayList<Int>()
        for (lineIndex in leadIndices) {
            val spans = wordSpansOf(lines[lineIndex].text)
            spansByLine[lineIndex] = spans
            spans.forEachIndexed { wordIndex, span ->
                flatNorms.add(span.normalized)
                flatLine.add(lineIndex)
                flatWord.add(wordIndex)
            }
        }
        if (flatNorms.isEmpty()) return null

        val matches = alignSequences(flatNorms, usable.map { it.normalized })
        val matchedByLine = HashMap<Int, MutableMap<Int, TranscribedWord>>()
        for ((flatIndex, tokenIndex) in matches) {
            matchedByLine.getOrPut(flatLine[flatIndex]) { HashMap() }[flatWord[flatIndex]] =
                usable[tokenIndex].token
        }

        var passed = 0
        val alignedLead = HashMap<Int, LyricLine>()
        for (lineIndex in leadIndices) {
            val words = buildWords(
                lines[lineIndex],
                spansByLine.getValue(lineIndex),
                matchedByLine[lineIndex].orEmpty(),
            ) ?: continue
            passed += 1
            alignedLead[lineIndex] = lines[lineIndex].copy(
                words = words,
                wordsAiMeasured = true,
                // The words now on this line are the measured ones, not
                // whatever an earlier estimate claimed: leaving a stale
                // estimated flag set would misreport them downstream.
                wordsEstimated = false,
            )
        }
        // Strictly more than half, or the song keeps its line sync whole.
        if (passed * 2 <= leadIndices.size) return null

        return lines.mapIndexed { index, line ->
            val base = alignedLead[index] ?: line
            val background = line.background ?: return@mapIndexed base
            val alignedBackground = alignBackground(line, background, usable)
                ?: return@mapIndexed base
            base.copy(background = alignedBackground)
        }
    }

    /**
     * [background] with words measured from the tokens sung around it, or
     * null when it does not earn them on its own. The window runs from a
     * little before the background's own stamp — an echo can land a touch
     * ahead of where the line split put it — to its own stated end where
     * it has one, else its parent line's end, else a fixed span past its
     * stamp.
     */
    private fun alignBackground(
        parent: LyricLine,
        background: LyricLine,
        usable: List<TimedToken>,
    ): LyricLine? {
        if (background.text.isBlank()) return null
        val windowStart = background.timeMs - BACKGROUND_LEAD_IN_MS
        // The window's far edge: the background's own stated end where it
        // has one, else the parent's end where the parent knows one (see
        // [LyricLine.hasKnownEnd] — a bare [LyricLine.endMs] always
        // answers *something*, even when nothing stated an end), else a
        // fixed span past the background's own stamp.
        val windowEnd = background.sungUntilMs
            ?: if (parent.hasKnownEnd) {
                parent.endMs
            } else {
                background.timeMs + BACKGROUND_FALLBACK_WINDOW_MS
            }
        val windowTokens = usable.filter { it.token.startMs in windowStart..windowEnd }
        if (windowTokens.isEmpty()) return null
        val spans = wordSpansOf(background.text)
        if (spans.isEmpty()) return null
        val matches = alignSequences(spans.map { it.normalized }, windowTokens.map { it.normalized })
        val byWord = HashMap<Int, TranscribedWord>()
        for ((wordIndex, tokenIndex) in matches) {
            byWord[wordIndex] = windowTokens[tokenIndex].token
        }
        val words = buildWords(background, spans, byWord) ?: return null
        return background.copy(
            words = words,
            wordsAiMeasured = true,
            wordsEstimated = false,
        )
    }

    /**
     * The emitted words for one line from its anchor [matches] — lyric
     * word index to the token that word matched — or null when the line
     * fails acceptance: fewer than [MIN_ANCHORS] anchors, or anchors
     * covering less than 60% of the line's words.
     *
     * Each anchor emits one word spanning from its own start — or, for
     * the first anchor, from the line's first word, so the unmatched
     * words ahead of it merge forward into it — through the last
     * unmatched word before the next anchor, which merges back into it.
     * The text is the line's own substring over that span, and the timing
     * is the anchor token's, untouched.
     */
    private fun buildWords(
        line: LyricLine,
        spans: List<WordSpan>,
        matches: Map<Int, TranscribedWord>,
    ): List<LyricWord>? {
        if (spans.isEmpty()) return null
        if (matches.size < MIN_ANCHORS) return null
        // At least 60% matched, in integer terms: matched/total >= 3/5.
        if (matches.size * 5 < spans.size * 3) return null
        val anchors = matches.keys.sorted()
        return anchors.mapIndexed { position, anchor ->
            val spanStart = if (position == 0) 0 else anchor
            val spanEnd = anchors.getOrNull(position + 1)?.minus(1) ?: spans.lastIndex
            val token = matches.getValue(anchor)
            LyricWord(
                startMs = token.startMs,
                endMs = token.endMs,
                text = line.text.substring(spans[spanStart].start, spans[spanEnd].end),
            )
        }
    }

    /**
     * The best in-order matching between the lyric words and the tokens,
     * as lyric index to token index — a Needleman–Wunsch alignment whose
     * only diagonal moves are matches: an exact normalised match scores
     * [EXACT_SCORE], a near match [NEAR_SCORE], and every skipped word or
     * token costs [GAP_PENALTY]. Aligning two *different* words is never
     * on the table, so a timing is only ever borrowed from a token the
     * word genuinely lines up with. Song-scale inputs — a few hundred
     * words against a few hundred tokens — keep the table small.
     *
     * Ties break towards matching, then towards skipping a lyric word,
     * so the result is the same on every run for the same inputs.
     */
    private fun alignSequences(
        lyricNorms: List<String>,
        tokenNorms: List<String>,
    ): Map<Int, Int> {
        if (lyricNorms.isEmpty() || tokenNorms.isEmpty()) return emptyMap()
        val lyricCount = lyricNorms.size
        val tokenCount = tokenNorms.size
        val dp = Array(lyricCount + 1) { IntArray(tokenCount + 1) }
        for (i in 1..lyricCount) dp[i][0] = dp[i - 1][0] + GAP_PENALTY
        for (j in 1..tokenCount) dp[0][j] = dp[0][j - 1] + GAP_PENALTY
        for (i in 1..lyricCount) {
            for (j in 1..tokenCount) {
                var best = maxOf(dp[i - 1][j] + GAP_PENALTY, dp[i][j - 1] + GAP_PENALTY)
                val score = matchScore(lyricNorms[i - 1], tokenNorms[j - 1])
                if (score != null) best = maxOf(best, dp[i - 1][j - 1] + score)
                dp[i][j] = best
            }
        }
        val matches = HashMap<Int, Int>()
        var i = lyricCount
        var j = tokenCount
        while (i > 0 && j > 0) {
            val score = matchScore(lyricNorms[i - 1], tokenNorms[j - 1])
            when {
                score != null && dp[i][j] == dp[i - 1][j - 1] + score -> {
                    matches[i - 1] = j - 1
                    i -= 1
                    j -= 1
                }
                dp[i][j] == dp[i - 1][j] + GAP_PENALTY -> i -= 1
                else -> j -= 1
            }
        }
        return matches
    }

    /**
     * What pairing these two normalised words is worth, or null when they
     * do not pair at all. A near match — Levenshtein distance at most
     * one — is only trusted between words of at least
     * [MIN_NEAR_LENGTH] characters: shorter than that, a letter's
     * difference is likelier a different word than a mishearing. A word
     * that normalised to nothing matches nothing.
     */
    private fun matchScore(lyricWord: String, tokenWord: String): Int? {
        if (lyricWord.isEmpty() || tokenWord.isEmpty()) return null
        if (lyricWord == tokenWord) return EXACT_SCORE
        if (minOf(lyricWord.length, tokenWord.length) < MIN_NEAR_LENGTH) return null
        // A distance of one can change the length by at most one.
        if (kotlin.math.abs(lyricWord.length - tokenWord.length) > 1) return null
        return if (levenshtein(lyricWord, tokenWord) <= 1) NEAR_SCORE else null
    }

    /** Edit distance between two short normalised words, two rows at a time. */
    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + cost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /**
     * The line's words as spans of its own text: maximal runs of
     * non-whitespace, each keeping the exact substring as written —
     * capitals, punctuation and all — because the emitted [LyricWord.text]
     * has to be found in the line by [LyricLine.wordSpans], and only the
     * normalised form is ever compared.
     */
    private fun wordSpansOf(text: String): List<WordSpan> {
        val spans = ArrayList<WordSpan>()
        var index = 0
        while (index < text.length) {
            if (text[index].isWhitespace()) {
                index += 1
                continue
            }
            val start = index
            while (index < text.length && !text[index].isWhitespace()) index += 1
            val word = text.substring(start, index)
            spans += WordSpan(
                text = word,
                start = start,
                end = index,
                normalized = normalizeTokenImpl(word),
            )
        }
        return spans
    }

    /** One lyric word's place in its line — see [wordSpansOf]. */
    private data class WordSpan(
        val text: String,
        val start: Int,
        val end: Int,
        val normalized: String,
    )

    /** A transcribed token that survived filtering, with its normalised form. */
    private data class TimedToken(
        val token: TranscribedWord,
        val normalized: String,
    )

    /** What an exact normalised match is worth in the alignment. */
    private const val EXACT_SCORE = 10

    /** What a near match is worth: real, but an exact match beats it. */
    private const val NEAR_SCORE = 4

    /** What skipping one lyric word or one token costs. */
    private const val GAP_PENALTY = -3

    /** The shortest word a near match is trusted on. */
    private const val MIN_NEAR_LENGTH = 4

    /** The fewest anchor matches a line needs before any of it is sung. */
    private const val MIN_ANCHORS = 2

    /** How far before a background line's stamp its window opens. */
    private const val BACKGROUND_LEAD_IN_MS = 1_500L

    /** The window a background line gets when nothing states its end. */
    private const val BACKGROUND_FALLBACK_WINDOW_MS = 8_000L
}
