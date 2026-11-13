package com.music.sonariq.playback

import com.music.sonariq.data.model.Song
import com.music.sonariq.playback.smart.EnergySample
import com.music.sonariq.playback.smart.TrackAnalysis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The discovery compatibility scorer at decision level: what it weighs,
 * when it may judge, how rejection and the best-available fallback
 * behave, and that the session's no-repeat machinery composes with it
 * unchanged.
 */
class AutoplayCompatibilityTest {

    private fun song(id: String, artist: String = "Artist $id") =
        Song(videoId = id, title = "Song $id", artist = artist, thumbnailUrl = null)

    /** A measured track with a flat energy curve at [energy] and loudness [loudness]. */
    private fun analysis(
        id: String,
        bpm: Double,
        key: String,
        energy: Double,
        vocal: Double,
        loudness: Double = 0.0,
    ): TrackAnalysis = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = id,
        duration = 200.0,
        bpm = bpm,
        beatInterval = 60.0 / bpm,
        beatConfidence = 0.9,
        key = key,
        keyConfidence = 0.9,
        energyCurve = (0 until 40).map { EnergySample(it.toDouble(), energy) },
        vocalProbability = vocal,
        loudnessLufs = loudness,
    )

    private val chillSeed = analysis("seed", bpm = 75.0, key = "C major", energy = 0.25, vocal = 0.8, loudness = -16.0)
    private val chill = analysis("chill", bpm = 78.0, key = "A minor", energy = 0.30, vocal = 0.85, loudness = -15.0)
    private val metal = analysis("metal", bpm = 160.0, key = "F♯ major", energy = 0.95, vocal = 0.9, loudness = -7.0)

    private fun scoredWith(
        candidate: TrackAnalysis,
        signals: AutoplayCandidateSignals = AutoplayCandidateSignals(analysis = candidate),
    ) = AutoplayCompatibility.score(chillSeed, song(candidate.trackId), signals)

    @Test
    fun chillFollowsChillAndMetalIsRejected() {
        val chillScore = scoredWith(chill)
        val metalScore = scoredWith(metal)
        assertTrue(chillScore.judgeable)
        assertTrue(chillScore.passes, "chill scored ${chillScore.score}")
        assertTrue(chillScore.score > 0.9)
        // The metal track is not vetoed by any single factor — its key
        // is even folded to a near tempo — but energy, key and vocals
        // together price it below the acceptance line.
        assertTrue(metalScore.judgeable)
        assertTrue(metalScore.rejected, "metal scored ${metalScore.score}")
        assertTrue(metalScore.score < chillScore.score)
    }

    @Test
    fun atmosphereFitOutweighsArtistFamiliarity() {
        // The "different genre, compatible atmosphere" case at decision
        // level: no genre field exists on a song, so familiarity arrives
        // as artist taste. A fully familiar artist cannot rescue a track
        // whose measured atmosphere clashes, and an unfamiliar one with
        // the right atmosphere still wins.
        val familiarButClashing = AutoplayCompatibility.score(
            chillSeed,
            song("metal"),
            AutoplayCandidateSignals(analysis = metal, artistTaste = 1.0),
        )
        val unfamiliarButFitting = scoredWith(chill)
        assertTrue(familiarButClashing.score < unfamiliarButFitting.score)
    }

    @Test
    fun curveShapeIsTheEnergyFallbackWhenLoudnessIsUnmeasured() {
        // Neither track carries loudness, so energy is judged on the
        // curves' sustainedness alone: a wall of sound against a dynamic
        // track prices lower than the same wall against itself, with
        // tempo, key and vocals held identical.
        val sustained = analysis("a", bpm = 90.0, key = "C major", energy = 1.0, vocal = 0.5)
        val dynamicCurve = (0 until 40).map {
            EnergySample(it.toDouble(), if (it % 2 == 0) 1.0 else 0.1)
        }
        val dynamic = sustained.copy(trackId = "b", energyCurve = dynamicCurve)
        val sameShape = AutoplayCompatibility.score(
            sustained,
            song("a2"),
            AutoplayCandidateSignals(analysis = sustained.copy(trackId = "a2")),
        ).score
        val otherShape = AutoplayCompatibility.score(
            sustained,
            song("b"),
            AutoplayCandidateSignals(analysis = dynamic),
        ).score
        assertTrue(otherShape < sameShape, "dynamic $otherShape vs sustained $sameShape")
    }

    @Test
    fun halfAndDoubleTimeCountAsTheSameTempo() {
        val atTempo = analysis("a", bpm = 75.0, key = "C major", energy = 0.25, vocal = 0.8)
        val atDouble = analysis("b", bpm = 150.0, key = "C major", energy = 0.25, vocal = 0.8)
        val atHalf = analysis("c", bpm = 37.5, key = "C major", energy = 0.25, vocal = 0.8)
        val base = scoredWith(atTempo).score
        assertEquals(base, scoredWith(atDouble).score, 1e-9)
        assertEquals(base, scoredWith(atHalf).score, 1e-9)
    }

    @Test
    fun skippedKindsOfPicksScoreDown() {
        val plain = scoredWith(chill)
        val demoted = AutoplayCompatibility.score(
            chillSeed,
            song("chill"),
            AutoplayCandidateSignals(analysis = chill, artistSkipShare = 1.0),
        )
        assertEquals(plain.score * 0.65, demoted.score, 1e-9)
        // Demotion is a price, not a ban: a third skipped still leaves a
        // passing candidate passing.
        val third = AutoplayCompatibility.score(
            chillSeed,
            song("chill"),
            AutoplayCandidateSignals(analysis = chill, artistSkipShare = 1.0 / 3.0),
        )
        assertTrue(third.passes)
    }

    @Test
    fun aLearnedContinuationIsStrongEvidence() {
        val linked = AutoplayCompatibility.score(
            chillSeed,
            song("metal"),
            AutoplayCandidateSignals(analysis = metal, learnedAffinity = 1.0),
        )
        // Even the strongest learned link cannot make the clashing track
        // the top pick — mood and energy still dominate — but it lifts it
        // clear of rejection: the listener's own habit is real evidence.
        assertTrue(linked.score > scoredWith(metal).score)
        assertFalse(linked.rejected)
    }

    @Test
    fun noEvidenceIsNeverJudged() {
        val blind = AutoplayCompatibility.score(null, song("x"), AutoplayCandidateSignals())
        assertEquals(0.0, blind.coverage, 1e-9)
        assertFalse(blind.judgeable)
        assertFalse(blind.rejected)
        // A single thin signal is not enough to judge on either.
        val thin = AutoplayCompatibility.score(
            null,
            song("x"),
            AutoplayCandidateSignals(trackTaste = 1.0),
        )
        assertFalse(thin.judgeable)
        assertFalse(thin.rejected)
    }

    @Test
    fun stageOneHoldsBackRejectedCandidatesAndKeepsTheBlind() {
        val pool = listOf(song("metal"), song("chill"), song("blind"))
        val scored = AutoplayCompatibility.rank(chillSeed, pool) { candidate ->
            when (candidate.videoId) {
                "metal" -> AutoplayCandidateSignals(analysis = metal)
                "chill" -> AutoplayCandidateSignals(analysis = chill)
                else -> AutoplayCandidateSignals()
            }
        }
        val stageOne = AutoplayCompatibility.stageOne(scored)
        // The passing candidate leads; the unjudgeable one keeps its pool
        // place behind it; the rejected one is gone while the pool lasts.
        assertEquals(listOf("chill", "blind"), stageOne.map { it.videoId })
        // The fallback returns everybody, rejected last.
        val all = AutoplayCompatibility.allRanked(scored)
        assertEquals(listOf("chill", "blind", "metal"), all.map { it.videoId })
    }

    @Test
    fun theBestAvailableIsStillDeliveredWhenEverythingFails() {
        val pool = listOf(song("metal"))
        val scored = AutoplayCompatibility.rank(chillSeed, pool) {
            AutoplayCandidateSignals(analysis = metal)
        }
        assertTrue(AutoplayCompatibility.stageOne(scored).isEmpty())
        assertEquals(
            listOf("metal"),
            AutoplayCompatibility.allRanked(scored).map { it.videoId },
        )
    }

    @Test
    fun sessionExclusionStillDecidesWhatRepeats() {
        // The scorer ranks; the session memory still owns repetition.
        // A heard candidate, however well it scores, is not re-picked
        // while an unheard one fills the slot.
        val session = AutoplaySessionMemory()
        val current = song("seed")
        val heardChill = song("chill")
        val freshChill = song("chill2")
        session.noteBecameCurrent(current.videoId, previousEnded = false)
        session.noteBecameCurrent(heardChill.videoId, previousEnded = true)
        val pool = listOf(heardChill, freshChill)
        val scored = AutoplayCompatibility.rank(chillSeed, pool) { candidate ->
            AutoplayCandidateSignals(
                analysis = if (candidate.videoId == "chill") chill else chill.copy(trackId = "chill2"),
            )
        }
        val picks = session.selectForQueue(
            AutoplayCompatibility.stageOne(scored),
            // The queue plus the session's history songs, as the service
            // passes them: history rides inside `existing`, which is how
            // the tier exclusions reach the builder.
            existing = listOf(current, heardChill),
            queueIds = setOf(current.videoId),
            limit = 1,
        )
        assertEquals(listOf("chill2"), picks.map { it.videoId })
    }
}
