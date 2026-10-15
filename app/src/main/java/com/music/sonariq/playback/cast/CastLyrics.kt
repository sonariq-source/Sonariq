package com.music.sonariq.playback.cast

import com.google.android.gms.cast.CastMediaControlIntent
import com.music.sonariq.data.lyrics.LyricLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The wire format for synced lyrics on a Cast receiver.
 *
 * When the phone casts to a TV running the Sonariq receiver, the receiver
 * shows the lyrics with word-by-word karaoke, driven by its *own* media
 * clock — no position spam from the phone, and sample-accurate sync because
 * the clock being read is the one playing the audio. The phone only sends
 * this payload when the track changes (and a clear when there are no lyrics).
 *
 * Keys are short on purpose: a fully word-timed song is a few hundred words,
 * and a Cast message must stay under 64 KiB.
 */
object CastLyrics {
    /** The custom channel the Sonariq TV receiver listens on. */
    const val NAMESPACE = "urn:x-cast:com.sonariq.lyrics"

    /** Stay under the Cast 64 KiB message cap with room to spare. */
    const val MAX_MESSAGE_BYTES = 60_000

    /** Message types on [NAMESPACE]. */
    const val TYPE_LYRICS = "lyrics"
    const val TYPE_CLEAR = "clear"

    @Serializable
    data class Word(
        @SerialName("s") val startMs: Long,
        @SerialName("e") val endMs: Long,
        @SerialName("x") val text: String,
    )

    @Serializable
    data class Line(
        @SerialName("t") val timeMs: Long,
        @SerialName("x") val text: String,
        @SerialName("w") val words: List<Word> = emptyList(),
        @SerialName("u") val sungUntilMs: Long? = null,
        @SerialName("e") val estimated: Boolean = false,
    )

    @Serializable
    data class Message(
        @SerialName("type") val type: String,
        @SerialName("id") val mediaId: String,
        @SerialName("ti") val title: String = "",
        @SerialName("ar") val artist: String = "",
        @SerialName("im") val artworkUrl: String? = null,
        @SerialName("lines") val lines: List<Line> = emptyList(),
    )

    private val json = Json { encodeDefaults = true }

    /**
     * Which receiver the Cast framework should launch.
     *
     * Blank (the default) means the stock Default Media Receiver: audio-only,
     * exactly as before. A custom App ID — registered in the Cast Developer
     * Console for the hosted Sonariq receiver page — gets the lyrics UI.
     */
    fun resolveReceiverAppId(configuredAppId: String): String {
        val trimmed = configuredAppId.trim()
        return trimmed.ifEmpty { CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID }
    }

    /** True when lyrics can be shown on the TV: a custom receiver is configured. */
    fun tvLyricsAvailable(configuredAppId: String, tvLyricsEnabled: Boolean): Boolean =
        tvLyricsEnabled && configuredAppId.trim().isNotEmpty()

    /** A "clear" message: the TV should drop its lyrics (track has none). */
    fun clearMessage(mediaId: String): String =
        json.encodeToString(Message(type = TYPE_CLEAR, mediaId = mediaId))

    /**
     * The lyrics for [mediaId] as a JSON message, or null when there is
     * nothing to send.
     *
     * Oversized payloads are slimmed, never dropped: syllable detail is
     * irrelevant to the TV renderer (it highlights whole words), and if the
     * song is still too big the word timings fall back to line timings rather
     * than losing the lyrics entirely.
     */
    fun lyricsMessage(
        mediaId: String,
        title: String,
        artist: String,
        artworkUrl: String?,
        lines: List<LyricLine>,
    ): String? {
        val timed = lines.filter { it.timeMs >= 0 }
        if (timed.isEmpty()) return null
        val full = Message(
            type = TYPE_LYRICS,
            mediaId = mediaId,
            title = title,
            artist = artist,
            artworkUrl = artworkUrl?.takeIf { it.startsWith("http") },
            lines = timed.map { it.toCastLine() },
        )
        val encoded = json.encodeToString(full)
        if (encoded.toByteArray().size <= MAX_MESSAGE_BYTES) return encoded
        // Too big: words without per-word detail still karaoke by line.
        val wordless = full.lines.map { it.copy(words = emptyList()) }
        val slimEncoded = json.encodeToString(full.copy(lines = wordless))
        if (slimEncoded.toByteArray().size <= MAX_MESSAGE_BYTES) return slimEncoded
        // Still too big: keep the earliest lines that fit — never drop entirely.
        var low = 1
        var high = wordless.size
        var best: String? = null
        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = json.encodeToString(full.copy(lines = wordless.take(mid)))
            if (candidate.toByteArray().size <= MAX_MESSAGE_BYTES) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    private fun LyricLine.toCastLine(): Line = Line(
        timeMs = timeMs,
        text = text,
        words = words.map { Word(startMs = it.startMs, endMs = it.endMs, text = it.text) },
        sungUntilMs = sungUntilMs,
        estimated = wordsEstimated,
    )
}
