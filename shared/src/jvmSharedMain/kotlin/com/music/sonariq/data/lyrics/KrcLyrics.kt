package com.music.sonariq.data.lyrics

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * KuGou's karaoke lyric format — the word-timed sibling of the line-level LRC
 * [KuGou] otherwise downloads.
 *
 * A KRC document is base64 in the download response. Decoded, it either is
 * plain text already or opens with the four bytes `krc1`, after which every
 * byte is XORed with the repeating key `[@Geky]` and the result is zlib
 * data. The text itself is one line per sung line:
 *
 * ```
 * [ar:artist]
 * [ti:title]
 * [1000,3000]<0,500,0>hello<500,200,0> <700,900,0>world
 * ```
 *
 * The `[startMs,durationMs]` header times the line; each
 * `<offsetMs,durationMs,?>` token times one word (or the space between two),
 * with offsets relative to the line's own start. Those are genuine measured
 * timings from KuGou's karaoke catalogue — not estimates — so a line parsed
 * here reports itself as word-synced through the usual [LyricLine.isWordSynced].
 *
 * Anything unexpected — a bad header, a failed inflate, a malformed line —
 * yields no lines rather than wrong ones; the caller falls back to LRC.
 */
object KrcLyrics {

    /** The XOR key KuGou scrambles KRC payloads with, as bytes. */
    private val XOR_KEY = byteArrayOf(0x40, 0x47, 0x65, 0x6B, 0x79)

    private val HEADER = byteArrayOf(0x6B, 0x72, 0x63, 0x31) // "krc1"

    /**
     * The download response's base64 `content` as text, or null when it is
     * not a KRC document at all.
     */
    fun decrypt(base64Content: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(base64Content.trim())
        if (!bytes.startsWith(HEADER)) {
            // Unencrypted KRC text, or something else entirely — only accept
            // it if it actually looks like KRC.
            val text = bytes.toString(Charsets.UTF_8)
            return@runCatching text.takeIf { LINE_HEADER.containsMatchIn(it) }
        }
        val scrambled = bytes.copyOfRange(HEADER.size, bytes.size)
        for (i in scrambled.indices) {
            scrambled[i] = (scrambled[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        inflate(scrambled)?.toString(Charsets.UTF_8)
            ?.takeIf { LINE_HEADER.containsMatchIn(it) }
    }.getOrNull()

    private fun inflate(deflated: ByteArray): ByteArray? = runCatching {
        val inflater = Inflater()
        return@runCatching try {
            inflater.setInput(deflated)
            val out = ByteArrayOutputStream(deflated.size * 2)
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput()) break else throw DataFormatException("stalled")
                }
                out.write(buffer, 0, count)
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } finally {
            inflater.end()
        }
    }.getOrNull()

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    /**
     * A decrypted KRC document as lyric lines with true word timings.
     *
     * Word tokens whose text is only whitespace are silence between words —
     * they split the words around them but carry no word of their own.
     */
    fun parse(krc: String): List<LyricLine> {
        val lines = krc.lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            val header = LINE_HEADER.find(line) ?: return@mapNotNull null
            val (startMs, durationMs) = header.destructured
            val lineStart = startMs.toLong()
            val tokens = WORD_TOKEN.findAll(line).toList()
            if (tokens.isEmpty()) return@mapNotNull null
            val runs = tokens.mapNotNull { token ->
                val (offsetMs, wordDurationMs, rawText) = token.destructured
                if (rawText.isEmpty()) return@mapNotNull null
                val start = lineStart + offsetMs.toLong()
                TimedRun(start, start + wordDurationMs.toLong(), rawText)
            }
            val words = wordsFromRuns(runs)
                .filter { it.text.isNotBlank() }
            if (words.isEmpty()) return@mapNotNull null
            LyricLine(
                timeMs = minOf(lineStart, words.first().startMs),
                text = words.joinToString(" ") { it.text },
                words = words,
                sungUntilMs = (lineStart + durationMs.toLong()).takeIf { it > lineStart },
            )
        }.sortedBy { it.timeMs }.toList()
        return lines.withInstrumentalGaps()
    }

    private val LINE_HEADER = Regex("""\[(\d+),(\d+)]""")
    private val WORD_TOKEN = Regex("""<(\d+),(\d+),\d+>([^<]*)""")
}
