package com.music.sonariq.playback

import com.music.sonariq.data.model.Song
import com.music.sonariq.playback.smart.TrackAnalysis
import com.music.sonariq.playback.smart.alignTempoOctave
import com.music.sonariq.playback.smart.camelotScore
import kotlin.math.abs
import kotlin.math.min

/**
 * Analysis-scored Autoplay selection: how well one candidate fits the
 * track that is actually playing, judged from evidence rather than from
 * its position in a radio list.
 *
 * Until this existed, the discovery pool reached selection in the order
 * the provider returned it, seasoned by learned predictions and by
 * cross-session freshness — nothing ever asked whether a candidate
 * *sounded* like a continuation of the current song. This scorer asks.
 * Its inputs are only what the app genuinely knows:
 *
 *  - the current track's stored analysis and the candidate's, when the
 *    analyzer has measured either (energy character — loudness when
 *    both carry it, else curve shape — tempo, Camelot key, vocal
 *    likelihood; each scored as closeness, tempo with half/double-time
 *    folded in the way the transition policy folds it);
 *  - the learned song-to-song transition affinity for this exact pair,
 *    when the predictor has one (absence is not evidence against — a
 *    fresh discovery has no learned link by definition);
 *  - taste: the candidate track's own play history, and its artist's,
 *    aggregated from the same history;
 *  - popularity: the candidate's place on the listener's country
 *    charts (see [PopularPool]), rank-decayed — real familiarity
 *    evidence, but a bounded one: it seasons the fit judgement, it
 *    never replaces it (see [popularityForRank]);
 *  - how often this session has skipped this candidate's artist, which
 *    demotes rather than vetoes.
 *
 * Nothing is fetched or analyzed per candidate: a stream candidate the
 * analyzer has never measured is scored on the non-analysis factors
 * alone, and the score's [Scored.coverage] says how much of the full
 * judgement was actually available. A candidate is *rejected* only when
 * it was judgeable — enough evidence present — and still scored below
 * [MIN_SCORE]. Unjudgeable candidates are never rejected on a guess:
 * they keep their pool order behind the passing ones, and if a whole
 * pool fails, the full ranked order is the fallback, best first, so
 * selection never stalls and never pretends a weak match was strong.
 *
 * Pure and deterministic; the weights are constants, not settings,
 * because they describe what a good continuation is rather than a taste
 * the listener tunes (the exploration setting already bends how far
 * Autoplay may wander, upstream of here).
 */
data class AutoplayCandidateSignals(
    /** The candidate's stored analysis, when the analyzer has measured it. */
    val analysis: TrackAnalysis? = null,
    /** Learned affinity for current → candidate, 0..1; null when no link is known. */
    val learnedAffinity: Double? = null,
    /** The candidate track's own taste score, 0..1; null when no history was readable. */
    val trackTaste: Double? = null,
    /** The candidate artist's taste score, 0..1; null under the same condition. */
    val artistTaste: Double? = null,
    /**
     * Of the session tracks sharing the candidate's artist, the fraction
     * the listener skipped, 0..1; null when the session has not heard the
     * artist at all.
     */
    val artistSkipShare: Double? = null,
    /**
     * The candidate's rank in the popular pool (0 = the top of the
     * listener's country charts), or null when it is not charting.
     * Priced through [AutoplayCompatibility.popularityForRank].
     */
    val chartRank: Int? = null,
)

object AutoplayCompatibility {

    /** A judgeable candidate scoring below this is rejected while the search continues. */
    const val MIN_SCORE = 0.5

    /** The weight fraction that must be present before a score may reject anyone. */
    const val MIN_COVERAGE = 0.25

    private const val W_ENERGY = 0.20
    private const val W_BPM = 0.15
    private const val W_KEY = 0.15
    private const val W_VOCAL = 0.08
    private const val W_LEARNED = 0.17
    private const val W_TRACK_TASTE = 0.10
    private const val W_ARTIST_TASTE = 0.15
    private const val W_POPULARITY = 0.18

    /** How hard a fully-skipped artist demotes its candidates: a multiplier, never a veto. */
    private const val SKIP_DEMOTION = 0.35

    /** One candidate's judgement. */
    data class Scored(
        val song: Song,
        /** Weighted compatibility, 0..1; meaningful only beside [coverage]. */
        val score: Double,
        /** Fraction of the full judgement's weight that evidence existed for, 0..1. */
        val coverage: Double,
    ) {
        /** Enough evidence existed for the score to mean something. */
        val judgeable: Boolean get() = coverage >= MIN_COVERAGE

        /** Judgeable and good enough to lead a batch. */
        val passes: Boolean get() = judgeable && score >= MIN_SCORE

        /** Judgeable and not good enough: held back while alternatives exist. */
        val rejected: Boolean get() = judgeable && score < MIN_SCORE
    }

    /**
     * Scores [candidate] against the currently playing track, whose own
     * analysis is [currentAnalysis] (null when it has none usable).
     */
    fun score(
        currentAnalysis: TrackAnalysis?,
        candidate: Song,
        signals: AutoplayCandidateSignals,
    ): Scored {
        var weighted = 0.0
        var weight = 0.0
        fun add(w: Double, value: Double?) {
            if (value == null) return
            weighted += w * value.coerceIn(0.0, 1.0)
            weight += w
        }

        val current = currentAnalysis?.takeIf { it.isUsable }
        val theirs = signals.analysis?.takeIf { it.isUsable }
        if (current != null && theirs != null) {
            add(W_ENERGY, energyCloseness(current, theirs))
            add(W_BPM, bpmCloseness(current.bpm, theirs.bpm))
            add(W_KEY, camelotScore(current.key, theirs.key))
            add(W_VOCAL, 1.0 - abs(current.vocalProbability - theirs.vocalProbability))
        }
        add(W_LEARNED, signals.learnedAffinity)
        add(W_TRACK_TASTE, signals.trackTaste)
        add(W_ARTIST_TASTE, signals.artistTaste)
        add(W_POPULARITY, signals.chartRank?.let(::popularityForRank))

        var value = if (weight > 0) weighted / weight else 0.0
        signals.artistSkipShare?.let { share ->
            value *= 1.0 - SKIP_DEMOTION * share.coerceIn(0.0, 1.0)
        }
        return Scored(candidate, value, weight)
    }

    /**
     * How much a chart rank is worth, 0..1: the top of the chart is
     * full-strength familiarity, halved by rank 8 and quartered by
     * rank 24, fading down the chart rather than cutting off at an
     * arbitrary depth.
     *
     * The bound that matters is the weight, not the curve: popularity
     * enters the weighted average at [W_POPULARITY] of the full
     * judgement, so it can only ever move a score by that share. A
     * candidate whose fit evidence is zero everywhere stays rejected
     * even at rank 0 (0.18 of weight against zeroes elsewhere prices
     * under the line), and no rank outranks a genuinely good fit —
     * popular *and* fitting beats obscure and fitting, which is the
     * whole of what the charts are allowed to say.
     */
    fun popularityForRank(rank: Int): Double {
        if (rank < 0) return 0.0
        return 1.0 / (1.0 + rank / 8.0)
    }

    /** Scores every song in [pool], in pool order. */
    fun rank(
        currentAnalysis: TrackAnalysis?,
        pool: List<Song>,
        signalsFor: (Song) -> AutoplayCandidateSignals,
    ): List<Scored> = pool.map { score(currentAnalysis, it, signalsFor(it)) }

    /**
     * The pool selection should draw from first: passing candidates,
     * best score first, then the unjudgeable in their original pool
     * order (their order already carries the provider's and freshness's
     * judgement). Rejected candidates are absent — that absence is what
     * lets the caller's re-request path fire when a whole pool fails.
     */
    fun stageOne(scored: List<Scored>): List<Song> =
        scored.filter { it.passes }.sortedByDescending { it.score }.map { it.song } +
            scored.filter { !it.judgeable }.map { it.song }

    /**
     * The whole pool in ranked order: [stageOne], then the rejected,
     * best of them first. The fallback when nothing passed — the batch
     * still gets built, from the least-bad candidates, and the caller
     * marks it lower confidence rather than the score pretending
     * otherwise.
     */
    fun allRanked(scored: List<Scored>): List<Song> =
        stageOne(scored) +
            scored.filter { it.rejected }.sortedByDescending { it.score }.map { it.song }

    /** Loudness difference, in LUFS, at which two tracks share no energy character. */
    private const val LOUDNESS_SPAN = 14.0

    /**
     * A track's energy *shape*: mean energy against its own 90th
     * percentile — how sustained the track is against its own peaks.
     * The analyzer normalizes every curve against the track's own
     * reference level, so the curve's shape is comparable across tracks
     * and its absolute level is not; nothing here pretends otherwise.
     * Null when there is no curve to read.
     */
    private fun energyShape(analysis: TrackAnalysis): Double? {
        val energies = analysis.energyCurve
            .map { it.energy }
            .filter { it.isFinite() && it >= 0 }
        if (energies.size < 2) return null
        val sorted = energies.sorted()
        val reference = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.lastIndex)]
        if (reference <= 0) return null
        return (energies.sum() / energies.size / reference).coerceIn(0.0, 1.0)
    }

    /**
     * How close two tracks' energy character sits. Loudness is the
     * honest cross-track figure — measured against full scale — so when
     * both tracks carry it, closeness is priced on it. When either side
     * has none, the fallback is shape closeness (see [energyShape]).
     */
    private fun energyCloseness(current: TrackAnalysis, candidate: TrackAnalysis): Double? {
        val loudA = current.loudnessLufs.takeIf { it in -70.0..-1.0 }
        val loudB = candidate.loudnessLufs.takeIf { it in -70.0..-1.0 }
        if (loudA != null && loudB != null) {
            return 1.0 - min(abs(loudA - loudB) / LOUDNESS_SPAN, 1.0)
        }
        val a = energyShape(current) ?: return null
        val b = energyShape(candidate) ?: return null
        return 1.0 - abs(a - b)
    }

    /**
     * Tempo closeness with half/double-time folded in first (a 75 BPM
     * ballad and a 150 BPM track whose beat lands the same way are close;
     * [alignTempoOctave] is the transition policy's own folding). A 50%
     * post-fold deviation is no closeness at all.
     */
    private fun bpmCloseness(currentBpm: Double, candidateBpm: Double): Double? {
        if (currentBpm <= 0 || candidateBpm <= 0) return null
        val aligned = alignTempoOctave(currentBpm, candidateBpm)
        val deviation = abs(aligned / currentBpm - 1.0)
        return (1.0 - deviation * 2).coerceIn(0.0, 1.0)
    }
}
