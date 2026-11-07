package com.music.sonariq.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The waveform seek bar's promise is that its heights are a stable
 * fingerprint of the track id alone: same id, same soundline, forever;
 * different id, different soundline; and every height inside the band
 * the bar draws.
 */
class WaveformHeightsTest {

    @Test
    fun sameSeedSameHeights() {
        val a = waveformBarHeights("dQw4w9WgXcQ", 48)
        val b = waveformBarHeights("dQw4w9WgXcQ", 48)
        assertEquals(a.toList(), b.toList())
    }

    @Test
    fun differentSeedsDiffer() {
        val a = waveformBarHeights("track-one", 48)
        val b = waveformBarHeights("track-two", 48)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun heightsStayInBand() {
        val heights = waveformBarHeights("any-track-id", 48)
        assertEquals(48, heights.size)
        assertTrue(heights.all { it in 0.22f..1.0f })
    }

    @Test
    fun emptySeedStillDraws() {
        val heights = waveformBarHeights("", 48)
        assertEquals(48, heights.size)
        assertTrue(heights.all { it in 0.22f..1.0f })
    }

    @Test
    fun zeroCountIsEmpty() {
        assertEquals(0, waveformBarHeights("x", 0).size)
    }
}
