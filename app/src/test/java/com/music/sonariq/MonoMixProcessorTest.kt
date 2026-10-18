package com.music.sonariq

import com.music.sonariq.playback.audio.AudioBlock
import com.music.sonariq.playback.audio.MonoMixProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the mono downmix actually does to a signal.
 *
 * The contract under test: enabled, every channel of a frame becomes the
 * frame's mean; disabled or already-mono, the block leaves bit-identical.
 */
class MonoMixProcessorTest {

    private fun stereoBlock(vararg pairs: Pair<Float, Float>): AudioBlock {
        val block = AudioBlock(channelCount = 2, capacityFrames = pairs.size.coerceAtLeast(1))
        pairs.forEachIndexed { frame, (left, right) ->
            block.samples[frame * 2] = left
            block.samples[frame * 2 + 1] = right
        }
        block.setFrameCount(pairs.size)
        return block
    }

    @Test
    fun `stereo folds to the mean on both channels`() {
        val processor = MonoMixProcessor().apply {
            enabled = true
            configure(sampleRate = 44100, channelCount = 2)
        }
        val block = stereoBlock(1f to -1f, 0.5f to 0.25f, -0.75f to -0.25f)

        processor.process(block)

        assertArrayEquals(
            floatArrayOf(0f, 0f, 0.375f, 0.375f, -0.5f, -0.5f),
            block.samples.sliceArray(0 until block.sampleCount),
            1e-6f,
        )
    }

    @Test
    fun `nothing panned hard is lost`() {
        val processor = MonoMixProcessor().apply {
            enabled = true
            configure(sampleRate = 44100, channelCount = 2)
        }
        // A guitar panned hard left at full scale.
        val block = stereoBlock(1f to 0f)

        processor.process(block)

        assertEquals(0.5f, block.samples[0], 1e-6f)
        assertEquals(0.5f, block.samples[1], 1e-6f)
    }

    @Test
    fun `disabled leaves the block bit-identical`() {
        val processor = MonoMixProcessor().apply {
            enabled = false
            configure(sampleRate = 44100, channelCount = 2)
        }
        val block = stereoBlock(0.3f to -0.7f, 0.9f to 0.1f)
        val before = block.samples.copyOf()

        processor.process(block)

        assertArrayEquals(before, block.samples, 0f)
    }

    @Test
    fun `a mono stream passes through untouched`() {
        val processor = MonoMixProcessor().apply {
            enabled = true
            configure(sampleRate = 44100, channelCount = 1)
        }
        val block = AudioBlock(channelCount = 1, capacityFrames = 4)
        block.samples[0] = 0.5f
        block.samples[1] = -0.5f
        block.setFrameCount(2)
        val before = block.samples.copyOf()

        processor.process(block)

        assertArrayEquals(before, block.samples, 0f)
    }

    @Test
    fun `silence stays silence and headroom is preserved`() {
        val processor = MonoMixProcessor().apply {
            enabled = true
            configure(sampleRate = 48000, channelCount = 2)
        }
        // Out-of-range intermediates must not be clamped by the mix.
        val block = stereoBlock(0f to 0f, 1.5f to 1.5f)

        processor.process(block)

        assertEquals(0f, block.samples[0], 0f)
        assertEquals(0f, block.samples[1], 0f)
        assertEquals(1.5f, block.samples[2], 1e-6f)
        assertEquals(1.5f, block.samples[3], 1e-6f)
    }

    @Test
    fun `four channels fold to their mean`() {
        val processor = MonoMixProcessor().apply {
            enabled = true
            configure(sampleRate = 48000, channelCount = 4)
        }
        val block = AudioBlock(channelCount = 4, capacityFrames = 2)
        block.samples[0] = 1f
        block.samples[1] = 2f
        block.samples[2] = 3f
        block.samples[3] = 4f
        block.setFrameCount(1)

        processor.process(block)

        assertArrayEquals(floatArrayOf(2.5f, 2.5f, 2.5f, 2.5f), block.samples.sliceArray(0 until 4), 1e-6f)
    }
}
