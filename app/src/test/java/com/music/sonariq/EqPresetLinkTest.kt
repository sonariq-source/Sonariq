package com.music.sonariq

import com.music.sonariq.data.settings.EqPresetLink
import com.music.sonariq.data.settings.UserEqualizerPreset
import com.music.sonariq.playback.EqLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sharing an EQ preset as a Sonariq link: the encode/decode round trip and
 * the incoming-link parsing.
 *
 * The Android intent plumbing ([EqPresetLink.consume]) is not what's under
 * test — unit tests run against a stub android.jar — so this covers the
 * pure halves: the payload codec and the URL shapes.
 */
class EqPresetLinkTest {

    private val vShape = listOf(6f, 3f, 0f, -1f, 0f, 3f, 6f)
    private val preset = UserEqualizerPreset("Night Drive", vShape)

    // ---- Codec round trip ------------------------------------------------

    @Test
    fun `encode and decode round trip the name and bands`() {
        val decoded = EqPresetLink.decode(EqPresetLink.encode(preset))

        assertEquals("Night Drive", decoded?.name)
        assertEquals(vShape, decoded?.bandsDb)
    }

    @Test
    fun `a name with odd characters survives the link`() {
        val tricky = UserEqualizerPreset("Bass / Treble + \"loud\"", vShape)
        val decoded = EqPresetLink.decode(EqPresetLink.encode(tricky))

        assertEquals("Bass / Treble + \"loud\"", decoded?.name)
    }

    @Test
    fun `the payload is a single url-safe path segment`() {
        val payload = EqPresetLink.encode(preset)

        assertTrue(payload.none { it == '/' || it == '+' || it == '=' || it.isWhitespace() })
    }

    @Test
    fun `wild gains are clamped not refused`() {
        val decoded = EqPresetLink.decode(
            EqPresetLink.encode(UserEqualizerPreset("Hot", List(EqLayout.MANUAL_COUNT) { 99f })),
        )

        assertEquals(
            List(EqLayout.MANUAL_COUNT) { EqLayout.MANUAL_RANGE_DB },
            decoded?.bandsDb,
        )
    }

    @Test
    fun `garbage payloads are refused`() {
        assertNull(EqPresetLink.decode(null))
        assertNull(EqPresetLink.decode(""))
        assertNull(EqPresetLink.decode("!!!not-base64!!!"))
        assertNull(EqPresetLink.decode("aGVsbG8")) // "hello": no separator, no gains
    }

    // ---- URL shapes ---------------------------------------------------------

    @Test
    fun `parses the web link shape`() {
        val parsed = EqPresetLink.parse(EqPresetLink.shareUrl(preset))

        assertEquals("Night Drive", parsed?.name)
        assertEquals(vShape, parsed?.bandsDb)
    }

    @Test
    fun `parses the custom scheme shape`() {
        val parsed = EqPresetLink.parse(EqPresetLink.schemeUrl(preset))

        assertEquals("Night Drive", parsed?.name)
        assertEquals(vShape, parsed?.bandsDb)
    }

    @Test
    fun `rejects other hosts schemes and paths`() {
        val payload = EqPresetLink.encode(preset)
        assertNull(EqPresetLink.parse("https://example.com/eq/$payload"))
        assertNull(EqPresetLink.parse("http://sonariq.giize.com/eq/$payload"))
        assertNull(EqPresetLink.parse("sonariq://party/$payload"))
        assertNull(EqPresetLink.parse("https://sonariq.giize.com/s/$payload"))
        assertNull(EqPresetLink.parse(null))
        assertNull(EqPresetLink.parse("not a url"))
    }

    @Test
    fun `the share url uses the official host and eq path`() {
        val url = EqPresetLink.shareUrl(preset)

        assertTrue(url.startsWith("https://sonariq.giize.com/eq/"))
    }
}
