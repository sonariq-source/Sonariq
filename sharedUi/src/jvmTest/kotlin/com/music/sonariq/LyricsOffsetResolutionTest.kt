package com.music.sonariq

import com.music.sonariq.ui.player.MAX_LYRICS_OFFSET_MS
import com.music.sonariq.ui.player.MIN_LYRICS_OFFSET_MS
import com.music.sonariq.ui.player.resolveLyricsOffsetMs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The resolved lyrics offset is measurement alone: the track's measured
 * auto offset, or 0 where nothing was measured. The hand-set layers
 * that used to compose above it — a global offset and per-track
 * overrides — no longer exist in the resolution at all, so values
 * stored by them can never reach the playhead again.
 */
class LyricsOffsetResolutionTest {
    @Test
    fun `the measured auto offset is the resolution`() {
        assertEquals(1_500, resolveLyricsOffsetMs(autoMs = 1_500))
        assertEquals(-250, resolveLyricsOffsetMs(autoMs = -250))
    }

    @Test
    fun `no measurement resolves to zero`() {
        assertEquals(0, resolveLyricsOffsetMs(autoMs = 0))
    }

    @Test
    fun `the resolution is held to the offset range`() {
        assertEquals(MAX_LYRICS_OFFSET_MS, resolveLyricsOffsetMs(autoMs = 99_999))
        assertEquals(MIN_LYRICS_OFFSET_MS, resolveLyricsOffsetMs(autoMs = -99_999))
    }
}
