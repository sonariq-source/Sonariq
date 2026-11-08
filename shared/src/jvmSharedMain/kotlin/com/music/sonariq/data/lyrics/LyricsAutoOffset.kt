package com.music.sonariq.data.lyrics

import java.security.MessageDigest

/**
 * One track's measured auto offset: the constant by which its lyrics'
 * stamps sit off the track's own audio, measured by
 * [AiLyricAligner.measureUniformOffset] from that audio and stored so
 * the player can shift the whole lyric file by it — the measured
 * counterpart of the listener's manual per-track offset.
 *
 * [offsetMs] uses the offset pipeline's own sign: added to the resolved
 * offset it delays the lyrics' display by that much, exactly as a
 * manual offset does. Zero is a real record, not an absence: it means
 * the track was measured and needed nothing.
 *
 * [fingerprint] is the identity of the exact lines the measurement was
 * made against — see [lyricsLinesFingerprint]. The record applies only
 * while the lines on screen still carry that fingerprint: a different
 * source's lines, or the same source upgraded from line sync to true
 * word timings, are different lines, and a shift measured against one
 * set is never silently applied to another. [version] is the record's
 * own schema version; a record written by a newer shape is not read by
 * an older one.
 */
data class LyricsAutoOffset(
    val offsetMs: Int,
    val fingerprint: String,
    val version: Int = CURRENT_VERSION,
) {
    companion object {
        /** The record shape this build writes and reads. */
        const val CURRENT_VERSION = 1
    }
}

/**
 * The identity of one set of lyric lines, for [LyricsAutoOffset]: a
 * hash over every line's stamp, text, stated end and whether it carries
 * a provider's own word timings — lead lines and their answering
 * vocals alike.
 *
 * Word timings the AI measured are deliberately *not* part of the
 * identity: an AI-measured result is the provider's lines with measured
 * words laid over them, same stamps, same text, so a measurement made
 * against the provider's lines still names the AI result derived from
 * them. A provider's own word timings *are* part of it: their arrival
 * changes what is on screen from the lines that were measured into
 * verified timings that were not, and the fingerprint changing is what
 * retires the auto offset at exactly that moment.
 */
fun lyricsLinesFingerprint(lines: List<LyricLine>): String {
    val text = StringBuilder()
    fun feed(line: LyricLine) {
        text.append(line.timeMs).append('|')
            .append(line.sungUntilMs ?: -1L).append('|')
            .append(if (line.hasProviderWords()) 'w' else '-').append('|')
            .append(line.text).append('\n')
        line.background?.let { feed(it) }
    }
    lines.forEach { feed(it) }
    return MessageDigest.getInstance("SHA-256")
        .digest(text.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * Whether this line carries a provider's own word timings: words that
 * were neither measured by the AI alignment nor interpolated. Only
 * those make a line set a different thing from the line sync an auto
 * offset may have been measured against — see [lyricsLinesFingerprint].
 */
private fun LyricLine.hasProviderWords(): Boolean =
    words.isNotEmpty() && !wordsAiMeasured && !wordsEstimated
