package com.music.sonariq.playback

import com.music.sonariq.ui.player.AudioOutputKind
import com.music.sonariq.ui.player.OutputSyncSource
import com.music.sonariq.ui.player.OutputSyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The Android [OutputSyncSource]: which route class is carrying the
 * sound, published off the same output status the output drawer reads,
 * so a route change moves the lyrics playhead's compensation on its own
 * (see OutputSync.kt in the shared UI for the timing model).
 *
 * The latency estimate is null on every route, deliberately. The audio
 * track belongs to the player framework's audio sink, which hands out
 * no handle to it, so the platform's track timestamp and latency
 * readings cannot be reached; and the position the player reports
 * already stops at the sink's playback head, so the sink's own buffer
 * is not part of the unaccounted gap and must not be compensated again.
 * What remains after the playback head — the output device's own
 * processing and, on a wireless route, the codec and the link — has no
 * public measurement API. Rather than invent a number, this reports
 * "not measurable" and the listener's per-route calibration carries
 * the compensation, which is what the calibration exists for. If a
 * route ever gains a real measurement, it lands here and nowhere else.
 */
object AndroidOutputSync : OutputSyncSource {
    override val supported: Boolean = true

    override val state: StateFlow<OutputSyncState> =
        AudioOutputStatus.current
            .map { snapshot ->
                OutputSyncState(
                    route = snapshot.routeKind.toAudioOutputKind(),
                    estimatedLatencyMs = null,
                )
            }
            .stateIn(
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                SharingStarted.Eagerly,
                OutputSyncState(
                    route = AudioOutputStatus.current.value.routeKind
                        .toAudioOutputKind(),
                ),
            )
}

/** The route classes are named the same on both sides of this seam. */
private fun AudioRouting.Kind.toAudioOutputKind(): AudioOutputKind = when (this) {
    AudioRouting.Kind.PHONE -> AudioOutputKind.PHONE
    AudioRouting.Kind.WIRED -> AudioOutputKind.WIRED
    AudioRouting.Kind.USB -> AudioOutputKind.USB
    AudioRouting.Kind.BLUETOOTH -> AudioOutputKind.BLUETOOTH
    AudioRouting.Kind.HDMI -> AudioOutputKind.HDMI
    AudioRouting.Kind.OTHER -> AudioOutputKind.OTHER
}
