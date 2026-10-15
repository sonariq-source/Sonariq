package com.music.sonariq.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class VisualizerOverlayTest {

    // ---- circularBarAngles ----

    @Test
    fun `empty for no bars`() {
        assertEquals(0, circularBarAngles(0).size)
        assertEquals(0, circularBarAngles(-4).size)
    }

    @Test
    fun `first bar starts at the top`() {
        val angles = circularBarAngles(48)
        assertEquals(48, angles.size)
        // -PI/2 is straight up in canvas coordinates (y grows downward).
        assertEquals((-PI / 2).toFloat(), angles[0], 1e-6f)
    }

    @Test
    fun `bars are evenly spaced around the full circle`() {
        val n = 48
        val angles = circularBarAngles(n)
        val step = (2 * PI / n).toFloat()
        for (i in 1 until n) {
            assertEquals(step, angles[i] - angles[i - 1], 1e-5f)
        }
        // The last bar stops one step short of wrapping back onto the first.
        assertTrue(angles[n - 1] < angles[0] + 2 * PI.toFloat() - 1e-5f)
    }

    @Test
    fun `single bar sits at the top`() {
        val angles = circularBarAngles(1)
        assertEquals(1, angles.size)
        assertEquals((-PI / 2).toFloat(), angles[0], 1e-6f)
    }

    @Test
    fun `quarter turns land on the axes`() {
        // 4 bars: top, right, bottom, left.
        val angles = circularBarAngles(4)
        assertEquals((-PI / 2).toFloat(), angles[0], 1e-6f)
        assertEquals(0f, angles[1], 1e-6f)
        assertEquals((PI / 2).toFloat(), angles[2], 1e-6f)
        assertEquals(PI.toFloat(), angles[3], 1e-6f)
    }
}
