package com.music.sonariq.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [VisualizerController]. The controller itself wraps
 * android.media.audiofx.Visualizer and can't run on the JVM; everything
 * decided here — capture sizes, callback rates, attach policy — lives in
 * the companion so it can be pinned down without the device.
 */
class VisualizerControllerTest {

    @Test
    fun `full rate is twenty frames a second`() {
        assertEquals(20_000, VisualizerController.captureRateMilliHz(false))
    }

    @Test
    fun `battery saver halves the callback rate`() {
        assertEquals(10_000, VisualizerController.captureRateMilliHz(true))
    }

    @Test
    fun `capture size takes the largest power of two the device allows`() {
        assertEquals(1024, VisualizerController.selectCaptureSize(intArrayOf(128, 1024), false))
        assertEquals(2048, VisualizerController.selectCaptureSize(intArrayOf(128, 2048), false))
    }

    @Test
    fun `battery saver caps the capture size at 512`() {
        assertEquals(512, VisualizerController.selectCaptureSize(intArrayOf(128, 1024), true))
        assertEquals(512, VisualizerController.selectCaptureSize(intArrayOf(128, 2048), true))
    }

    @Test
    fun `capture size never drops below the device minimum`() {
        assertEquals(256, VisualizerController.selectCaptureSize(intArrayOf(256, 256), false))
        assertEquals(256, VisualizerController.selectCaptureSize(intArrayOf(256, 256), true))
    }

    @Test
    fun `a missing range falls back to sane defaults`() {
        assertEquals(1024, VisualizerController.selectCaptureSize(intArrayOf(), false))
        assertEquals(512, VisualizerController.selectCaptureSize(intArrayOf(), true))
    }

    @Test
    fun `attach needs both a real session and a capture request`() {
        assertTrue(VisualizerController.shouldAttach(sessionId = 42, captureWanted = true))
        assertFalse(VisualizerController.shouldAttach(sessionId = 0, captureWanted = true))
        assertFalse(VisualizerController.shouldAttach(sessionId = -1, captureWanted = true))
        assertFalse(VisualizerController.shouldAttach(sessionId = 42, captureWanted = false))
        assertFalse(VisualizerController.shouldAttach(sessionId = 0, captureWanted = false))
    }
}
