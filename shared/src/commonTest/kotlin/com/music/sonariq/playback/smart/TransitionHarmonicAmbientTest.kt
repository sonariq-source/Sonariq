package com.music.sonariq.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The harmonic factor in tier selection and the ambient strategy: keys
 * the analyzer trusts can demote a beat-match and reroute a blend, keys
 * it does not trust change nothing, and a pair that is calm at the join
 * earns the long ambient blend instead of either DJ treatment.
 */
class TransitionHarmonicAmbientTest {

    /**
     * A synthetic, fully analysed track on a steady grid. [energyAt]
     * shapes the energy curve over time so calm-edge fixtures can be
     * built; the default is the flat full-energy curve the rest of the
     * planner suites use.
     */
    private fun track(
        id: String,
        bpm: Double,
        key: String,
        vocal: Double,
        keyConfidence: Double = 0.9,
        energyAt: (Double) -> Double = { 1.0 },
        duration: Double = 200.0,
    ): TrackAnalysis {
        val beat = 60.0 / bpm
        val downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it < duration - 1 }.toList()
        val energy = (0 until (duration * 2).toInt()).map {
            EnergySample(it / 2.0, energyAt(it / 2.0))
        }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.9,
            downbeats = downbeats,
            phraseBoundaries = downbeats.filterIndexed { index, _ -> index % 8 == 0 },
            firstBeat = 0.5,
            key = key,
            keyConfidence = keyConfidence,
            audibleStartTime = 0.5,
            contentEndTime = duration - 2,
            mixInTime = downbeats[4],
            energyCurve = energy,
            vocalProbability = vocal,
        )
    }

    private fun plan(
        current: TrackAnalysis,
        next: TrackAnalysis,
        advanced: Boolean,
        currentTime: Double = 10.0,
    ) = planTransition(
        analysis = current,
        nextAnalysis = next,
        currentTrack = TransitionTrackInfo(current.trackId, (current.duration * 1000).toLong()),
        nextTrack = TransitionTrackInfo(next.trackId, (next.duration * 1000).toLong()),
        currentTime = currentTime,
        duration = current.duration,
        mode = CrossfadeMode.SMART,
        advanced = advanced,
    )

    @Test
    fun clashingTrustedKeysDemoteABeatmatchedPair() {
        val current = track("a", 120.0, "C major", vocal = 0.2)
        val next = track("b", 120.0, "F♯ major", vocal = 0.2)
        val verdict = assessTransitionTier(current, next, advanced = true)
        assertEquals(TransitionTier.DJ_ASSISTED, verdict.tier)
        assertTrue(verdict.reasons.contains("key-clash"))
        // And the plan follows the tier: no grid blend for a clashing pair.
        assertFalse(plan(current, next, advanced = true).transitionStyle == TransitionStyle.DJ_BLEND)
    }

    @Test
    fun clashingTrustedKeysRerouteTheClassicBlendToo() {
        val current = track("a", 120.0, "C major", vocal = 0.2)
        val next = track("b", 120.0, "F♯ major", vocal = 0.2)
        assertEquals(
            TransitionStyle.DJ_FILTER,
            plan(current, next, advanced = false).transitionStyle,
        )
    }

    @Test
    fun relativeKeysSupportABeatmatchedBlend() {
        val current = track("a", 120.0, "C major", vocal = 0.1)
        val next = track("b", 120.0, "A minor", vocal = 0.1)
        val verdict = assessTransitionTier(current, next, advanced = true)
        assertEquals(TransitionTier.BEATMATCHED, verdict.tier)
        assertFalse(verdict.reasons.contains("key-clash"))
        assertEquals(
            TransitionStyle.DJ_BLEND,
            plan(current, next, advanced = true).transitionStyle,
        )
    }

    @Test
    fun anUntrustedClashChangesNothing() {
        // Same clashing pairing, but one key is barely a guess: the guess
        // must not demote the tier or reroute the plan.
        val current = track("a", 120.0, "C major", vocal = 0.2)
        val next = track("b", 120.0, "F♯ major", vocal = 0.2, keyConfidence = 0.1)
        val verdict = assessTransitionTier(current, next, advanced = true)
        assertEquals(TransitionTier.BEATMATCHED, verdict.tier)
        assertFalse(verdict.reasons.contains("key-clash"))
    }

    /** Quiet at both edges of the join, full in the middle: a calm fixture. */
    private val calmEdges: (Double) -> Double = { t ->
        if (t < 16.0 || t > 176.0) 0.15 else 1.0
    }

    @Test
    fun aCalmPairEarnsTheAmbientStrategy() {
        val current = track("a", 72.0, "C major", vocal = 0.1, energyAt = calmEdges)
        val next = track("b", 72.0, "A minor", vocal = 0.1, energyAt = calmEdges)
        val plan = plan(current, next, advanced = true)
        assertEquals(TransitionStyle.AMBIENT, plan.transitionStyle)
        assertTrue(plan.fadeSeconds >= 10.0, "ambient fade ${plan.fadeSeconds}")
        // No tempo manipulation, ever: the ambient strategy blends levels only.
        assertEquals(1.0, plan.incomingPlaybackRate, 1e-9)
        assertEquals(1.0, plan.outgoingPlaybackRate, 1e-9)
    }

    @Test
    fun anEnergeticPairNeverGetsTheAmbientStrategy() {
        val current = track("a", 120.0, "C major", vocal = 0.1)
        val next = track("b", 120.0, "G major", vocal = 0.1)
        val plan = plan(current, next, advanced = true)
        assertFalse(plan.transitionStyle == TransitionStyle.AMBIENT)
        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
    }

    @Test
    fun aCalmPairWithSingingDoesNotGoAmbient() {
        // Calm edges but voices on both sides: the vocal-safe machinery
        // owns this pair, not the ambient blend.
        val current = track("a", 72.0, "C major", vocal = 0.9, energyAt = calmEdges)
        val next = track("b", 72.0, "A minor", vocal = 0.9, energyAt = calmEdges)
        assertFalse(plan(current, next, advanced = true).transitionStyle == TransitionStyle.AMBIENT)
    }
}
