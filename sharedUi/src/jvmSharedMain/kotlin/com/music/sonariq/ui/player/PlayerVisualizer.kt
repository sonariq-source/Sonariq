package com.music.sonariq.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Now Playing visualizer: spectrum, waveform and circular styles drawn
 * on Canvas in Sonariq lime and green, driven by the player session's
 * live audio capture.
 *
 * The platform side is [VisualizerUi], answered by the application the
 * player is drawn in — on the phone, android.media.audiofx.Visualizer on
 * the ExoPlayer session; [NoVisualizer] anywhere that captures nothing.
 * Nothing here touches audio itself: the overlay only reads capture frames.
 *
 * Fork of https://github.com/kushagrasinghx/BitChord (GPL-3.0).
 */

/** Sonariq's lime and green — the same stops the app theme uses (SonariqLime / SonariqGreen). */
private val VisualizerLime = Color(0xFFFFB020)
private val VisualizerGreen = Color(0xFF4FD1B5)

/** Bars drawn by the spectrum and circular styles. */
private const val VISUALIZER_BARS = 48
/** Points in the waveform polyline. */
private const val VISUALIZER_WAVEFORM_SAMPLES = 120

/**
 * The platform's side of the visualizer: live capture frames from the
 * player session, and the switch that starts and stops them.
 *
 * Frames arrive on the platform's capture thread; the overlay reads them in
 * its own composition scope, so a 20 Hz capture never recomposes the
 * player around it.
 */
interface VisualizerUi {
    /**
     * False where the platform cannot capture — an unsupported device, or
     * no audio session yet. The overlay hides itself rather than showing a
     * dead control.
     */
    val available: StateFlow<Boolean>

    /**
     * Whether a real audio session (> 0) is attached right now. False —
     * before anything has played, or after the session went away — hides
     * the overlay: there is no audio to draw.
     */
    val sessionActive: StateFlow<Boolean>

    /** Latest 8-bit unsigned waveform capture, 128 = silence; null while idle. */
    val waveform: StateFlow<ByteArray?>

    /** Latest FFT capture in the Visualizer.getFft layout; null while idle. */
    val fft: StateFlow<ByteArray?>

    /**
     * Starts capture for the current audio session, attaching the platform
     * visualizer first if it isn't; false pauses capture but keeps the
     * attachment so resuming is instant.
     */
    fun setCaptureEnabled(enabled: Boolean)

    /** Frees the platform visualizer; the next [setCaptureEnabled] re-attaches. */
    fun release()
}

/** The [PlayerHost.visualizer] answer where the platform captures nothing. */
object NoVisualizer : VisualizerUi {
    override val available = MutableStateFlow(false)
    override val sessionActive = MutableStateFlow(false)
    override val waveform = MutableStateFlow<ByteArray?>(null)
    override val fft = MutableStateFlow<ByteArray?>(null)
    override fun setCaptureEnabled(enabled: Boolean) = Unit
    override fun release() = Unit
}

/**
 * Whether the live visualizer can run right now: a style is picked, the
 * platform can actually capture, and reduced motion is off. The sleeve's tap
 * answers to this, so it never offers a dead view.
 */
@Composable
internal fun rememberVisualizerCanRun(): Boolean {
    val style by PlayerSettings.visualizerStyle.collectAsStateWithLifecycle()
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val visualizer = PlayerPlatform.host.visualizer
    val available by visualizer.available.collectAsStateWithLifecycle()
    val sessionActive by visualizer.sessionActive.collectAsStateWithLifecycle()
    return style != VisualizerStyle.OFF && available && sessionActive && !reduceAnimation
}

/** The latest capture frames, with the mount that produces them. */
private class VisualizerFrames(
    val style: VisualizerStyle,
    val intensity: Float,
    val waveform: ByteArray?,
    val fft: ByteArray?,
)

/**
 * Mounts live capture and returns the latest frames, or null when the
 * visualizer cannot run — style off, platform unable to capture, or reduced
 * motion. Capture runs only while this is resumed and music is playing;
 * paused, the caller draws its idle floor rather than a frozen frame.
 */
@Composable
private fun rememberVisualizerFrames(isPlaying: Boolean): VisualizerFrames? {
    val style by PlayerSettings.visualizerStyle.collectAsStateWithLifecycle()
    val intensity by PlayerSettings.visualizerIntensity.collectAsStateWithLifecycle()
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val visualizer = PlayerPlatform.host.visualizer
    val available by visualizer.available.collectAsStateWithLifecycle()
    val sessionActive by visualizer.sessionActive.collectAsStateWithLifecycle()

    if (style == VisualizerStyle.OFF || !available || !sessionActive || reduceAnimation) return null

    // Capture only while the visualizer is on screen and music is playing.
    // LifecycleResumeEffect restarts when a key changes, running the old
    // block's onPauseOrDispose first — so a play-to-pause flip pauses
    // capture before anything else, and backgrounding always pauses it.
    LifecycleResumeEffect(isPlaying, visualizer) {
        visualizer.setCaptureEnabled(isPlaying)
        onPauseOrDispose { visualizer.setCaptureEnabled(false) }
    }
    // The native Visualizer is freed when this goes away — a style switched
    // off, the overlay closed, the player closed — never merely on pause.
    // The next mount re-attaches through setCaptureEnabled.
    DisposableEffect(visualizer) {
        onDispose { visualizer.release() }
    }

    val waveform by visualizer.waveform.collectAsStateWithLifecycle()
    val fft by visualizer.fft.collectAsStateWithLifecycle()
    return VisualizerFrames(style, intensity, waveform, fft)
}

/**
 * The live visualizer filling its bounds — the artwork's alternate face in
 * the Apple-style player, tapped into from the sleeve.
 *
 * Mounted only when the visualizer can actually run (see
 * [rememberVisualizerCanRun]) — otherwise nothing at all, so the setting
 * costs no layout and an unsupported device shows no dead control.
 */
@Composable
internal fun PlayerVisualizerFull(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val frames = rememberVisualizerFrames(isPlaying) ?: return

    // One smoothed bar array across frames; each new capture recomposes this
    // scope alone and folds its frame in. Keyed on the style so switching
    // modes starts from silence instead of the old mode's decay.
    val smoothedBars = remember(frames.style) { FloatArray(VISUALIZER_BARS) }

    Canvas(modifier = modifier.fillMaxSize()) {
        // The artwork stays mounted underneath; dim it out so the bars read
        // against any cover.
        drawRect(Color.Black.copy(alpha = 0.78f))
        when (frames.style) {
            VisualizerStyle.SPECTRUM -> {
                val next = frames.fft
                    ?.let { applyVisualizerIntensity(fftToBarMagnitudes(it, VISUALIZER_BARS), frames.intensity) }
                    ?: FloatArray(VISUALIZER_BARS)
                val bars = if (isPlaying) smoothVisualizerBars(smoothedBars, next)
                else FloatArray(VISUALIZER_BARS)
                drawSpectrumBars(bars)
            }
            VisualizerStyle.WAVEFORM -> {
                val amplitudes = frames.waveform
                    ?.takeIf { isPlaying }
                    ?.let { waveformToAmplitudes(it, VISUALIZER_WAVEFORM_SAMPLES) }
                    ?: FloatArray(VISUALIZER_WAVEFORM_SAMPLES)
                drawWaveform(amplitudes, frames.intensity)
            }
            VisualizerStyle.CIRCULAR -> {
                val next = frames.fft
                    ?.let { applyVisualizerIntensity(fftToBarMagnitudes(it, VISUALIZER_BARS), frames.intensity) }
                    ?: FloatArray(VISUALIZER_BARS)
                val bars = if (isPlaying) smoothVisualizerBars(smoothedBars, next)
                else FloatArray(VISUALIZER_BARS)
                drawCircularRadial(bars)
            }
            VisualizerStyle.OFF -> Unit
        }
    }
}

/**
 * Angles for [barCount] bars radiating a full circle, in radians, starting
 * at the top and running clockwise — the square's answer to the old strip's
 * fan, which only ever swept the upper half.
 */
internal fun circularBarAngles(barCount: Int): FloatArray {
    if (barCount <= 0) return FloatArray(0)
    return FloatArray(barCount) { i ->
        (i.toFloat() / barCount) * 2f * PI.toFloat() - PI.toFloat() / 2f
    }
}

/**
 * Bottom-up rounded bars. Height-graded green to lime: quiet bars read
 * green, loud ones flare lime.
 */
private fun DrawScope.drawSpectrumBars(bars: FloatArray) {
    if (bars.isEmpty()) return
    val gap = 3.dp.toPx()
    val slot = size.width / bars.size
    val barWidth = (slot - gap).coerceAtLeast(1f)
    val radius = (barWidth / 2).coerceAtMost(4.dp.toPx())
    bars.forEachIndexed { i, level ->
        val clamped = level.coerceIn(0f, 1f)
        val barHeight = (clamped * size.height)
            .coerceAtLeast(if (clamped > 0f) 2.dp.toPx() else 0f)
        if (barHeight <= 0f) return@forEachIndexed
        val x = i * slot + (slot - barWidth) / 2
        drawRoundRect(
            color = lerp(VisualizerGreen, VisualizerLime, clamped)
                .copy(alpha = 0.35f + 0.65f * clamped),
            topLeft = Offset(x, size.height - barHeight),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(radius),
        )
    }
}

/** The raw waveform as a trace: a soft lime halo under a green line. */
private fun DrawScope.drawWaveform(amplitudes: FloatArray, intensity: Float) {
    if (amplitudes.size < 2) return
    val midY = size.height / 2
    val gain = size.height / 2 * 0.92f * intensity.coerceIn(0.25f, 2f)
    val path = Path()
    amplitudes.forEachIndexed { i, amplitude ->
        val x = i.toFloat() / (amplitudes.size - 1) * size.width
        val y = midY - (amplitude * gain).coerceIn(-midY, midY)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = VisualizerLime.copy(alpha = 0.28f),
        style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round),
    )
    drawPath(
        path = path,
        color = VisualizerGreen,
        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
    )
}

/**
 * The spectrum as a full circle: bars radiating from a centre ring, quiet
 * bars green at the ring, loud ones flaring lime outward. The square's
 * answer to the old strip's upper-half fan.
 */
private fun DrawScope.drawCircularRadial(bars: FloatArray) {
    if (bars.isEmpty()) return
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = minOf(size.width, size.height) / 2f
    val innerR = radius * 0.44f
    val maxLen = radius * 0.50f
    val stroke = 4.dp.toPx()
    val angles = circularBarAngles(bars.size)
    val denom = (bars.size - 1).coerceAtLeast(1).toFloat()
    bars.forEachIndexed { i, level ->
        val clamped = level.coerceIn(0f, 1f)
        val len = (clamped * maxLen)
            .coerceAtLeast(if (clamped > 0f) 2.dp.toPx() else 0f)
        if (len <= 0f) return@forEachIndexed
        val angle = angles[i]
        val dx = cos(angle)
        val dy = sin(angle)
        drawLine(
            color = lerp(VisualizerGreen, VisualizerLime, i.toFloat() / denom)
                .copy(alpha = 0.4f + 0.6f * clamped),
            start = Offset(cx + dx * innerR, cy + dy * innerR),
            end = Offset(cx + dx * (innerR + len), cy + dy * (innerR + len)),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
