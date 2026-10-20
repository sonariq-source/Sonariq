package com.music.sonariq.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The energy curves behind the 2.21 autoplay update: the preset queue curves
 * (Steady / Build / Peak & Coast / Wind down) that hand autoplay its energy
 * target as a session plays out, plus the workout session's warm-up / main
 * set / peak / cool-down profile and the wind-down session's slide.
 *
 * NOTE: these tests were written against the spec for SessionEnergy in
 * playback/AutoplaySessionMath.kt before that file existed; they pin the
 * specified values and must be reconciled with the implementation once it
 * lands (see the coordinator).
 */
class SessionEnergyTest {

    private fun assertCurve(expected: Float, preset: Int, tracksPlayed: Int) {
        assertEquals(expected, SessionEnergy.curveTarget(preset, tracksPlayed)!!, 0.001f)
    }

    // ----------------------------------------------------------------- Steady --

    @Test
    fun `steady has no energy target at any point`() {
        assertNull(SessionEnergy.curveTarget(0, 0))
        assertNull(SessionEnergy.curveTarget(0, 5))
        assertNull(SessionEnergy.curveTarget(0, 30))
    }

    // ------------------------------------------------------------------ Build --

    @Test
    fun `build climbs five points a track from a third`() {
        assertCurve(0.35f, 1, 0)
        assertCurve(0.40f, 1, 1)
        assertCurve(0.60f, 1, 5)
        assertCurve(0.65f, 1, 6)
    }

    @Test
    fun `build caps at 0 85`() {
        assertCurve(0.85f, 1, 10)
        assertCurve(0.85f, 1, 11)
        assertCurve(0.85f, 1, 12)
        assertCurve(0.85f, 1, 30)
    }

    // ----------------------------------------------------------- Peak and Coast --

    @Test
    fun `peak and coast ramps to the peak over six tracks`() {
        assertCurve(0.5f, 2, 0)
        assertCurve(0.7f, 2, 3)
        assertCurve(0.8333f, 2, 5)
    }

    @Test
    fun `peak and coast holds the peak through track eleven`() {
        assertCurve(0.9f, 2, 6)
        assertCurve(0.9f, 2, 8)
        assertCurve(0.9f, 2, 11)
    }

    @Test
    fun `peak and coast eases off after the peak and floors at 0 4`() {
        assertCurve(0.85f, 2, 12)
        assertCurve(0.65f, 2, 16)
        assertCurve(0.4f, 2, 21)
        assertCurve(0.4f, 2, 30)
    }

    // --------------------------------------------------------- Wind down curve --

    @Test
    fun `the wind down curve drops four points a track from 0 7`() {
        assertCurve(0.7f, 3, 0)
        assertCurve(0.5f, 3, 5)
        assertCurve(0.26f, 3, 11)
        assertCurve(0.22f, 3, 12)
    }

    @Test
    fun `the wind down curve floors at 0 2`() {
        assertCurve(0.2f, 3, 13)
        assertCurve(0.2f, 3, 20)
        assertCurve(0.2f, 3, 30)
    }

    // ----------------------------------------------------------- workoutTarget --

    @Test
    fun `the workout warm up rises from 0 45 to 0 6`() {
        assertEquals(0.45f, SessionEnergy.workoutTarget(0f), 0.001f)
        assertEquals(0.525f, SessionEnergy.workoutTarget(0.075f), 0.001f)
        assertEquals(0.6f, SessionEnergy.workoutTarget(0.15f), 0.001f)
    }

    @Test
    fun `the workout main set climbs to 0 9 by three quarters`() {
        assertEquals(0.75f, SessionEnergy.workoutTarget(0.45f), 0.001f)
        assertEquals(0.9f, SessionEnergy.workoutTarget(0.75f), 0.001f)
    }

    @Test
    fun `the workout peak holds through 0 9 of the session`() {
        assertEquals(0.9f, SessionEnergy.workoutTarget(0.8f), 0.001f)
        assertEquals(0.9f, SessionEnergy.workoutTarget(0.9f), 0.001f)
    }

    @Test
    fun `the workout cool down falls back to 0 45`() {
        assertEquals(0.675f, SessionEnergy.workoutTarget(0.95f), 0.001f)
        assertEquals(0.45f, SessionEnergy.workoutTarget(1f), 0.001f)
    }

    @Test
    fun `the workout target never falls before the cool down`() {
        var previous = SessionEnergy.workoutTarget(0f)
        var p = 0.05f
        while (p <= 0.75f) {
            val current = SessionEnergy.workoutTarget(p)
            assertTrue("workout target fell at progress $p", current >= previous - 0.001f)
            previous = current
            p += 0.05f
        }
    }

    @Test
    fun `the workout target never rises during the cool down`() {
        var previous = SessionEnergy.workoutTarget(0.9f)
        var p = 0.92f
        while (p <= 1.0f) {
            val current = SessionEnergy.workoutTarget(p)
            assertTrue("workout target rose at progress $p", current <= previous + 0.001f)
            previous = current
            p += 0.02f
        }
    }

    // ---------------------------------------------------------- windDownTarget --

    @Test
    fun `the wind down session slides from 0 6 to 0 15`() {
        assertEquals(0.6f, SessionEnergy.windDownTarget(0f), 0.001f)
        assertEquals(0.375f, SessionEnergy.windDownTarget(0.5f), 0.001f)
        assertEquals(0.15f, SessionEnergy.windDownTarget(1f), 0.001f)
    }

    @Test
    fun `the wind down session clamps progress outside the session`() {
        assertEquals(0.6f, SessionEnergy.windDownTarget(-0.5f), 0.001f)
        assertEquals(0.15f, SessionEnergy.windDownTarget(1.5f), 0.001f)
        assertEquals(0.15f, SessionEnergy.windDownTarget(2f), 0.001f)
    }
}
