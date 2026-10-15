package com.music.sonariq.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Mic-based karaoke singing score.
 *
 * While a song with lyrics plays, the microphone is sampled at 16 kHz in
 * 20 ms frames. Each frame's RMS energy is voice-activity checked against a
 * noise floor measured over the first 500 ms of capture (threshold = floor *
 * 3), with a lightweight autocorrelation pitch gate so a mic thump with no
 * vocal pitch in it does not count. A lyric word counts as "sung" when a
 * voiced frame lands inside its time window; the live score is
 * 100 * sungWords / totalWords, finalized when the track ends.
 *
 * Nothing is simulated: no mic, no permission, or no lyrics means [score]
 * stays null and the player shows nothing. Driven by [PlaybackService] —
 * start on song start when playing, pause on pause, finalize on track end.
 */
object KaraokeScorer {

    /** 16 kHz mono, 20 ms frames. */
    private const val SAMPLE_RATE = 16_000
    private const val FRAME_MS = 20L
    private const val FRAME_SAMPLES = (SAMPLE_RATE * FRAME_MS / 1_000).toInt()

    /** First stretch of capture used as the noise floor. */
    private const val CALIBRATION_MS = 500L

    /** Grace around each word's window for position jitter. */
    private const val WINDOW_PAD_MS = 120L

    /** Floor under the calibrated threshold, so dead silence can't score. */
    private const val MIN_THRESHOLD = 150f

    /** Energy this far over threshold counts even without detectable pitch. */
    private const val LOUD_MULTIPLIER = 4f

    /** Vocal pitch gate, Hz. Below is rumble, above is not a voice. */
    private const val PITCH_MIN_HZ = 70f
    private const val PITCH_MAX_HZ = 1000f

    /** Autocorrelation must clear this normalized value to call a pitch. */
    private const val PITCH_CONFIDENCE = 0.5f

    private val _score = MutableStateFlow<Int?>(null)

    /**
     * The live score 0..100, or null when scoring is off, the mic is
     * unavailable, or the current song has no lyrics to score against.
     */
    val score: StateFlow<Int?> = _score.asStateFlow()

    /** One word's time window, padded for position jitter. */
    private data class Window(val startMs: Long, val endMs: Long)

    @Volatile
    private var captureThread: Thread? = null

    @Volatile
    private var running = false

    /** The live record, so [stopCapture] can unblock a blocking read. */
    @Volatile
    private var activeRecord: AudioRecord? = null

    /** Read by the capture thread, written on the main thread. */
    @Volatile
    private var positionMs: (() -> Long)? = null

    /** Whether playback is ongoing — capture runs only while this is true. */
    @Volatile
    private var wantCapture = false

    private var windows: List<Window> = emptyList()
    private var peakEnergy = FloatArray(0)
    private var threshold = Float.MAX_VALUE

    /**
     * A new track became current: the mic stops, the old words go away, and
     * the score clears. Called synchronously on track change, before the new
     * lyrics have been fetched.
     */
    fun onTrackReset() {
        wantCapture = false
        stopCapture()
        windows = emptyList()
        peakEnergy = FloatArray(0)
        threshold = Float.MAX_VALUE
        _score.value = null
    }

    /**
     * The current track's lyrics resolved. Scoring starts only when the user
     * enabled it, the mic permission is granted, there are words to score,
     * and [isPlaying].
     */
    fun onTrackLines(
        context: Context,
        lines: List<LyricLine>?,
        isPlaying: Boolean,
        positionMs: () -> Long,
    ) {
        windows = buildWindows(lines.orEmpty())
        peakEnergy = FloatArray(windows.size)
        threshold = Float.MAX_VALUE
        this.positionMs = positionMs
        wantCapture = isPlaying
        _score.value = null
        maybeStart(context)
    }

    /** Playback started or resumed. */
    fun onPlaybackResumed(context: Context, positionMs: () -> Long) {
        this.positionMs = positionMs
        wantCapture = true
        maybeStart(context)
    }

    /** Playback paused: the mic stops, the words' peaks and score stay. */
    fun onPlaybackPaused() {
        wantCapture = false
        stopCapture()
    }

    /** Track ended: the mic stops and the final score stands. */
    fun onTrackEnded() {
        wantCapture = false
        stopCapture()
    }

    /** Service going away: everything stops and releases. */
    fun release() {
        wantCapture = false
        stopCapture()
        windows = emptyList()
        peakEnergy = FloatArray(0)
        positionMs = null
        _score.value = null
    }

    private fun maybeStart(context: Context) {
        if (!wantCapture || windows.isEmpty()) return
        if (!AppSettings.karaokeScoring.value) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (captureThread != null) return
        startCapture()
    }

    private fun startCapture() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, FRAME_SAMPLES * 4),
            )
        } catch (e: Exception) {
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        // Best effort: keep the phone's own speaker out of the measurement.
        val echoCanceler = if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        } else {
            null
        }
        try {
            record.startRecording()
        } catch (e: Exception) {
            echoCanceler?.release()
            record.release()
            return
        }
        running = true
        activeRecord = record
        captureThread = Thread({
            try {
                captureLoop(record)
            } finally {
                activeRecord = null
                runCatching { record.stop() }
                record.release()
                echoCanceler?.release()
            }
        }, "KaraokeScorer").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopCapture() {
        running = false
        // Unblocks a read() parked waiting for the next frame.
        activeRecord?.let { record ->
            runCatching { record.stop() }
        }
        val thread = captureThread
        captureThread = null
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(1_000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun captureLoop(record: AudioRecord) {
        val frame = ShortArray(FRAME_SAMPLES)
        var calibrationSum = 0.0
        var calibrationFrames = 0
        var calibrated = false
        val calibrationDeadline = SystemClock.elapsedRealtime() + CALIBRATION_MS
        while (running) {
            val read = try {
                record.read(frame, 0, FRAME_SAMPLES)
            } catch (e: Exception) {
                break
            }
            if (read <= 0) continue
            val rms = rms(frame, read)
            if (!calibrated) {
                if (SystemClock.elapsedRealtime() < calibrationDeadline) {
                    calibrationSum += rms
                    calibrationFrames++
                    continue
                }
                val floor = if (calibrationFrames > 0) {
                    (calibrationSum / calibrationFrames).toFloat()
                } else {
                    0f
                }
                threshold = maxOf(floor * 3f, MIN_THRESHOLD)
                calibrated = true
            }
            val position = positionMs?.invoke() ?: continue
            val pitch = estimatePitchHz(frame, read)
            val voiced = rms > threshold && pitch in PITCH_MIN_HZ..PITCH_MAX_HZ
            if (voiced || rms > threshold * LOUD_MULTIPLIER) {
                for (i in windows.indices) {
                    val window = windows[i]
                    if (position in window.startMs..window.endMs && rms > peakEnergy[i]) {
                        peakEnergy[i] = rms
                    }
                }
                publishScore()
            }
        }
    }

    private fun publishScore() {
        val total = windows.size
        if (total == 0) return
        var sung = 0
        for (i in 0 until total) {
            if (peakEnergy[i] > threshold) sung++
        }
        val next = (100f * sung / total).roundToInt().coerceIn(0, 100)
        if (_score.value != next) _score.value = next
    }

    private fun rms(frame: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) {
            val s = frame[i].toDouble() / 32768.0
            sum += s * s
        }
        return (sqrt(sum / n) * 32768.0).toFloat()
    }

    /**
     * Autocorrelation pitch estimate for one frame, Hz, or 0 when nothing
     * periodic stands out. A 20 ms frame holds enough samples for lags down
     * to ~100 Hz; deeper voices fall back to the loud gate in [captureLoop].
     */
    private fun estimatePitchHz(frame: ShortArray, n: Int): Float {
        var energy = 0.0
        for (i in 0 until n) {
            val s = frame[i].toDouble() / 32768.0
            energy += s * s
        }
        if (energy <= 0.0) return 0f
        val minLag = (SAMPLE_RATE / PITCH_MAX_HZ).toInt().coerceAtLeast(1)
        val maxLag = (SAMPLE_RATE / PITCH_MIN_HZ).toInt().coerceAtMost(n / 2)
        var bestLag = 0
        var bestCorrelation = 0f
        for (lag in minLag..maxLag) {
            var correlation = 0.0
            for (i in 0 until n - lag) {
                correlation += (frame[i].toDouble() / 32768.0) *
                    (frame[i + lag].toDouble() / 32768.0)
            }
            val normalized = (correlation / energy).toFloat()
            if (normalized > bestCorrelation) {
                bestCorrelation = normalized
                bestLag = lag
            }
        }
        return if (bestLag > 0 && bestCorrelation >= PITCH_CONFIDENCE) {
            SAMPLE_RATE.toFloat() / bestLag
        } else {
            0f
        }
    }

    /**
     * Flattens lyric lines into word windows. Words with measured timings are
     * used as-is; a line with only a line stamp gets its window split evenly
     * across its words. Blank lines and answering vocals are not scored.
     */
    private fun buildWindows(lines: List<LyricLine>): List<Window> {
        val out = ArrayList<Window>()
        for ((index, line) in lines.withIndex()) {
            if (line.isGap || line.text.isBlank()) continue
            if (line.words.isNotEmpty()) {
                for (word in line.words) {
                    if (word.endMs > word.startMs) {
                        out.add(
                            Window(
                                word.startMs - WINDOW_PAD_MS,
                                word.endMs + WINDOW_PAD_MS,
                            ),
                        )
                    }
                }
            } else {
                val end = lineEndMs(line, lines.getOrNull(index + 1)?.timeMs)
                val span = end - line.timeMs
                if (span <= 0) continue
                val parts = line.text.trim().split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                if (parts.isEmpty()) continue
                val perWord = span.toDouble() / parts.size
                parts.forEachIndexed { i, _ ->
                    out.add(
                        Window(
                            (line.timeMs + perWord * i).toLong() - WINDOW_PAD_MS,
                            (line.timeMs + perWord * (i + 1)).toLong() + WINDOW_PAD_MS,
                        ),
                    )
                }
            }
        }
        return out
    }

    private fun lineEndMs(line: LyricLine, nextStartMs: Long?): Long {
        if (line.hasKnownEnd) return line.endMs
        if (nextStartMs != null && nextStartMs > line.timeMs) return nextStartMs
        return line.timeMs
    }
}
