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
        assertEquals(0xFF1DB954L, AccentColor.LIME.darkArgb)
        assertEquals(0xFF147A3DL, AccentColor.LIME.lightArgb)
        assertEquals(0xFF35E887L, AccentColor.EMERALD.darkArgb)
        assertEquals(0xFF0E7A44L, AccentColor.EMERALD.lightArgb)
        assertEquals(0xFF3ECF8EL, AccentColor.TEAL.darkArgb)
        assertEquals(0xFF177A52L, AccentColor.TEAL.lightArgb)
        assertEquals(0xFF8EF5BDL, AccentColor.GREEN.darkArgb)
        assertEquals(0xFF2E7D46L, AccentColor.GREEN.lightArgb)
        assertEquals(0xFFB9E769L, AccentColor.YELLOW.darkArgb)
        assertEquals(0xFF5F7A12L, AccentColor.YELLOW.lightArgb)
        assertEquals(0xFF7ED491L, AccentColor.AMBER.darkArgb)
        assertEquals(0xFF3E7A34L, AccentColor.AMBER.lightArgb)
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
