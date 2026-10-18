package com.music.sonariq

import com.music.sonariq.playback.AudioRouting
import com.music.sonariq.playback.DeviceEqProfile
import com.music.sonariq.playback.decodeDeviceProfiles
import com.music.sonariq.playback.deviceKey
import com.music.sonariq.playback.encodeDeviceProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-device equalizer profiles: stable device keys and the round trip
 * through the preference string.
 *
 * The SharedPreferences I/O in [DeviceEqStore] and the live [AppSettings]
 * read/write in [snapshotEq]/[applyEqProfile] need a device, so this covers
 * the pure halves: the key factory and the serialisation.
 */
class DeviceEqProfilesTest {

    private val flat = DeviceEqProfile(
        enabled = true,
        mode = "DYNAMIC",
        toneX = 1,
        toneY = -2,
        focused = true,
        balance = 0.25f,
        bandsDb = listOf(3f, 1.5f, 0f, -1f, 0f, 2f, 4f),
        bassDb = 2f,
        trebleDb = -1f,
    )

    // ---- Device keys -----------------------------------------------------

    @Test
    fun `the phone speaker is keyed by kind alone`() {
        assertEquals("PHONE|default", deviceKey(AudioRouting.Kind.PHONE, ""))
    }

    @Test
    fun `a named device is keyed by kind and name`() {
        assertEquals(
            "BLUETOOTH|Acme Buds",
            deviceKey(AudioRouting.Kind.BLUETOOTH, "Acme Buds"),
        )
    }

    @Test
    fun `different kinds never share a key even with the same name`() {
        val wired = deviceKey(AudioRouting.Kind.WIRED, "")
        val phone = deviceKey(AudioRouting.Kind.PHONE, "")
        assertTrue(wired != phone)
    }

    // ---- Serialisation ---------------------------------------------------

    @Test
    fun `a profile round-trips through the preference string`() {
        val stored = mapOf("BLUETOOTH|Acme Buds" to flat)

        val decoded = decodeDeviceProfiles(encodeDeviceProfiles(stored))

        assertEquals(stored, decoded)
    }

    @Test
    fun `several devices keep their own profiles`() {
        val stored = mapOf(
            "PHONE|default" to flat,
            "BLUETOOTH|Acme Buds" to flat.copy(balance = -0.5f, bassDb = 6f),
        )

        val decoded = decodeDeviceProfiles(encodeDeviceProfiles(stored))

        assertEquals(0.25f, decoded["PHONE|default"]?.balance)
        assertEquals(-0.5f, decoded["BLUETOOTH|Acme Buds"]?.balance)
        assertEquals(6f, decoded["BLUETOOTH|Acme Buds"]?.bassDb)
    }

    @Test
    fun `an empty or corrupt store decodes to nothing, not a crash`() {
        assertTrue(decodeDeviceProfiles(null).isEmpty())
        assertTrue(decodeDeviceProfiles("").isEmpty())
        assertTrue(decodeDeviceProfiles("not json at all").isEmpty())
    }

    @Test
    fun `a missing device simply has no profile`() {
        val decoded = decodeDeviceProfiles(encodeDeviceProfiles(mapOf("PHONE|default" to flat)))

        assertNull(decoded["BLUETOOTH|Acme Buds"])
    }

    @Test
    fun `unknown future fields are ignored rather than failing the decode`() {
        val raw = encodeDeviceProfiles(mapOf("PHONE|default" to flat))
            .replace("\"bassDb\":", "\"someFutureField\":1,\"bassDb\":")

        val decoded = decodeDeviceProfiles(raw)

        assertEquals(flat, decoded["PHONE|default"])
    }
}
