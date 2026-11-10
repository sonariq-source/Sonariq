package com.music.sonariq.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The policy half of AutoMix, pinned at the level the renderer trusts:
 * tempo math, tier routing, transition-point placement, vocal-aware
 * shaping, the no-analysis fallback and the handoff fields the
 * two-player controller consumes. Rendering itself is covered by the
 * desktop engine tests and by [AdvancedAutomixPlannerTest].
 */
class AutomixTransitionPolicyTest {

    /** A synthetic, fully analysed track on a steady grid, as in the advanced suite. */
    private fun track(
        id: String,
        bpm: Double,
        key: String,
        vocal: Double,
        duration: Double = 200.0,
        vocalMask: Boolean = false,
    ): TrackAnalysis {
        val beat = 60.0 / bpm
        val downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it < duration - 1 }.toList()
        val energy = (0 until (duration * 2).toInt()).map { EnergySample(it / 2.0, 1.0) }
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
            keyConfidence = 0.9,
            audibleStartTime = 0.5,
            contentEndTime = duration - 2,
            mixInTime = downbeats[4],
            energyCurve = energy,
            vocalProbability = vocal,
            vocalActivityMask = if (vocalMask) energy.map { 1.0 } else emptyList(),
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
    fun tempoOctaveAlignmentFoldsHalvesAndDoubles() {
        assertEquals(120.0, alignTempoOctave(120.0, 60.0), 1e-9)
        assertEquals(120.0, alignTempoOctave(120.0, 240.0), 1e-9)
        assertEquals(103.0, alignTempoOctave(100.0, 103.0), 1e-9)
        assertEquals(1.0, speedUpBetween(120.0, 120.0), 1e-9)
        assertEquals(1.05, speedUpBetween(120.0, 126.0), 1e-9)
    }

    @Test
    fun beatmatchWindowIsTightForClassicAndSpeedUpOnlyForAdvanced() {
        val current = track("a", 120.0, "C major", vocal = 0.2)
        val eightPercentAway = track("b", 129.6, "C major", vocal = 0.2)
        // Classic Automix stretches at most 4%: 8% apart is assisted, not matched.
        assertEquals(
            TransitionTier.DJ_ASSISTED,
            assessTransitionTier(current, eightPercentAway, advanced = false).tier,
        )
        // Advanced may speed one side up by up to 10%: the same pair matches.
        assertEquals(
            TransitionTier.BEATMATCHED,
            assessTransitionTier(current, eightPercentAway, advanced = true).tier,
        )
        // Beyond the speed-up window even Advanced declines to beat-match.
        val far = track("c", 144.0, "C major", vocal = 0.2)
        assertEquals(
            TransitionTier.DJ_ASSISTED,
            assessTransitionTier(current, far, advanced = true).tier,
        )
        assertTrue(
            assessTransitionTier(current, far, advanced = true)
                .reasons.contains("tempo-distance"),
        )
    }

    @Test
    fun untrustedGridsFallBackToAPlainCrossfadeTier() {
        val current = track("a", 120.0, "C major", vocal = 0.2)
            .copy(beatConfidence = 0.1)
        val next = track("b", 121.0, "C major", vocal = 0.2)
            .copy(beatConfidence = 0.1)
        val verdict = assessTransitionTier(current, next, advanced = true)
        assertEquals(TransitionTier.PLAIN_CROSSFADE, verdict.tier)
        assertTrue(verdict.reasons.contains("beat-confidence"))
    }

    @Test
    fun noAnalysisMeansAPlainEqualPowerFadeAndNoInventedTempo() {
        // The Part 2.7 fallback: nothing measured, so nothing is claimed.
        // The plan is the conventional crossfade at the configured length,
        // with no BPM, no stretch and no beat alignment.
        val plan = planTransition(
            currentTrack = TransitionTrackInfo("a", 200_000),
            nextTrack = TransitionTrackInfo("b", 200_000),
            currentTime = 10.0,
            duration = 200.0,
            fadeSeconds = 6.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        assertFalse(plan.blocked)
        assertEquals(TransitionStyle.EQUAL_POWER, plan.transitionStyle)
        assertEquals(6.0, plan.fadeSeconds, 1e-9)
        assertEquals(1.0, plan.incomingPlaybackRate, 1e-9)
        assertEquals(0.0, plan.outgoingBpm, 1e-9)
        assertEquals(0.0, plan.incomingBpm, 1e-9)
        assertEquals(0.0, plan.beatSeconds, 1e-9)
    }

    @Test
    fun aBeatmatchedPlanStartsOnTheOutgoingGridInWholeBars() {
        val current = track("a", 120.0, "C major", vocal = 0.1)
        val next = track("b", 120.0, "G major", vocal = 0.1)
        val plan = plan(current, next, advanced = true)

        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
        // Downbeats sit at 0.5 + k * 2s on this grid; the start is one of them.
        val offset = (plan.transitionStart - 0.5) % 2.0
        assertTrue(offset < 1e-6 || offset > 2.0 - 1e-6, "start off the grid: ${plan.transitionStart}")
        // The overlap is a whole number of bars (4 beats at 0.5s each).
        val bars = plan.fadeSeconds / 2.0
        assertEquals(bars, bars.toInt().toDouble(), 1e-6, "overlap not in whole bars: ${plan.fadeSeconds}")
    }

    @Test
    fun twoSingingTracksShortenTheOverlap() {
        val instrumentalOut = track("a", 120.0, "C major", vocal = 0.1)
        val instrumentalIn = track("b", 120.0, "G major", vocal = 0.1)
        val singingOut = track("a", 120.0, "C major", vocal = 0.95, vocalMask = true)
        val singingIn = track("b", 120.0, "G major", vocal = 0.95, vocalMask = true)

        val instrumental = plan(instrumentalOut, instrumentalIn, advanced = true)
        val singing = plan(singingOut, singingIn, advanced = true)

        assertTrue(
            singing.fadeSeconds < instrumental.fadeSeconds,
            "two vocals kept the full blend: ${singing.fadeSeconds} vs ${instrumental.fadeSeconds}",
        )
    }

    @Test
    fun vocalOverlapIsGovernedByTheQuieterSide() {
        assertEquals(0.0, vocalOverlapAmount(null, 0.95), 1e-9)
        assertEquals(0.0, vocalOverlapAmount(0.5, 0.95), 1e-9)
        assertEquals(0.75, vocalOverlapAmount(0.9, 0.9), 1e-9)
        assertEquals(0.25, vocalOverlapAmount(0.7, 0.95), 1e-9)
    }

    @Test
    fun theTransitionEndsAtTheAnalyzedContentEndNotTheFileEnd() {
        // A long quiet tail: the analysis says the music ends 30s before
        // the file does, and the plan must honour that rather than
        // blending across the silence.
        val current = track("a", 120.0, "C major", vocal = 0.2)
            .copy(contentEndTime = 170.0)
        val next = track("b", 97.0, "F♯ major", vocal = 0.2)
        val plan = plan(current, next, advanced = true)

        assertTrue(plan.transitionEnd <= 170.0 + 1e-6, "end past content end: ${plan.transitionEnd}")
        assertTrue(plan.transitionEnd < current.duration)
    }

    @Test
    fun aBeatmatchedPlanHandsTheRendererAConsistentCue() {
        // The fields the two-player handoff consumes: at matched tempi the
        // incoming cue and handoff bracket exactly the overlap the
        // outgoing side fades over, and the handoff sits at the
        // equal-power midpoint.
        val current = track("a", 120.0, "C major", vocal = 0.1)
        val next = track("b", 120.0, "G major", vocal = 0.1)
        val plan = plan(current, next, advanced = true)

        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
        assertEquals(
            plan.fadeSeconds,
            plan.incomingHandoffTime - plan.incomingCueTime,
            1e-6,
        )
        assertEquals(
            plan.fadeSeconds,
            plan.transitionEnd - plan.transitionStart,
            1e-6,
        )
        assertEquals(0.5, plan.handoffFraction, 1e-9)
        assertTrue(plan.incomingCueTime >= 0.0)
        assertTrue(plan.transitionEnd <= current.duration)
        assertFalse(plan.phaseLock)
    }
}
