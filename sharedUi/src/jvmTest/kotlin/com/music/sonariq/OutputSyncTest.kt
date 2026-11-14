package com.music.sonariq

import com.music.sonariq.ui.player.AudioOutputKind
import com.music.sonariq.ui.player.MAX_OUTPUT_LATENCY_MS
import com.music.sonariq.ui.player.MAX_ROUTE_CALIBRATION_MS
import com.music.sonariq.ui.player.MAX_TOTAL_LYRICS_SHIFT_MS
import com.music.sonariq.ui.player.OutputSyncState
import com.music.sonariq.ui.player.adjustedLyricsPosition
import com.music.sonariq.ui.player.adjustedLyricsSeekTarget
import com.music.sonariq.ui.player.decodeRouteCalibrations
import com.music.sonariq.ui.player.effectiveLyricsShiftMs
import com.music.sonariq.ui.player.encodeRouteCalibrations
import com.music.sonariq.ui.player.outputSyncShiftMs
import com.music.sonariq.ui.player.routeCalibrationMs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The output-sync composition: one sign convention (a shift is a delay;
 * the playhead subtracts it), latency and the per-route "Lyrics ms"
 * value composed over the resolved offset (the measured auto offset),
 * values that belong to exactly one route, and nothing compensated when
 * nothing was measured.
 */
class OutputSyncTest {

    @Test
    fun positiveLatencyMovesTheEffectivePositionEarlier() {
        // A word stamped at 10 s, heard through a route that runs 200 ms
        // late: the listener hears it when the media clock reads 10.2 s,
        // so the effective position must reach the stamp exactly there.
        val shift = effectiveLyricsShiftMs(
            resolvedOffsetMs = 0,
            calibrations = emptyMap(),
            sync = OutputSyncState(
                route = AudioOutputKind.BLUETOOTH,
                estimatedLatencyMs = 200L,
            ),
        )
        assertEquals(200, shift)
        assertEquals(10_000L, adjustedLyricsPosition(10_200L, shift))
        assertEquals(9_999L, adjustedLyricsPosition(10_199L, shift))
    }

    @Test
    fun resolvedOffsetKeepsItsDirection() {
        // The standing meaning of a positive shift: lyrics show later.
        assertEquals(9_700L, adjustedLyricsPosition(10_000L, 300))
        assertEquals(10_300L, adjustedLyricsPosition(10_000L, -300))
        // And latency composes in the same direction as a positive offset.
        val shift = effectiveLyricsShiftMs(
            resolvedOffsetMs = 300,
            calibrations = emptyMap(),
            sync = OutputSyncState(estimatedLatencyMs = 200L),
        )
        assertEquals(500, shift)
    }

    @Test
    fun autoOffsetAndLyricsMsCompose() {
        // The whole default path in one number: the track's measured
        // auto offset plus the current route's "Lyrics ms" value, with
        // no manual layer anywhere in it.
        val shift = effectiveLyricsShiftMs(
            resolvedOffsetMs = 120,
            calibrations = mapOf(AudioOutputKind.BLUETOOTH to 180),
            sync = OutputSyncState(AudioOutputKind.BLUETOOTH),
        )
        assertEquals(300, shift)
        // The same auto offset on the speaker, where no value is set.
        val speakerShift = effectiveLyricsShiftMs(
            resolvedOffsetMs = 120,
            calibrations = mapOf(AudioOutputKind.BLUETOOTH to 180),
            sync = OutputSyncState(AudioOutputKind.PHONE),
        )
        assertEquals(120, speakerShift)
    }

    @Test
    fun noEstimateMeansNoLatencyCompensation() {
        val sync = OutputSyncState(
            route = AudioOutputKind.BLUETOOTH,
            estimatedLatencyMs = null,
        )
        assertEquals(0, outputSyncShiftMs(emptyMap(), sync))
        assertEquals(
            -120,
            effectiveLyricsShiftMs(resolvedOffsetMs = -120, calibrations = emptyMap(), sync = sync),
        )
    }

    @Test
    fun calibrationBelongsToExactlyOneRoute() {
        val calibrations = mapOf(AudioOutputKind.BLUETOOTH to 180)
        assertEquals(
            180,
            outputSyncShiftMs(calibrations, OutputSyncState(AudioOutputKind.BLUETOOTH)),
        )
        // The wireless calibration must not leak onto any other route.
        for (route in AudioOutputKind.entries - AudioOutputKind.BLUETOOTH) {
            assertEquals(
                "route $route",
                0,
                outputSyncShiftMs(calibrations, OutputSyncState(route)),
            )
        }
    }

    @Test
    fun routeSwitchSwitchesTheActiveCalibration() {
        val calibrations = mapOf(
            AudioOutputKind.BLUETOOTH to 180,
            AudioOutputKind.PHONE to -30,
        )
        assertEquals(
            180,
            outputSyncShiftMs(calibrations, OutputSyncState(AudioOutputKind.BLUETOOTH)),
        )
        assertEquals(
            -30,
            outputSyncShiftMs(calibrations, OutputSyncState(AudioOutputKind.PHONE)),
        )
        assertEquals(
            0,
            outputSyncShiftMs(calibrations, OutputSyncState(AudioOutputKind.WIRED)),
        )
    }

    @Test
    fun calibrationAndLatencyCompose() {
        val shift = outputSyncShiftMs(
            mapOf(AudioOutputKind.BLUETOOTH to 100),
            OutputSyncState(AudioOutputKind.BLUETOOTH, estimatedLatencyMs = 150L),
        )
        assertEquals(250, shift)
    }

    @Test
    fun calibrationIsBounded() {
        assertEquals(
            MAX_ROUTE_CALIBRATION_MS,
            routeCalibrationMs(
                mapOf(AudioOutputKind.USB to 99_999),
                AudioOutputKind.USB,
            ),
        )
        assertEquals(
            -MAX_ROUTE_CALIBRATION_MS,
            routeCalibrationMs(
                mapOf(AudioOutputKind.USB to -99_999),
                AudioOutputKind.USB,
            ),
        )
    }

    @Test
    fun implausibleLatencyReportsAreBounded() {
        // A negative "latency" would pull the words ahead of the sound;
        // it is a broken report, not a measurement, and clamps to none.
        assertEquals(
            0,
            outputSyncShiftMs(
                emptyMap(),
                OutputSyncState(estimatedLatencyMs = -50L),
            ),
        )
        assertEquals(
            MAX_OUTPUT_LATENCY_MS.toInt(),
            outputSyncShiftMs(
                emptyMap(),
                OutputSyncState(estimatedLatencyMs = 3_600_000L),
            ),
        )
    }

    @Test
    fun totalShiftIsBounded() {
        assertEquals(
            MAX_TOTAL_LYRICS_SHIFT_MS,
            effectiveLyricsShiftMs(resolvedOffsetMs = 5_000, syncShiftMs = 60_000),
        )
        assertEquals(
            -MAX_TOTAL_LYRICS_SHIFT_MS,
            effectiveLyricsShiftMs(resolvedOffsetMs = -5_000, syncShiftMs = -60_000),
        )
    }

    @Test
    fun seekTargetUndoesTheShiftExactly() {
        // Wherever the shift puts the display, a tap on a line must land
        // the effective position on that line's stamp.
        for (shift in listOf(-1_000, -250, 0, 180, 450, MAX_TOTAL_LYRICS_SHIFT_MS)) {
            assertEquals(
                "shift $shift",
                10_000L,
                adjustedLyricsPosition(adjustedLyricsSeekTarget(10_000L, shift), shift),
            )
        }
    }

    @Test
    fun calibrationCodecRoundTrips() {
        val calibrations = mapOf(
            AudioOutputKind.BLUETOOTH to 180,
            AudioOutputKind.PHONE to -30,
            AudioOutputKind.USB to 0,
        )
        assertEquals(
            calibrations,
            decodeRouteCalibrations(encodeRouteCalibrations(calibrations)),
        )
        assertEquals("", encodeRouteCalibrations(emptyMap()))
        assertEquals(emptyMap<AudioOutputKind, Int>(), decodeRouteCalibrations(""))
    }

    @Test
    fun calibrationCodecDropsMalformedEntriesAndBoundsValues() {
        assertEquals(
            mapOf(AudioOutputKind.BLUETOOTH to MAX_ROUTE_CALIBRATION_MS),
            decodeRouteCalibrations(
                "BLUETOOTH:99999,GARBAGE,NOPE:10,WIRED:abc,HDMI:",
            ),
        )
    }

    @Test
    fun calibrationCodecWritesInEnumOrder() {
        assertEquals(
            "PHONE:-30,BLUETOOTH:180",
            encodeRouteCalibrations(
                mapOf(
                    AudioOutputKind.BLUETOOTH to 180,
                    AudioOutputKind.PHONE to -30,
                ),
            ),
        )
    }
}
