package com.music.sonariq

import com.music.sonariq.playback.AudioOutputStatus
import com.music.sonariq.playback.DspEffectAvailability
import com.music.sonariq.playback.EqLayout
import com.music.sonariq.playback.dspEffectAvailability
import com.music.sonariq.playback.manualCurve
import com.music.sonariq.playback.toneCurve
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bass/treble tone trims: where they land in each tab's curve, and when
 * the UI is allowed to offer them at all.
 *
 * The trims share the shelf sections rather than sitting in series with them
 * (see [com.music.sonariq.playback.EqualizerProcessor]): bass adds into the
 * low shelf's gain, treble into the high shelf's, each summed with whatever
 * the tab put there and kept inside the section's range.
 */
class EqualizerToneTest {

    // ---- Manual tab --------------------------------------------------------

    @Test
    fun `bass adds into the 60 Hz low shelf`() {
        val curve = manualCurve(List(7) { 0f }, bassDb = 6f, trebleDb = 0f)

        assertEquals(6f, curve.gainsDb[EqLayout.MANUAL_FIRST], 0.001f)
        assertEquals(0f, curve.gainsDb[EqLayout.MANUAL_FIRST + 6], 0.001f)
    }

    @Test
    fun `treble adds into the 14 kHz high shelf`() {
        val curve = manualCurve(List(7) { 0f }, bassDb = 0f, trebleDb = -4f)

        assertEquals(-4f, curve.gainsDb[EqLayout.MANUAL_FIRST + 6], 0.001f)
        assertEquals(0f, curve.gainsDb[EqLayout.MANUAL_FIRST], 0.001f)
    }

    @Test
    fun `trims sum with the band under them`() {
        val bands = listOf(3f, 0f, 0f, 0f, 0f, 0f, -2f)

        val curve = manualCurve(bands, bassDb = 4f, trebleDb = 5f)

        assertEquals(7f, curve.gainsDb[EqLayout.MANUAL_FIRST], 0.001f)
        assertEquals(3f, curve.gainsDb[EqLayout.MANUAL_FIRST + 6], 0.001f)
    }

    @Test
    fun `the summed shelf never leaves the fader range`() {
        val bands = listOf(12f, 0f, 0f, 0f, 0f, 0f, -12f)

        val curve = manualCurve(bands, bassDb = 12f, trebleDb = -12f)

        assertEquals(12f, curve.gainsDb[EqLayout.MANUAL_FIRST], 0.001f)
        assertEquals(-12f, curve.gainsDb[EqLayout.MANUAL_FIRST + 6], 0.001f)
    }

    @Test
    fun `a wild trim is clamped before it reaches the shelf`() {
        val curve = manualCurve(List(7) { 0f }, bassDb = 99f, trebleDb = -99f)

        assertEquals(12f, curve.gainsDb[EqLayout.MANUAL_FIRST], 0.001f)
        assertEquals(-12f, curve.gainsDb[EqLayout.MANUAL_FIRST + 6], 0.001f)
    }

    @Test
    fun `no trims is the old curve exactly`() {
        val bands = listOf(1f, -2f, 3f, -4f, 5f, -6f, 7f)

        val curve = manualCurve(bands)

        for (band in bands.indices) {
            assertEquals(bands[band], curve.gainsDb[EqLayout.MANUAL_FIRST + band], 0.001f)
        }
        assertEquals(0f, curve.gainsDb[EqLayout.TONE_LOW], 0.001f)
    }

    // ---- Dynamic tab -------------------------------------------------------

    @Test
    fun `trims add into the tone pad's tilt pair`() {
        // Puck at dead centre: tilt is zero, so the shelves carry the trims alone.
        val curve = toneCurve(x = 0, y = 0, focused = false, bassDb = 5f, trebleDb = -3f)

        assertEquals(5f, curve.gainsDb[EqLayout.TONE_LOW], 0.001f)
        assertEquals(-3f, curve.gainsDb[EqLayout.TONE_HIGH], 0.001f)
        assertEquals(0f, curve.gainsDb[EqLayout.TONE_MID], 0.001f)
    }

    @Test
    fun `trims sum with the tilt`() {
        // x = -5 is fully warm: tilt lifts the low shelf by 6 dB.
        val curve = toneCurve(x = -5, y = 0, focused = false, bassDb = 4f, trebleDb = 0f)

        assertEquals(10f, curve.gainsDb[EqLayout.TONE_LOW], 0.001f)
        assertEquals(-6f, curve.gainsDb[EqLayout.TONE_HIGH], 0.001f)
    }

    @Test
    fun `no trims is the old tone curve exactly`() {
        val curve = toneCurve(x = -5, y = 3, focused = true)

        assertEquals(6f, curve.gainsDb[EqLayout.TONE_LOW], 0.001f)
        assertEquals(-6f, curve.gainsDb[EqLayout.TONE_HIGH], 0.001f)
        assertEquals(3.6f, curve.gainsDb[EqLayout.TONE_MID], 0.001f)
    }

    // ---- Availability ------------------------------------------------------

    @Test
    fun `effects are available on the normal decoded path`() {
        assertEquals(
            DspEffectAvailability.AVAILABLE,
            dspEffectAvailability(AudioOutputStatus.Snapshot(dspAvailable = true)),
        )
    }

    @Test
    fun `a passthrough bitstream reports the effects unavailable`() {
        assertEquals(
            DspEffectAvailability.UNAVAILABLE_BITSTREAM,
            dspEffectAvailability(AudioOutputStatus.Snapshot(dspAvailable = false)),
        )
    }

    @Test
    fun `the default snapshot is the available one`() {
        assertEquals(
            DspEffectAvailability.AVAILABLE,
            dspEffectAvailability(AudioOutputStatus.Snapshot()),
        )
    }
}
