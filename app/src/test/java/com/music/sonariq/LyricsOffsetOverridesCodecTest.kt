package com.music.sonariq

import com.music.sonariq.ui.player.decodeLyricOffsets
import com.music.sonariq.ui.player.encodeLyricOffsets
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsOffsetOverridesCodecTest {
    @Test
    fun `round trip preserves entries in order`() {
        val map = mapOf("dQw4w9WgXcQ" to 1500, "9bZkp7q19f0" to -300, "60ItHLz5WEA" to 0)
        assertEquals(map, decodeLyricOffsets(encodeLyricOffsets(map)))
    }

    @Test
    fun `empty map encodes to a blank string that decodes back to empty`() {
        assertEquals("", encodeLyricOffsets(emptyMap()))
        assertEquals(emptyMap<String, Int>(), decodeLyricOffsets(""))
    }

    @Test
    fun `malformed entries are dropped`() {
        assertEquals(
            mapOf("dQw4w9WgXcQ" to 100),
            decodeLyricOffsets(
                "dQw4w9WgXcQ:100," +
                    "no-colon-here," +
                    "short:5," +
                    "toolongvideoid123:7," +
                    "bad!chars*:9," +
                    ":42," +
                    "9bZkp7q19f0:notanumber",
            ),
        )
    }

    @Test
    fun `decoded values are coerced to the offset range`() {
        assertEquals(
            mapOf("dQw4w9WgXcQ" to 5_000, "9bZkp7q19f0" to -5_000),
            decodeLyricOffsets("dQw4w9WgXcQ:99999,9bZkp7q19f0:-99999"),
        )
    }

    @Test
    fun `encode caps at 100 entries, oldest first to fall off`() {
        val big = (0 until 150).associate { "vid${it.toString().padStart(8, '0')}" to it }
        val decoded = decodeLyricOffsets(encodeLyricOffsets(big))
        assertEquals(100, decoded.size)
        // The newest 100 survive: vid00000050..vid00000149.
        assertEquals(50, decoded["vid00000050"])
        assertEquals(149, decoded["vid00000149"])
    }
}
