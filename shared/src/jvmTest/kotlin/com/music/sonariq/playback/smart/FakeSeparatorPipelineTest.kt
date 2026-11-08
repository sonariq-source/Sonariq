package com.music.sonariq.playback.smart

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The waveform overlap-add / divider accumulation [MdxSeparator] finishes with, tested at the
 * level it is factored to: a list of `(offset, chunkWaveform, windowLength)` triples.
 *
 * Feeding it windowed chunks of a known signal must return that signal. Wherever the window
 * sum is positive the division is exact in principle — numerator is `signal * sum(w)` and the
 * divider is `sum(w)` — so the interior tolerance only has to cover float rounding. The two
 * endpoints are the interesting samples: the symmetric Hann window is zero at its edges, so
 * the signal's first and last samples are covered by nothing but zero weights, the divider is
 * zero there, and the guard must leave them at exactly 0 rather than producing NaN from 0 / 0.
 * (In the real pipeline those samples sit inside the trimmed padding, which is why losing them
 * is harmless.)
 */
class FakeSeparatorPipelineTest {

    private fun signal(length: Int): FloatArray =
        FloatArray(length) { index ->
            (0.6 * sin(2.0 * PI * 5.0 * index / length) +
                0.3 * sin(2.0 * PI * 23.0 * index / length + 1.3)).toFloat()
        }

    @Test
    fun overlappingWindowedChunksReconstructTheSignal() {
        val length = 1_000
        val chunkSize = 256
        val step = 192 // 25% overlap, the MDX demix ratio
        val samples = signal(length)

        val chunks = ArrayList<Triple<Int, FloatArray, Int>>()
        var start = 0
        while (start < length) {
            val actual = minOf(chunkSize, length - start)
            val waveform = FloatArray(chunkSize)
            samples.copyInto(waveform, 0, start, start + actual)
            chunks += Triple(start, waveform, actual)
            start += step
        }

        val restored = overlapAdd(length, chunks)
        assertEquals(length, restored.size)
        assertTrue(restored.all { it.isFinite() }, "output must contain no NaN or infinite sample")

        // Divider-zero endpoints: exactly zero, by the guard.
        assertEquals(0f, restored[0])
        assertEquals(0f, restored[length - 1])

        var worst = 0.0
        for (index in 1 until length - 1) {
            val error = abs(samples[index] - restored[index]).toDouble()
            if (error > worst) worst = error
        }
        assertTrue(worst < 1e-3, "interior max error $worst")
    }

    @Test
    fun uncoveredRegionsStayZeroAndFinite() {
        // One chunk in the middle of a longer buffer: everything outside it has a zero divider
        // and must come out as clean zeros, not NaN.
        val length = 512
        val samples = signal(length)
        val waveform = FloatArray(128)
        samples.copyInto(waveform, 0, 200, 328)
        val restored = overlapAdd(length, listOf(Triple(200, waveform, 128)))

        assertTrue(restored.all { it.isFinite() })
        for (index in 0 until 200) assertEquals(0f, restored[index])
        for (index in 328 until length) assertEquals(0f, restored[index])
        // Interior of the covered span reconstructs (its edge samples are window-zero samples).
        var worst = 0.0
        for (index in 201 until 327) {
            val error = abs(samples[index] - restored[index]).toDouble()
            if (error > worst) worst = error
        }
        assertTrue(worst < 1e-3, "covered interior max error $worst")
    }

    @Test
    fun symmetricHannMatchesNumpyHanning() {
        assertTrue(symmetricHann(0).isEmpty())
        assertEquals(1f, symmetricHann(1)[0])
        val window = symmetricHann(5)
        // np.hanning(5) = [0, 0.5, 1, 0.5, 0]: endpoints exactly zero, centre exactly one.
        assertEquals(0f, window[0])
        assertEquals(0f, window[4])
        assertEquals(1f, window[2])
        assertTrue(abs(window[1] - 0.5f) < 1e-6f)
        assertTrue(abs(window[3] - 0.5f) < 1e-6f)
    }
}
