package com.music.sonariq.playback

import android.media.audiofx.Visualizer
import com.music.sonariq.ui.player.VisualizerUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Owns the platform [Visualizer] behind the Now Playing visualizer strip.
 *
 * The capture reads the app's own player session — the
 * [audioSessionId][com.music.sonariq.data.settings.AppSettings.audioSessionId]
 * PlaybackService publishes — which needs no permission and never touches
 * the microphone: a Visualizer on your own session is a read-only tap on
 * your own mix, not a recording.
 *
 * Lifecycle: the session (and battery-saver) flow is watched for the life
 * of the process; a valid session with capture wanted attaches the
 * Visualizer, session 0 or an invalid id releases it. [setCaptureEnabled]
 * only flips the native enabled flag — pausing capture without tearing the
 * attachment down, so play/pause is instant — and [release] frees the
 * native object outright. Every Visualizer call is guarded: on a device
 * that throws, [available] goes false and the strip hides itself rather
 * than showing a dead control.
 */
class VisualizerController(
    scope: CoroutineScope,
    private val sessions: StateFlow<Int>,
    private val batterySaver: StateFlow<Boolean>,
) : VisualizerUi {

    private val _available = MutableStateFlow(true)
    override val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _sessionActive = MutableStateFlow(false)
    override val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private val _waveform = MutableStateFlow<ByteArray?>(null)
    override val waveform: StateFlow<ByteArray?> = _waveform.asStateFlow()

    private val _fft = MutableStateFlow<ByteArray?>(null)
    override val fft: StateFlow<ByteArray?> = _fft.asStateFlow()

    private val lock = Any()
    private var visualizer: Visualizer? = null
    private var captureWanted = false

    init {
        scope.launch {
            combine(sessions, batterySaver) { session, saver -> session to saver }
                .collect { (session, saver) ->
                    synchronized(lock) {
                        releaseLocked()
                        _waveform.value = null
                        _fft.value = null
                        _sessionActive.value = session > 0
                        if (shouldAttach(session, captureWanted)) {
                            attachLocked(session, saver)
                        }
                    }
                }
        }
    }

    override fun setCaptureEnabled(enabled: Boolean) {
        synchronized(lock) {
            captureWanted = enabled
            val current = visualizer
            if (current == null) {
                // Re-attaches on the current session if there is one — this
                // is also how the strip comes back after [release].
                if (enabled) attachLocked(sessions.value, batterySaver.value)
            } else {
                runCatching { current.enabled = enabled }
            }
        }
    }

    override fun release() {
        synchronized(lock) { releaseLocked() }
    }

    private fun attachLocked(sessionId: Int, saver: Boolean) {
        var viz: Visualizer? = null
        try {
            viz = Visualizer(sessionId)
            viz.captureSize = selectCaptureSize(Visualizer.getCaptureSizeRange(), saver)
            viz.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        bytes: ByteArray?,
                        samplingRate: Int,
                    ) {
                        if (bytes != null) _waveform.value = bytes.copyOf()
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        bytes: ByteArray?,
                        samplingRate: Int,
                    ) {
                        if (bytes != null) _fft.value = bytes.copyOf()
                    }
                },
                // milliHertz: 20_000 is a frame every 50 ms.
                captureRateMilliHz(saver),
                true, // waveform
                true, // fft
            )
            viz.enabled = true
            visualizer = viz
            viz = null // Handed over; the finally below must not free it.
            // A later session can succeed where an earlier one threw — the
            // strip comes back with it.
            _available.value = true
        } catch (_: Throwable) {
            _available.value = false
        } finally {
            // A throw past the constructor leaves a native object nobody
            // owns; free it here rather than leaking it.
            val leaked = viz
            if (leaked != null) runCatching { leaked.release() }
        }
    }

    private fun releaseLocked() {
        val viz = visualizer
        visualizer = null
        if (viz != null) runCatching { viz.release() }
    }

    companion object {
        /**
         * The capture callback rate in milliHertz. The battery-saver rung
         * halves it — the strip still animates, just at 10 fps.
         */
        fun captureRateMilliHz(batterySaver: Boolean): Int =
            if (batterySaver) 10_000 else 20_000

        /**
         * Picks the capture size: the largest power of two the device
         * reports, capped at 512 on the battery-saver rung, and never below
         * the device's own minimum (itself a power of two).
         */
        fun selectCaptureSize(range: IntArray, batterySaver: Boolean): Int {
            val min = range.getOrElse(0) { 128 }.coerceAtLeast(2)
            val max = range.getOrElse(1) { 1024 }.coerceAtLeast(min)
            val ceiling = minOf(if (batterySaver) 512 else max, max)
            val picked = Integer.highestOneBit(ceiling.coerceAtLeast(1))
            return maxOf(picked, Integer.highestOneBit(min)).coerceIn(min, max)
        }

        /**
         * Whether the controller should hold a Visualizer at all: only with
         * a real session id and the UI asking for capture.
         */
        fun shouldAttach(sessionId: Int, captureWanted: Boolean): Boolean =
            captureWanted && sessionId > 0
    }
}
