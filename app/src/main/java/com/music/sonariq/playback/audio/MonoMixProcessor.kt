package com.music.sonariq.playback.audio

/**
 * Mono downmix: folds every channel of a frame into their mean and writes it
 * back to all channels.
 *
 * For a listener with one working earbud, hearing damage on one side, or a
 * single-speaker dock, a stereo mix silently drops half the music — a guitar
 * panned hard left simply never arrives. Averaging both channels into each
 * output keeps every part of the mix audible on every transducer.
 *
 * Stateless by design: each frame is mixed from its own samples alone, so
 * enabling or seeking needs no history flush. Self-bypassing when off or when
 * the stream is already mono — the block leaves bit-identical, the same
 * contract every other stage in [DspChain] keeps. Headroom is preserved: the
 * mean of two in-range samples cannot exceed full scale, and out-of-range
 * intermediate values pass through untouched rather than being clamped.
 *
 * Sits in [DspChain] after the equalizer, so the L/R balance trim is folded
 * into the mono mix rather than fighting it.
 */
class MonoMixProcessor : FloatAudioProcessor {

    /** Set from the app thread; read on the audio thread. */
    @Volatile
    var enabled: Boolean = false

    private var channelCount: Int = AudioBlock.DEFAULT_CHANNELS

    override fun configure(sampleRate: Int, channelCount: Int) {
        this.channelCount = channelCount.coerceAtLeast(1)
    }

    override fun process(block: AudioBlock) {
        val frames = block.frameCount
        val channels = channelCount
        // Parked: off, nothing to do, or a mono stream — which is already
        // exactly what this stage would produce.
        if (!enabled || frames == 0 || channels < 2) return

        val samples = block.samples
        val perChannel = 1f / channels
        var index = 0
        val end = frames * channels
        while (index < end) {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += samples[index + channel]
            }
            val mean = sum * perChannel
            for (channel in 0 until channels) {
                samples[index + channel] = mean
            }
            index += channels
        }
    }

    override fun flush() {
        // No state to flush.
    }

    override fun reset() {
        channelCount = AudioBlock.DEFAULT_CHANNELS
    }
}
