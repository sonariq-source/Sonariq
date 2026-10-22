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
 * Three choices here are about how singing is actually written, on the
 * lyric side and on the transcription side, and they are applied to both
 * sides identically so the same spelling always compares equal:
 *
 *  - Apostrophes are dropped wherever they sit. Tagalog writes the same
 *    word both ways — "ngayo'y" in a lyric, "ngayoy" in a transcription —
 *    and a hanging mark is the same again: "'di" is "di".
 *  - A hyphen is a separator, not decoration. "ah-ah" is two sung units
 *    in one written word, so it normalises to "ah ah" and each half can
 *    anchor on its own token; the written word still counts as one word
 *    everywhere a count is taken (see [AiLyricAligner]).
 *  - A run of two or more identical letters collapses to one letter.
 *    Held vowels are written by ear — "ooh" against "oh", "ngayoon"
 *    against "ngayon" — and the same collapse on both sides also means an
 *    ordinary doubled letter costs nothing: "hello" is "helo" on both
 *    sides, and still matches exactly.
 *
 * Everything else that is not a letter, a digit or whitespace is dropped
 * outright. A word that normalises to nothing — pure punctuation, a dash
 * marking a pause — is unmatchable: it can never anchor a timing, only be
 * carried by a neighbouring anchor (see [AiLyricAligner]).
 */
internal fun normalizeToken(raw: String): String = normalizeTokenImpl(raw)

/** The work of [normalizeToken], shared with [AiLyricAligner.normalizeToken]. */
private fun normalizeTokenImpl(raw: String): String {
    val lowered = raw.lowercase().trim()
    val out = StringBuilder(lowered.length)
    for (char in lowered) {
        when {
            char.isLetterOrDigit() -> out.append(char)
            // Both apostrophe forms go, in any position: the mark is
            // spelling, never part of the sung word.
            char == '\'' || char == '’' -> Unit
            // A hyphen joins two units the singer sings separately, so it
            // becomes a space and the halves normalise as two units.
            char == '-' || char == '‐' || char == '‑' -> out.append(' ')
            char.isWhitespace() -> out.append(' ')
            // Anything else — commas, brackets, symbols — is dropped: it
            // is spelling decoration, not part of the word that was sung.
            else -> Unit
        }
    }
    // Split into units on the spaces the source spacing and the hyphens
    // left behind, collapse each unit's doubled letters, and join back
    // with single spaces, which trims the ends in the same pass.
    return out.toString()
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { unit -> collapseRepeatedLetters(unit) }
}

/**
 * [unit] with every run of two or more identical letters collapsed to a
 * single letter. Digits are left alone: a repeated digit is a different
 * number, while a repeated letter in a sung word is a held sound written
 * down two ways.
 */
private fun collapseRepeatedLetters(unit: String): String {
    if (unit.length < 2) return unit
    val out = StringBuilder(unit.length)
    for (char in unit) {
        if (char.isLetter() && out.isNotEmpty() && out[out.length - 1] == char) continue
        out.append(char)
    }
    return out.toString()
}

/**
 * Word timings measured from the track's own audio, for songs no provider
 * could word-sync. This is alignment version 2 — see [ALIGNMENT_VERSION] —
 * rebuilt around the line timings the provider lookup already trusts.
 *
 * Version 1 aligned the whole song in one global sequence alignment, and
 * real songs broke it three ways. A repeated chorus confused the global
 * match, so words took timings from the wrong repetition. A line that
 * failed its acceptance stayed line-synced inside a word-synced song, so
 * one song mixed both kinds of sync. And an emitted word ended where the
 * transcription's recognition of it ended — a fraction of the sung note —
 * so the highlight raced ahead of the singing.
 *
 * Version 2 works per line instead:
 *
 *  - Each top-level line owns a window: from its own stamp to the next
 *    top-level line's stamp (the last line ends at its stated end, the
 *    song's end, or the last token's end). A token is a candidate for a
 *    line only when its midpoint falls in that window, widened by half a
 *    second at each end. Lines are processed in order and a token one
 *    line's alignment matched is consumed — no later line can borrow it.
 *  - Before any windowing, the whole transcription is calibrated against
 *    the line windows. Long words (normalised length at least five) that
 *    match exactly, looked for in a window widened by six seconds, are
 *    anchors: each one's expected place is estimated from its character
 *    position inside its line, and the difference between that estimate
 *    and the token's actual start is a residual. With at least four
 *    anchors the median residual — clamped to five seconds either way —
 *    is the transcription's offset, and every token time is shifted by
 *    it before windowing. That estimate is only ever a yardstick for the
 *    offset; no emitted timing is ever derived from it. If the anchors
 *    disagree wildly (a median absolute deviation over 750 ms) the
 *    transcription is a different edit or a different song, calibration
 *    is reported discarded and the whole attempt returns null.
 *  - Each line is then matched on its own, with the same
 *    Needleman–Wunsch alignment version 1 used, run over just that
 *    line's words against just that window's tokens. A line passes when
 *    at least half of its words are anchored and it has enough anchors:
 *    two for a line of four or more words, one for a shorter line.
 *  - Every sung line must pass. If even one non-gap lead line fails, the
 *    outcome is null — no half-measured songs, no line left line-synced
 *    among word-synced ones. [alignDetailed] still reports how many lead
 *    lines passed, so a caller can tell a near miss from a wrong song.
 *
 * A lyric word with no match has no honest timing of its own, so it is
 * merged into a matched neighbour's span instead: the emitted word's text
 * grows to cover it — the exact substring of the line, spaces and all —
 * while keeping the anchor's measured start. Words before a line's first
 * match merge forward into that match; words after one merge back into
 * it. Because the emitted texts are contiguous substrings of the line in
 * order, [LyricLine.wordSpans] still locates every one of them.
 *
 * A matched word's END is never the transcription token's end. A token
 * ends where the recogniser stopped hearing the word, which is far
 * sooner than a held note ends, and copying it made the sweep jump. So
 * every emitted word runs until the next emitted word of its line
 * starts, and the line's last word runs until the line's display end —
 * its stated end where it has one, capped at its window's end. Token
 * ends are used only for midpoints and candidacy, never copied out.
 *
 * Background lines keep the version 1 shape — their own window around
 * their own stamp — except that they draw only on tokens no lead line
 * consumed: an echo is sung where it is sung, and it may not reuse the
 * lead's measurements. A background that fails its own acceptance simply
 * stays unchanged; it never sinks its parent line or the song.
 *
 * Matching itself is unchanged: a word matches exactly on its normalised
 * form, or nearly — a Levenshtein distance of at most one between words
 * of at least four characters, worth less than an exact match. Short
 * words get no near matches: at two or three letters, one letter wrong
 * is a different word, not a mishearing. Tokens that run backwards or
 * normalise to nothing are ignored, not repaired.
 *
 * Everything here is pure and deterministic: no I/O, no clock, no
 * randomness, the same inputs giving the same timings on every run. The
 * results are real measurements but not a provider's, so passing lines
 * are flagged [LyricLine.wordsAiMeasured] and stay provisional in
 * [LyricsLookupPolicy] — provider timings replace them whenever the hunt
 * later lands some.
 */
object AiLyricAligner {

    /**
     * The version of this alignment design. Results measured by an older
     * version are not this version's measurements: stored word timings
     * carry the version they were made with, and anything older than the
     * current version is recomputed rather than trusted.
     */
    const val ALIGNMENT_VERSION: Int = 2

    /**
     * The full report of one [alignDetailed] run: the word-timed [lines]
     * when the song earned them, and in every case how the attempt went,
     * so a null result can still be told apart by its cause.
     */
    data class AlignOutcome(
        /** The fully word-timed lines, or null unless every non-gap lead line passed. */
        val lines: List<LyricLine>?,
        /** How many non-gap lead lines passed their own acceptance. */
        val passedLeadLines: Int,
        /** How many non-gap lead lines the song has. */
        val leadLineCount: Int,
        /**
         * True when calibration found enough anchors but they disagreed
         * too wildly to trust, so the attempt was discarded before any
         * line was aligned. A discarded calibration says the
         * transcription is likely a different edit or a different song;
         * retrying it elsewhere is reasonable, trusting it is not.
         */
        val calibrationDiscarded: Boolean,
        /**
         * The offset calibration measured and removed from every token
         * time, in milliseconds — the amount the token times were shifted
         * by is the negative of this. Zero when there were too few
         * anchors to calibrate, and when calibration was discarded.
         */
        val offsetMs: Long,
    )

    /** [normalizeToken], for callers who address the engine as a whole. */
    internal fun normalizeToken(raw: String): String = normalizeTokenImpl(raw)

    /**
     * These [lines] with word timings measured onto them from [tokens],
     * or null unless every non-gap lead line earned them — see the class
     * doc for the acceptance rules. Gap lines pass through untouched;
     * background lines are judged separately either way, as the class doc
     * describes. The list is otherwise the input, same lines in the same
     * order. [songEndMs], where the caller knows it, only bounds the
     * last line's window.
     */
    fun align(
        lines: List<LyricLine>,
        tokens: List<TranscribedWord>,
        songEndMs: Long? = null,
    ): List<LyricLine>? = alignDetailed(lines, tokens, songEndMs).lines

    /**
     * [align] with the attempt's full report — see [AlignOutcome]. The
     * gap (blank) lines are excluded from every count and pass through
     * untouched; they still bound the windows of the lines before them,
     * because a gap line's stamp is where the previous line's slot ends.
     */
    fun alignDetailed(
        lines: List<LyricLine>,
        tokens: List<TranscribedWord>,
        songEndMs: Long? = null,
    ): AlignOutcome {
        val leadIndices = lines.indices.filter { lines[it].text.isNotBlank() }
        val leadLineCount = leadIndices.size

        // Tokens that say nothing usable: a span that does not run
        // forwards carries no measurement, and a token that normalises
        // to nothing can never match. Both are ignored, not repaired.
        val usable = tokens.mapNotNull { token ->
            if (token.endMs <= token.startMs) return@mapNotNull null
            val normalized = normalizeTokenImpl(token.text)
            if (normalized.isEmpty()) null else TimedToken(token, normalized)
        }
        if (usable.isEmpty() || leadLineCount == 0) {
            return AlignOutcome(
                lines = null,
                passedLeadLines = 0,
                leadLineCount = leadLineCount,
                calibrationDiscarded = false,
                offsetMs = 0L,
            )
        }

        // Calibration first, against the windows the unshifted tokens
        // imply, so one steady offset in the transcription — a different
        // edit's intro, a ripped file's padding — is measured and taken
        // out before it can push tokens out of their lines' windows.
        val calibration = calibrate(lines, leadIndices, usable, songEndMs)
        if (calibration.discarded) {
            return AlignOutcome(
                lines = null,
                passedLeadLines = 0,
                leadLineCount = leadLineCount,
                calibrationDiscarded = true,
                offsetMs = 0L,
            )
        }
        val offsetMs = calibration.offsetMs
        val shifted = if (offsetMs == 0L) {
            usable
        } else {
            usable.map { timed ->
                timed.copy(
                    token = timed.token.copy(
                        startMs = timed.token.startMs - offsetMs,
                        endMs = timed.token.endMs - offsetMs,
                    ),
                )
            }
        }

        val windows = lineWindows(lines, songEndMs, shifted.maxOf { it.token.endMs })

        // Lead lines in order. A token matched by one line's alignment
        // is consumed: it leaves every later line's candidate pool, so a
        // word sung once can only ever time the line it was sung in.
        val consumed = HashSet<Int>()
        val alignedLead = HashMap<Int, LyricLine>()
        var passedLeadLines = 0
        for (lineIndex in leadIndices) {
            val line = lines[lineIndex]
            val attempt = attemptLine(
                line = line,
                candidates = candidateUnits(shifted, consumed) { timed ->
                    val midpoint = (timed.token.startMs + timed.token.endMs) / 2
                    midpoint >= windows[lineIndex].startMs - WINDOW_SLACK_MS &&
                        midpoint < windows[lineIndex].endMs + WINDOW_SLACK_MS
                },
                displayEndMs = displayEnd(line, windows[lineIndex].endMs),
            )
            consumed += attempt.matchedTokenIndices
            val words = attempt.words ?: continue
            passedLeadLines += 1
            alignedLead[lineIndex] = line.copy(
                words = words,
                wordsAiMeasured = true,
                // The words now on this line are the measured ones, not
                // whatever an earlier estimate claimed: leaving a stale
                // estimated flag set would misreport them downstream.
                wordsEstimated = false,
            )
        }
        // Every line or nothing: one failing lead line discards the
        // whole song, so no song ships with mixed word and line sync.
        if (passedLeadLines < leadLineCount) {
            return AlignOutcome(
                lines = null,
                passedLeadLines = passedLeadLines,
                leadLineCount = leadLineCount,
                calibrationDiscarded = false,
                offsetMs = offsetMs,
            )
        }

        val aligned = lines.mapIndexed { index, line ->
            val base = alignedLead[index] ?: line
            // Gap lines pass through untouched, background and all.
            if (line.text.isBlank()) return@mapIndexed base
            val background = line.background ?: return@mapIndexed base
            val alignedBackground = alignBackground(line, background, shifted, consumed)
                ?: return@mapIndexed base
            base.copy(background = alignedBackground)
        }
        return AlignOutcome(
            lines = aligned,
            passedLeadLines = passedLeadLines,
            leadLineCount = leadLineCount,
            calibrationDiscarded = false,
            offsetMs = offsetMs,
        )
    }

    /**
     * The token windows of the top-level [lines]: line `i` runs from its
     * own stamp to the next top-level line's stamp, gap or not. The last
     * line runs to its own stated end, else [songEndMs], else the last
     * token's end — [maxTokenEndMs], which the caller guarantees exists
     * whenever there is a usable token at all.
     */
    private fun lineWindows(
        lines: List<LyricLine>,
        songEndMs: Long?,
        maxTokenEndMs: Long,
    ): List<LineWindow> = lines.mapIndexed { index, line ->
        val end = if (index + 1 < lines.size) {
            lines[index + 1].timeMs
        } else {
            line.sungUntilMs ?: songEndMs ?: maxTokenEndMs
        }
        LineWindow(startMs = line.timeMs, endMs = end)
    }

    /**
     * The display end of [line] inside a window ending at [windowEndMs]:
     * the line's own stated end where it has one, capped at the window's
     * end so a held last word never runs into the next line's slot.
     */
    private fun displayEnd(line: LyricLine, windowEndMs: Long): Long =
        if (line.sungUntilMs != null) minOf(line.sungUntilMs, windowEndMs) else windowEndMs

    /**
     * The calibration described in the class doc, over the original
     * (unshifted) [usable] tokens: the offset to remove from every token
     * time, or a discard when the anchors disagree too wildly to be one
     * steady offset at all.
     *
     * Anchors are claimed greedily, lines and words in order, each token
     * anchoring at most one word — a repeated long word later in the
     * song must not borrow an earlier token's residual. The expected
     * place of a word is a yardstick only: its character position in its
     * line, spread over the line's window. Singing is not that even, and
     * no single residual is trusted; it is the median of many, and the
     * spread around that median, that decide.
     */
    private fun calibrate(
        lines: List<LyricLine>,
        leadIndices: List<Int>,
        usable: List<TimedToken>,
        songEndMs: Long?,
    ): Calibration {
        val windows = lineWindows(lines, songEndMs, usable.maxOf { it.token.endMs })
        val claimed = HashSet<Int>()
        val residuals = ArrayList<Long>()
        for (lineIndex in leadIndices) {
            val line = lines[lineIndex]
            if (line.text.isEmpty()) continue
            val window = windows[lineIndex]
            val expandedStart = window.startMs - CALIBRATION_EXPANSION_MS
            val expandedEnd = window.endMs + CALIBRATION_EXPANSION_MS
            for (span in wordSpansOf(line.text)) {
                if (span.normalized.length < MIN_ANCHOR_LENGTH) continue
                val tokenIndex = usable.indices.firstOrNull { index ->
                    if (index in claimed) return@firstOrNull false
                    val timed = usable[index]
                    if (timed.normalized != span.normalized) return@firstOrNull false
                    val midpoint = (timed.token.startMs + timed.token.endMs) / 2
                    midpoint >= expandedStart && midpoint < expandedEnd
                } ?: continue
                claimed += tokenIndex
                // The word's expected place: its character offset into
                // the line, as the same share of the line's window.
                val expected = window.startMs +
                    (span.start.toDouble() / line.text.length * (window.endMs - window.startMs)).toLong()
                residuals += usable[tokenIndex].token.startMs - expected
            }
        }
        if (residuals.size < MIN_CALIBRATION_ANCHORS) {
            // Too little evidence to move anything: no shift, no discard.
            return Calibration(offsetMs = 0L, discarded = false)
        }
        val offset = medianOfLongs(residuals)
        val spread = medianOfLongs(residuals.map { kotlin.math.abs(it - offset) })
        if (spread > MAX_CALIBRATION_SPREAD_MS) {
            return Calibration(offsetMs = 0L, discarded = true)
        }
        return Calibration(
            offsetMs = offset.coerceIn(-MAX_CALIBRATION_OFFSET_MS, MAX_CALIBRATION_OFFSET_MS),
            discarded = false,
        )
    }

    /** The median of [values]: the middle one, or the mean of the two middle ones. */
    private fun medianOfLongs(values: List<Long>): Long {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }

    /**
     * [background] with words measured from the tokens sung around it, or
     * null when it does not earn them on its own. The window keeps the
     * version 1 shape — from a little before the background's own stamp
     * (an echo can land a touch ahead of where the line split put it) to
     * its own stated end where it has one, else its parent line's end,
     * else a fixed span past its stamp — but the pool is only the tokens
     * no lead line consumed, so the echo can never reuse the lead's own
     * measurements. Tokens are candidacy-checked by their start here, as
     * they were in version 1, not by midpoint.
     */
    private fun alignBackground(
        parent: LyricLine,
        background: LyricLine,
        shifted: List<TimedToken>,
        consumed: MutableSet<Int>,
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
        val attempt = attemptLine(
            line = background,
            candidates = candidateUnits(shifted, consumed) { timed ->
                timed.token.startMs in windowStart..windowEnd
            },
            displayEndMs = displayEnd(background, windowEnd),
        )
        consumed += attempt.matchedTokenIndices
        val words = attempt.words ?: return null
        return background.copy(
            words = words,
            wordsAiMeasured = true,
            wordsEstimated = false,
        )
    }

    /**
     * One line's alignment attempt against its candidate tokens: the
     * emitted words when the line passes, the indices of the tokens its
     * alignment matched either way (matched tokens are consumed even by
     * a failing line — the alignment did happen, and its tokens were
     * sung in this line's window, not a later one's).
     *
     * The line's written words are flattened into normalised units first
     * (see [normalizeToken]): a hyphenated word contributes several
     * units, each remembering its parent word. Counts — anchored and
     * total alike — are taken over WORDS, not units: a word is anchored
     * when at least one of its units matched, and its start is its
     * earliest matched unit's token start.
     *
     * A line passes when anchoredWords * 2 >= totalWords (at least half
     * of its words anchored) and it has at least [minAnchorsFor] anchors.
     */
    private fun attemptLine(
        line: LyricLine,
        candidates: List<CandidateUnit>,
        displayEndMs: Long,
    ): LineAttempt {
        val spans = wordSpansOf(line.text)
        if (spans.isEmpty()) {
            return LineAttempt(words = null, matchedTokenIndices = emptySet())
        }
        val units = ArrayList<LyricUnit>()
        spans.forEachIndexed { spanIndex, span ->
            if (span.normalized.isEmpty()) return@forEachIndexed
            for (unit in span.normalized.split(' ')) {
                if (unit.isNotEmpty()) units += LyricUnit(spanIndex = spanIndex, normalized = unit)
            }
        }
        if (units.isEmpty() || candidates.isEmpty()) {
            return LineAttempt(words = null, matchedTokenIndices = emptySet())
        }
        val matches = alignSequences(units.map { it.normalized }, candidates.map { it.normalized })
        val matchedTokenIndices = matches.values.mapTo(HashSet()) { candidates[it].tokenIndex }
        // Each anchored word's start: its earliest matched unit's token
        // start — earliest in the line's own order, which the in-order
        // alignment keeps in step with the tokens' order.
        val startBySpan = HashMap<Int, Long>()
        for (unitIndex in matches.keys.sorted()) {
            val spanIndex = units[unitIndex].spanIndex
            if (spanIndex !in startBySpan) {
                startBySpan[spanIndex] = candidates[matches.getValue(unitIndex)].token.startMs
            }
        }
        val anchored = startBySpan.size
        val total = spans.size
        val minAnchors = minAnchorsFor(total)
        if (anchored < minAnchors || anchored * 2 < total) {
            return LineAttempt(words = null, matchedTokenIndices = matchedTokenIndices)
        }
        return LineAttempt(
            words = buildWords(line, spans, startBySpan, displayEndMs),
            matchedTokenIndices = matchedTokenIndices,
        )
    }

    /** The fewest anchors a line of [totalWords] words needs: two, or one for a short line. */
    private fun minAnchorsFor(totalWords: Int): Int = if (totalWords >= 4) 2 else 1

    /**
     * The candidate units of one line's pool: every unconsumed token the
     * [inPool] check admits, flattened into its normalised units in
     * order, each unit keeping the index of the token it came from — a
     * matched unit consumes its whole token.
     */
    private fun candidateUnits(
        shifted: List<TimedToken>,
        consumed: Set<Int>,
        inPool: (TimedToken) -> Boolean,
    ): List<CandidateUnit> {
        val candidates = ArrayList<CandidateUnit>()
        shifted.forEachIndexed { tokenIndex, timed ->
            if (tokenIndex in consumed || !inPool(timed)) return@forEachIndexed
            for (unit in timed.normalized.split(' ')) {
                if (unit.isNotEmpty()) {
                    candidates += CandidateUnit(tokenIndex = tokenIndex, normalized = unit, token = timed.token)
                }
            }
        }
        return candidates
    }

    /**
     * The emitted words for one passing line, from its anchored words —
     * word index to that word's measured start.
     *
     * Each anchor emits one word spanning from its own start — or, for
     * the first anchor, from the line's first word, so the unmatched
     * words ahead of it merge forward into it — through the last
     * unmatched word before the next anchor, which merges back into it.
     * The text is the line's own substring over that span.
     *
     * Ends hold, as the class doc describes: a word ends where the next
     * emitted word starts, and the last word ends at the line's display
     * end. No end is ever a token's end. Every end is also kept strictly
     * after its start, so a word always has a span to be sung over.
     */
    private fun buildWords(
        line: LyricLine,
        spans: List<WordSpan>,
        startBySpan: Map<Int, Long>,
        displayEndMs: Long,
    ): List<LyricWord> {
        val anchors = startBySpan.keys.sorted()
        return anchors.mapIndexed { position, anchor ->
            val spanStart = if (position == 0) 0 else anchor
            val spanEnd = anchors.getOrNull(position + 1)?.minus(1) ?: spans.lastIndex
            val start = startBySpan.getValue(anchor)
            val nextStart = anchors.getOrNull(position + 1)?.let { startBySpan.getValue(it) }
            val end = maxOf(nextStart ?: displayEndMs, start + 1)
            LyricWord(
                startMs = start,
                endMs = end,
                text = line.text.substring(spans[spanStart].start, spans[spanEnd].end),
            )
        }
    }

    /**
     * The best in-order matching between the lyric units and the
     * candidate units, as lyric index to candidate index — a
     * Needleman–Wunsch alignment whose only diagonal moves are matches:
     * an exact normalised match scores [EXACT_SCORE], a near match
     * [NEAR_SCORE], and every skipped unit or token costs [GAP_PENALTY].
     * Aligning two *different* words is never on the table, so a timing
     * is only ever borrowed from a token the word genuinely lines up
     * with. Run per line, the table is a line's words against a window's
     * tokens — small by construction.
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

    /** One normalised unit of a line's words, and the word it belongs to. */
    private data class LyricUnit(
        val spanIndex: Int,
        val normalized: String,
    )

    /** A transcribed token that survived filtering, with its normalised form. */
    private data class TimedToken(
        val token: TranscribedWord,
        val normalized: String,
    )

    /** One normalised unit of a candidate token, and the token it came from. */
    private data class CandidateUnit(
        val tokenIndex: Int,
        val normalized: String,
        val token: TranscribedWord,
    )

    /** One top-level line's token window — see [lineWindows]. */
    private data class LineWindow(
        val startMs: Long,
        val endMs: Long,
    )

    /** The result of [calibrate] — see there. */
    private data class Calibration(
        val offsetMs: Long,
        val discarded: Boolean,
    )

    /** The result of [attemptLine] — see there. */
    private data class LineAttempt(
        val words: List<LyricWord>?,
        val matchedTokenIndices: Set<Int>,
    )

    /** What an exact normalised match is worth in the alignment. */
    private const val EXACT_SCORE = 10

    /** What a near match is worth: real, but an exact match beats it. */
    private const val NEAR_SCORE = 4

    /** What skipping one lyric word or one token costs. */
    private const val GAP_PENALTY = -3

    /** The shortest word a near match is trusted on. */
    private const val MIN_NEAR_LENGTH = 4

    /** The shortest normalised word trusted as a calibration anchor. */
    private const val MIN_ANCHOR_LENGTH = 5

    /** The fewest calibration anchors before any offset is measured. */
    private const val MIN_CALIBRATION_ANCHORS = 4

    /** How far calibration looks past a line's window at each end. */
    private const val CALIBRATION_EXPANSION_MS = 6_000L

    /** The widest anchor disagreement, as a median spread, calibration tolerates. */
    private const val MAX_CALIBRATION_SPREAD_MS = 750L

    /** The largest offset calibration will ever remove from the tokens. */
    private const val MAX_CALIBRATION_OFFSET_MS = 5_000L

    /** How far a line's candidate window reaches past its stamps at each end. */
    private const val WINDOW_SLACK_MS = 500L

    /** How far before a background line's stamp its window opens. */
    private const val BACKGROUND_LEAD_IN_MS = 1_500L

    /** The window a background line gets when nothing states its end. */
    private const val BACKGROUND_FALLBACK_WINDOW_MS = 8_000L
}

/**
 * [AiLyricAligner.AlignOutcome] under its short name, for callers who
 * address the report without addressing the engine as a whole. The report
 * itself lives on the engine; this is only the same type by another name.
 */
typealias AlignOutcome = AiLyricAligner.AlignOutcome
