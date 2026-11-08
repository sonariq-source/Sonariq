package com.music.sonariq.playback.audio

import com.music.sonariq.vocal.StemStore
import com.music.sonariq.vocal.WavStemFile
import java.io.File
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor

/**
 * Vocal Remover, AI layer: mixes a pre-separated instrumental stem (see
 * `StemStore` / `StemJobs`) against the live mix in the DSP chain.
 *
 * The stem on its own is the instrumental. The vocal is not stored anywhere —
 * it is derived per sample, exactly as the separators derive it:
 *
 *     vocal = original − instrumental
 *     out   = instrumental + level × vocal
 *
 * so [level] 1.0 reconstructs the original mix, 0.0 is the pure instrumental,
 * and anything between is a true vocal fader rather than a centre-channel
 * approximation. That is the whole point of running this stage *before*
 * [VocalRemoverProcessor] in [DspChain] and parking that stage at 1.0 while a
 * stem is engaged (see PlaybackService's stem routing): with a stem in hand
 * the DSP stage has nothing left to approximate.
 *
 * ## Synchronisation
 *
 * The chain is fed decoder buffers, not a playhead, so this stage keeps its
 * own frame counter: it advances by every block [process] sees, and the stem
 * read position is `startStemFrame + elapsed × (44100 / chainSampleRate)`,
 * where startStemFrame is set by [syncToFrame] — the service hands over the
 * player's position in chain-rate frames on every track transition and seek.
 * A sync is *pending* until consumed: [flush] and [configure] consume it
 * (that is when the sink says the stream actually jumped), and [process]
 * also consumes it at the next block boundary, because the service's seek
 * callback and the sink's flush are ordered by different threads and a sync
 * that arrived a moment after the flush must not wait for the next seek to
 * take effect. While the rates differ the read position lands between stem
 * frames and the samples are linearly interpolated; at 44.1 kHz in and out
 * (the common case) reads are exact.
 *
 * Known limit, stated plainly: the counter counts *processed* frames, and
 * the sink processes ahead of the speaker, so after a transition the stem is
 * aligned to the buffer timeline the service reported, not to the exact
 * sample the listener hears — the residual error is the sink's lead, tens of
 * milliseconds, and it is corrected at the next seek or boundary. There is
 * no drift correction: decoder and stem share the track's own timeline, so
 * a sync, once taken, stays taken.
 *
 * ## Contracts
 *
 * The same ones every stage in [DspChain] keeps:
 * - **Bit-transparent when off.** At [level] 1.0 with the gain settled, with
 *   no stem open, or on a non-stereo stream, the block leaves untouched —
 *   the stage returns before reading a single sample (the frame counter
 *   still advances, which is bookkeeping, not audio).
 * - **No stem, no silence.** A read position past the stem's end means the
 *   stem ran out (a short stem, a longer edit of the track): those frames
 *   pass through as the original mix rather than dropping to silence.
 * - **Click-free level changes.** The same ~30 ms one-pole smoothing as
 *   [VocalRemoverProcessor], snapping to the target on flush/configure.
 */
class AiStemProcessor : FloatAudioProcessor {

    /** The vocal level, 1.0 (original mix) down to 0.0 (instrumental only). */
    @Volatile
    var level: Float = 1f

    private val lock = Any()

    private var reader: WavStemFile? = null
    private var readerFile: File? = null

    /** A stem queued for the next gapless boundary; see [queueStemFile]. */
    private var pendingFile: File? = null
    private var hasPendingFile: Boolean = false

    /** A position handed over by [syncToFrame] and not yet consumed, in chain-rate frames. */
    @Volatile
    private var pendingSyncFrame: Long? = null

    private var channelCount: Int = AudioBlock.DEFAULT_CHANNELS
    private var sampleRate: Int = 0

    /** The level actually in force, smoothed toward [level] per frame. */
    private var currentGain: Float = 1f

    /** Chain-rate frames processed since the last sync was consumed. */
    private var frameCounter: Long = 0L

    /** Stem-frame position (at 44.1 kHz) corresponding to [frameCounter] zero. */
    private var startStemFrame: Double = 0.0

    /** Bulk-read scratch for the stem frames one block spans; grows to the largest block seen. */
    private var scratch = FloatArray(0)

    /** The stem file currently open, if any — for callers deciding whether a switch is needed. */
    val currentStemFile: File? get() = synchronized(lock) { readerFile }

    /** Whether a stem is open on this stage right now. */
    val hasStem: Boolean get() = synchronized(lock) { reader != null }

    /**
     * Opens [file] as this stage's stem (null closes the current one) and
     * registers it with [StemStore] so eviction leaves it alone. The read
     * position restarts at zero; the caller follows with [syncToFrame] for
     * anything but a track starting at its head.
     */
    fun setStemFile(file: File?) {
        synchronized(lock) {
            closeReaderLocked()
            if (file != null) {
                val opened = WavStemFile.open(file)
                if (opened != null) {
                    reader = opened
                    readerFile = file
                    StemStore.registerOpen(file)
                }
            }
            frameCounter = 0L
            startStemFrame = 0.0
        }
    }

    /**
     * Queues [file] to become the stem at the next gapless stream boundary
     * ([onStreamBoundary]), without disturbing the stem playing now. This is
     * how a same-player gapless advance switches stems on the exact sample
     * the sink crosses over, rather than whenever a service callback happens
     * to run.
     */
    fun queueStemFile(file: File?) {
        synchronized(lock) {
            pendingFile = file
            hasPendingFile = true
        }
    }

    /**
     * Hands over the player's position, in chain-rate frames, for the stem
     * to adopt. Pending until [flush], [configure] or the next [process]
     * block consumes it — see the class doc for why all three count.
     */
    fun syncToFrame(chainFrame: Long) {
        pendingSyncFrame = chainFrame.coerceAtLeast(0L)
    }

    /**
     * The gapless boundary: the sink is crossing from one track to the next
     * on this exact sample. A queued stem (see [queueStemFile]) becomes the
     * open one, the frame counter restarts at the new track's head, any
     * unconsumed sync (which described the outgoing track) is dropped, and
     * the gain snaps to the requested level.
     */
    fun onStreamBoundary() {
        synchronized(lock) {
            if (hasPendingFile) {
                val next = pendingFile
                pendingFile = null
                hasPendingFile = false
                closeReaderLocked()
                if (next != null) {
                    val opened = WavStemFile.open(next)
                    if (opened != null) {
                        reader = opened
                        readerFile = next
                        StemStore.registerOpen(next)
                    }
                }
            }
            frameCounter = 0L
            startStemFrame = 0.0
            pendingSyncFrame = null
            currentGain = level.coerceIn(0f, 1f)
        }
    }

    override fun configure(sampleRate: Int, channelCount: Int) {
        synchronized(lock) {
            this.sampleRate = sampleRate
            this.channelCount = channelCount.coerceAtLeast(1)
            frameCounter = 0L
            startStemFrame = 0.0
            // A sync handed over before the format was known is consumed
            // here, now that the rate exists to convert it with.
            adoptPendingSyncLocked()
            currentGain = level.coerceIn(0f, 1f)
        }
    }

    override fun process(block: AudioBlock) {
        val frames = block.frameCount
        if (frames == 0) return

        val activeReader: WavStemFile
        val startCounter: Long
        val startPos: Double
        val ratio: Double
        synchronized(lock) {
            adoptPendingSyncLocked()
            startCounter = frameCounter
            frameCounter += frames
            val current = reader
            if (current == null || channelCount != 2) return
            val target = level.coerceIn(0f, 1f)
            if (target == 1f && currentGain == 1f) return
            activeReader = current
            ratio = if (sampleRate > 0) STEM_RATE / sampleRate else 1.0
            startPos = startStemFrame + startCounter * ratio
        }

        // The stem frames this block spans, plus one for interpolation at
        // the trailing edge.
        val firstFrame = floor(startPos).toLong()
        val lastFrame = ceil(startPos + frames * ratio).toLong() + 1
        val wanted = (lastFrame - firstFrame).toInt().coerceAtLeast(0)
        if (wanted == 0) return
        if (scratch.size < wanted * 2) scratch = FloatArray(wanted * 2)
        val got = activeReader.readFrames(firstFrame, wanted, scratch)
        if (got <= 0) return // Stem exhausted (or unreadable): the mix passes through.

        val total = activeReader.totalFrames
        val target = level.coerceIn(0f, 1f)
        val rate = if (sampleRate > 0) sampleRate else STEM_RATE.toInt()
        val coeff = 1f - exp(-1f / (rate * RAMP_SECONDS)).toFloat()
        var gain = currentGain
        val samples = block.samples
        var index = 0
        for (frame in 0 until frames) {
            gain += (target - gain) * coeff
            val pos = startPos + frame * ratio
            val stemFrame = floor(pos).toLong()
            if (stemFrame < 0 || stemFrame >= total) {
                // No stem under this frame: pass the original through. Only
                // the index advances — the samples are not touched.
                index += 2
                continue
            }
            val local = (stemFrame - firstFrame).toInt()
            if (local < 0 || local >= got) {
                index += 2
                continue
            }
            val frac = (pos - stemFrame).toFloat()
            val instLeft: Float
            val instRight: Float
            if (frac == 0f || local + 1 >= got) {
                instLeft = scratch[local * 2]
                instRight = scratch[local * 2 + 1]
            } else {
                val next = (local + 1) * 2
                instLeft = scratch[local * 2] + (scratch[next] - scratch[local * 2]) * frac
                instRight = scratch[local * 2 + 1] + (scratch[next + 1] - scratch[local * 2 + 1]) * frac
            }
            val origLeft = samples[index]
            val origRight = samples[index + 1]
            samples[index] = instLeft + gain * (origLeft - instLeft)
            samples[index + 1] = instRight + gain * (origRight - instRight)
            index += 2
        }
        synchronized(lock) {
            // Only the gain is published back; a flush or boundary that
            // landed mid-block has already snapped it to where it wants.
            currentGain = gain
        }
    }

    override fun flush() {
        synchronized(lock) {
            adoptPendingSyncLocked()
            currentGain = level.coerceIn(0f, 1f)
        }
    }

    override fun reset() {
        synchronized(lock) {
            channelCount = AudioBlock.DEFAULT_CHANNELS
            sampleRate = 0
            frameCounter = 0L
            startStemFrame = 0.0
            pendingSyncFrame = null
            currentGain = level.coerceIn(0f, 1f)
            // The stem itself stays open: a sink reset is a renderer event,
            // not a track change, and the service re-points the stage at
            // every real transition anyway.
        }
    }

    /**
     * Consumes a pending sync: the frame counter restarts and the stem
     * position becomes the synced chain frame converted to 44.1 kHz. Left
     * pending when the chain rate is not known yet — [configure] retries it
     * with the real rate rather than guessing one.
     */
    private fun adoptPendingSyncLocked() {
        val pending = pendingSyncFrame ?: return
        if (sampleRate <= 0) return
        pendingSyncFrame = null
        frameCounter = 0L
        startStemFrame = pending * (STEM_RATE / sampleRate)
    }

    private fun closeReaderLocked() {
        reader?.let { runCatching { it.close() } }
        readerFile?.let { StemStore.unregisterOpen(it) }
        reader = null
        readerFile = null
    }

    private companion object {
        /** Gain smoothing time constant, in seconds — the chain's shared value. */
        const val RAMP_SECONDS = 0.030f

        /** The stem storage rate, as a Double for position math. */
        const val STEM_RATE = 44_100.0
    }
}
