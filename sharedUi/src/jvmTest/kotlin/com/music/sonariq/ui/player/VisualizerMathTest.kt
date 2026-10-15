package com.music.sonariq.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VisualizerMathTest {

    // ---- fftToBarMagnitudes ----

    /** A capture of [captureSize] bytes with one (re, im) pair poked into bin [bin]. */
    private fun fftWithBin(captureSize: Int, bin: Int, re: Byte, im: Byte): ByteArray {
        val fft = ByteArray(captureSize)
        fft[2 * bin] = re
        fft[2 * bin + 1] = im
        return fft
    }

    @Test
    fun `a single strong bin lights its own bar loudest`() {
        // Capture size 16 -> usable bins 1..7.
        val bars = fftToBarMagnitudes(fftWithBin(16, 3, 100, 0), 8)
        assertEquals(8, bars.size)
        val peak = bars.indices.maxBy { bars[it] }
        assertEquals(binToBar(3, 7, 8), peak)
        assertTrue(bars[peak] > 0.5f)
        assertTrue(bars.all { it in 0f..1f })
    }

    @Test
    fun `dc is skipped even when it dominates`() {
        val fft = ByteArray(16)
        fft[0] = 127 // DC magnitude pegged; bins carry nothing.
        val bars = fftToBarMagnitudes(fft, 8)
        assertTrue(bars.all { it == 0f })
    }

    @Test
    fun `full-scale bins normalise to one`() {
        // Every bin at hypot(127, 127): the theoretical ceiling.
        val fft = ByteArray(16) { 127 }
        val bars = fftToBarMagnitudes(fft, 8)
        assertTrue(bars.any { abs(it - 1f) < 0.001f })
        assertTrue(bars.all { it <= 1f })
    }

    @Test
    fun `imaginary part counts as much as real`() {
        val reOnly = fftToBarMagnitudes(fftWithBin(16, 2, 90, 0), 8)
        val imOnly = fftToBarMagnitudes(fftWithBin(16, 2, 0, 90), 8)
        val peak = binToBar(2, 7, 8)
        assertEquals(reOnly[peak], imOnly[peak], 0.001f)
    }

    @Test
    fun `too-short captures produce silence rather than crashing`() {
        assertTrue(fftToBarMagnitudes(ByteArray(0), 8).all { it == 0f })
        assertTrue(fftToBarMagnitudes(ByteArray(2), 8).all { it == 0f })
    }

    @Test
    fun `result always has exactly barCount bars`() {
        assertEquals(48, fftToBarMagnitudes(ByteArray(1024), 48).size)
        assertEquals(1, fftToBarMagnitudes(ByteArray(1024), 1).size)
    }

    // ---- binToBar ----

    @Test
    fun `log bucketing is monotonic and covers the whole range`() {
        val bins = 511 // capture size 1024
        val barCount = 48
        var previous = -1
        for (k in 1..bins) {
            val bar = binToBar(k, bins, barCount)
            assertTrue("bin $k went backwards", bar >= previous)
            previous = bar
        }
        assertEquals(0, binToBar(1, bins, barCount))
        assertEquals(barCount - 1, binToBar(bins, bins, barCount))
    }

    @Test
    fun `log bucketing gives bass as many bars per octave as treble`() {
        val bins = 511
        val barCount = 48
        // Two octaves each: bins 1..4 against bins 128..511.
        // What log bucketing guarantees is equal bar *span* per octave, not
        // equal distinct hits: four discrete bins can never light more than
        // four bars, however wide their octave range.
        val bassSpan = (1..4).map { binToBar(it, bins, barCount) }
            .let { it.max() - it.min() }
        val trebleSpan = (128..511).map { binToBar(it, bins, barCount) }
            .let { it.max() - it.min() }
        assertTrue(
            "bass spans $bassSpan bars, treble spans $trebleSpan",
            bassSpan >= trebleSpan,
        )
    }

    // ---- waveformToAmplitudes ----

    @Test
    fun `silence maps to zero`() {
        val amps = waveformToAmplitudes(ByteArray(1024) { 128.toByte() }, 120)
        assertEquals(120, amps.size)
        assertTrue(amps.all { abs(it) < 0.001f })
    }

    @Test
    fun `full-scale swing maps to plus-minus one`() {
        val amps = waveformToAmplitudes(ByteArray(64) { 255.toByte() }, 8)
        assertTrue(amps.all { abs(it - 0.9921875f) < 0.001f })
        val low = waveformToAmplitudes(ByteArray(64) { 0.toByte() }, 8)
        assertTrue(low.all { abs(it + 1f) < 0.001f })
    }

    @Test
    fun `downsampling averages whole windows`() {
        // First half loud, second half silent: the seam must land mid-array.
        val wave = ByteArray(1000) { i -> if (i < 500) 255.toByte() else 0.toByte() }
        val amps = waveformToAmplitudes(wave, 10)
        assertEquals(10, amps.size)
        assertTrue(amps.take(5).all { it > 0.9f })
        assertTrue(amps.takeLast(5).all { it < -0.9f })
    }

    @Test
    fun `empty capture is silence`() {
        assertTrue(waveformToAmplitudes(ByteArray(0), 16).all { it == 0f })
    }

    // ---- applyVisualizerIntensity ----

    @Test
    fun `intensity scales and clips honestly`() {
        val out = applyVisualizerIntensity(floatArrayOf(0.5f, 0.8f, 0f), 2f)
        assertEquals(1f, out[0], 0.001f)
        assertEquals(1f, out[1], 0.001f) // clipped, not rescaled
        assertEquals(0f, out[2], 0.001f)
        val quiet = applyVisualizerIntensity(floatArrayOf(0.5f), 0.25f)
        assertEquals(0.125f, quiet[0], 0.001f)
    }

    @Test
    fun `unity intensity leaves values alone`() {
        val values = floatArrayOf(0.1f, 0.5f, 0.9f)
        val out = applyVisualizerIntensity(values, 1f)
        values.forEachIndexed { i, v -> assertEquals(v, out[i], 0.0001f) }
    }

    // ---- smoothVisualizerBars ----

    @Test
    fun `attack is faster than release`() {
        val rising = smoothVisualizerBars(floatArrayOf(0f), floatArrayOf(1f))
        val falling = smoothVisualizerBars(floatArrayOf(1f), floatArrayOf(0f))
        assertEquals(0.6f, rising[0], 0.001f)
        assertEquals(0.88f, falling[0], 0.001f)
        assertTrue(rising[0] - 0f > 1f - falling[0])
    }

    @Test
    fun `smoothing writes into the previous array`() {
        val previous = floatArrayOf(0f, 1f)
        val next = floatArrayOf(1f, 0f)
        val returned = smoothVisualizerBars(previous, next)
        assertTrue(returned === previous)
        assertEquals(0.6f, previous[0], 0.001f)
        assertEquals(0.88f, previous[1], 0.001f)
    }

    // ---- VisualizerStyle ----

    @Test
    fun `style names round-trip through persistence`() {
        VisualizerStyle.entries.forEach { style ->
            assertEquals(style, VisualizerStyle.fromPersistedName(style.persistedName))
        }
    }

    @Test
    fun `unknown persisted style names fall back to spectrum`() {
        assertEquals(VisualizerStyle.SPECTRUM, VisualizerStyle.fromPersistedName(null))
        assertEquals(VisualizerStyle.SPECTRUM, VisualizerStyle.fromPersistedName(""))
        assertEquals(VisualizerStyle.SPECTRUM, VisualizerStyle.fromPersistedName("bars"))
    }
}
