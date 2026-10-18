package com.music.sonariq.data.scrobbling

import com.music.sonariq.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Private session: a toggle that genuinely suspends recording, not a label.
 *
 * While active, three writes stop:
 * 1. Scrobbles — [ScrobbleManager]'s timers never start and its "now playing"
 *    updates never send.
 * 2. ListenBrainz — neither `playing_now` nor finished-listen submissions.
 * 3. History — [com.music.sonariq.data.stats.ListeningRecorder]'s samples are
 *    dropped, so the Replay stats, smart playlists and "recently played"
 *    never see the session.
 *
 * The gating lives in the existing call sites (Phase 2 edits, listed below),
 * not here: a single boolean here answers "are we private", and each writer
 * checks it at the moment it would write. That keeps the suspension total —
 * there is no path that records "just the stats" or "just the scrobble".
 *
 * Enabling mid-track cancels the in-flight scrobble timer and forgets the
 * recorder's current track, so a song already past the scrobble threshold
 * cannot be submitted after the toggle flips.
 *
 * New AppSettings key (Phase 2 adds, same pattern as the existing settings):
 * - `privateSessionEnabled`: Boolean, default `false`
 *
 * Phase 2 edits (all in existing files, none made here):
 * - PlaybackService.kt ~line 5486 (`ListeningRecorder.onSample(...)`): skip
 *   when [suspendsRecording].
 * - PlaybackService.kt ~line 874/3028/6700 (`ListeningRecorder.onStopped()`):
 *   also call when private session is enabled, to forget the current track.
 * - ScrobbleManager.onSongStart / onPlayerStateChanged / updateNowPlaying:
 *   return early when [suspendsRecording].
 * - PlaybackService.kt ~lines 6601, 6611, 6689
 *   (`ListenBrainzManager.submitPlayingNow/submitFinished`): skip when
 *   [suspendsRecording].
 * - Wherever the scrobble manager is reachable on toggle (the service's
 *   settings observer): on enable, call `scrobbleManager.onSongStop()` and
 *   `ListeningRecorder.onStopped()` to cancel in-flight timers.
 */
object PrivateSession {

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Call once at startup, after AppSettings.init. */
    fun load() {
        _active.value = AppSettings.privateSessionEnabled.value
    }

    fun setActive(active: Boolean) {
        _active.value = active
        AppSettings.privateSessionEnabled.value = active
    }

    /** True while recording writes must be skipped. Read at write time. */
    fun suspendsRecording(): Boolean = _active.value

    /**
     * The pure gate the writers share. Tests drive this with a fake supplier;
     * production passes [suspendsRecording].
     */
    class Gate(private val suspended: () -> Boolean) {
        /** Whether a scrobble or history write may proceed right now. */
        fun mayRecord(): Boolean = !suspended()
    }

    /** The gate the call sites use. */
    fun gate(): Gate = Gate(::suspendsRecording)
}
