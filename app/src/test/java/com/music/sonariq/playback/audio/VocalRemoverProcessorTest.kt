package com.music.sonariq.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Deterministic tests for [VocalRemoverProcessor]'s mid/side math:
 * identity at full level, side-only at zero, mono streams untouched,
 * click-free gain ramps, and no clipping introduced on hot input.
 */
class VocalRemoverProcessorTest {

    private val sampleRate = 44_100

    private fun stereoBlock(frames: List<Pair<Float, Float>>): AudioBlock {
        val block = AudioBlock(channelCount = 2, capacityFrames = frames.size)
        frames.forEachIndexed { index, (left, right) ->
            block.samples[index * 2] = left
            block.samples[index * 2 + 1] = right
        }
        block.reset(frames.size)
        return block
    }

    private fun processor(level: Float): VocalRemoverProcessor {
        val processor = VocalRemoverProcessor()
        processor.level = level
        processor.configure(sampleRate, 2)
        // Configure snaps the gain to the requested level, so the math
        // below is the settled state rather than a ramp in flight.
        return processor
    }

    @Test
    fun `full level is bit transparent`() {
        val input = listOf(
            0.25f to -0.5f,
            -0.9f to 0.9f,
            0.0f to 0.0f,
            1.0f to -1.0f,
            -0.123456f to 0.654321f,
        )
        val block = stereoBlock(input)
        val before = block.samples.copyOf()
        processor(1.0f).process(block)
        assertTrue(block.samples.contentEquals(before))
    }

    @Test
    fun `zero level leaves only the side signal`() {
        // L=0.8, R=0.2: mid=0.5, side=0.3 -> (0.3, -0.3).
        // L=-0.4, R=0.6: mid=0.1, side=-0.5 -> (-0.5, 0.5).
        val block = stereoBlock(listOf(0.8f to 0.2f, -0.4f to 0.6f))
        processor(0.0f).process(block)
        assertEquals(0.3f, block.samples[0], 1e-6f)
        assertEquals(-0.3f, block.samples[1], 1e-6f)
        assertEquals(-0.5f, block.samples[2], 1e-6f)
        assertEquals(0.5f, block.samples[3], 1e-6f)
    }

    @Test
    fun `half level scales the centre and keeps the sides`() {
        // L=0.8, R=0.2: mid=0.5, side=0.3, mid*0.5=0.25 -> (0.55, -0.05).
        val block = stereoBlock(listOf(0.8f to 0.2f))
        processor(0.5f).process(block)
        assertEquals(0.55f, block.samples[0], 1e-6f)
        assertEquals(-0.05f, block.samples[1], 1e-6f)
    }

    @Test
    fun `pure centre content fades with the level`() {
        // A signal identical in both channels is all mid: at zero level
        // it is gone, at half level it is halved, in both channels alike.
        val block = stereoBlock(listOf(0.6f to 0.6f, -0.2f to -0.2f))
        processor(0.0f).process(block)
        assertEquals(0f, block.samples[0], 1e-6f)
        assertEquals(0f, block.samples[1], 1e-6f)
        assertEquals(0f, block.samples[2], 1e-6f)
        assertEquals(0f, block.samples[3], 1e-6f)
    }

    @Test
    fun `mono stream passes through unchanged at any level`() {
        val block = AudioBlock(channelCount = 1, capacityFrames = 4)
        block.samples[0] = 0.5f
        block.samples[1] = -0.25f
        block.samples[2] = 0.75f
        block.samples[3] = 0.0f
        block.reset(4)
        val before = block.samples.copyOf()
        val processor = VocalRemoverProcessor()
        processor.level = 0.0f
        processor.configure(sampleRate, 1)
        processor.process(block)
        assertTrue(block.samples.contentEquals(before))
    }

    @Test
    fun `level changes ramp smoothly instead of clicking`() {
        // All-centre signal: the output amplitude is the gain itself, so
        // the per-frame steps of the output are the gain's own steps.
        val frames = 16_384
        val block = stereoBlock(List(frames) { 0.5f to 0.5f })
        val processor = VocalRemoverProcessor()
        processor.level = 1.0f
        processor.configure(sampleRate, 2)
        processor.level = 0.0f // changed live, no flush: must ramp
        processor.process(block)
        var previous = 0.5f
        var maxStep = 0f
        for (frame in 0 until frames) {
            val out = block.samples[frame * 2]
            maxStep = maxOf(maxStep, abs(out - previous))
            previous = out
        }
        // ~30 ms time constant at 44.1 kHz: the largest single-frame move
        // is a small fraction of the full swing, never the whole jump.
        assertTrue("max per-frame step was $maxStep", maxStep < 0.01f)
        // And it lands: the last frames sit at the removed-centre floor.
        assertTrue(abs(block.samples[(frames - 1) * 2]) < 0.01f)
    }

    @Test
    fun `flush snaps the gain to the requested level`() {
        val processor = VocalRemoverProcessor()
        processor.level = 1.0f
        processor.configure(sampleRate, 2)
        processor.level = 0.0f
        processor.flush()
        val block = stereoBlock(listOf(0.6f to 0.6f))
        processor.process(block)
        assertEquals(0f, block.samples[0], 1e-6f)
        assertEquals(0f, block.samples[1], 1e-6f)
    }

    @Test
    fun `hot input never exceeds the loudest input channel`() {
        val input = listOf(
            1.0f to -1.0f,
            1.0f to 0.5f,
            -1.0f to -0.25f,
            0.75f to -0.75f,
        )
        for (level in listOf(0.0f, 0.25f, 0.5f, 0.75f)) {
            val block = stereoBlock(input)
            processor(level).process(block)
            input.forEachIndexed { index, (left, right) ->
                val limit = maxOf(abs(left), abs(right))
                assertTrue(
                    "level $level frame $index left",
                    abs(block.samples[index * 2]) <= limit + 1e-6f,
                )
                assertTrue(
                    "level $level frame $index right",
                    abs(block.samples[index * 2 + 1]) <= limit + 1e-6f,
                )
            }
        }
    }

    @Test
    fun `out of range levels are coerced`() {
        val block = stereoBlock(listOf(0.8f to 0.2f))
        processor(2.5f).process(block)
        // Above 1 coerces to 1: the original mix, untouched.
        assertEquals(0.8f, block.samples[0], 1e-6f)
        assertEquals(0.2f, block.samples[1], 1e-6f)
        val block2 = stereoBlock(listOf(0.8f to 0.2f))
        processor(-1.0f).process(block2)
        // Below 0 coerces to 0: side only.
        assertEquals(0.3f, block2.samples[0], 1e-6f)
        assertEquals(-0.3f, block2.samples[1], 1e-6f)
    }
}
