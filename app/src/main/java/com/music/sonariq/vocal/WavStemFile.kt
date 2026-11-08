package com.music.sonariq.vocal

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/**
 * A separated stem on disk: a plain 16-bit PCM stereo WAV at 44.1 kHz.
 *
 * WAV rather than any compressed container, deliberately: playback reads the
 * stem *randomly* — a seek lands mid-track and the DSP stage needs the frames
 * at exactly that offset, in the audio callback, with no decoder in between.
 * A WAV frame's byte offset is arithmetic (`header + frame * 4`), so a stem
 * behaves like the planar [com.music.sonariq.playback.smart.StereoStems] it
 * was written from, just paged in from disk a block at a time instead of held
 * in the heap (a four-minute stem is ~70 MB of float PCM; as int16 WAV it is
 * ~35 MB on disk and a few kilobytes in memory at a time).
 *
 * This is the minimal honest WAV: one canonical 44-byte header, no extra
 * chunks, sizes patched in when the writer closes. No external dependencies —
 * the format is forty-four bytes of header and little-endian shorts.
 *
 * Sample conversion matches the rest of the pipeline (see
 * `AudioDecoder`): floats are stored as `round(x * 32768)` clamped to the
 * int16 range and read back divided by 32768, so -1.0 round-trips exactly and
 * +1.0 lands on 32767/32768.
 */
class WavStemFile private constructor(
    private val raf: RandomAccessFile,
    /** Total stereo frames in the file. */
    val totalFrames: Long,
) : Closeable {

    /** The only rate stems are stored at — the separators' output rate. */
    val sampleRate: Int = SAMPLE_RATE

    /**
     * Reads up to [frameCount] frames starting at [startFrame] into [dest] as
     * interleaved L/R floats, writing them from frame slot [destFrameOffset].
     *
     * Returns how many frames were actually read: fewer at the end of the
     * stem, and 0 when [startFrame] is at or past it. That shortfall is how
     * the playback stage learns the stem has run out — see
     * `AiStemProcessor`, which passes the original mix through for any frame
     * the stem does not cover rather than silencing the tail.
     *
     * Safe to call from the audio thread while another thread closes the
     * file: the read and the close serialise on the file handle, and a read
     * that loses that race answers with what it managed (usually 0) instead
     * of throwing into the DSP chain.
     */
    fun readFrames(startFrame: Long, frameCount: Int, dest: FloatArray, destFrameOffset: Int = 0): Int {
        if (frameCount <= 0 || startFrame < 0 || startFrame >= totalFrames) return 0
        val frames = minOf(frameCount.toLong(), totalFrames - startFrame).toInt()
        val bytes = ByteArray(frames * BYTES_PER_FRAME)
        val read = synchronized(raf) {
            runCatching {
                raf.seek(HEADER_BYTES + startFrame * BYTES_PER_FRAME)
                raf.readFully(bytes)
                frames
            }.getOrDefault(0)
        }
        if (read <= 0) return 0
        var byteIndex = 0
        var sampleIndex = destFrameOffset * CHANNELS
        repeat(read) {
            dest[sampleIndex] = shortAt(bytes, byteIndex) / SCALE
            dest[sampleIndex + 1] = shortAt(bytes, byteIndex + 2) / SCALE
            byteIndex += BYTES_PER_FRAME
            sampleIndex += CHANNELS
        }
        return read
    }

    /** The float [sample] becomes on disk and comes back as — exposed for tests and level checks. */
    fun quantised(sample: Float): Float = quantise(sample)

    override fun close() {
        synchronized(raf) { runCatching { raf.close() } }
    }

    /**
     * Writes a stem frame by frame group, then patches the header sizes on
     * [close]. Writing streams: a whole track's planar channels are handed
     * over in whatever chunks the caller has (the whole array, for the MDX
     * pass; one joined buffer, for the segmented UMX pass) and never need a
     * second copy.
     *
     * The caller writes to a temp file and renames it into place (see
     * [StemStore.writeStem]), so a stem that is visible under its final name
     * is always a complete one — this writer closing is what makes it so.
     */
    class Writer(file: File) : Closeable {
        private val raf = RandomAccessFile(file, "rw")

        /** Frames written so far; also the data-chunk size the header is patched with. */
        var framesWritten: Long = 0L
            private set

        private val encodeBuffer = ByteArray(ENCODE_CHUNK_FRAMES * BYTES_PER_FRAME)

        init {
            raf.setLength(0L)
            // Placeholder header; the two size fields are patched in close().
            raf.write(headerBytes(dataBytes = 0L))
        }

        /** Appends [count] frames from planar [left]/[right], starting at [offset] in each. */
        fun writeFrames(left: FloatArray, right: FloatArray, offset: Int = 0, count: Int = left.size - offset) {
            require(right.size - offset >= count && count >= 0) { "right channel shorter than count" }
            var written = 0
            while (written < count) {
                val chunk = minOf(ENCODE_CHUNK_FRAMES, count - written)
                var byteIndex = 0
                for (i in 0 until chunk) {
                    putShort(encodeBuffer, byteIndex, quantiseToShort(left[offset + written + i]))
                    putShort(encodeBuffer, byteIndex + 2, quantiseToShort(right[offset + written + i]))
                    byteIndex += BYTES_PER_FRAME
                }
                raf.write(encodeBuffer, 0, chunk * BYTES_PER_FRAME)
                written += chunk
            }
            framesWritten += count
        }

        override fun close() {
            runCatching {
                raf.seek(0L)
                raf.write(headerBytes(dataBytes = framesWritten * BYTES_PER_FRAME))
            }
            runCatching { raf.close() }
        }
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val HEADER_BYTES = 44
        const val BYTES_PER_FRAME = 4

        private const val SCALE = 32768f
        private const val ENCODE_CHUNK_FRAMES = 8192

        /** The int16 a float sample is stored as: `round(x * 32768)`, clamped to the int16 range. */
        internal fun quantiseToShort(sample: Float): Int =
            (sample.coerceIn(-1f, 1f) * SCALE).roundToInt().coerceIn(-32768, 32767)

        /** The float a stored sample reads back as — the round trip [quantiseToShort] makes. */
        internal fun quantise(sample: Float): Float = quantiseToShort(sample) / SCALE

        /**
         * Opens [file] as a stem, or null when it is not one of ours: wrong
         * magic, a format other than 16-bit stereo PCM at 44.1 kHz, or a data
         * chunk that runs past the end of the file (a writer that died before
         * patching its header — exactly what the temp-file rename in
         * [StemStore] exists to keep invisible, and worth refusing anyway).
         */
        fun open(file: File): WavStemFile? {
            if (!file.isFile || file.length() < HEADER_BYTES) return null
            val raf = runCatching { RandomAccessFile(file, "r") }.getOrNull() ?: return null
            val frames = runCatching {
                val header = ByteArray(HEADER_BYTES)
                raf.readFully(header)
                if (!header.hasAscii(0, "RIFF") || !header.hasAscii(8, "WAVE") ||
                    !header.hasAscii(12, "fmt ") || !header.hasAscii(36, "data")
                ) {
                    return@runCatching null
                }
                if (int16At(header, 20) != 1 || int16At(header, 22) != CHANNELS ||
                    int32At(header, 24) != SAMPLE_RATE || int16At(header, 34) != 16
                ) {
                    return@runCatching null
                }
                val dataBytes = int32At(header, 40).toLong() and 0xFFFF_FFFFL
                if (dataBytes % BYTES_PER_FRAME != 0L) return@runCatching null
                if (HEADER_BYTES + dataBytes > file.length()) return@runCatching null
                dataBytes / BYTES_PER_FRAME
            }.getOrNull()
            if (frames == null) {
                runCatching { raf.close() }
                return null
            }
            return WavStemFile(raf, frames)
        }

        private fun headerBytes(dataBytes: Long): ByteArray {
            val out = ByteArray(HEADER_BYTES)
            putAscii(out, 0, "RIFF")
            putInt32(out, 4, 36 + dataBytes)
            putAscii(out, 8, "WAVE")
            putAscii(out, 12, "fmt ")
            putInt32(out, 16, 16)
            putInt16(out, 20, 1) // PCM
            putInt16(out, 22, CHANNELS)
            putInt32(out, 24, SAMPLE_RATE.toLong())
            putInt32(out, 28, (SAMPLE_RATE * BYTES_PER_FRAME).toLong()) // byte rate
            putInt16(out, 32, BYTES_PER_FRAME) // block align
            putInt16(out, 34, 16) // bits per sample
            putAscii(out, 36, "data")
            putInt32(out, 40, dataBytes)
            return out
        }

        private fun ByteArray.hasAscii(offset: Int, text: String): Boolean =
            text.indices.all { this[offset + it].toInt().toChar() == text[it] }

        private fun putAscii(out: ByteArray, offset: Int, text: String) {
            text.forEachIndexed { index, char -> out[offset + index] = char.code.toByte() }
        }

        private fun putInt16(out: ByteArray, offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        private fun putInt32(out: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 4) out[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }

        private fun int16At(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun int32At(bytes: ByteArray, offset: Int): Int {
            var value = 0
            for (i in 0 until 4) value = value or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
            return value
        }

        private fun shortAt(bytes: ByteArray, offset: Int): Float {
            val value = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            // Sign-extend the 16-bit value.
            return (if (value >= 0x8000) value - 0x10000 else value).toFloat()
        }

        private fun putShort(out: ByteArray, offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
    }
}
