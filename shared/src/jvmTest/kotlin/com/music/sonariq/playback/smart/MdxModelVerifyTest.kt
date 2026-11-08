package com.music.sonariq.playback.smart

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The model integrity gate: missing, truncated, and substituted files are rejected, and a
 * file whose size and hash genuinely match is accepted.
 *
 * The expected size/hash are parameters of `MdxModel.verify` precisely so the true path can be
 * exercised here with a small synthetic file instead of the real 64 MB model — no test in this
 * suite downloads anything.
 */
class MdxModelVerifyTest {

    private fun tempFile(bytes: ByteArray): File {
        val file = File.createTempFile("mdx-model-test", ".bin")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun constantsAreTheVerifiedModel() {
        assertEquals("UVR-MDX-NET-Inst_HQ_3.onnx", MdxModel.FILE_NAME)
        assertEquals(66_759_214L, MdxModel.SIZE_BYTES)
        assertEquals(64, MdxModel.SHA256.length)
        assertEquals(44_100, MdxModel.SAMPLE_RATE)
        assertEquals(6_144, MdxModel.N_FFT)
        assertEquals(1_024, MdxModel.HOP)
        assertEquals(3_072, MdxModel.DIM_F)
        assertEquals(256, MdxModel.DIM_T)
    }

    @Test
    fun sha256HexMatchesTheKnownDigestOfAbc() {
        // The published SHA-256 test vector for the three bytes "abc".
        val file = tempFile("abc".toByteArray(Charsets.US_ASCII))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            MdxModel.sha256Hex(file),
        )
    }

    @Test
    fun missingFileDoesNotVerify() {
        val missing = File(System.getProperty("java.io.tmpdir"), "mdx-model-definitely-absent.onnx")
        missing.delete()
        assertFalse(MdxModel.verify(missing))
    }

    @Test
    fun truncatedFileDoesNotVerify() {
        // Right idea, wrong length: the size check rejects it before the hash is ever read.
        val file = tempFile(ByteArray(1_024) { (it % 251).toByte() })
        assertFalse(MdxModel.verify(file))
    }

    @Test
    fun sameSizeFileWithWrongBytesDoesNotVerify() {
        // Same length as the genuine article, one byte different: size passes, hash must not.
        val genuine = ByteArray(4_096) { (it % 251).toByte() }
        val genuineFile = tempFile(genuine)
        val expectedHash = MdxModel.sha256Hex(genuineFile)

        val substituted = genuine.copyOf()
        substituted[123] = (substituted[123] + 1).toByte()
        val substitutedFile = tempFile(substituted)
        assertEquals(genuine.size.toLong(), substitutedFile.length())
        assertFalse(
            MdxModel.verify(substitutedFile, expectedSizeBytes = genuine.size.toLong(), expectedSha256 = expectedHash),
        )
    }

    @Test
    fun fileMatchingSizeAndHashVerifies() {
        val content = ByteArray(4_096) { (it % 251).toByte() }
        val file = tempFile(content)
        val hash = MdxModel.sha256Hex(file)
        assertTrue(
            MdxModel.verify(file, expectedSizeBytes = content.size.toLong(), expectedSha256 = hash),
        )
    }
}
