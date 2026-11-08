package com.music.sonariq.playback.smart

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Full-track source separation with the bundled open-unmix vocals model — the same weights
 * [VocalTracker] uses for vocal-presence tracking, here driven as an actual separator.
 *
 * ## How the estimate becomes an instrumental
 *
 * The model consumes **magnitude** spectrograms and predicts the vocal magnitude; it says
 * nothing about phase. So the instrumental is not reconstructed from the model's output
 * directly. Instead the predicted vocal magnitude becomes a soft (Wiener-style) mask over the
 * mix's own complex spectrogram:
 *
 * ```
 * ratio = clamp(vocalMag / (mixMag + eps), 0, 1)
 * instrumental = mix * (1 - ratio)
 * ```
 *
 * per bin, frame and channel, and the masked complex spectrogram is inverse-transformed. The
 * mix's phase is retained throughout, which is what makes a magnitude-only model usable as a
 * separator at all.
 *
 * ## Windowing over the track
 *
 * The model's input width is fixed at [WINDOW_FRAMES] frames (~22.3 s at this front end), so
 * the track's magnitude spectrogram is processed in windows advancing by [STEP_FRAMES] — a 50%
 * overlap. Predictions are accumulated under a Hann window over the frame axis and divided by
 * the accumulated weight per frame, so a frame covered by two windows is the weighted mean of
 * two predictions rather than either one, and the hard seams a single fixed-width pass would
 * leave at each window boundary are smoothed away. The final window is zero-padded.
 *
 * The complex mix spectrogram is computed once for the whole track and reused for both the
 * magnitudes and the masking; the instrumental spectrogram is produced by scaling it in place
 * once the mask exists, rather than allocating a second one.
 *
 * Everything fails soft, exactly as [VocalTracker] does: unavailable resampling, an unreadable
 * model, a failed inference, or a cancellation all produce null.
 */
class UmxSeparator(
    /**
     * Where the model lives, resolved lazily.
     *
     * A path rather than a [Context]: this is the only thing the separator ever wanted the
     * platform for, and each application already knows how to put its own copy of the weights
     * somewhere readable — unpacked from assets on Android, from the jar's resources on the
     * desktop. Same contract as [VocalTracker]'s constructor.
     */
    private val modelPath: () -> String,
    /** How many threads inference may use. */
    private val inferenceThreads: () -> Int,
) {

    @Volatile private var session: OrtSession? = null
    @Volatile private var sessionThreads = 0
    private val lock = Any()

    private val transform = SpectralTransform(FFT_SIZE, HOP)

    /** Parsing the graph is far too expensive to repeat per track, so one session is kept. */
    private fun session(): OrtSession? {
        val threads = inferenceThreads()
        session?.takeIf { sessionThreads == threads }?.let { return it }
        synchronized(lock) {
            session?.takeIf { sessionThreads == threads }?.let { return it }
            runCatching { session?.close() }
            session = null
            return runCatching {
                val file = File(modelPath())
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(threads)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    // Same reasoning as VocalTracker: the arena retains every block it allocates
                    // for the life of the session, which a backgrounded music player cannot
                    // justify.
                    setCPUArenaAllocator(false)
                    setMemoryPatternOptimization(false)
                }
                OrtEnvironment.getEnvironment().createSession(file.absolutePath, options)
                    .also {
                        session = it
                        sessionThreads = threads
                    }
            }.onFailure { AnalysisLog.warn("UMX model unavailable; no separation will be produced", it) }
                .getOrNull()
        }
    }

    /**
     * Separates the instrumental stem from planar stereo [left]/[right] at [sampleRate].
     *
     * [onProgress] is called after each model window with the fraction of windows completed, in
     * `0..1`. [cancelled] is polled once per window; when it turns true the separation aborts
     * and returns null. Returns null on any failure.
     */
    fun separate(
        left: FloatArray,
        right: FloatArray,
        sampleRate: Double,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): StereoStems? {
        if (left.isEmpty() || left.size != right.size) return null
        val mixLeft = MelSpectrogram.resample(left, sampleRate, SAMPLE_RATE) ?: return null
        val mixRight = MelSpectrogram.resample(right, sampleRate, SAMPLE_RATE) ?: return null
        if (cancelled()) return null
        val active = session() ?: return null

        val length = mixLeft.size
        val specLeft = transform.stft(mixLeft)
        val specRight = transform.stft(mixRight)
        val frames = specLeft.frames
        if (frames == 0) return null

        // Magnitudes, flattened [bin][frame] per channel to match the model's tensor layout
        // with no transposition when a window is cut out of them.
        val magLeft = magnitudes(specLeft)
        val magRight = magnitudes(specRight)

        // Predicted vocal magnitudes, accumulated under the frame-axis window; weights are per
        // frame (shared by every bin and channel), so the normaliser is a single array.
        val vocalLeft = FloatArray(BINS * frames)
        val vocalRight = FloatArray(BINS * frames)
        val weightSum = FloatArray(frames)
        val frameWindow = FloatArray(WINDOW_FRAMES) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / WINDOW_FRAMES)).toFloat()
        }

        val windowCount = (frames + STEP_FRAMES - 1) / STEP_FRAMES
        var windowIndex = 0
        var start = 0
        while (start < frames) {
            if (cancelled()) return null
            val ok = runCatching {
                runWindow(active, magLeft, magRight, start, frames, frameWindow, vocalLeft, vocalRight)
            }.onFailure { AnalysisLog.warn("UMX inference failed", it) }.getOrDefault(false)
            if (!ok) return null
            for (local in 0 until WINDOW_FRAMES) {
                val frame = start + local
                if (frame >= frames) break
                weightSum[frame] += frameWindow[local]
            }
            windowIndex += 1
            onProgress(windowIndex.toFloat() / windowCount)
            start += STEP_FRAMES
        }

        // Normalise the accumulated predictions by their accumulated weight, in place. A frame
        // no window weighted (only possible at the extreme edges, where the Hann window is
        // zero) keeps a prediction of zero, which masks nothing — the mix passes through.
        for (frame in 0 until frames) {
            val weight = weightSum[frame]
            if (weight <= MIN_WEIGHT) continue
            for (bin in 0 until BINS) {
                val index = bin * frames + frame
                vocalLeft[index] /= weight
                vocalRight[index] /= weight
            }
        }

        applyInstrumentalMask(specLeft, magLeft, vocalLeft)
        applyInstrumentalMask(specRight, magRight, vocalRight)

        val outLeft = transform.istft(specLeft, length)
        val outRight = transform.istft(specRight, length)
        return StereoStems(outLeft, outRight, SAMPLE_RATE)
    }

    /** Per-bin magnitudes of [spec], flattened as `bin * frames + frame`. */
    private fun magnitudes(spec: ComplexSpectrogram): FloatArray {
        val frames = spec.frames
        val out = FloatArray(BINS * frames)
        for (bin in 0 until BINS) {
            val realRow = spec.real[bin]
            val imagRow = spec.imag[bin]
            val base = bin * frames
            for (frame in 0 until frames) {
                val re = realRow[frame].toDouble()
                val im = imagRow[frame].toDouble()
                out[base + frame] = sqrt(re * re + im * im).toFloat()
            }
        }
        return out
    }

    /**
     * Runs the model once over the window starting at [start] and accumulates the predicted
     * vocal magnitudes, Hann-weighted, into [vocalLeft]/[vocalRight].
     *
     * The tensor is a heap [FloatBuffer] over a plain array: at `[1, 2, 2049, 960]` it is
     * ~15 MB, and unlike [VocalTracker]'s transition-sized window this pass runs over a whole
     * track's worth of windows, where the direct-buffer discipline matters less than not
     * re-implementing it per call. Frames beyond the track's end are zero-padded.
     */
    private fun runWindow(
        active: OrtSession,
        magLeft: FloatArray,
        magRight: FloatArray,
        start: Int,
        frames: Int,
        frameWindow: FloatArray,
        vocalLeft: FloatArray,
        vocalRight: FloatArray,
    ): Boolean {
        val usable = min(WINDOW_FRAMES, frames - start)
        val input = FloatArray(CHANNELS * BINS * WINDOW_FRAMES)
        copyWindow(magLeft, start, usable, frames, input, channelOffset = 0)
        copyWindow(magRight, start, usable, frames, input, channelOffset = BINS * WINDOW_FRAMES)

        val environment = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, CHANNELS.toLong(), BINS.toLong(), WINDOW_FRAMES.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            active.run(mapOf(active.inputNames.first() to tensor)).use { outputs ->
                // Absolute-index reads on the output tensor's buffer, as in VocalTracker:
                // `.value` would box this [1, 2, 2049, 960] result into thousands of arrays.
                val output = (outputs.get(0) as OnnxTensor).floatBuffer
                accumulateWindow(output, start, usable, frameWindow, vocalLeft, channelOffset = 0, frames)
                accumulateWindow(output, start, usable, frameWindow, vocalRight, channelOffset = BINS * WINDOW_FRAMES, frames)
            }
        }
        return true
    }

    /** Copies one channel's `[bin][local frame]` window out of the flattened magnitudes. */
    private fun copyWindow(
        mag: FloatArray,
        start: Int,
        usable: Int,
        frames: Int,
        into: FloatArray,
        channelOffset: Int,
    ) {
        for (bin in 0 until BINS) {
            val sourceBase = bin * frames + start
            val targetBase = channelOffset + bin * WINDOW_FRAMES
            for (local in 0 until usable) {
                into[targetBase + local] = mag[sourceBase + local]
            }
        }
    }

    /** Adds one channel's windowed predictions from the model output into the accumulator. */
    private fun accumulateWindow(
        output: FloatBuffer,
        start: Int,
        usable: Int,
        frameWindow: FloatArray,
        vocal: FloatArray,
        channelOffset: Int,
        frames: Int,
    ) {
        for (bin in 0 until BINS) {
            val sourceBase = channelOffset + bin * WINDOW_FRAMES
            val targetBase = bin * frames + start
            for (local in 0 until usable) {
                vocal[targetBase + local] += output.get(sourceBase + local) * frameWindow[local]
            }
        }
    }

    /**
     * Scales [spec] in place by `1 - vocalMag / (mixMag + eps)`, clamped so the ratio stays in
     * `[0, 1]`: a prediction louder than the mix removes the bin entirely rather than inverting
     * its phase, and a silent mix bin is left alone instead of dividing by ~zero.
     */
    private fun applyInstrumentalMask(
        spec: ComplexSpectrogram,
        mixMag: FloatArray,
        vocalMag: FloatArray,
    ) {
        val frames = spec.frames
        for (bin in 0 until BINS) {
            val realRow = spec.real[bin]
            val imagRow = spec.imag[bin]
            val base = bin * frames
            for (frame in 0 until frames) {
                val ratio = (vocalMag[base + frame] / (mixMag[base + frame] + EPSILON))
                    .coerceIn(0f, 1f)
                val keep = 1f - ratio
                realRow[frame] *= keep
                imagRow[frame] *= keep
            }
        }
    }

    fun release() {
        synchronized(lock) {
            runCatching { session?.close() }
            session = null
            sessionThreads = 0
        }
    }

    companion object {
        /** The weights this separator needs, named once for every build that ships them. */
        const val MODEL_ASSET = "vocals_umxhq_int8.onnx"

        /** The front end the model was trained on: 4096-point STFT, hop 1024, at 44.1 kHz. */
        const val FFT_SIZE = 4096
        const val HOP = 1024
        const val BINS = 2049
        const val SAMPLE_RATE = 44_100.0
        const val CHANNELS = 2

        /** The model's fixed input width in frames, and the 50% step windows advance by. */
        const val WINDOW_FRAMES = 960
        const val STEP_FRAMES = 480

        /** Denominator guard for the mask ratio. */
        private const val EPSILON = 1e-8f

        /** Accumulated weight at or below this means no window really covered the frame. */
        private const val MIN_WEIGHT = 1e-8f
    }
}
