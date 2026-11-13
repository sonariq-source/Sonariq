package com.music.sonariq.data.lyrics

/**
 * What can honestly be said about a lyrics result: whether its words are
 * a provider's and verified against the recording, whether its word
 * timings were measured from the track's own audio, whether it is only
 * line-timed, or whether what it claims cannot belong to the track it
 * would be shown against.
 *
 * This is the classification the accept / reject / replace decisions are
 * stated in. It is internal: the player never badges a song with its
 * verdict, and a verdict is only ever as strong as the checks behind it
 * — a result is [VERIFIED_WORD_SYNC] when a provider supplied true word
 * timings *and* those timings can belong to this recording, never merely
 * because a provider answered.
 */
enum class LyricsVerdict {
    /** A provider's plain text: the provider's match is its only claim, and nothing contradicts it. */
    VERIFIED_PROVIDER,

    /** A provider's true word timings, and they fit this recording. */
    VERIFIED_WORD_SYNC,

    /**
     * The words themselves are a transcription of the track's own audio.
     * No path in the current pipeline produces one — the AI alignment
     * borrows a transcription's *timings* for a provider's text and
     * discards the transcription's words, so nothing the player shows is
     * ever a raw transcription. The state exists so that if such a path
     * is ever added, its results classify as what they are from the
     * first day, and so nothing today can be mistaken for one.
     */
    AI_TRANSCRIBED,

    /** Word timings measured from the track's own audio by the AI alignment, and they fit. */
    AI_MEASURED,

    /** Honest whole-line timing: the words are a provider's, the stamps only place lines. */
    LINE_SYNCED,

    /**
     * Something was found, but what it claims cannot belong to this
     * recording — its sung span overshoots the track, ends long before
     * it, or starts where no intro could — or its timings were only ever
     * estimated. An unverified result's word timings are never shown
     * (see [LyricsValidation.withoutUnfitWordTimings]); its text may
     * still be shown line-timed, as the less precise but honest display,
     * and it is never presented as verified anywhere.
     */
    UNVERIFIED,

    /** Nothing was found at all. */
    NO_LYRICS,
}

/**
 * The validation decisions behind the lyrics pipeline, stated once and
 * used by the repository's delivery and the player's application alike,
 * so a result judged in one place is judged the same in the other.
 *
 * The evidence is [LyricsLookupPolicy.assessFit]: a candidate's sung
 * span read against the track's real duration, with that policy's
 * tolerances as the thresholds. Fit is a claim about *timings* — it can
 * prove a word-synced result belongs to a different recording, and it
 * says nothing about whether the same words, untimed, are the song's.
 * That asymmetry is deliberate, and it is why the rejection below falls
 * on timings rather than text: wrong *words* are kept out by recording
 * identification ([RecordingMatch], applied where a provider exposes
 * what it matched) and by the aligner's refusal to time a transcription
 * that does not match; wrong *timings* are what fit can prove, and
 * timings are what it rejects.
 *
 * Pure: no I/O, no clock. An unknown duration (0) proves nothing, so
 * nothing is condemned on it — the same discipline [assessFit] keeps.
 */
object LyricsValidation {

    /**
     * The verdict on [result] for a track [durationMs] long; a null
     * result is [LyricsVerdict.NO_LYRICS].
     */
    fun classify(result: LyricsRepository.Result?, durationMs: Long): LyricsVerdict {
        result ?: return LyricsVerdict.NO_LYRICS
        val fits = LyricsLookupPolicy.assessFit(result.lines, durationMs).fits
        return when (result.timing) {
            LyricTiming.WORD_SYNCED ->
                if (fits) LyricsVerdict.VERIFIED_WORD_SYNC else LyricsVerdict.UNVERIFIED
            LyricTiming.WORD_SYNCED_AI_MEASURED ->
                if (fits) LyricsVerdict.AI_MEASURED else LyricsVerdict.UNVERIFIED
            LyricTiming.WORD_SYNCED_ESTIMATED ->
                // Interpolated timings were never measured against
                // anything; no fit verdict can rescue them.
                LyricsVerdict.UNVERIFIED
            LyricTiming.LINE_SYNCED ->
                if (fits) LyricsVerdict.LINE_SYNCED else LyricsVerdict.UNVERIFIED
            LyricTiming.PLAIN ->
                // Plain text claims no timings, so fit has nothing to
                // judge: the provider match is the whole claim.
                LyricsVerdict.VERIFIED_PROVIDER
        }
    }

    /**
     * [result] with word timings it cannot honestly carry taken out.
     *
     * When [classify] judges a word-timed result
     * [LyricsVerdict.UNVERIFIED] — its timings provably belong to a
     * different recording — the timings are rejected: every word timing
     * is stripped ([withoutWordTimings]) and the result comes back as
     * the honest line sync underneath, its text untouched. Showing those
     * words lighting up word by word would present another recording's
     * performance as this one's, which is the one failure this system
     * exists to prevent; showing the same words line-timed claims only
     * what may be true.
     *
     * Anything else — a fitting result, a result with no word timings,
     * any result at all when the duration is unknown — is returned
     * exactly as it was, the same instance.
     */
    fun LyricsRepository.Result.withoutUnfitWordTimings(
        durationMs: Long,
    ): LyricsRepository.Result {
        if (durationMs <= 0L) return this
        if (timing != LyricTiming.WORD_SYNCED &&
            timing != LyricTiming.WORD_SYNCED_AI_MEASURED
        ) {
            return this
        }
        if (classify(this, durationMs) != LyricsVerdict.UNVERIFIED) return this
        val stripped = lines.withoutWordTimings()
        return copy(lines = stripped, timing = stripped.lyricTiming())
    }
}
