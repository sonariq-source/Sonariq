package com.music.sonariq

import com.music.sonariq.data.settings.MAX_USER_EQ_PRESETS
import com.music.sonariq.data.settings.UserEqualizerPreset
import com.music.sonariq.data.settings.decodeUserEqualizerPresets
import com.music.sonariq.data.settings.encodeUserEqualizerPresets
import com.music.sonariq.data.settings.eqBandsMatch
import com.music.sonariq.data.settings.removeUserPreset
import com.music.sonariq.data.settings.sanitizePresetName
import com.music.sonariq.data.settings.upsertUserPreset
import com.music.sonariq.playback.EqLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user-preset list: saving, recalling, deleting, and the round trip
 * through the preference string.
 *
 * The SharedPreferences I/O itself is not what's under test — unit tests run
 * against a stub android.jar — so this covers the pure halves the settings
 * object delegates to: the list surgery ([upsertUserPreset],
 * [removeUserPreset]) and the serialisation ([encodeUserEqualizerPresets],
 * [decodeUserEqualizerPresets]). What the settings object adds on top is
 * writing the resulting string to prefs, which needs a device.
 */
class UserEqualizerPresetTest {

    private val flat = List(EqLayout.MANUAL_COUNT) { 0f }
    private val vShape = listOf(6f, 3f, 0f, -1f, 0f, 3f, 6f)

    // ---- Saving --------------------------------------------------------

    @Test
    fun `saving a preset keeps its name and bands`() {
        val updated = upsertUserPreset(emptyList(), "My curve", vShape)

        assertEquals(1, updated.size)
        assertEquals("My curve", updated[0].name)
        assertEquals(vShape, updated[0].bandsDb)
    }

    @Test
    fun `saving under an existing name replaces it instead of duplicating`() {
        val once = upsertUserPreset(emptyList(), "Mine", flat)
        val twice = upsertUserPreset(once, "Mine", vShape)

        assertEquals(1, twice.size)
        assertEquals(vShape, twice[0].bandsDb)
    }

    @Test
    fun `a blank name refuses the save`() {
        val updated = upsertUserPreset(emptyList(), "   ", vShape)

        assertTrue(updated.isEmpty())
    }

    @Test
    fun `names are sanitised on save`() {
        val updated = upsertUserPreset(emptyList(), "  late   night  ", vShape)

        assertEquals("late night", updated[0].name)
    }

    @Test
    fun `out-of-range bands are clamped on save`() {
        val updated = upsertUserPreset(emptyList(), "Loud", List(7) { 99f })

        assertTrue(updated[0].bandsDb.all { it == EqLayout.MANUAL_RANGE_DB })
    }

    @Test
    fun `the list is capped, oldest goes first`() {
        var presets = emptyList<UserEqualizerPreset>()
        for (i in 0..MAX_USER_EQ_PRESETS) {
            presets = upsertUserPreset(presets, "Preset $i", flat)
        }

        assertEquals(MAX_USER_EQ_PRESETS, presets.size)
        assertEquals("Preset 1", presets.first().name)
        assertEquals("Preset $MAX_USER_EQ_PRESETS", presets.last().name)
    }

    // ---- Recalling and deleting ----------------------------------------

    @Test
    fun `deleting removes only the named preset`() {
        val presets = listOf(
            UserEqualizerPreset("A", flat),
            UserEqualizerPreset("B", vShape),
        )

        val updated = removeUserPreset(presets, "A")

        assertEquals(listOf(UserEqualizerPreset("B", vShape)), updated)
    }

    @Test
    fun `deleting a name that is not there changes nothing`() {
        val presets = listOf(UserEqualizerPreset("A", flat))

        assertEquals(presets, removeUserPreset(presets, "Missing"))
    }

    @Test
    fun `bands match within a twentieth of a decibel`() {
        val a = listOf(6f, 3f, 0f, -1f, 0f, 3f, 6f)
        val b = listOf(6.04f, 2.96f, 0f, -1f, 0f, 3f, 6f)
        val c = listOf(6.06f, 3f, 0f, -1f, 0f, 3f, 6f)

        assertTrue(eqBandsMatch(a, b))
        assertFalse(eqBandsMatch(a, c))
        assertFalse(eqBandsMatch(a, a.dropLast(1)))
    }

    // ---- Persistence round trip ----------------------------------------

    @Test
    fun `encode then decode returns the same presets`() {
        val presets = listOf(
            UserEqualizerPreset("Late night", vShape),
            UserEqualizerPreset("Flat", flat),
        )

        val decoded = decodeUserEqualizerPresets(encodeUserEqualizerPresets(presets))

        assertEquals(presets, decoded)
    }

    @Test
    fun `names carrying the separator characters survive the round trip`() {
        // sanitizePresetName strips the control characters the format uses
        // as separators, so such a name still decodes to one clean entry.
        val presets = listOf(UserEqualizerPreset("a\u001Eb\u001Fc", vShape))

        val decoded = decodeUserEqualizerPresets(encodeUserEqualizerPresets(presets))

        assertEquals(1, decoded.size)
        assertEquals("a b c", decoded[0].name)
    }

    @Test
    fun `decoding null or blank is an empty list`() {
        assertTrue(decodeUserEqualizerPresets(null).isEmpty())
        assertTrue(decodeUserEqualizerPresets("").isEmpty())
    }

    @Test
    fun `one corrupt entry does not eat the rest`() {
        val good = encodeUserEqualizerPresets(listOf(UserEqualizerPreset("Good", flat)))
        val stored = "\u001Eno-separator-here\u001E$good"

        val decoded = decodeUserEqualizerPresets(stored)

        assertEquals(1, decoded.size)
        assertEquals("Good", decoded[0].name)
    }

    @Test
    fun `short gain lists pad with flat bands`() {
        val stored = "Short\u001F1.5,-2.5"

        val decoded = decodeUserEqualizerPresets(stored)

        assertEquals(1, decoded.size)
        assertEquals(
            listOf(1.5f, -2.5f, 0f, 0f, 0f, 0f, 0f),
            decoded[0].bandsDb,
        )
    }

    @Test
    fun `gains outside the fader range are clamped on load`() {
        val stored = "Hot\u001F99,-99,0,0,0,0,0"

        val decoded = decodeUserEqualizerPresets(stored)

        assertEquals(12f, decoded[0].bandsDb[0], 0f)
        assertEquals(-12f, decoded[0].bandsDb[1], 0f)
    }

    // ---- Name sanitising -------------------------------------------------

    @Test
    fun `sanitise trims, collapses whitespace and caps length`() {
        assertEquals("late night", sanitizePresetName("  late   night\n"))
        assertEquals("", sanitizePresetName("    "))
        assertTrue(sanitizePresetName("x".repeat(100)).length <= 40)
    }
}
