package com.music.sonariq.playback.smart

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

/**
 * A separated pair of planar stereo channels at [sampleRate].
 *
 * Both separators in this package return the **instrumental** stem only. The vocal stem is the
 * caller's to derive as `original - instrumental` sample by sample, which keeps one inference
 * per track instead of two and guarantees the two stems sum back to the mix exactly.
 */
data class StereoStems(
    val left: FloatArray,
    val right: FloatArray,
    val sampleRate: Double,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is StereoStems && sampleRate == other.sampleRate &&
            left.contentEquals(other.left) && right.contentEquals(other.right))

    override fun hashCode(): Int =
        31 * (31 * left.contentHashCode() + right.contentHashCode()) + sampleRate.hashCode()
}

/**
 * The chunk layout [MdxSeparator] applies to a mix of [mixtureLength] padded samples.
 *
 * Exposed (module-internally) as a pure function of the unpadded length so the geometry can be
 * tested against hand-computed values without a model file or an inference session.
 */
internal data class MdxChunkGeometry(
    /** Zero samples appended after the mix (the leading [MdxSeparator.TRIM] zeros are separate). */
    val pad: Int,
    /** Total padded length: trim + mix + pad. */
    val mixtureLength: Int,
    /** Number of chunk starts: `0, STEP, 2 * STEP, ...` below [mixtureLength]. */
    val chunkCount: Int,
)

/**
 * Computes the padding and chunk count for a mix of [length] samples at the model's rate.
 *
 * The pad rounds the mixture up so the final chunk still starts a full generation window after
 * the previous one: `pad = GEN_SIZE + TRIM - (length % GEN_SIZE)`. Note the exact-multiple
 * case keeps a whole `GEN_SIZE + TRIM` of pad rather than collapsing to `TRIM` — that is the
 * reference algorithm's behaviour, and "optimising" it would shift every later chunk boundary.
 */
internal fun mdxChunkGeometry(length: Int): MdxChunkGeometry {
    val remainder = if (length <= 0) 0 else length % MdxSeparator.GEN_SIZE
    val pad = MdxSeparator.GEN_SIZE + MdxSeparator.TRIM - remainder
    val mixtureLength = MdxSeparator.TRIM + length.coerceAtLeast(0) + pad
    val chunkCount = (mixtureLength + MdxSeparator.STEP - 1) / MdxSeparator.STEP
    return MdxChunkGeometry(pad = pad, mixtureLength = mixtureLength, chunkCount = chunkCount)
}

/**
 * Symmetric Hann window of [length] samples, matching `np.hanning`: endpoints are exactly
 * zero and the divisor is `length - 1`, unlike the periodic window the STFT uses. A length of
 * 1 is the single sample 1.0 (numpy's own special case); a length of 0 is empty.
 */
internal fun symmetricHann(length: Int): FloatArray {
    if (length <= 0) return FloatArray(0)
    if (length == 1) return floatArrayOf(1f)
    return FloatArray(length) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / (length - 1))).toFloat()
    }
}

/**
 * Overlap-add accumulation of windowed waveform chunks into a single buffer, with the
 * normalisation the MDX demix loop applies.
 *
 * Each [add] folds `waveform[i] * window[i]` into the sum at `offset + i` and `window[i]` into
 * a parallel divider; [finish] divides the two wherever the divider is at least [DIVIDER_EPSILON]
 * and leaves the sample at zero where it is not. The guard matters at the mixture's very edges,
 * where the symmetric Hann window is itself zero: without it those samples would be `0 / 0`.
 *
 * Only the first `window.size` samples of each waveform are consumed, so a caller can hand in a
 * full chunk-length reconstruction alongside the (possibly shorter) window of the slice that
 * was actually taken from the mixture — exactly the MDX last-chunk shape.
 */
internal class WaveformOverlapAdd(val length: Int) {

    private val sum = FloatArray(length)
    private val divider = FloatArray(length)

    fun add(offset: Int, waveform: FloatArray, window: FloatArray) {
        if (offset < 0 || offset >= length) return
        val count = min(min(window.size, waveform.size), length - offset)
        if (count <= 0) return
        for (index in 0 until count) {
            sum[offset + index] += waveform[index] * window[index]
            divider[offset + index] += window[index]
        }
    }

    /** The accumulated waveform, normalised by the divider, with unguarded samples left at zero. */
    fun finish(): FloatArray {
        val out = FloatArray(length)
        for (index in 0 until length) {
            out[index] = if (divider[index] >= DIVIDER_EPSILON) sum[index] / divider[index] else 0f
        }
        return out
    }

    companion object {
        /** Divider below this is treated as "no window covered this sample". */
        const val DIVIDER_EPSILON = 1e-8f
    }
}

/**
 * Reconstructs a signal from windowed chunks of itself: the standalone form of
 * [WaveformOverlapAdd], taking `(offset, chunkWaveform, windowLength)` triples and building
 * each chunk's symmetric Hann window itself. This is the exact accumulation [MdxSeparator]
 * performs per channel, factored out so its continuity can be tested without a model.
 */
internal fun overlapAdd(totalLength: Int, chunks: List<Triple<Int, FloatArray, Int>>): FloatArray {
    val accumulator = WaveformOverlapAdd(totalLength)
    for ((offset, waveform, windowLength) in chunks) {
        accumulator.add(offset, waveform, symmetricHann(windowLength))
    }
    return accumulator.finish()
}

/**
 * Full-track source separation with the downloadable MDX-Net model ([MdxModel]), producing the
 * instrumental stem.
 *
 * ## Algorithm (the reference demix, replicated)
 *
 * The mix is resampled to 44.1 kHz, peak-normalised to 0.9, and laid into a padded mixture:
 * [TRIM] zeros, the mix, then the pad from [mdxChunkGeometry]. Chunks of [CHUNK_SIZE] samples
 * advance by [STEP] (75% of a chunk; the 25% overlap is what the reconstruction window smooths
 * over). Each chunk is STFT'd per channel (n_fft 6144, hop 1024), assembled into the model's
 * `[1, 4, 3072, 256]` tensor in `[L_real, L_imag, R_real, R_imag]` order with the spectrum
 * cropped to 3072 bins and the first three bins zeroed (the model was trained with its lowest
 * bins silenced; feeding them back would hand it content it never saw in training). The output
 * spectrograms are rebuilt to 3073 bins with a zero top bin, inverse-transformed to a full chunk
 * of waveform, windowed by a symmetric Hann over the chunk's actual (unpadded) length, and
 * overlap-added with divider normalisation. Finally the trim is stripped from both ends, the
 * result is truncated to the mix length, and the peak normalisation is undone.
 *
 * Everything fails soft: an unavailable resampler, an unreadable model, a failed inference, or
 * a cancellation all produce null rather than an exception or a partial stem, and the caller
 * keeps the unseparated track.
 */
class MdxSeparator(
    /** The verified model file (see [MdxModel.verify]); opened lazily on first use. */
    private val modelFile: File,
    /** How many threads inference may use; separation is the most expensive pass in the app. */
    private val inferenceThreads: () -> Int,
    /**
     * Platform hook applied to the session options after the shared configuration, so a caller
     * can attempt a platform accelerator (for example an Android NNAPI attempt) without this
     * module knowing the platform exists. A hook that throws is ignored — accelerated inference
     * is an optimisation, never a requirement.
     */
    private val configureOptions: (OrtSession.SessionOptions) -> Unit = {},
) {

    @Volatile private var session: OrtSession? = null
    @Volatile private var sessionThreads = 0
    private val lock = Any()

    private val transform = SpectralTransform(MdxModel.N_FFT, MdxModel.HOP)

    /** One session per thread count, kept across tracks: parsing a 64 MB graph is not per-track work. */
    private fun session(): OrtSession? {
        val threads = inferenceThreads()
        session?.takeIf { sessionThreads == threads }?.let { return it }
        synchronized(lock) {
            session?.takeIf { sessionThreads == threads }?.let { return it }
            runCatching { session?.close() }
            session = null
            return runCatching {
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(threads)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    // Same reasoning as VocalTracker: the arena retains every block it allocates
                    // for the life of the session, which a backgrounded music player cannot
                    // justify.
                    setCPUArenaAllocator(false)
                    setMemoryPatternOptimization(false)
                }
                runCatching { configureOptions(options) }
                    .onFailure { AnalysisLog.warn("MDX session option hook failed; continuing without it", it) }
                OrtEnvironment.getEnvironment().createSession(modelFile.absolutePath, options)
                    .also {
                        session = it
                        sessionThreads = threads
                    }
            }.onFailure { AnalysisLog.warn("MDX model unavailable; no separation will be produced", it) }
                .getOrNull()
        }
    }

    /**
     * Separates the instrumental stem from planar stereo [left]/[right] at [sampleRate].
     *
     * [onProgress] is called after each chunk with the fraction of chunks completed, in
     * `0..1`. [cancelled] is polled once per chunk; when it turns true the separation aborts
     * and returns null, discarding the partial accumulation. Returns null on any failure.
     */
    fun separate(
        left: FloatArray,
        right: FloatArray,
        sampleRate: Double,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): StereoStems? {
        if (left.isEmpty() || left.size != right.size) return null
        val mixLeft = MelSpectrogram.resample(left, sampleRate, MdxModel.SAMPLE_RATE.toDouble())
            ?: return null
        val mixRight = MelSpectrogram.resample(right, sampleRate, MdxModel.SAMPLE_RATE.toDouble())
            ?: return null
        if (cancelled()) return null
        val active = session() ?: return null

        // Peak-normalise to the level the reference pipeline feeds the model, remembering the
        // factor so the stem comes back at the mix's own level — the caller's derived vocal
        // (original - instrumental) is only meaningful if both sides share a scale.
        var peak = 0f
        for (value in mixLeft) peak = maxOf(peak, abs(value))
        for (value in mixRight) peak = maxOf(peak, abs(value))
        val scale = if (peak > MAX_PEAK) MAX_PEAK / peak else 1f

        val length = mixLeft.size
        val geometry = mdxChunkGeometry(length)
        val mixtureLeft = FloatArray(geometry.mixtureLength)
        val mixtureRight = FloatArray(geometry.mixtureLength)
        for (index in 0 until length) {
            mixtureLeft[TRIM + index] = mixLeft[index] * scale
            mixtureRight[TRIM + index] = mixRight[index] * scale
        }

        val accumulatedLeft = WaveformOverlapAdd(geometry.mixtureLength)
        val accumulatedRight = WaveformOverlapAdd(geometry.mixtureLength)

        var start = 0
        var chunkIndex = 0
        while (start < geometry.mixtureLength) {
            if (cancelled()) return null
            val actualLength = min(CHUNK_SIZE, geometry.mixtureLength - start)
            val chunkLeft = FloatArray(CHUNK_SIZE)
            val chunkRight = FloatArray(CHUNK_SIZE)
            mixtureLeft.copyInto(chunkLeft, 0, start, start + actualLength)
            mixtureRight.copyInto(chunkRight, 0, start, start + actualLength)

            val reconstructed = runCatching { runChunk(active, chunkLeft, chunkRight) }
                .onFailure { AnalysisLog.warn("MDX inference failed", it) }
                .getOrNull() ?: return null

            val window = symmetricHann(actualLength)
            accumulatedLeft.add(start, reconstructed.first, window)
            accumulatedRight.add(start, reconstructed.second, window)

            chunkIndex += 1
            onProgress(chunkIndex.toFloat() / geometry.chunkCount)
            start += STEP
        }

        val paddedLeft = accumulatedLeft.finish()
        val paddedRight = accumulatedRight.finish()
        val outLeft = FloatArray(length)
        val outRight = FloatArray(length)
        for (index in 0 until length) {
            outLeft[index] = paddedLeft[TRIM + index] / scale
            outRight[index] = paddedRight[TRIM + index] / scale
        }
        return StereoStems(outLeft, outRight, MdxModel.SAMPLE_RATE.toDouble())
    }

    /**
     * Runs one zero-padded chunk through the model and returns the reconstructed chunk waveform
     * per channel, each [CHUNK_SIZE] samples long. Null when the output is not the shape the
     * front end promised — a mismatched model file is a failure, not something to improvise
     * around.
     */
    private fun runChunk(
        active: OrtSession,
        chunkLeft: FloatArray,
        chunkRight: FloatArray,
    ): Pair<FloatArray, FloatArray>? {
        val specLeft = transform.stft(chunkLeft)
        val specRight = transform.stft(chunkRight)
        val frames = specLeft.frames
        if (frames != MdxModel.DIM_T) return null

        // [1, 4, DIM_F, DIM_T] in [L_real, L_imag, R_real, R_imag] order, bins cropped from
        // 3073 to DIM_F and the first three bins left at zero.
        val input = FloatArray(4 * MdxModel.DIM_F * frames)
        for (bin in FIRST_KEPT_BIN until MdxModel.DIM_F) {
            val base = bin * frames
            val leftReal = specLeft.real[bin]
            val leftImag = specLeft.imag[bin]
            val rightReal = specRight.real[bin]
            val rightImag = specRight.imag[bin]
            for (frame in 0 until frames) {
                input[base + frame] = leftReal[frame]
                input[(MdxModel.DIM_F + bin) * frames + frame] = leftImag[frame]
                input[(2 * MdxModel.DIM_F + bin) * frames + frame] = rightReal[frame]
                input[(3 * MdxModel.DIM_F + bin) * frames + frame] = rightImag[frame]
            }
        }

        val environment = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, 4, MdxModel.DIM_F.toLong(), frames.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            active.run(mapOf(active.inputNames.first() to tensor)).use { outputs ->
                // The output tensor's buffer, read by absolute index, rather than `.value`:
                // that property would box 3.1M floats into nested arrays to read them straight
                // back out (the same trap VocalTracker documents for its own output).
                val output = (outputs.get(0) as OnnxTensor).floatBuffer
                val outSpecLeft = rebuildSpectrogram(output, channelBase = 0, frames = frames)
                val outSpecRight = rebuildSpectrogram(output, channelBase = 2, frames = frames)
                val waveLeft = transform.istft(outSpecLeft, CHUNK_SIZE)
                val waveRight = transform.istft(outSpecRight, CHUNK_SIZE)
                waveLeft to waveRight
            }
        }
    }

    /**
     * Reads one channel's complex spectrogram out of the model's `[1, 4, DIM_F, DIM_T]` output,
     * where [channelBase] is 0 for left (real at 0, imag at 1) and 2 for right. The bin the
     * model never saw — index [MdxModel.DIM_F], cropped on the way in — is rebuilt as zero so
     * the ISTFT gets its full `N_FFT / 2 + 1` bins.
     */
    private fun rebuildSpectrogram(
        output: FloatBuffer,
        channelBase: Int,
        frames: Int,
    ): ComplexSpectrogram {
        val bins = MdxModel.N_FFT / 2 + 1
        val real = Array(bins) { FloatArray(frames) }
        val imag = Array(bins) { FloatArray(frames) }
        val realBase = channelBase * MdxModel.DIM_F * frames
        val imagBase = (channelBase + 1) * MdxModel.DIM_F * frames
        for (bin in 0 until MdxModel.DIM_F) {
            val row = bin * frames
            for (frame in 0 until frames) {
                real[bin][frame] = output.get(realBase + row + frame)
                imag[bin][frame] = output.get(imagBase + row + frame)
            }
        }
        return ComplexSpectrogram(real, imag)
    }

    fun release() {
        synchronized(lock) {
            runCatching { session?.close() }
            session = null
            sessionThreads = 0
        }
    }

    companion object {
        /** Samples per inference chunk: `HOP * (DIM_T - 1)` = 1024 * 255. */
        const val CHUNK_SIZE = 261_120

        /** Zeros trimmed from each end of the mixture; equals the front end's `N_FFT / 2`. */
        const val TRIM = 3_072

        /** New mix samples each chunk generates: chunk minus the trim on both sides. */
        const val GEN_SIZE = CHUNK_SIZE - 2 * TRIM // 254_976

        /** Distance between chunk starts: chunks overlap by 25% of their length. */
        const val STEP = 195_840 // (1 - 0.25) * CHUNK_SIZE

        /** The level the mix is peak-normalised to before inference. */
        private const val MAX_PEAK = 0.9f

        /** Bins 0..2 are zeroed on the way into the model; this is the first bin kept. */
        private const val FIRST_KEPT_BIN = 3
    }
}
