package com.music.sonariq

import com.music.sonariq.ui.player.resolveLyricsOffsetMs
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsOffsetResolutionTest {
    @Test
    fun `per-track override wins over the global offset`() {
        assertEquals(
            300,
            resolveLyricsOffsetMs(
                overrides = mapOf("dQw4w9WgXcQ" to 300),
                globalMs = -200,
                videoId = "dQw4w9WgXcQ",
            ),
        )
    }

    @Test
    fun `a track without an override falls back to the global offset`() {
        assertEquals(
            -200,
            resolveLyricsOffsetMs(
                overrides = mapOf("dQw4w9WgXcQ" to 300),
                globalMs = -200,
                videoId = "9bZkp7q19f0",
            ),
        )
    }

    @Test
    fun `null videoId falls back to the global offset`() {
        assertEquals(
            -200,
            resolveLyricsOffsetMs(
                overrides = mapOf("dQw4w9WgXcQ" to 300),
                globalMs = -200,
                videoId = null,
            ),
        )
    }

    @Test
    fun `blank videoId falls back to the global offset`() {
        assertEquals(
            -200,
            resolveLyricsOffsetMs(
                overrides = mapOf("dQw4w9WgXcQ" to 300),
                globalMs = -200,
                videoId = "",
            ),
        )
    }

    @Test
    fun `an empty override map uses the global offset`() {
        assertEquals(
            -200,
            resolveLyricsOffsetMs(
                overrides = emptyMap(),
                globalMs = -200,
                videoId = "dQw4w9WgXcQ",
            ),
        )
    }
}
