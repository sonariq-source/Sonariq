package com.music.sonariq.playback.smart

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One STFT's worth of complex spectra, laid out `[bin][frame]`.
 *
 * Bin-major because both model front ends in this package consume spectrograms as
 * `[channel, bin, frame]` tensors: a frame-at-a-time FFT produces exactly the transpose, so the
 * values are scattered into this layout as each frame is computed and nothing transposes later.
 * [bins] is always `fftSize / 2 + 1` for a spectrogram produced by [SpectralTransform.stft] —
 * the non-negative half of a real signal's spectrum, the only half either model reads.
 */
class ComplexSpectrogram(
    val real: Array<FloatArray>,
    val imag: Array<FloatArray>,
) {
    val bins: Int get() = real.size
    val frames: Int get() = if (real.isEmpty()) 0 else real[0].size
}

/**
 * A reusable complex STFT / ISTFT pair with the exact conventions the separation models were
 * trained under.
 *
 * ## Conventions (torch.stft / torch.istft, as used by the native front end)
 *
 *  - Periodic Hann window: `w[n] = 0.5 - 0.5 * cos(2 * pi * n / fftSize)`, the divisor being the
 *    window length rather than length - 1. This matches `torch.hann_window(periodic = true)` and
 *    the native analyzer's `HannWindow` in `vocal_spectrogram.cpp`.
 *  - `center = true` with reflect padding of `fftSize / 2` on both sides, so frame `f` is
 *    centred on sample `f * hopLength` and a signal of length `L` yields
 *    `1 + floor(L / hopLength)` frames.
 *  - No normalisation: the forward transform is the raw DFT, the inverse carries the `1 / N`
 *    factor, exactly like `torch.stft(normalized = false)` / `torch.istft`.
 *
 * ## Reflect padding and short inputs
 *
 * Reflect padding mirrors the signal without repeating the edge sample (`x[-j] = x[j]`,
 * `x[L + j] = x[L - 2 - j]`) and is only defined when the signal is longer than the pad. torch
 * raises in that case; a separator must not, because a very short clip is a legitimate thing to
 * be handed. So when `samples.size <= fftSize / 2` this falls back to zero centre padding: the
 * samples sit in the middle of a zero frame-width margin instead of being mirrored. The frame
 * count formula is unchanged, and the ISTFT still inverts the STFT exactly — only the content of
 * the padded margin differs from torch's (undefinable) answer.
 *
 * ## FFT sizes
 *
 * [fftSize] 4096 takes an iterative radix-2 path. 6144 (`2048 * 3`, the MDX-Net front end) is
 * not a power of two, so it takes a Cooley–Tukey mixed-radix path: three radix-2 transforms of
 * 2048, a twiddle multiply, and a length-3 DFT per output group. Any other size falls back to
 * Bluestein's algorithm over the radix-2 path. All internal arithmetic is double precision:
 * spectral magnitudes reach into the thousands for a 6144-point transform of full-scale audio,
 * and accumulating the butterflies in float cost more round-trip error than the float storage of
 * [ComplexSpectrogram] itself. Converting at the boundary keeps the `istft(stft(x))` round-trip
 * error orders of magnitude below the tolerance the tests assert.
 *
 * Instances hold FFT scratch buffers and are **not** thread-safe; each separator owns one and
 * drives it from its single inference thread.
 */
class SpectralTransform(val fftSize: Int, val hopLength: Int) {

    init {
        require(fftSize >= 2) { "fftSize must be at least 2, was $fftSize" }
        require(hopLength > 0) { "hopLength must be positive, was $hopLength" }
    }

    /** Frequency bins kept per frame: the non-negative half of the spectrum, DC and Nyquist included. */
    val bins: Int = fftSize / 2 + 1

    private val pad: Int = fftSize / 2

    /** Periodic Hann, held in double because it multiplies every sample of every frame. */
    private val window = DoubleArray(fftSize) { index ->
        0.5 - 0.5 * cos(2.0 * PI * index / fftSize)
    }

    private val fft = FftPlan(fftSize)

    /**
     * Computes the complex STFT of [samples].
     *
     * The result has [bins] rows and `1 + samples.size / hopLength` frames, following the torch
     * frame-count rule for centre-padded input. An empty input yields a single all-zero frame.
     */
    fun stft(samples: FloatArray): ComplexSpectrogram {
        val frames = 1 + samples.size / hopLength
        val real = Array(bins) { FloatArray(frames) }
        val imag = Array(bins) { FloatArray(frames) }
        val padded = centrePad(samples)

        val frameRe = DoubleArray(fftSize)
        val frameIm = DoubleArray(fftSize)
        for (frame in 0 until frames) {
            val start = frame * hopLength
            for (index in 0 until fftSize) {
                frameRe[index] = padded[start + index] * window[index]
                frameIm[index] = 0.0
            }
            fft.forward(frameRe, frameIm)
            for (bin in 0 until bins) {
                real[bin][frame] = frameRe[bin].toFloat()
                imag[bin][frame] = frameIm[bin].toFloat()
            }
        }
        return ComplexSpectrogram(real, imag)
    }

    /**
     * Inverts [spec] by overlap-add, returning [length] samples.
     *
     * Each frame is inverse-transformed, windowed again, and accumulated; the sum is divided by
     * the accumulated squared-window envelope wherever that envelope is meaningfully non-zero
     * (below [ENVELOPE_EPSILON] the frame sum carries no signal and the output is left at zero,
     * mirroring torch's guard against dividing by a vanishing envelope). The centre padding is
     * then trimmed, so with the default [length] the output is `(spec.frames - 1) * hopLength`
     * samples — torch.istft's length for centre-padded input. Asking for more than that pads
     * with zeros; asking for less truncates.
     */
    fun istft(
        spec: ComplexSpectrogram,
        length: Int = (spec.frames - 1) * hopLength,
    ): FloatArray {
        require(length >= 0) { "length must not be negative, was $length" }
        val frames = spec.frames
        if (frames == 0 || length == 0) return FloatArray(length)

        val olaLength = (frames - 1) * hopLength + fftSize
        val ola = DoubleArray(olaLength)
        val envelope = DoubleArray(olaLength)

        val frameRe = DoubleArray(fftSize)
        val frameIm = DoubleArray(fftSize)
        val keptBins = minOf(spec.bins, bins)
        for (frame in 0 until frames) {
            frameRe.fill(0.0)
            frameIm.fill(0.0)
            for (bin in 0 until keptBins) {
                frameRe[bin] = spec.real[bin][frame].toDouble()
                frameIm[bin] = spec.imag[bin][frame].toDouble()
            }
            // Rebuild the negative half by conjugate symmetry; a real signal's spectrum is
            // Hermitian, and bins at or above `keptBins` (should a caller hand in a cropped
            // spectrogram) mirror the zeros they were initialised with.
            for (bin in bins until fftSize) {
                frameRe[bin] = frameRe[fftSize - bin]
                frameIm[bin] = -frameIm[fftSize - bin]
            }
            fft.inverse(frameRe, frameIm)
            val start = frame * hopLength
            for (index in 0 until fftSize) {
                ola[start + index] += frameRe[index] * window[index]
                envelope[start + index] += window[index] * window[index]
            }
        }

        val out = FloatArray(length)
        val available = minOf(length, olaLength - pad)
        for (index in 0 until available) {
            val sum = envelope[pad + index]
            out[index] = if (sum > ENVELOPE_EPSILON) (ola[pad + index] / sum).toFloat() else 0f
        }
        return out
    }

    /**
     * Centre-pads [samples] by [pad] on both sides into a double buffer of `size + fftSize`.
     *
     * Reflect padding when the signal is long enough to mirror (`size > pad`, torch's own
     * requirement); zero padding otherwise — see the class documentation for why the fallback
     * exists and why it changes nothing about invertibility.
     */
    private fun centrePad(samples: FloatArray): DoubleArray {
        val out = DoubleArray(samples.size + fftSize)
        if (samples.size > pad) {
            // Left margin: out[i] holds signal index i - pad, and reflection maps -j to +j.
            for (index in 0 until pad) out[index] = samples[pad - index].toDouble()
            for (index in samples.indices) out[pad + index] = samples[index].toDouble()
            // Right margin: signal index size + j reflects to size - 2 - j.
            val size = samples.size
            for (index in 0 until pad) out[pad + size + index] = samples[size - 2 - index].toDouble()
        } else {
            for (index in samples.indices) out[pad + index] = samples[index].toDouble()
        }
        return out
    }

    private companion object {
        /** Envelope below this carries no recoverable signal; the ISTFT leaves those samples at zero. */
        const val ENVELOPE_EPSILON = 1e-10
    }
}

/**
 * A forward/inverse FFT over double arrays for one fixed [size], dispatching on the size's
 * factorisation.
 *
 * The inverse is the conjugate trick around the forward transform
 * (`ifft(x) = conj(fft(conj(x))) / N`), so each size needs only one carefully-checked forward
 * implementation. Scratch arrays live on the plan, which is why [SpectralTransform] is not
 * thread-safe.
 */
private class FftPlan(private val size: Int) {

    private val isPowerOfTwo = size and (size - 1) == 0

    /** The radix-2 leg length when size = 3 * 2^k (6144 = 3 * 2048), or 0 when not applicable. */
    private val mixedLeg: Int =
        if (!isPowerOfTwo && size % 3 == 0 && (size / 3).let { it and (it - 1) == 0 }) size / 3 else 0

    /** Bluestein scratch and precomputed kernel transform; allocated only when that path is used. */
    private val bluestein: Bluestein? =
        if (!isPowerOfTwo && mixedLeg == 0 && size > 1) Bluestein(size) else null

    /** Mixed-radix scratch, one complex array the size of the transform. */
    private val scratchRe = DoubleArray(size)
    private val scratchIm = DoubleArray(size)

    fun forward(re: DoubleArray, im: DoubleArray) {
        when {
            size == 1 -> Unit
            isPowerOfTwo -> radix2(re, im, 0, size)
            mixedLeg > 0 -> mixedRadix3(re, im)
            else -> bluestein!!.forward(re, im, scratchRe, scratchIm)
        }
    }

    fun inverse(re: DoubleArray, im: DoubleArray) {
        for (index in 0 until size) im[index] = -im[index]
        forward(re, im)
        for (index in 0 until size) {
            re[index] /= size
            im[index] = -im[index] / size
        }
    }

    /**
     * Cooley–Tukey for `N = 3 * N1` with `N1` a power of two.
     *
     * Writing `n = 3j + n2` and `k = k2 * N1 + k1`, the DFT factors into `N1`-point transforms
     * over the three strided subsequences, a twiddle multiply by `W_N^(n2 * k1)`, and a 3-point
     * DFT over `n2` for each `k1`, since `W_N^(N1) = W_3`. The 3-point DFT is written out with
     * `W_3 = -1/2 - i*sqrt(3)/2` rather than computed through trig per output.
     */
    private fun mixedRadix3(re: DoubleArray, im: DoubleArray) {
        val n1 = mixedLeg
        // Stage 1: gather each strided subsequence into a contiguous segment and transform it.
        for (n2 in 0 until 3) {
            val base = n2 * n1
            for (j in 0 until n1) {
                scratchRe[base + j] = re[3 * j + n2]
                scratchIm[base + j] = im[3 * j + n2]
            }
            radix2(scratchRe, scratchIm, base, n1)
        }
        // Stages 2 and 3: twiddle, then the 3-point DFT across the three segments.
        val w3Re = -0.5
        val w3Im = -sqrt(3.0) / 2.0
        for (k1 in 0 until n1) {
            val b0Re = scratchRe[k1]
            val b0Im = scratchIm[k1]

            val angle = -2.0 * PI * k1 / size
            val tRe = cos(angle)
            val tIm = sin(angle)
            val a1Re = scratchRe[n1 + k1]
            val a1Im = scratchIm[n1 + k1]
            val b1Re = a1Re * tRe - a1Im * tIm
            val b1Im = a1Re * tIm + a1Im * tRe

            // The n2 = 2 twiddle is the square of the n2 = 1 twiddle: W_N^(2 * k1).
            val t2Re = tRe * tRe - tIm * tIm
            val t2Im = 2.0 * tRe * tIm
            val a2Re = scratchRe[2 * n1 + k1]
            val a2Im = scratchIm[2 * n1 + k1]
            val b2Re = a2Re * t2Re - a2Im * t2Im
            val b2Im = a2Re * t2Im + a2Im * t2Re

            re[k1] = b0Re + b1Re + b2Re
            im[k1] = b0Im + b1Im + b2Im
            // X[k1 + N1] = b0 + b1 * W_3 + b2 * W_3^2, and W_3^2 is W_3's conjugate.
            re[k1 + n1] = b0Re + (b1Re * w3Re - b1Im * w3Im) + (b2Re * w3Re + b2Im * w3Im)
            im[k1 + n1] = b0Im + (b1Re * w3Im + b1Im * w3Re) + (-b2Re * w3Im + b2Im * w3Re)
            // X[k1 + 2 * N1] = b0 + b1 * W_3^2 + b2 * W_3.
            re[k1 + 2 * n1] = b0Re + (b1Re * w3Re + b1Im * w3Im) + (b2Re * w3Re - b2Im * w3Im)
            im[k1 + 2 * n1] = b0Im + (-b1Re * w3Im + b1Im * w3Re) + (b2Re * w3Im + b2Im * w3Re)
        }
    }

    /**
     * Bluestein's algorithm: an arbitrary-size DFT expressed as a convolution of chirps, itself
     * evaluated with power-of-two transforms of size at least `2 * size - 1`.
     *
     * Only a fallback for sizes that are neither a power of two nor `3 * 2^k`; the two front ends
     * in this package never take it. It exists so a future [SpectralTransform] parameterisation
     * is correct by construction rather than by a thrown exception.
     */
    private class Bluestein(private val size: Int) {
        private val convSize: Int = run {
            var m = 1
            while (m < 2 * size - 1) m = m shl 1
            m
        }
        private val chirpRe = DoubleArray(size)
        private val chirpIm = DoubleArray(size)
        private val kernelFftRe = DoubleArray(convSize)
        private val kernelFftIm = DoubleArray(convSize)
        private val convRe = DoubleArray(convSize)
        private val convIm = DoubleArray(convSize)

        init {
            // chirp[i] = exp(-i * pi * i^2 / size); the exponent is taken mod 2 * size first,
            // which the exponential's periodicity allows and which keeps the trig argument small.
            for (i in 0 until size) {
                val angle = PI * ((i.toLong() * i) % (2L * size)) / size
                chirpRe[i] = cos(angle)
                chirpIm[i] = -sin(angle)
            }
            // Kernel exp(+i * pi * j^2 / size) for j in -(size - 1)..(size - 1), placed at
            // j mod convSize so a cyclic convolution reads the linear one.
            for (j in -(size - 1) until size) {
                val angle = PI * ((j.toLong() * j) % (2L * size)) / size
                val index = if (j < 0) j + convSize else j
                kernelFftRe[index] = cos(angle)
                kernelFftIm[index] = sin(angle)
            }
            radix2(kernelFftRe, kernelFftIm, 0, convSize)
        }

        fun forward(re: DoubleArray, im: DoubleArray, outRe: DoubleArray, outIm: DoubleArray) {
            convRe.fill(0.0)
            convIm.fill(0.0)
            for (i in 0 until size) {
                convRe[i] = re[i] * chirpRe[i] - im[i] * chirpIm[i]
                convIm[i] = re[i] * chirpIm[i] + im[i] * chirpRe[i]
            }
            radix2(convRe, convIm, 0, convSize)
            for (i in 0 until convSize) {
                val productRe = convRe[i] * kernelFftRe[i] - convIm[i] * kernelFftIm[i]
                val productIm = convRe[i] * kernelFftIm[i] + convIm[i] * kernelFftRe[i]
                convRe[i] = productRe
                convIm[i] = productIm
            }
            inverseRadix2(convRe, convIm, convSize)
            for (i in 0 until size) {
                outRe[i] = convRe[i] * chirpRe[i] - convIm[i] * chirpIm[i]
                outIm[i] = convRe[i] * chirpIm[i] + convIm[i] * chirpRe[i]
            }
            outRe.copyInto(re, 0, 0, size)
            outIm.copyInto(im, 0, 0, size)
        }
    }

    companion object {
        /**
         * In-place iterative radix-2 forward DFT (unnormalised, `exp(-2 * pi * i * k * n / N)`)
         * over the segment of [n] samples starting at [offset]. Unnormalised to match
         * torch.stft: all scaling belongs to the inverse.
         */
        fun radix2(re: DoubleArray, im: DoubleArray, offset: Int, n: Int) {
            // Bit-reversal permutation.
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val tmpRe = re[offset + i]
                    re[offset + i] = re[offset + j]
                    re[offset + j] = tmpRe
                    val tmpIm = im[offset + i]
                    im[offset + i] = im[offset + j]
                    im[offset + j] = tmpIm
                }
            }
            var length = 2
            while (length <= n) {
                val angle = -2.0 * PI / length
                val wlenRe = cos(angle)
                val wlenIm = sin(angle)
                var base = 0
                while (base < n) {
                    var wRe = 1.0
                    var wIm = 0.0
                    for (k in 0 until length / 2) {
                        val evenIndex = offset + base + k
                        val oddIndex = evenIndex + length / 2
                        val oddRe = re[oddIndex] * wRe - im[oddIndex] * wIm
                        val oddIm = re[oddIndex] * wIm + im[oddIndex] * wRe
                        re[oddIndex] = re[evenIndex] - oddRe
                        im[oddIndex] = im[evenIndex] - oddIm
                        re[evenIndex] = re[evenIndex] + oddRe
                        im[evenIndex] = im[evenIndex] + oddIm
                        val nextRe = wRe * wlenRe - wIm * wlenIm
                        wIm = wRe * wlenIm + wIm * wlenRe
                        wRe = nextRe
                    }
                    base += length
                }
                length = length shl 1
            }
        }

        /** Inverse radix-2 via the conjugate trick, with the `1 / n` scaling applied. */
        fun inverseRadix2(re: DoubleArray, im: DoubleArray, n: Int) {
            for (i in 0 until n) im[i] = -im[i]
            radix2(re, im, 0, n)
            for (i in 0 until n) {
                re[i] /= n
                im[i] = -im[i] / n
            }
        }
    }
}
