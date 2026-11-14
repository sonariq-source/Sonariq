package com.music.sonariq.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Output-latency compensation for the lyrics playhead.
 *
 * THE SIGN CONVENTION — the only one in the app. Every lyrics shift is a
 * *delay*, in milliseconds: how much later than the media clock the
 * listener's experience runs. The playhead subtracts the total shift from
 * the media position (see [adjustedLyricsPosition]), so a positive shift
 * shows each word later, and a tap on a line seeks to the line's stamp
 * *plus* the total shift (see [adjustedLyricsSeekTarget]). The three
 * components all speak this one language, so they simply add:
 *
 *     total shift = resolved lyrics offset (the track's measured auto
 *                   offset alone — [resolveLyricsOffsetMs])
 *                 + per-route "Lyrics ms" value ([routeCalibrationMs])
 *                 + estimated output latency ([OutputSyncState.estimatedLatencyMs])
 *
 * The auto offset corrects the *lyrics*; the "Lyrics ms" value and the
 * latency correct the *output*. A positive output latency — the sound
 * physically leaves the device late — therefore enters with a plus: at
 * any given media position less of the song has been heard, so the
 * heard position is earlier and the words must land later by exactly
 * that much.
 *
 * THE DOUBLE-COMPENSATION GUARD. The position the player reports is the
 * media clock read at the audio sink's playback head: samples still
 * queued in the sink's own buffer do not count as played yet. The sink
 * buffer and the mixer's pipeline are therefore already excluded from
 * the reported position and must never be compensated a second time.
 * What is NOT in the reported position is everything after the playback
 * head: the output device's own processing and, on a wireless route, the
 * codec and the link — small on a built-in speaker, commonly 100–300 ms
 * on a wireless headset. Only that remainder belongs in
 * [OutputSyncState.estimatedLatencyMs].
 *
 * When a platform cannot measure that remainder on the current route,
 * the estimate is null — never a fabricated number, and never displayed
 * as 0 ms — it contributes nothing, and the listener's per-route
 * "Lyrics ms" value is the compensation. That value is keyed by the
 * exact route class: a wireless value must not leak onto the speaker.
 */

/** The most the per-route "Lyrics ms" value may shift lyrics, either way. */
const val MAX_ROUTE_CALIBRATION_MS = 1_000

/**
 * The most the total shift (offset + calibration + latency) may reach.
 * The offset alone is capped at [MAX_LYRICS_OFFSET_MS]; the headroom
 * above it is for the output-side components.
 */
const val MAX_TOTAL_LYRICS_SHIFT_MS = MAX_LYRICS_OFFSET_MS + MAX_ROUTE_CALIBRATION_MS + 2_000

/** A sanity bound on a platform-reported output latency; real routes sit far below it. */
const val MAX_OUTPUT_LATENCY_MS = 5_000L

/**
 * What a platform knows about the audio output right now, for lyrics
 * timing: which class of route is carrying the sound, and — only where
 * the platform genuinely measures it — how far behind the playback head
 * the heard sound runs.
 */
data class OutputSyncState(
    /** The route class carrying playback now; [AudioOutputKind.OTHER] until a platform says better. */
    val route: AudioOutputKind = AudioOutputKind.OTHER,
    /**
     * The measured post-playback-head output latency in milliseconds, or
     * null where this route offers no real measurement. Null contributes
     * nothing to the shift; it is a statement about measurability, not a
     * claim that the latency is zero.
     */
    val estimatedLatencyMs: Long? = null,
)

/** Where a platform publishes its [OutputSyncState] for the player. */
interface OutputSyncSource {
    val state: StateFlow<OutputSyncState>

    /**
     * Whether this platform tracks the output route at all. Where false
     * the route stays [AudioOutputKind.OTHER] and the "Lyrics ms" value
     * is the single route-less one; compensation still applies.
     */
    val supported: Boolean get() = false
}

/** The default source: no route knowledge, no estimate, no compensation. */
object NoOutputSync : OutputSyncSource {
    override val state: StateFlow<OutputSyncState> = MutableStateFlow(OutputSyncState())
}

/**
 * The "Lyrics ms" value stored for exactly [route], or 0 where the
 * listener has not set one for that route. Route classes never inherit
 * from one another — the fallback for an unset route is the plain
 * resolved offset, not a neighbour's value.
 */
fun routeCalibrationMs(
    calibrations: Map<AudioOutputKind, Int>,
    route: AudioOutputKind,
): Int = (calibrations[route] ?: 0)
    .coerceIn(-MAX_ROUTE_CALIBRATION_MS, MAX_ROUTE_CALIBRATION_MS)

/**
 * The output-side part of the total shift: the current route's
 * "Lyrics ms" value plus the measured latency where one exists. A route
 * whose latency cannot be measured contributes its value alone.
 */
fun outputSyncShiftMs(
    calibrations: Map<AudioOutputKind, Int>,
    sync: OutputSyncState,
): Int {
    val latencyMs = (sync.estimatedLatencyMs ?: 0L)
        .coerceIn(0L, MAX_OUTPUT_LATENCY_MS)
        .toInt()
    return routeCalibrationMs(calibrations, sync.route) + latencyMs
}

/**
 * The total lyrics shift: the resolved offset (see
 * [resolveLyricsOffsetMs]) with the output side ([outputSyncShiftMs])
 * composed on top, bounded by [MAX_TOTAL_LYRICS_SHIFT_MS]. The output
 * side applies regardless of which track is showing, because output
 * latency does not care which lyric file is on screen.
 */
fun effectiveLyricsShiftMs(resolvedOffsetMs: Int, syncShiftMs: Int): Int =
    (resolvedOffsetMs + syncShiftMs)
        .coerceIn(-MAX_TOTAL_LYRICS_SHIFT_MS, MAX_TOTAL_LYRICS_SHIFT_MS)

/** See [effectiveLyricsShiftMs]; the parts read straight from their sources. */
fun effectiveLyricsShiftMs(
    resolvedOffsetMs: Int,
    calibrations: Map<AudioOutputKind, Int>,
    sync: OutputSyncState,
): Int = effectiveLyricsShiftMs(
    resolvedOffsetMs,
    outputSyncShiftMs(calibrations, sync),
)

/**
 * `"BLUETOOTH:150,PHONE:-20"` — the stored form of the per-route
 * calibrations, keyed by [AudioOutputKind] name, written in enum order
 * so the stored string is stable.
 */
fun encodeRouteCalibrations(calibrations: Map<AudioOutputKind, Int>): String =
    AudioOutputKind.entries
        .mapNotNull { route ->
            calibrations[route]?.let { offsetMs ->
                "${route.name}:${offsetMs.coerceIn(-MAX_ROUTE_CALIBRATION_MS, MAX_ROUTE_CALIBRATION_MS)}"
            }
        }
        .joinToString(",")

/** The inverse of [encodeRouteCalibrations]; malformed entries fall out quietly. */
fun decodeRouteCalibrations(stored: String): Map<AudioOutputKind, Int> {
    if (stored.isBlank()) return emptyMap()
    return stored.split(",")
        .mapNotNull { entry ->
            val route = AudioOutputKind.entries
                .firstOrNull { it.name == entry.substringBefore(":") }
                ?: return@mapNotNull null
            val offsetMs = entry.substringAfter(":", "").toIntOrNull()
                ?: return@mapNotNull null
            route to offsetMs.coerceIn(-MAX_ROUTE_CALIBRATION_MS, MAX_ROUTE_CALIBRATION_MS)
        }
        .toMap()
}

/**
 * The current output-side shift as Compose state: follows the output
 * route (a route change re-reads the estimate and switches to that
 * route's calibration on its own) and the stored calibrations.
 */
@Composable
internal fun rememberOutputSyncShiftMs(): State<Int> {
    val sync by PlayerPlatform.host.outputSync.state.collectAsStateWithLifecycle()
    val calibrations by PlayerSettings.lyricsRouteCalibrations.collectAsStateWithLifecycle()
    return remember {
        derivedStateOf { outputSyncShiftMs(calibrations, sync) }
    }
}
