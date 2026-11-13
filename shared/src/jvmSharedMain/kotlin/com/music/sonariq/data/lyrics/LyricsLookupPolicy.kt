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

    /**
     * How long to wait before re-offering a track to AI measurement after
     * a full set of attempts already failed to time it.
     *
     * The throttle that keeps a song the measurement cannot crack from
     * being re-measured on every play: one full set of attempts a day is
     * persistence, ten a day is a loop. A track that could not be timed
     * yesterday may be timed today — a different edit, a corrected
     * alignment, a quieter network — so the record expires rather than
     * barring the track forever, but inside the interval background
     * triggers leave it alone entirely.
     */
    const val AI_RETRY_INTERVAL_MS: Long = 24L * 60 * 60 * 1000

    /** The most tracks one list (Home shelf, detail page) warms at once. */
    const val LIST_PREFETCH_CAP = 40

    /**
     * The sources that can answer with TTML: the document format that
     * carries per-syllable timings, not just per-word ones. Membership here
     * is about the format a source can return, independent of its
     * [LyricsSource.wordSynced] flag.
     */
    val TTML_SOURCES: Set<LyricsSource> = setOf(
        LyricsSource.LRC_RED,
        LyricsSource.BINI_LYRICS,
        LyricsSource.BETTER_LYRICS,
        LyricsSource.BETTER_LYRICS_PORTATO,
        LyricsSource.PAXSENIX,
        LyricsSource.PAXSENIX_SPOTIFY,
        LyricsSource.PAXSENIX_MUSIXMATCH,
        LyricsSource.UNISON,
    )

    /**
     * Whether any word in [result] carries its own syllable timings —
     * including words on a nested background line, which is a line in its
     * own right with its own words.
     */
    fun hasSyllables(result: LyricsRepository.Result): Boolean {
        fun lineHasSyllables(line: LyricLine): Boolean =
            line.words.any { it.syllables.isNotEmpty() } ||
                line.background?.let { lineHasSyllables(it) } == true
        return result.lines.any { lineHasSyllables(it) }
    }

    /**
     * How final a matched result is, as a tier — lower is better.
     *
     *  - 0: a [TTML_SOURCES] result with true word sync and syllable timings.
     *  - 1: a [TTML_SOURCES] result with true word sync.
     *  - 2: any other source's true word sync, with syllable timings.
     *  - 3: any other source's true word sync.
     *  - 4: [LyricTiming.WORD_SYNCED_AI_MEASURED] — word timings measured
     *    from the track's own audio by the AI alignment fallback — and
     *    [LyricTiming.WORD_SYNCED_AI_TRANSCRIBED], a transcription of
     *    the track's own audio standing in as the lyric itself. Real
     *    measurements, so they sit above line sync, but no provider
     *    supplied them, so they sit below every provider tier. The two
     *    share the tier; which of them may replace the other in the
     *    cache is the store guards' call, not the race's (see
     *    [LyricsRepository.storeAiResult]).
     *  - 5: everything else — line sync, plain text, and
     *    [LyricTiming.WORD_SYNCED_ESTIMATED], which is not real word sync
     *    and so earns no tier of its own.
     *
     * Only tiers 0–3 are final (see [isFinal]): a tier-4 or tier-5 result
     * is worth showing, but the lookup keeps hunting for a real
     * word-synced one — which is what lets provider timings replace
     * AI-measured ones whenever they turn up.
     */
    fun tierOf(result: LyricsRepository.Result): Int {
        if (result.timing == LyricTiming.WORD_SYNCED_AI_MEASURED ||
            result.timing == LyricTiming.WORD_SYNCED_AI_TRANSCRIBED
        ) {
            return 4
        }
        if (result.timing != LyricTiming.WORD_SYNCED) return 5
        val fromTtml = result.source in TTML_SOURCES
        val syllables = hasSyllables(result)
        return when {
            fromTtml && syllables -> 0
            fromTtml -> 1
            syllables -> 2
            else -> 3
        }
    }

    /** True when [result] is real word sync — tiers 0–3 of [tierOf]. */
    fun isFinal(result: LyricsRepository.Result): Boolean = tierOf(result) <= 3

    /**
     * True when [result] is AI-measured word sync, line sync, plain, or
     * estimated — tiers 4–5 of [tierOf].
     */
    fun isProvisional(result: LyricsRepository.Result): Boolean = !isFinal(result)

    /**
     * Strength of a result's timing bucket alone, for telling two results
     * in the same [tierOf] tier apart. Provider word timings outrank
     * AI-measured ones, which outrank line sync, which outranks plain
     * text. Estimated word timings rank no higher than the line sync they
     * were derived from: they are that line sync, with a guess laid over
     * it, not a timing of their own.
     */
    fun timingRank(timing: LyricTiming): Int = when (timing) {
        LyricTiming.WORD_SYNCED -> 3
        LyricTiming.WORD_SYNCED_AI_MEASURED -> 2
        LyricTiming.WORD_SYNCED_AI_TRANSCRIBED -> 2
        LyricTiming.LINE_SYNCED -> 1
        LyricTiming.WORD_SYNCED_ESTIMATED -> 1
        LyricTiming.PLAIN -> 0
    }

    /**
     * True when [candidate] is strictly better than [current]: first by
     * [tierOf] (the lower tier wins), then, within a tier, by [timingRank].
     */
    fun isUpgrade(
        current: LyricsRepository.Result?,
        candidate: LyricsRepository.Result?,
    ): Boolean {
        candidate ?: return false
        current ?: return true
        val candidateTier = tierOf(candidate)
        val currentTier = tierOf(current)
        if (candidateTier != currentTier) return candidateTier < currentTier
        return timingRank(candidate.timing) > timingRank(current.timing)
    }

    /**
     * The best of the results that have already completed, in race
     * (priority) order — what the deadline surfaces.
     *
     * Mirrors the race's own winner rules so the deadline can never surface
     * something the full race would have rejected: the lowest [tierOf]
     * tier wins, and priority order breaks ties within a tier — in both
     * prioritization modes, since the tiers already encode what
     * prioritization is for, and within a shared tier (plain and
     * line-synced results are both tier 5) there is no further shortcut:
     * the higher-priority result is the winner. [fallback] competes as a
     * candidate ahead of the completed results, so it is itself beaten
     * only by a strictly better tier and otherwise stands — that is what
     * keeps a merely provisional fallback (or a provisional cache seed,
     * passed in as the fallback) on screen until something genuinely
     * better has completed. A tier-4 or tier-5 winner may still surface
     * this way — it is worth reading, it just is not final. Null results
     * (misses) are
     * skipped, and a result that has not completed yet is simply absent
     * from [completedInOrder] — never guessed at.
     *
     * [prioritizeSyllableSync] is the race's own mode, passed through by
     * the caller; it no longer changes the pick itself, because the tiers
     * already rank syllable sync above word sync above line sync, which
     * is all the mode ever asked the deadline to prefer.
     *
     * Within the best tier, a candidate whose timings fit the track
     * ([durationMs], where the caller knows it) beats one whose cannot —
     * the same tiebreak [isUpgradeWithFit] applies — and where every
     * candidate in the tier fails the fit, priority order decides as
     * ever: a misfit is still worth surfacing over silence.
     */
    fun pickDeadlineWinner(
        completedInOrder: List<LyricsRepository.Result?>,
        prioritizeSyllableSync: Boolean,
        fallback: LyricsRepository.Result? = null,
        durationMs: Long = 0L,
    ): LyricsRepository.Result? {
        val candidates = listOfNotNull(fallback) + completedInOrder.filterNotNull()
        if (candidates.isEmpty()) return null
        val bestTier = candidates.minOf { tierOf(it) }
        val inTier = candidates.filter { tierOf(it) == bestTier }
        if (durationMs > 0L) {
            inTier.firstOrNull { fitsTrack(it, durationMs) }?.let { return it }
        }
        return inTier.first()
    }

    // ---- Version fit ------------------------------------------------

    /**
     * How far a candidate's lyric span may run past the track's end
     * before the timings are judged to belong to a longer recording.
     *
     * Generous on purpose: providers round, tracks carry a few seconds
     * of tail the lyric file never claimed, and this check only ever
     * re-ranks — a candidate it condemns is still delivered when nothing
     * better exists. What it exists to catch is not rounding but a
     * different edit: an album cut's timings against a single's audio
     * overshoot by the length of a verse, not by a breath.
     */
    const val FIT_OVERSHOOT_TOLERANCE_MS = 10_000L

    /**
     * How much track may run on after a candidate's last *known* lyric
     * end before the timings are judged to belong to a shorter recording.
     * Only judged when the candidate states an end (word timings or a
     * line's own stated end): a line-synced file's last stamp is a start,
     * not an end, and long outros are a real shape of song. A minute of
     * stated silence after the singing stops is not an outro shape any
     * catalogue entry needs.
     */
    const val FIT_TRAILING_TOLERANCE_MS = 60_000L

    /**
     * How late a candidate's first vocal may start, in absolute terms,
     * before onset is itself a fit signal — combined with
     * [FIT_ONSET_LATE_SHARE] of the track's length, so a long track's
     * long intro is not condemned by the absolute term alone.
     */
    const val FIT_ONSET_LATE_MS = 60_000L

    /** The share of the track that may pass before the first vocal; see [FIT_ONSET_LATE_MS]. */
    const val FIT_ONSET_LATE_SHARE_NUMERATOR = 3L
    const val FIT_ONSET_LATE_SHARE_DENOMINATOR = 5L

    /**
     * The verdict of [assessFit]: whether a candidate's timings can
     * belong to the track they would be shown against, and the spans the
     * verdict was read off, so a caller (or a test) can see *why*.
     */
    data class FitAssessment(
        val fits: Boolean,
        /** The first sung moment the candidate claims, or null when it claims none. */
        val firstStartMs: Long?,
        /** The last sung moment the candidate claims, or null when it claims none. */
        val lastEndMs: Long?,
        /** How far [lastEndMs] runs past the track's duration, floored at zero. */
        val overshootMs: Long,
        /** How far the track runs past [lastEndMs], floored at zero. */
        val trailingGapMs: Long,
    )

    /**
     * Whether [lines]' timings can belong to a track [durationMs] long.
     *
     * The evidence is the sung span the lines claim — the first line's
     * start (its first word's start where words exist) to the last
     * line's end (its last word's end, its stated end, or its stamp,
     * whichever reaches furthest; an answering vocal's end counts,
     * being sung content of its line) — read against the track's real
     * duration:
     *
     *  - A span ending more than [FIT_OVERSHOOT_TOLERANCE_MS] past the
     *    track cannot be this recording: a longer edit's words would
     *    still be singing after this track has stopped.
     *  - A span whose *known* end leaves more than
     *    [FIT_TRAILING_TOLERANCE_MS] of track after it cannot be this
     *    recording either: a shorter edit's words, done a minute before
     *    the music is.
     *  - A first vocal arriving after [FIT_ONSET_LATE_MS] *and* after
     *    three fifths of the track is not an intro, it is a different
     *    structure.
     *
     * Anything without evidence answers "fits": an unknown duration, a
     * candidate with no timed sung line, plain text (which claims no
     * timings at all). The check condemns only what the numbers rule
     * out, because its only power is re-ranking — see [isUpgradeWithFit]
     * — and a candidate it condemns is still the answer when it is the
     * only one.
     */
    fun assessFit(lines: List<LyricLine>, durationMs: Long): FitAssessment {
        if (durationMs <= 0L) return FitAssessment(true, null, null, 0L, 0L)
        var firstStart = Long.MAX_VALUE
        var lastEnd = Long.MIN_VALUE
        var lastEndKnown = false
        for (line in lines) {
            if (line.text.isBlank()) continue
            val start = lineStartMs(line)
            if (start < firstStart) firstStart = start
            val (end, known) = lineEndMs(line)
            if (end > lastEnd) {
                lastEnd = end
                lastEndKnown = known
            } else if (end == lastEnd && known) {
                lastEndKnown = true
            }
        }
        if (firstStart == Long.MAX_VALUE) {
            // No sung line claims any timing: nothing to rule out.
            return FitAssessment(true, null, null, 0L, 0L)
        }
        val overshoot = maxOf(0L, lastEnd - durationMs)
        val trailing = maxOf(0L, durationMs - lastEnd)
        val lateOnset = firstStart > FIT_ONSET_LATE_MS &&
            firstStart * FIT_ONSET_LATE_SHARE_DENOMINATOR >
            durationMs * FIT_ONSET_LATE_SHARE_NUMERATOR
        val fits = overshoot <= FIT_OVERSHOOT_TOLERANCE_MS &&
            !(lastEndKnown && trailing > FIT_TRAILING_TOLERANCE_MS) &&
            !lateOnset
        return FitAssessment(fits, firstStart, lastEnd, overshoot, trailing)
    }

    /** [assessFit] answered for a whole [result]. */
    fun fitsTrack(result: LyricsRepository.Result, durationMs: Long): Boolean =
        assessFit(result.lines, durationMs).fits

    /**
     * The first sung moment [line] claims: its first word's start where
     * it has words (an answering vocal's first word counts — it is sung
     * as part of this line), else its stamp.
     */
    private fun lineStartMs(line: LyricLine): Long {
        var start = line.words.firstOrNull()?.startMs ?: line.timeMs
        line.background?.let { start = minOf(start, lineStartMs(it)) }
        return start
    }

    /**
     * The last sung moment [line] claims and whether anything actually
     * stated it: the furthest of its last word's end, its stated end and
     * its stamp, with an answering vocal's end counted the same way.
     * The stamp alone is a start, not an end, so a line with neither
     * words nor a stated end reports its end as *not known* — the fact
     * the trailing-gap judgement in [assessFit] stands on.
     */
    private fun lineEndMs(line: LyricLine): Pair<Long, Boolean> {
        var end = line.timeMs
        var known = false
        line.words.lastOrNull()?.let {
            end = maxOf(end, it.endMs)
            known = true
        }
        line.sungUntilMs?.let {
            end = maxOf(end, it)
            known = true
        }
        line.background?.let { background ->
            val (backgroundEnd, backgroundKnown) = lineEndMs(background)
            end = maxOf(end, backgroundEnd)
            known = known || backgroundKnown
        }
        return end to known
    }

    /**
     * [isUpgrade] with version fit as the same-tier tiebreak: a
     * candidate that is strictly better by tier or timing strength is an
     * upgrade as ever; a candidate of the *same* tier and the same
     * timing strength is an upgrade when its timings can belong to a
     * track [durationMs] long and [current]'s cannot.
     *
     * Fit never crosses tiers — true word timings still outrank line
     * sync whatever either's fit — and it never manufactures a win from
     * nothing: when both fit, or neither does, priority order decides
     * exactly as [isUpgrade] leaves it.
     */
    fun isUpgradeWithFit(
        current: LyricsRepository.Result?,
        candidate: LyricsRepository.Result?,
        durationMs: Long,
    ): Boolean {
        candidate ?: return false
        current ?: return true
        if (isUpgrade(current, candidate)) return true
        if (tierOf(candidate) != tierOf(current)) return false
        if (timingRank(candidate.timing) != timingRank(current.timing)) return false
        return fitsTrack(candidate, durationMs) && !fitsTrack(current, durationMs)
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

    /**
     * Whether AI measurement is due for a track whose last full set of
     * attempts was exhausted at [exhaustedAtMs], judged at [nowMs].
     *
     * Due when there is no record at all ([exhaustedAtMs] is null) — the
     * track has never exhausted its attempts, so nothing throttles it —
     * and due once the full [intervalMs] has passed since the recorded
     * exhaustion, the boundary itself included. Not due inside the
     * interval, and not due when the record sits in the future: a clock
     * that moved backwards leaves a negative age, which is inside every
     * positive interval, so a skewed clock delays a retry rather than
     * triggering one early. Pure arithmetic, so the caller decides what
     * "now" is and tests need no clock.
     */
    fun isAiRetryDue(
        exhaustedAtMs: Long?,
        nowMs: Long,
        intervalMs: Long = AI_RETRY_INTERVAL_MS,
    ): Boolean {
        val exhaustedAt = exhaustedAtMs ?: return true
        return nowMs - exhaustedAt >= intervalMs
    }
}
