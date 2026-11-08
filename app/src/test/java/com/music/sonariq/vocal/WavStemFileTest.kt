package com.music.sonariq.vocal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.roundToInt

/**
 * Round-trip tests for [WavStemFile]: known frames written as 16-bit PCM
 * come back at exactly their int16-quantised values, the frame count is
 * the header's, and reads are random-access.
 */
class WavStemFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** The documented storage conversion: round(x * 32768) clamped to int16, read back / 32768. */
    private fun quantised(sample: Float): Float =
        (sample.coerceIn(-1f, 1f) * 32768f).roundToInt().coerceIn(-32768, 32767) / 32768f

    private fun writeStemFile(name: String, frames: List<Pair<Float, Float>>): File {
        val file = File(folder.root, name)
        WavStemFile.Writer(file).use { writer ->
            writer.writeFrames(
                FloatArray(frames.size) { frames[it].first },
                FloatArray(frames.size) { frames[it].second },
            )
        }
        return file
    }

    @Test
    fun `round trip returns exact int16 quantised values`() {
        val frames = listOf(
            0.0f to 0.0f,
            1.0f to -1.0f,
            -1.0f to 1.0f,
            0.5f to -0.5f,
            -0.123456f to 0.654321f,
            0.99999f to -0.99999f,
            -0.25f to 0.75f,
        )
        val file = writeStemFile("roundtrip.wav", frames)
        // Header patched on close: the file is exactly header + frames * 4.
        assertEquals(44L + frames.size * 4L, file.length())

        val stem = WavStemFile.open(file)!!
        stem.use {
            assertEquals(frames.size.toLong(), it.totalFrames)
            assertEquals(44_100, it.sampleRate)
            val dest = FloatArray(frames.size * 2)
            val read = it.readFrames(0, frames.size, dest)
            assertEquals(frames.size, read)
            frames.forEachIndexed { index, (left, right) ->
                assertEquals(quantised(left), dest[index * 2], 0f)
                assertEquals(quantised(right), dest[index * 2 + 1], 0f)
            }
        }
    }

    @Test
    fun `random access reads land on the asked frames`() {
        val frames = (0 until 16).map { i -> (i / 16f) to (-i / 16f) }
        val file = writeStemFile("random.wav", frames)
        val stem = WavStemFile.open(file)!!
        stem.use {
            val dest = FloatArray(4 * 2)
            val read = it.readFrames(5, 4, dest)
            assertEquals(4, read)
            for (i in 0 until 4) {
                assertEquals(quantised(frames[5 + i].first), dest[i * 2], 0f)
                assertEquals(quantised(frames[5 + i].second), dest[i * 2 + 1], 0f)
            }
        }
    }

    @Test
    fun `reads at and past the end come back short and empty`() {
        val frames = listOf(0.1f to 0.2f, 0.3f to 0.4f, 0.5f to 0.6f)
        val file = writeStemFile("short.wav", frames)
        val stem = WavStemFile.open(file)!!
        stem.use {
            val dest = FloatArray(8 * 2)
            // Asking for more than remains returns only what exists.
            assertEquals(2, it.readFrames(1, 8, dest))
            // At and past the end: nothing.
            assertEquals(0, it.readFrames(3, 4, dest))
            assertEquals(0, it.readFrames(100, 4, dest))
        }
    }

    @Test
    fun `a file that is not a stem does not open`() {
        val notWav = File(folder.root, "not.wav")
        notWav.writeBytes(ByteArray(128) { 7 })
        assertTrue(WavStemFile.open(notWav) == null)
        assertTrue(WavStemFile.open(File(folder.root, "missing.wav")) == null)
    }

    @Test
    fun `chunked writes append in order`() {
        val file = File(folder.root, "chunked.wav")
        WavStemFile.Writer(file).use { writer ->
            writer.writeFrames(floatArrayOf(0.1f, 0.2f), floatArrayOf(-0.1f, -0.2f))
            writer.writeFrames(floatArrayOf(0.3f), floatArrayOf(-0.3f))
            assertEquals(3L, writer.framesWritten)
        }
        val stem = WavStemFile.open(file)!!
        stem.use {
            val dest = FloatArray(3 * 2)
            assertEquals(3, it.readFrames(0, 3, dest))
            assertEquals(quantised(0.2f), dest[2], 0f)
            assertEquals(quantised(-0.3f), dest[5], 0f)
        }
    }
}
