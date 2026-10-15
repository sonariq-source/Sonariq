package com.music.sonariq

import com.music.sonariq.ui.theme.AccentColor
import com.music.sonariq.ui.theme.accentColorFromStored
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The accent choice: what the stored name maps to, and the curated set
 * itself.
 *
 * The persistence mapping is pure — [accentColorFromStored] — because the
 * SharedPreferences read in AppSettings needs a device. The ARGB values are
 * pinned here so a careless edit to the palette fails loudly: each accent is
 * a neon for the dark theme and the same hue deepened for the light
 * theme, which is what keeps white text legible on it.
 */
class AccentColorTest {

    @Test
    fun `the default is the brand lime`() {
        assertEquals(AccentColor.LIME, accentColorFromStored(null))
    }

    @Test
    fun `a stored name restores its accent`() {
        assertEquals(AccentColor.LIME, accentColorFromStored("LIME"))
        assertEquals(AccentColor.EMERALD, accentColorFromStored("EMERALD"))
        assertEquals(AccentColor.TEAL, accentColorFromStored("TEAL"))
        assertEquals(AccentColor.GREEN, accentColorFromStored("GREEN"))
        assertEquals(AccentColor.YELLOW, accentColorFromStored("YELLOW"))
        assertEquals(AccentColor.AMBER, accentColorFromStored("AMBER"))
    }

    @Test
    fun `anything unknown falls back to lime, not a crash`() {
        assertEquals(AccentColor.LIME, accentColorFromStored(""))
        assertEquals(AccentColor.LIME, accentColorFromStored("TURQUOISE"))
        assertEquals(AccentColor.LIME, accentColorFromStored("lime"))
        assertEquals(AccentColor.LIME, accentColorFromStored("VIOLET"))
    }

    @Test
    fun `the curated set keeps its dark and light primaries`() {
        assertEquals(0xFFFFB020L, AccentColor.LIME.darkArgb)
        assertEquals(0xFF9A5600L, AccentColor.LIME.lightArgb)
        assertEquals(0xFF4FD1B5L, AccentColor.EMERALD.darkArgb)
        assertEquals(0xFF00796BL, AccentColor.EMERALD.lightArgb)
        assertEquals(0xFF7DC4FFL, AccentColor.TEAL.darkArgb)
        assertEquals(0xFF005C99L, AccentColor.TEAL.lightArgb)
        assertEquals(0xFFFF8FB1L, AccentColor.GREEN.darkArgb)
        assertEquals(0xFFB0285AL, AccentColor.GREEN.lightArgb)
        assertEquals(0xFFE6E15AL, AccentColor.YELLOW.darkArgb)
        assertEquals(0xFF6B6A00L, AccentColor.YELLOW.lightArgb)
        assertEquals(0xFFB6A4FFL, AccentColor.AMBER.darkArgb)
        assertEquals(0xFF5B45B8L, AccentColor.AMBER.lightArgb)
    }

    @Test
    fun `every light variant differs from its dark neon`() {
        for (accent in AccentColor.entries) {
            assertEquals(
                "light variant of $accent must be the deepened hue, not a copy",
                true,
                accent.lightArgb != accent.darkArgb,
            )
        }
    }
}
