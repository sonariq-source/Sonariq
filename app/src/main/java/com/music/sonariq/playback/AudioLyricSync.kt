package com.music.sonariq.playback

import android.media.audiofx.Visualizer
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.log10

/**
 * Live lyric re-sync: the app *hears* the song and nudges the words into place.
 *
 * Lyrics from providers carry timestamps, but they're never perfectly aligned
 * to the actual audio — different masters, encoder delays, provider slop. This
 * listens to the audio output (via [Visualizer], no mic needed) and watches
 * for vocal onsets: sudden energy bursts in the vocal band (300 Hz – 3.4 kHz).
 *
 * The first few detected onsets are matched against the expected word-start
 * times from the lyric data. The median drift becomes a live offset correction
 * that the lyric display applies on top of the provider timings — so when the
 * singer actually starts, the words light up, and when they pause, the words
 * hold. Like real karaoke.
 *
 * Nothing is simulated: no audio session, no lyrics, or no detectable onsets
 * means [offsetMs] stays 0 and the display falls back to provider timings.
 */
object AudioLyricSync {

    /** Vocal band: where the human voice lives. */
    private const val VOCAL_LOW_HZ = 300
    private const val VOCAL_HIGH_HZ = 3400

    /** An onset is this many dB above the running floor. */
    private const val ONSET_DB = 6.0

    /** Ignore onsets closer together than this — one word, one onset. */
    private const val MIN_ONSET_GAP_MS = 180L

    /** How many onsets to collect before trusting the offset. */
    private const val CALIBRATION_ONSETS = 6

    /** Throw away drift corrections bigger than this — that's a mismatch, not drift. */
    private const val MAX_DRIFT_MS = 2000L

    /** Re-calibrate if the drift wanders this far from the current correction. */
    private const val RECALIBRATE_DRIFT_MS = 800L

    private val _offsetMs = MutableStateFlow(0L)

    /**
     * The live correction in milliseconds. Add to provider word timings.
     * Positive means the audio runs ahead of the lyrics.
     */
    val offsetMs: StateFlow<Long> = _offsetMs.asStateFlow()

    /** How confident the sync is 0..100 — 0 means "using provider timings". */
    private val _confidence = MutableStateFlow(0)
    val confidence: StateFlow<Int> = _confidence.asStateFlow()

    @Volatile
    private var visualizer: Visualizer? = null

    @Volatile
    private var running = false

    /** Expected word-start times for the current song, ms. */
    @Volatile
    private var expectedOnsets: List<Long> = emptyList()

    /** When the song started, on the elapsed-realtime clock. */
    @Volatile
    private var songStartElapsedMs: Long = 0L

    private val detectedOnsets = mutableListOf<Long>()
    private var energyFloor = Double.NaN
    private var lastOnsetMs = 0L
    private val lock = Any()

    /**
     * Start listening. Call when a song with word timings starts playing.
     *
     * @param audioSessionId the player's audio session (from ExoPlayer).
     * @param wordStartTimesMs expected word-start times in song milliseconds.
     */
    fun start(audioSessionId: Int, wordStartTimesMs: List<Long>) {
        stop()
        if (audioSessionId == 0 || wordStartTimesMs.size < CALIBRATION_ONSETS) {
            _offsetMs.value = 0L
            _confidence.value = 0
            return
        }
        // Respect the user's toggle — off means pure provider timings.
        if (!com.music.sonariq.data.settings.AppSettings.liveLyricSync.value) {
            return
        }
        synchronized(lock) {
            expectedOnsets = wordStartTimesMs
            detectedOnsets.clear()
            energyFloor = Double.NaN
            lastOnsetMs = 0L
            songStartElapsedMs = SystemClock.elapsedRealtime()
        }
        try {
            val viz = Visualizer(audioSessionId)
            viz.captureSize = Visualizer.getCaptureSizeRange()[1]
            viz.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer?, waveform: ByteArray?, samplingRate: Int,
                    ) = Unit

                    override fun onFftDataCapture(
                        v: Visualizer?, fft: ByteArray?, samplingRate: Int,
                    ) {
                        if (fft != null) onFft(fft, samplingRate)
                    }
                },
                Visualizer.getMaxCaptureRate() / 2,
                false,
                true,
            )
            viz.enabled = true
            visualizer = viz
            running = true
        } catch (_: Exception) {
            // Visualizer unavailable (some devices/ROMs block it) — fall back
            // to provider timings silently.
            _offsetMs.value = 0L
            _confidence.value = 0
        }
    }

    fun stop() {
        running = false
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {
        }
        visualizer = null
        synchronized(lock) {
            detectedOnsets.clear()
            expectedOnsets = emptyList()
        }
        _offsetMs.value = 0L
        _confidence.value = 0
    }

    private fun onFft(fft: ByteArray, samplingRate: Int) {
        if (!running) return
        // FFT magnitude in the vocal band.
        val n = fft.size / 2
        var vocalEnergy = 0.0
        var bins = 0
        for (i in 1 until n) {
            val freqHz = i * samplingRate / fft.size
            if (freqHz in VOCAL_LOW_HZ..VOCAL_HIGH_HZ) {
                val re = fft[2 * i].toDouble()
                val im = fft[2 * i + 1].toDouble()
                vocalEnergy += re * re + im * im
                bins++
            }
        }
        if (bins == 0) return
        val db = 10 * log10(vocalEnergy / bins + 1.0)
        val nowMs = SystemClock.elapsedRealtime()

        synchronized(lock) {
            if (energyFloor.isNaN()) {
                energyFloor = db
                return
            }
            // Slow-adapting floor — follows the song's overall level.
            energyFloor = energyFloor * 0.995 + db * 0.005

            if (db - energyFloor >= ONSET_DB && nowMs - lastOnsetMs >= MIN_ONSET_GAP_MS) {
                lastOnsetMs = nowMs
                // Song position when this onset was heard.
                val songPosMs = nowMs - songStartElapsedMs
                detectedOnsets.add(songPosMs)
                if (detectedOnsets.size >= CALIBRATION_ONSETS) {
                    calibrateLocked()
                }
            }
        }
    }

    /**
     * Match detected onsets to expected word starts. The median of the
     * per-onset drifts is the correction — robust to a missed or extra onset.
     */
    private fun calibrateLocked() {
        val detected = detectedOnsets.toList()
        val expected = expectedOnsets
        if (detected.size < CALIBRATION_ONSETS || expected.size < CALIBRATION_ONSETS) return

        // For each detected onset, find the nearest expected word start and
        // record the drift. Only pair within a sane window.
        val drifts = mutableListOf<Long>()
        var expectedIdx = 0
        for (d in detected) {
            while (expectedIdx < expected.size - 1 &&
                abs(expected[expectedIdx + 1] - d) < abs(expected[expectedIdx] - d)
            ) {
                expectedIdx++
            }
            val drift = d - expected[expectedIdx]
            if (abs(drift) <= MAX_DRIFT_MS) {
                drifts.add(drift)
            }
        }
        if (drifts.size < CALIBRATION_ONSETS / 2) return

        drifts.sort()
        val median = drifts[drifts.size / 2]
        val current = _offsetMs.value
        // Only move the correction if it's a real change — no jitter.
        if (abs(median - current) >= 120 || _confidence.value == 0) {
            _offsetMs.value = median
            _confidence.value = (drifts.size * 100 / detected.size).coerceIn(0, 100)
        } else if (abs(median - current) >= RECALIBRATE_DRIFT_MS) {
            // Drift wandered off — re-anchor.
            _offsetMs.value = median
        }
        // Keep the window sliding: drop the oldest, keep listening.
        if (detectedOnsets.size > CALIBRATION_ONSETS * 2) {
            detectedOnsets.subList(0, CALIBRATION_ONSETS).clear()
        }
    }
}
