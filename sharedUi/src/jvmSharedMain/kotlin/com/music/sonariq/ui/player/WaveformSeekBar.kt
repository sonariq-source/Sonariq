package com.music.sonariq.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Sonariq's scrubber, in the Living Sound identity: a field of vertical
 * bars — a soundline the width of the track — instead of a hairline with
 * a thumb. Played bars run emerald into bright, the Automix transition
 * window tints mint, and what is still to play sits dim.
 *
 * The bar heights are a deterministic hash of the track's id: the same
 * track always grows the same soundline, on every screen and every
 * launch, and a different track grows a different one. It is a seek
 * control wearing the brand's shape — the heights are decoration derived
 * from the id alone, never from the audio, and nothing about them is
 * presented as analysis. All seeking runs through the same callbacks the
 * hairline bar used, with the same down/move/up behaviour.
 */
@Composable
fun WaveformSeekBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    /** The track identity the bar heights are hashed from. */
    seedKey: String = "",
    /** A version switch's wait: a slow sheen travels the bars instead. */
    loading: Boolean = false,
    /** An analysed Automix handoff window, tinted mint along the bars. */
    transitionWindow: ClosedFloatingPointRange<Float>? = null,
    /** The Automix blend pulse: while a blend runs, the fill covers the bar. */
    mixPulse: MixPulse? = null,
    barCount: Int = 48,
) {
    val heights = remember(seedKey, barCount) { waveformBarHeights(seedKey, barCount) }
    var scrubbing by remember { mutableStateOf(false) }
    // Only composed while a load is actually in flight, so the sheen's
    // clock costs nothing the rest of the time.
    val sheen = if (loading) waveformSheenPhase() else -1f
    val playedColor = GlassTokens.accent
    val brightColor = Color(0xFF35E887)
    val unplayedColor = Color.White.copy(alpha = 0.22f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(Unit) {
                fun fractionAt(x: Float): Float =
                    (x / size.width).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    scrubbing = true
                    onValueChange(fractionAt(down.position.x))
                    down.consume()
                    var up = false
                    while (!up) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            up = true
                        } else {
                            if (change.positionChanged()) {
                                onValueChange(fractionAt(change.position.x))
                                change.consume()
                            }
                            if (!change.pressed) up = true
                        }
                    }
                    scrubbing = false
                    onValueChangeFinished?.invoke()
                }
            },
    ) {
        val cover = mixPulse?.coverAnim?.value ?: 0f
        val fill = maxOf(value.coerceIn(0f, 1f), cover)
        val barWidth = size.width * 0.012f
        val gap = if (barCount > 1) (size.width - barWidth * barCount) / (barCount - 1) else 0f
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        val playheadIndex = fill * (barCount - 1)
        for (i in 0 until barCount) {
            val fraction = if (barCount > 1) i.toFloat() / (barCount - 1) else 0f
            var barHeight = size.height * heights[i]
            // Bars at the playhead stand taller while a finger is down, so
            // the field answers the touch the way the hairline thickened.
            if (scrubbing && abs(i - playheadIndex) <= 2.5f) {
                barHeight = (barHeight * 1.22f).coerceAtMost(size.height)
            }
            val inWindow = transitionWindow != null && fraction in transitionWindow
            var color = when {
                inWindow -> GlassTokens.mint
                fraction <= fill -> lerp(playedColor, brightColor, fraction)
                else -> unplayedColor
            }
            if (loading) {
                // The wait state: bars keep their colours but the travelling
                // sheen lifts the ones it passes over.
                val lift = (1f - abs(fraction - sheen)).coerceIn(0f, 1f)
                color = color.copy(alpha = (color.alpha * (0.55f + 0.45f * lift * lift)).coerceIn(0f, 1f))
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barWidth + gap), (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

/** The travelling highlight's phase, -0.2 → 1.2 across the bar and around again. */
@Composable
private fun waveformSheenPhase(): Float {
    val transition = rememberInfiniteTransition(label = "waveformSheen")
    val phase by transition.animateFloat(
        initialValue = -0.2f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
        label = "waveformSheenPhase",
    )
    return phase
}

/**
 * The deterministic bar heights for one track: an FNV-1a hash of the id
 * seeds a simple generator, and neighbouring bars are averaged so the
 * field reads as a soundline rather than static. Pure and stable — the
 * same id always yields the same heights — and derived from the id only.
 */
internal fun waveformBarHeights(seedKey: String, count: Int): FloatArray {
    if (count <= 0) return FloatArray(0)
    var hash = -3750763034362895579L // FNV offset basis, as a signed long.
    for (ch in seedKey) {
        hash = hash xor ch.code.toLong()
        hash *= 1099511628211L // FNV prime.
    }
    var state = if (hash == 0L) 0x2545F4914F6CDD1DL else hash
    val raw = FloatArray(count)
    for (i in 0 until count) {
        // SplitMix-style step: cheap, well spread, fully deterministic.
        state += -7046029284384853131L
        var z = state
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        z = z xor (z ushr 31)
        raw[i] = ((z ushr 40) and 0xFFFFFFL).toFloat() / 0xFFFFFF.toFloat()
    }
    val heights = FloatArray(count)
    for (i in 0 until count) {
        val prev = raw[(i - 1).coerceAtLeast(0)]
        val next = raw[(i + 1).coerceAtMost(count - 1)]
        val smoothed = prev * 0.25f + raw[i] * 0.5f + next * 0.25f
        heights[i] = 0.22f + smoothed * 0.78f
    }
    return heights
}
