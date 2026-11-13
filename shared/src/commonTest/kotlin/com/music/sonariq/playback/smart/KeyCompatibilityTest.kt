package com.music.sonariq.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Camelot wheel the compatibility scorer and the transition planner
 * share: same code, relative major/minor and ±1 neighbours compatible,
 * distant keys not, and nothing claimed for a key that was not measured
 * or not trusted.
 */
class KeyCompatibilityTest {

    @Test
    fun theSameKeyScoresFull() {
        assertEquals(1.0, camelotScore("C major", "C major"))
        assertEquals(1.0, camelotScore("A minor", "A minor"))
    }

    @Test
    fun relativeMajorAndMinorAreNearlyFullAndCompatible() {
        // C major and A minor share every note: the classic smooth join.
        assertEquals(0.95, camelotScore("C major", "A minor"))
        assertEquals(0.95, camelotScore("A minor", "C major"))
        assertTrue(camelotCompatible("C major", "A minor"))
    }

    @Test
    fun oneWheelStepOnTheSameLetterIsCompatible() {
        // Perfect-fifth neighbours: C major -> G major, A minor -> E minor.
        assertEquals(0.85, camelotScore("C major", "G major"))
        assertEquals(0.85, camelotScore("A minor", "E minor"))
        assertTrue(camelotCompatible("C major", "G major"))
        // Flats parse too: B♭ major -> E♭ major is the same fifth step.
        assertEquals(0.85, camelotScore("B♭ major", "E♭ major"))
    }

    @Test
    fun oneWheelStepAcrossLettersIsWeak() {
        // C major (8B) -> D minor (7A): adjacent on the wheel but the
        // mode flips, which is a rougher join than either pure neighbour.
        assertEquals(0.5, camelotScore("C major", "D minor"))
        assertFalse(camelotCompatible("C major", "D minor"))
    }

    @Test
    fun twoWheelStepsOnTheSameLetterAreWeak() {
        assertEquals(0.4, camelotScore("C major", "D major"))
        assertFalse(camelotCompatible("C major", "D major"))
    }

    @Test
    fun distantKeysScoreZero() {
        // C major (8B) against F♯ major (2B): the tritone pairing.
        assertEquals(0.0, camelotScore("C major", "F♯ major"))
        assertFalse(camelotCompatible("C major", "F♯ major"))
    }

    @Test
    fun unknownKeysScoreNothing() {
        assertNull(camelotScore("", "C major"))
        assertNull(camelotScore("C major", ""))
        assertNull(camelotScore("C major", "H major"))
        assertFalse(camelotCompatible("", "C major"))
    }

    @Test
    fun aKeyIsOnlyTrustedAtMeasuredConfidence() {
        val trusted = TrackAnalysis(trackId = "a", key = "C major", keyConfidence = 0.9)
        val shaky = TrackAnalysis(trackId = "b", key = "C major", keyConfidence = 0.1)
        val unmeasured = TrackAnalysis(trackId = "c", key = "", keyConfidence = 0.9)
        assertEquals("C major", trustedKeyOf(trusted))
        assertNull(trustedKeyOf(shaky))
        assertNull(trustedKeyOf(unmeasured))
    }

    @Test
    fun aClashNeedsTwoTrustedDistantKeys() {
        val cMajor = TrackAnalysis(trackId = "a", key = "C major", keyConfidence = 0.9)
        val fSharp = TrackAnalysis(trackId = "b", key = "F♯ major", keyConfidence = 0.9)
        val aMinor = TrackAnalysis(trackId = "c", key = "A minor", keyConfidence = 0.9)
        val shakyFSharp = TrackAnalysis(trackId = "d", key = "F♯ major", keyConfidence = 0.1)
        assertTrue(keyClashBetween(cMajor, fSharp))
        // A relative pair never clashes, and an untrusted key never vetoes.
        assertFalse(keyClashBetween(cMajor, aMinor))
        assertFalse(keyClashBetween(cMajor, shakyFSharp))
        assertFalse(keyClashBetween(cMajor, cMajor))
    }
}
