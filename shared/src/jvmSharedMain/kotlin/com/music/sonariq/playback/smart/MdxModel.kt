package com.music.sonariq.playback.smart

import java.io.File
import java.security.MessageDigest

/**
 * The MDX-Net pro model: where it comes from, how to recognise the genuine file,
 * and the front-end geometry it was trained with.
 *
 * These constants were verified against the actual published file and the reference
 * implementation, and they are ground truth for [MdxSeparator] — the chunk geometry, the tensor
 * shape, and the integrity check all derive from them. The model ships inside the phone app's
 * assets: the build fetches it from [DOWNLOAD_URL], checks it against these same constants, and
 * only then packages it, and [verify] runs again on the on-device copy before the inference
 * session — a truncated or substituted file must never reach ONNX Runtime, where it would fail
 * obscurely or, worse, not fail at all.
 *
 * The model's target is the **instrumental** stem. Vocals are not a second inference: the caller
 * derives them as `original - instrumental`, which is also why the separator's peak
 * normalisation has to be undone exactly — any scale error would leak straight into the derived
 * vocal.
 */
object MdxModel {

    /** Asset/file name the model is stored under, in the assets and in app-private storage. */
    const val FILE_NAME = "UVR-MDX-NET-Inst_HQ_3.onnx"

    /** The published release the build fetches the file from. */
    const val DOWNLOAD_URL =
        "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Inst_HQ_3.onnx"

    /** Exact size of the genuine file, in bytes. Checked before the hash: it is the cheap reject. */
    const val SIZE_BYTES = 66_759_214L

    /** SHA-256 of the genuine file, lowercase hex. */
    const val SHA256 = "317554b07fe1ea5279a77f2b1520a41ea4b93432560c4ffd08792c30fddf9adc"

    /** The only sample rate the model accepts; inputs are resampled to it before separation. */
    const val SAMPLE_RATE = 44_100

    /** STFT size of the model's front end. 6144 = 2048 * 3 — see [SpectralTransform]. */
    const val N_FFT = 6144

    /** STFT hop of the model's front end, in samples. */
    const val HOP = 1024

    /** Frequency bins the model consumes: the 3073-bin spectrum cropped by one. */
    const val DIM_F = 3072

    /** Frames the model consumes per inference: one chunk's STFT width. */
    const val DIM_T = 256

    /** Streams [file] through SHA-256 and returns the digest as lowercase hex. */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    /**
     * True when [file] exists, is exactly [expectedSizeBytes] long, and hashes to
     * [expectedSha256].
     *
     * The expected values are parameters defaulting to this model's constants so the check is
     * testable against small synthetic files; production callers pass nothing and get the real
     * gate. A missing or unreadable file is a false, not an exception: "not downloaded yet" and
     * "download is corrupt" are the same answer to the caller — do not run inference.
     */
    fun verify(
        file: File,
        expectedSizeBytes: Long = SIZE_BYTES,
        expectedSha256: String = SHA256,
    ): Boolean {
        if (!file.isFile) return false
        if (file.length() != expectedSizeBytes) return false
        return runCatching { sha256Hex(file) }
            .getOrNull()
            ?.equals(expectedSha256, ignoreCase = true) == true
    }
}
