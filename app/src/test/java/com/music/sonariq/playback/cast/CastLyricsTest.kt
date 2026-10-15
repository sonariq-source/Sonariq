package com.music.sonariq.playback.cast

import com.google.android.gms.cast.CastMediaControlIntent
import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.lyrics.LyricWord
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class CastLyricsTest {

    private fun line(t: Long, text: String, vararg words: LyricWord, estimated: Boolean = false) =
        LyricLine(timeMs = t, text = text, words = words.toList(), wordsEstimated = estimated)

    @Test
    fun `blank app id resolves to default receiver`() {
        assertEquals(
            CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
            CastLyrics.resolveReceiverAppId(""),
        )
        assertEquals(
            CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
            CastLyrics.resolveReceiverAppId("   "),
        )
    }

    @Test
    fun `custom app id is trimmed and used`() {
        assertEquals("ABCD1234", CastLyrics.resolveReceiverAppId("  ABCD1234\n"))
    }

    @Test
    fun `tv lyrics needs both toggle and app id`() {
        assertFalse(CastLyrics.tvLyricsAvailable("", true))
        assertFalse(CastLyrics.tvLyricsAvailable("ABCD1234", false))
        assertTrue(CastLyrics.tvLyricsAvailable("ABCD1234", true))
    }

    @Test
    fun `lyrics message carries word timings`() {
        val lines = listOf(
            line(1000, "hello world",
                LyricWord(1000, 1500, "hello"),
                LyricWord(1700, 2600, "world")),
            line(5000, "plain line"),
        )
        val json = CastLyrics.lyricsMessage(
            mediaId = "vid1", title = "Song", artist = "Artist",
            artworkUrl = "https://example.com/art.jpg", lines = lines,
        )
        assertNotNull(json)
        val msg = Json.decodeFromString<CastLyrics.Message>(json!!)
        assertEquals("lyrics", msg.type)
        assertEquals("vid1", msg.mediaId)
        assertEquals(2, msg.lines.size)
        assertEquals(2, msg.lines[0].words.size)
        assertEquals(1000L, msg.lines[0].words[0].startMs)
        assertEquals("world", msg.lines[0].words[1].text)
        assertEquals(0, msg.lines[1].words.size)
        assertEquals("https://example.com/art.jpg", msg.artworkUrl)
    }

    @Test
    fun `non-http artwork is dropped`() {
        val json = CastLyrics.lyricsMessage(
            mediaId = "v", title = "t", artist = "a",
            artworkUrl = "file:///local/art.jpg",
            lines = listOf(line(0, "hi")),
        )
        assertNotNull(json)
        assertNull(Json.decodeFromString<CastLyrics.Message>(json!!).artworkUrl)
    }

    @Test
    fun `empty lines yield null`() {
        assertNull(
            CastLyrics.lyricsMessage("v", "t", "a", null, emptyList())
        )
    }

    @Test
    fun `clear message is well-formed`() {
        val json = CastLyrics.clearMessage("vid9")
        val msg = Json.decodeFromString<CastLyrics.Message>(json!!)
        assertEquals("clear", msg.type)
        assertEquals("vid9", msg.mediaId)
    }

    @Test
    fun `estimated flag survives the trip`() {
        val json = CastLyrics.lyricsMessage(
            mediaId = "v", title = "t", artist = "a", artworkUrl = null,
            lines = listOf(line(0, "x", LyricWord(0, 500, "x"), estimated = true)),
        )
        assertNotNull(json)
        assertTrue(Json.decodeFromString<CastLyrics.Message>(json!!).lines[0].estimated)
    }

    @Test
    fun `oversized payload is slimmed not dropped`() {
        // 3000 fully word-timed lines: far over the 60 KiB cap, and still over
        // with word timings stripped — so lines are truncated to fit.
        val lines = (0 until 3000).map { i ->
            line(i * 3000L, "word ".repeat(20).trim(),
                *Array(20) { w -> LyricWord(i * 3000L + w * 100L, i * 3000L + w * 100L + 90L, "word") })
        }
        val json = CastLyrics.lyricsMessage("v", "t", "a", null, lines)
        assertNotNull(json)
        assertTrue(json!!.toByteArray().size <= CastLyrics.MAX_MESSAGE_BYTES)
        // Slimmed: earliest lines survive, word timings are gone.
        val msg = Json.decodeFromString<CastLyrics.Message>(json!!)
        assertTrue(msg.lines.isNotEmpty())
        assertTrue(msg.lines.size < 3000)
        assertTrue(msg.lines.all { it.words.isEmpty() })
    }
}
