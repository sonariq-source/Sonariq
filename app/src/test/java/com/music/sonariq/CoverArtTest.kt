package com.music.sonariq

import com.music.sonariq.data.discovery.COVER_PALETTES
import com.music.sonariq.data.discovery.coverFileName
import com.music.sonariq.data.discovery.displayTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cover maker's pure rules: title shaping, safe filenames, and the
 * palette contract the drawing keeps (white ink on dark grounds).
 */
class CoverArtTest {

    // ── Titles ──────────────────────────────────────────────────────────────

    @Test
    fun `title is trimmed and collapsed to one line`() {
        assertEquals("My Playlist", displayTitle("  My   Playlist  "))
        assertEquals("Line one Line two", displayTitle("Line one\nLine two"))
    }

    @Test
    fun `blank title becomes Untitled`() {
        assertEquals("Untitled", displayTitle(""))
        assertEquals("Untitled", displayTitle("   "))
    }

    @Test
    fun `long titles are cut with an ellipsis`() {
        val long = "This is a very long playlist title that goes on"
        val shown = displayTitle(long)
        assertTrue(shown.endsWith("…"))
        assertTrue(shown.length <= 28)
    }

    @Test
    fun `short titles pass through`() {
        assertEquals("Chill", displayTitle("Chill"))
    }

    // ── Filenames ───────────────────────────────────────────────────────────

    @Test
    fun `browse id becomes a safe filename`() {
        assertEquals("VLabc123.png", coverFileName("VLabc123"))
        assertEquals("local_playlist_sp_local_1.png", coverFileName("local:playlist:sp_local_1"))
    }

    @Test
    fun `blank browse id falls back`() {
        assertEquals("playlist.png", coverFileName(""))
        assertEquals("___.png", coverFileName(":::"))
    }

    // ── Palettes ────────────────────────────────────────────────────────────

    @Test
    fun `palettes exist and keep ink legible`() {
        assertTrue(COVER_PALETTES.size >= 8)
        COVER_PALETTES.forEach { palette ->
            assertTrue(palette.name.isNotBlank())
            // Both stops fully opaque: a translucent ground would show the
            // bitmap's black behind it and break the legibility contract.
            assertEquals(0xFF, (palette.start ushr 24) and 0xFF)
            assertEquals(0xFF, (palette.end ushr 24) and 0xFF)
            // Dark grounds: white ink stays readable without a shadow.
            assertTrue(luminance(palette.start) < 0.35)
            assertTrue(luminance(palette.end) < 0.35)
        }
    }

    /** Relative luminance of an ARGB int, 0 (black) to 1 (white). */
    private fun luminance(argb: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
