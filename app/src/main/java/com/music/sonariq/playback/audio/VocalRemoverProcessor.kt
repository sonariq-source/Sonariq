package com.music.sonariq.playback.audio

import kotlin.math.exp

/**
 * Vocal Remover: reduces the centre channel of a stereo mix — where a lead
 * vocal usually sits — by decomposing each frame into mid (the part both
 * channels share) and side (the part they differ by), scaling the mid by
 * [level], and recombining:
 *
 *     mid  = (L + R) / 2,  side = (L − R) / 2
 *     L'   = mid × level + side
 *     R'   = mid × level − side
 *
 * [level] 1.0 is the original mix and 0.0 is side-only. This is honest
 * centre-channel reduction, not stem separation: anything else mixed to
 * the centre (a centred bass, a mono-folded recording) fades with the
 * vocal, and a vocal panned off-centre survives it. Results vary by mix,
 * which the feature's own copy says out loud.
 *
 * Contracts, the same ones every stage in [DspChain] keeps:
 * - **Bit-transparent when off.** At [level] 1.0 the block leaves
 *   byte-identical — the stage returns before reading a single sample, so
 *   the default playback path is exactly the one without the feature.
 * - **Stateless per sample.** Each frame is computed from its own two
 *   samples alone, so seeking and track changes cannot desync it. The
 *   only state is the smoothed gain, which exists to keep level changes
 *   click-free and snaps to the target on flush/configure (a seek or a
 *   new stream starts at the requested level, not on a swell toward it).
 * - **No clipping introduced.** For in-range input the output is a
 *   weighted combination whose magnitude never exceeds the larger input
 *   channel's, so no clamp is applied; out-of-range intermediates pass
 *   through untouched, like every other stage.
 *
 * Stereo only: a mono stream is already all centre with nothing to
 * separate, and surround layouts have no single centre to scale — both
 * pass through unchanged.
 */
class VocalRemoverProcessor : FloatAudioProcessor {

    /**
     * The vocal level, 1.0 (original) down to 0.0 (centre removed).
     * Set from the app thread; read on the audio thread.
     */
    @Volatile
    var level: Float = 1f

    private var channelCount: Int = AudioBlock.DEFAULT_CHANNELS
    private var sampleRate: Int = 0

    /** The mid gain actually in force, smoothed toward [level] per frame. */
    private var currentGain: Float = 1f

    override fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount.coerceAtLeast(1)
        // A new stream starts at the requested level.
        currentGain = level.coerceIn(0f, 1f)
    }

    override fun process(block: AudioBlock) {
        val frames = block.frameCount
        // Parked: nothing to do, not stereo, or parked at the original
        // mix with the gain already settled there — the bit-transparent
        // bypass every stage in the chain keeps.
        if (frames == 0 || channelCount != 2) return
        val target = level.coerceIn(0f, 1f)
        if (target == 1f && currentGain == 1f) return

        val samples = block.samples
        // One-pole approach to the target, ~30 ms time constant: fast
        // enough to feel immediate, slow enough that a slider drag or a
        // reset never lands as a click.
        val rate = if (sampleRate > 0) sampleRate else 44_100
        val coeff = 1f - exp(-1f / (rate * RAMP_SECONDS)).toFloat()
        var gain = currentGain
        var index = 0
        val end = frames * 2
        while (index < end) {
            gain += (target - gain) * coeff
            val left = samples[index]
            val right = samples[index + 1]
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f
            val scaledMid = mid * gain
            samples[index] = scaledMid + side
            samples[index + 1] = scaledMid - side
            index += 2
        }
        currentGain = gain
    }

    override fun flush() {
        // A seek lands at the requested level at once rather than swelling
        // toward it from wherever the gain happened to be.
        currentGain = level.coerceIn(0f, 1f)
    }

    override fun reset() {
        channelCount = AudioBlock.DEFAULT_CHANNELS
        sampleRate = 0
        currentGain = level.coerceIn(0f, 1f)
    }

    private companion object {
        /** Gain smoothing time constant, in seconds. */
        const val RAMP_SECONDS = 0.030f
    }
}
