package com.music.sonariq.playback.audio

import com.music.sonariq.vocal.WavStemFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.roundToInt

/**
 * Tests for [AiStemProcessor]'s mix math against a synthetic stem file:
 * bit-identical at full level, the pure stem at zero, the midpoint between,
 * passthrough with no stem / past the stem's end / on mono, and a seek
 * sync landing on the asked stem frame.
 *
 * Every processor here is fresh and configured before use: configure()
 * snaps the smoothed gain to the requested level, so the math below is
 * the settled state rather than a ramp in flight — the same arrangement
 * [VocalRemoverProcessorTest] uses.
 */
class AiStemProcessorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val sampleRate = 44_100

    /** The stem storage conversion, so expectations are in stored (int16) terms. */
    private fun q(sample: Float): Float =
        (sample.coerceIn(-1f, 1f) * 32768f).roundToInt().coerceIn(-32768, 32767) / 32768f

    private fun stemFile(name: String, frames: List<Pair<Float, Float>>): File {
        val file = File(folder.root, name)
        WavStemFile.Writer(file).use { writer ->
            writer.writeFrames(
                FloatArray(frames.size) { frames[it].first },
                FloatArray(frames.size) { frames[it].second },
            )
        }
        return file
    }

    private fun stereoBlock(frames: List<Pair<Float, Float>>): AudioBlock {
        val block = AudioBlock(channelCount = 2, capacityFrames = frames.size)
        frames.forEachIndexed { index, (left, right) ->
            block.samples[index * 2] = left
            block.samples[index * 2 + 1] = right
        }
        block.reset(frames.size)
        return block
    }

    private fun processor(level: Float, stem: File?): AiStemProcessor {
        val processor = AiStemProcessor()
        processor.level = level
        if (stem != null) processor.setStemFile(stem)
        processor.configure(sampleRate, 2)
        return processor
    }

    private val input = listOf(
        0.8f to -0.4f,
        0.2f to 0.6f,
        -0.6f to -0.2f,
        0.4f to 0.4f,
    )
    private val stemFrames = listOf(
        0.3f to -0.1f,
        -0.2f to 0.25f,
        0.5f to -0.5f,
        0.0f to 0.1f,
    )

    @Test
    fun `full level is bit identical to the input`() {
        val block = stereoBlock(input)
        val before = block.samples.copyOf()
        processor(1.0f, stemFile("full.wav", stemFrames)).process(block)
        assertTrue(block.samples.contentEquals(before))
    }

    @Test
    fun `zero level leaves only the stem`() {
        val block = stereoBlock(input)
        processor(0.0f, stemFile("zero.wav", stemFrames)).process(block)
        stemFrames.forEachIndexed { index, (left, right) ->
            assertEquals(q(left), block.samples[index * 2], 1e-6f)
            assertEquals(q(right), block.samples[index * 2 + 1], 1e-6f)
        }
    }

    @Test
    fun `zero level output does not depend on the original at all`() {
        // At the bottom of the fader the vocal must be gone, which in
        // this design means the output is the stem alone: two completely
        // different originals over the same stem must produce identical
        // output — any residue of the original would show as a difference.
        val stem = stemFile("independence.wav", stemFrames)
        val loudBlock = stereoBlock(input)
        processor(0.0f, stem).process(loudBlock)
        val quietInput = listOf(
            0.01f to -0.01f,
            -0.02f to 0.02f,
            0.03f to -0.03f,
            -0.01f to 0.01f,
        )
        val quietBlock = stereoBlock(quietInput)
        processor(0.0f, stem).process(quietBlock)
        assertTrue(loudBlock.samples.contentEquals(quietBlock.samples))
    }

    @Test
    fun `dropping from full level to zero leaves the pure stem`() {
        // A level change mid-playback, in its settled state: the chain
        // snaps the smoothed gain on its boundaries (flush), and from
        // that snap onward the output is the quantised stem with nothing
        // of the original mixed back in — the fader's end states are
        // exact, not approached forever. A constant stem keeps the check
        // independent of the playback position.
        val constantStem = List(600) { 0.3f to -0.1f }
        val stem = stemFile("drop.wav", constantStem)
        val processor = processor(1.0f, stem)
        processor.process(stereoBlock(List(100) { 0.8f to -0.8f }))
        processor.level = 0.0f
        processor.flush()
        val block = stereoBlock(List(100) { 0.9f to -0.9f })
        processor.process(block)
        for (index in 0 until 100) {
            assertEquals(q(0.3f), block.samples[index * 2], 0f)
            assertEquals(q(-0.1f), block.samples[index * 2 + 1], 0f)
        }
    }

    @Test
    fun `half level is the midpoint of mix and stem`() {
        val block = stereoBlock(input)
        processor(0.5f, stemFile("half.wav", stemFrames)).process(block)
        input.forEachIndexed { index, (left, right) ->
            val stemLeft = q(stemFrames[index].first)
            val stemRight = q(stemFrames[index].second)
            assertEquals((left + stemLeft) / 2f, block.samples[index * 2], 1e-6f)
            assertEquals((right + stemRight) / 2f, block.samples[index * 2 + 1], 1e-6f)
        }
    }

    @Test
    fun `no stem passes through at any level`() {
        for (level in listOf(0.0f, 0.5f)) {
            val block = stereoBlock(input)
            val before = block.samples.copyOf()
            processor(level, null).process(block)
            assertTrue(block.samples.contentEquals(before))
        }
    }

    @Test
    fun `frames past the stem end pass through as the original`() {
        // A two-frame stem under a four-frame block, at zero level: the
        // first two frames are the stem, the tail is the untouched mix —
        // never silence.
        val shortStem = stemFile("short.wav", stemFrames.take(2))
        val block = stereoBlock(input)
        processor(0.0f, shortStem).process(block)
        assertEquals(q(stemFrames[0].first), block.samples[0], 1e-6f)
        assertEquals(q(stemFrames[1].second), block.samples[3], 1e-6f)
        assertEquals(input[2].first, block.samples[4], 0f)
        assertEquals(input[2].second, block.samples[5], 0f)
        assertEquals(input[3].first, block.samples[6], 0f)
        assertEquals(input[3].second, block.samples[7], 0f)
    }

    @Test
    fun `mono stream passes through unchanged`() {
        val block = AudioBlock(channelCount = 1, capacityFrames = 4)
        block.samples[0] = 0.5f
        block.samples[1] = -0.25f
        block.samples[2] = 0.75f
        block.samples[3] = 0.0f
        block.reset(4)
        val before = block.samples.copyOf()
        val processor = AiStemProcessor()
        processor.level = 0.0f
        processor.setStemFile(stemFile("mono.wav", stemFrames))
        processor.configure(sampleRate, 1)
        processor.process(block)
        assertTrue(block.samples.contentEquals(before))
    }

    @Test
    fun `a seek sync lands the stem on the asked frame`() {
        val frames = (0 until 16).map { i -> (i / 16f) to (-i / 16f) }
        val processor = AiStemProcessor()
        processor.level = 0.0f
        processor.setStemFile(stemFile("seek.wav", frames))
        processor.configure(sampleRate, 2)
        // Two frames play from the head…
        processor.process(stereoBlock(listOf(0f to 0f, 0f to 0f)))
        // …then a seek to frame 5, adopted by the flush a seek causes.
        processor.syncToFrame(5)
        processor.flush()
        val block = stereoBlock(listOf(0f to 0f))
        processor.process(block)
        assertEquals(q(frames[5].first), block.samples[0], 1e-6f)
        assertEquals(q(frames[5].second), block.samples[1], 1e-6f)
    }

    @Test
    fun `position advances across blocks without a sync`() {
        val frames = (0 until 16).map { i -> (i / 16f) to (-i / 16f) }
        val processor = processor(0.0f, stemFile("advance.wav", frames))
        processor.process(stereoBlock(listOf(0f to 0f, 0f to 0f)))
        val block = stereoBlock(listOf(0f to 0f))
        processor.process(block)
        // The third frame overall is stem frame 2: the counter carried over.
        assertEquals(q(frames[2].first), block.samples[0], 1e-6f)
        assertEquals(q(frames[2].second), block.samples[1], 1e-6f)
    }
}
