package com.music.sonariq.playback.smart

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STFT -> ISTFT round trips for both front-end sizes the separators use.
 *
 * The 2e-3 tolerance is not arbitrary: the transform itself runs in double precision (error
 * ~1e-12), but the spectrogram is *stored* as float, and spectral magnitudes for a 6144-point
 * transform of a unit-scale signal reach into the thousands, where one float ULP is already
 * ~2.4e-4. After the inverse transform's 1/N scaling that lands as at most ~1e-4 of absolute
 * error in the time domain (measured: ~2e-8). 2e-3 gives that an order of magnitude of headroom while staying
 * two-plus orders below the signal scale — any window, padding, or envelope mistake produces
 * errors of the order of the signal itself and fails loudly.
 */
class SpectralTransformTest {

    /** Deterministic mix of sines plus seeded noise, so the signal has energy at every bin. */
    private fun signal(length: Int): FloatArray {
        val random = Random(0x5EED)
        return FloatArray(length) { index ->
            val t = index.toDouble()
            (0.45 * sin(2.0 * PI * 3.0 * t / length) +
                0.30 * sin(2.0 * PI * 41.0 * t / length + 0.7) +
                0.15 * sin(2.0 * PI * 317.0 * t / length + 2.1) +
                0.10 * (random.nextDouble() - 0.5)).toFloat()
        }
    }

    private fun maxError(expected: FloatArray, actual: FloatArray, count: Int): Double {
        var worst = 0.0
        for (index in 0 until count) {
            val error = abs(expected[index] - actual[index]).toDouble()
            if (error > worst) worst = error
        }
        return worst
    }

    private fun roundTrip(fftSize: Int, hop: Int, length: Int) {
        val transform = SpectralTransform(fftSize, hop)
        val samples = signal(length)
        val spec = transform.stft(samples)
        assertEquals(fftSize / 2 + 1, spec.bins)
        assertEquals(1 + length / hop, spec.frames)

        val restored = transform.istft(spec)
        // torch.istft's length contract: (frames - 1) * hop, which can be shorter than the
        // input when the length is not a multiple of the hop. The covered prefix must match.
        assertEquals((spec.frames - 1) * hop, restored.size)
        val error = maxError(samples, restored, restored.size)
        assertTrue(error < 2e-3, "fftSize=$fftSize length=$length: max error $error")
    }

    @Test
    fun roundTripRadix2LengthMultipleOfHop() = roundTrip(fftSize = 4096, hop = 1024, length = 16_384)

    @Test
    fun roundTripRadix2LengthNotMultipleOfHop() = roundTrip(fftSize = 4096, hop = 1024, length = 10_007)

    @Test
    fun roundTripMixedRadixLengthMultipleOfHop() = roundTrip(fftSize = 6144, hop = 1024, length = 12_288)

    @Test
    fun roundTripMixedRadixLengthNotMultipleOfHop() = roundTrip(fftSize = 6144, hop = 1024, length = 10_007)

    @Test
    fun roundTripOtherHop() = roundTrip(fftSize = 4096, hop = 512, length = 9_999)

    @Test
    fun shortSignalBelowTheReflectPadStillRoundTrips() {
        // 100 samples is far below fftSize / 2, where torch's reflect pad is undefined and the
        // transform falls back to zero centre padding (documented on SpectralTransform). With an
        // explicit output length the single frame must still invert exactly.
        val transform = SpectralTransform(4096, 1024)
        val samples = signal(100)
        val spec = transform.stft(samples)
        assertEquals(1, spec.frames)
        val restored = transform.istft(spec, length = samples.size)
        assertEquals(samples.size, restored.size)
        val error = maxError(samples, restored, samples.size)
        assertTrue(error < 2e-3, "short signal: max error $error")
    }

    @Test
    fun emptySignalProducesOneSilentFrameAndEmptyDefaultOutput() {
        val transform = SpectralTransform(4096, 1024)
        val spec = transform.stft(FloatArray(0))
        assertEquals(1, spec.frames)
        assertTrue(spec.real.all { row -> row.all { it == 0f } })
        assertEquals(0, transform.istft(spec).size)
    }

    @Test
    fun silenceRoundTripsToSilence() {
        val transform = SpectralTransform(6144, 1024)
        val restored = transform.istft(transform.stft(FloatArray(8192)))
        assertEquals(8192, restored.size)
        assertTrue(restored.all { it == 0f })
    }
}
