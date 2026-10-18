package com.music.sonariq

import com.music.sonariq.data.settings.AudioQuality
import com.music.sonariq.playback.DataSaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The data saver's quality ceiling and its Wi-Fi-only streaming gate.
 *
 * Both are pure decisions over the user's settings and the connection state;
 * the stream resolvers read them through `AppSettings.effectiveAudioQuality`
 * and the playback path's metered check. What matters here is the precedence:
 * the saver overrides both per-connection ceilings when on, and vanishes
 * entirely when off.
 */
class DataSaverTest {

    // ---- Quality ceiling -------------------------------------------------

    @Test
    fun `with the saver off the connection decides as before`() {
        assertEquals(
            AudioQuality.HIGH,
            DataSaver.effectiveQuality(
                dataSaver = false,
                saverQuality = AudioQuality.LOW,
                metered = true,
                wifiQuality = AudioQuality.LOSSLESS,
                cellularQuality = AudioQuality.HIGH,
            ),
        )
        assertEquals(
            AudioQuality.LOSSLESS,
            DataSaver.effectiveQuality(
                dataSaver = false,
                saverQuality = AudioQuality.LOW,
                metered = false,
                wifiQuality = AudioQuality.LOSSLESS,
                cellularQuality = AudioQuality.HIGH,
            ),
        )
    }

    @Test
    fun `with the saver on its quality wins on every connection`() {
        for (metered in listOf(true, false, null)) {
            assertEquals(
                "metered=$metered",
                AudioQuality.LOW,
                DataSaver.effectiveQuality(
                    dataSaver = true,
                    saverQuality = AudioQuality.LOW,
                    metered = metered,
                    wifiQuality = AudioQuality.LOSSLESS,
                    cellularQuality = AudioQuality.HIGH,
                ),
            )
        }
    }

    @Test
    fun `an unknown connection falls back to the Wi-Fi ceiling`() {
        assertEquals(
            AudioQuality.MEDIUM,
            DataSaver.effectiveQuality(
                dataSaver = false,
                saverQuality = AudioQuality.LOW,
                metered = null,
                wifiQuality = AudioQuality.MEDIUM,
                cellularQuality = AudioQuality.LOW,
            ),
        )
    }

    // ---- Wi-Fi-only streaming --------------------------------------------

    @Test
    fun `with the gate off everything streams`() {
        assertTrue(DataSaver.streamingAllowed(wifiOnlyStreaming = false, metered = true))
        assertTrue(DataSaver.streamingAllowed(wifiOnlyStreaming = false, metered = false))
        assertTrue(DataSaver.streamingAllowed(wifiOnlyStreaming = false, metered = null))
    }

    @Test
    fun `with the gate on a metered connection streams nothing`() {
        assertFalse(DataSaver.streamingAllowed(wifiOnlyStreaming = true, metered = true))
    }

    @Test
    fun `with the gate on Wi-Fi still streams`() {
        assertTrue(DataSaver.streamingAllowed(wifiOnlyStreaming = true, metered = false))
    }

    @Test
    fun `with the gate on an unknown connection is allowed through`() {
        // Deliberate: a null metered state means "no active network" as often
        // as "unknown", and refusing there would blame the Wi-Fi setting for
        // an outage. The stream fails on the network and says so.
        assertTrue(DataSaver.streamingAllowed(wifiOnlyStreaming = true, metered = null))
    }
}
