package com.music.sonariq.ui.player

import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The pure number-crunching behind the Now Playing visualizer strip.
 *
 * Everything here is platform-free on purpose: the capture bytes come from
 * android.media.audiofx.Visualizer on the phone, but the bucketing, the
 * downsampling and the smoothing are plain math, so they live here and are
 * unit-tested rather than eyeballed.
 *
 * Fork of https://github.com/kushagrasinghx/BitChord (GPL-3.0).
 */

/**
 * Buckets a Visualizer FFT capture into [barCount] magnitudes in 0..1.
 *
 * The capture's layout (see Visualizer.getFft): byte 0 is the DC magnitude,
 * byte 1 the Nyquist magnitude, and bytes 2k / 2k+1 are the real and
 * imaginary parts of bin k, for k in 1..n/2-1. DC carries no musical
 * information and can sit far above the music, so it is skipped; the bins
 * are spread across the bars on a logarithmic axis so the bass — where the
 * music lives — gets the resolution. A square-root perceptual curve lifts
 * the mids so the bars read as music rather than as a bass meter.
 */
fun fftToBarMagnitudes(fft: ByteArray, barCount: Int): FloatArray {
    require(barCount > 0) { "barCount must be positive" }
    val bars = FloatArray(barCount)
    // Usable bins are 1..n/2-1; anything shorter has no bins at all.
    val bins = fft.size / 2 - 1
    if (bins <= 0) return bars
    // The largest magnitude one (re, im) byte pair can report.
    val fullScale = hypot(127.0, 127.0).toFloat()
    for (k in 1..bins) {
        val re = fft[2 * k].toInt()
        val im = fft[2 * k + 1].toInt()
        val magnitude = hypot(re.toDouble(), im.toDouble()).toFloat() / fullScale
        val bar = binToBar(k, bins, barCount)
        if (magnitude > bars[bar]) bars[bar] = magnitude
    }
    for (i in bars.indices) bars[i] = sqrt(bars[i]).coerceIn(0f, 1f)
    return bars
}

/**
 * Which of [barCount] bars bin [k] of [bins] lands in, on a logarithmic
 * axis: ln(k)/ln(bins) runs 0 at the first bin to 1 at the last, so every
 * octave of bins gets the same number of bars.
 */
internal fun binToBar(k: Int, bins: Int, barCount: Int): Int {
    if (bins <= 1) return 0
    val t = ln(k.toDouble()) / ln(bins.toDouble())
    return (t * barCount).toInt().coerceIn(0, barCount - 1)
}

/**
 * Downsamples an 8-bit unsigned waveform capture (128 = silence) to
 * [sampleCount] signed amplitudes in -1..1. Each output averages its whole
 * window, so a narrow view of a wide capture stays representative instead
 * of aliasing on every Nth byte.
 */
fun waveformToAmplitudes(waveform: ByteArray, sampleCount: Int): FloatArray {
    require(sampleCount > 0) { "sampleCount must be positive" }
    val out = FloatArray(sampleCount)
    if (waveform.isEmpty()) return out
    for (i in 0 until sampleCount) {
        val from = (i.toLong() * waveform.size / sampleCount).toInt()
        val to = ((i + 1).toLong() * waveform.size / sampleCount)
            .toInt()
            .coerceIn(from + 1, waveform.size)
        var sum = 0
        for (j in from until to) sum += waveform[j].toInt() and 0xFF
        val mean = sum.toFloat() / (to - from)
        out[i] = ((mean - 128f) / 128f).coerceIn(-1f, 1f)
    }
    return out
}

/**
 * Applies the user's intensity setting as a plain gain into 0..1. The gain
 * is honest — cranking it clips the loudest bars against the ceiling
 * instead of quietly rescaling everything — which is what a "sensitivity"
 * slider should do.
 */
fun applyVisualizerIntensity(values: FloatArray, intensity: Float): FloatArray {
    val out = FloatArray(values.size)
    for (i in values.indices) {
        out[i] = (values[i] * intensity).coerceIn(0f, 1f)
    }
    return out
}

/**
 * One-pole smoothing between capture frames: rises fast ([attack]) so
 * beats land, falls slow ([release]) so the bars decay instead of
 * flickering. Writes into [previous] and returns it — the caller keeps one
 * array across frames and folds each new one in.
 */
fun smoothVisualizerBars(
    previous: FloatArray,
    next: FloatArray,
    attack: Float = 0.6f,
    release: Float = 0.12f,
): FloatArray {
    require(previous.size == next.size) { "previous and next must match" }
    for (i in previous.indices) {
        val target = next[i]
        val rate = if (target > previous[i]) attack else release
        previous[i] += (target - previous[i]) * rate
    }
    return previous
}
