package com.music.sonariq.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The animated canvas: a living backdrop for tracks with no provider clip.
 *
 * A provider canvas (see `AndroidCanvasArtworkPlayer`) only exists for tracks
 * a catalogue published a clip for — most of a library has none, and a still
 * backdrop behind those is what this replaces. Four blobs of the artwork's own
 * colours orbit slowly behind the player on infinite transitions, so the
 * screen is always in motion while a track plays. This is real animation, not
 * a static gradient: every blob's position is a function of a looping clock,
 * and the palette re-reads from each new cover.
 *
 * Colours come from the mesh the player already read — no second decode, no
 * quantiser with opinions about which colour was the interesting one, just the
 * sleeve's own means. The palette crossfades over [PALETTE_FADE_MS] when the
 * track changes so the screen changes colour instead of cutting.
 *
 * Skipped entirely when the user asked for reduced animation: the blobs freeze
 * at a fixed arrangement rather than being removed, so the backdrop keeps its
 * colour without the motion.
 */
@Composable
fun CanvasBackdrop(
    mesh: ArtworkMesh?,
    modifier: Modifier = Modifier,
) {
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val palette = remember(mesh) { canvasPalette(mesh, count = BLOB_COUNT) }

    // One animated colour per blob; a track change glides the whole screen to
    // the new sleeve instead of cutting.
    val colours = List(BLOB_COUNT) { index ->
        val target = palette.getOrElse(index) { FallbackCanvasColours[index] }
        animateColorAsState(
            targetValue = target,
            animationSpec = tween(PALETTE_FADE_MS),
            label = "canvasColour$index",
        ).value
    }

    val clock = rememberInfiniteTransition(label = "canvasClock")
    // Three clocks at incommensurate periods: the arrangement never visibly
    // repeats, and no two blobs stay in lockstep.
    val t1 by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Restart), label = "canvasT1")
    val t2 by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(23_000, easing = LinearEasing), RepeatMode.Restart), label = "canvasT2")
    val t3 by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(31_000, easing = LinearEasing), RepeatMode.Restart), label = "canvasT3")
    val times = if (reduceAnimation) floatArrayOf(0.18f, 0.18f, 0.18f) else floatArrayOf(t1, t2, t3)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasBase),
    ) {
        val w = size.width
        val h = size.height
        // Portrait needs bigger blobs: the radius follows the short edge, so
        // on a tall screen the blobs would cluster in the middle and leave
        // the top and bottom empty. Scale up when the frame is taller than
        // it is wide so the canvas fills a phone or tablet held vertically.
        val portrait = h > w && w > 0f
        val aspectBoost = if (portrait) (h / w).coerceIn(1f, 2.2f) else 1f
        val radius = minOf(w, h) * BLOB_RADIUS_FRACTION * aspectBoost
        BLOB_ORBITS.forEachIndexed { index, orbit ->
            val t = times[index % times.size]
            val angle = (t * orbit.cycles + orbit.phase) * 2f * PI.toFloat()
            // In portrait the blobs travel further vertically so they reach
            // the top and bottom edges instead of hovering around the middle.
            val yAmp = if (portrait) (orbit.yAmp * 1.35f).coerceAtMost(0.42f) else orbit.yAmp
            val cx = w * (0.5f + orbit.xAmp * cos(angle))
            val cy = h * (0.5f + yAmp * sin(angle * orbit.wobble))
            val colour = colours[index % colours.size]
            drawCircle(
                brush = Brush.radialGradient(
                    0f to colour.copy(alpha = BLOB_ALPHA),
                    0.55f to colour.copy(alpha = BLOB_ALPHA * 0.45f),
                    1f to colour.copy(alpha = 0f),
                    center = Offset(cx, cy),
                    radius = radius * orbit.size,
                ),
                radius = radius * orbit.size,
                center = Offset(cx, cy),
            )
        }
        // Enough scrim to keep white controls off a bright canvas, no more.
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.28f),
                0.6f to Color.Black.copy(alpha = 0.42f),
                1f to Color.Black.copy(alpha = 0.60f),
            ),
        )
    }
}

/** Where one blob travels: amplitudes are fractions of the surface. */
private data class BlobOrbit(
    val xAmp: Float,
    val yAmp: Float,
    val cycles: Float,
    val phase: Float,
    val wobble: Float,
    val size: Float,
)

private val BLOB_ORBITS = listOf(
    BlobOrbit(xAmp = 0.34f, yAmp = 0.26f, cycles = 1f, phase = 0.00f, wobble = 1f, size = 1.15f),
    BlobOrbit(xAmp = 0.30f, yAmp = 0.32f, cycles = 1f, phase = 0.37f, wobble = 2f, size = 0.95f),
    BlobOrbit(xAmp = 0.36f, yAmp = 0.24f, cycles = 1f, phase = 0.61f, wobble = 1f, size = 1.05f),
    BlobOrbit(xAmp = 0.28f, yAmp = 0.30f, cycles = 1f, phase = 0.83f, wobble = 3f, size = 0.85f),
)

private const val BLOB_COUNT = 4
private const val BLOB_RADIUS_FRACTION = 0.55f
private const val BLOB_ALPHA = 0.9f
private const val PALETTE_FADE_MS = 900

private val CanvasBase = Color(0xFF060A0E)

private val FallbackCanvasColours = listOf(
    Color(0xFF1D5C4D),
    Color(0xFF0E3A52),
    Color(0xFF4D1D4A),
    Color(0xFF123B2A),
)

/** Thirds of the frame in spread-out order: corners, centre, then edges. */
private val SPREAD_ORDER = listOf(
    0 to 0, 2 to 2, 2 to 0, 0 to 2,
    1 to 1,
    1 to 0, 0 to 1, 2 to 1, 1 to 2,
)

/**
 * The artwork's palette, read off the mesh the player already holds.
 *
 * The mesh is a 32x32 texture of the sleeve's own means, so sampling it on a
 * coarse grid returns the cover's colours in roughly the cover's proportions
 * without a second decode. Each swatch averages a small window rather than a
 * single texel so one bright pixel cannot elect the whole screen's colour.
 */
fun canvasPalette(mesh: ArtworkMesh?, count: Int = BLOB_COUNT): List<Color> {
    val image = mesh?.image ?: return FallbackCanvasColours.take(count)
    val width = image.width
    val height = image.height
    if (width < 1 || height < 1) return FallbackCanvasColours.take(count)
    val pixels = IntArray(width * height)
    image.readPixels(pixels, startX = 0, startY = 0, width = width, height = height)
    return samplePaletteColors(pixels, width, height, count)
}

/**
 * Pure palette sampler: [count] swatches spread over the frame, each the mean
 * of a small window. Kept free of [ImageBitmap] so it is unit-testable.
 */
fun samplePaletteColors(
    pixels: IntArray,
    width: Int,
    height: Int,
    count: Int,
): List<Color> {
    if (pixels.isEmpty() || width < 1 || height < 1 || count < 1) {
        return FallbackCanvasColours.take(count.coerceAtLeast(1))
    }
    // Spread the swatches over the frame's thirds so they disagree with each
    // other the way a cover's regions do, rather than clustering in the middle.
    // Corners first, then the centre, then the edges: the first four swatches
    // are always the four corners.
    val radius = maxOf(1, minOf(width, height) / 16)
    return List(count) { index ->
        // One swatch alone is the whole frame's summary: take the centre.
        val (sx, sy) = if (count == 1) 1 to 1 else SPREAD_ORDER[index % SPREAD_ORDER.size]
        val cx = ((sx + 0.5f) / 3f * width).roundToInt().coerceIn(0, width - 1)
        val cy = ((sy + 0.5f) / 3f * height).roundToInt().coerceIn(0, height - 1)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (dy in -radius..radius) {
            val y = (cy + dy).coerceIn(0, height - 1)
            for (dx in -radius..radius) {
                val x = (cx + dx).coerceIn(0, width - 1)
                val pixel = pixels[y * width + x]
                r += (pixel shr 16) and 0xFF
                g += (pixel shr 8) and 0xFF
                b += pixel and 0xFF
                n++
            }
        }
        Color(
            red = (r / n).toInt(),
            green = (g / n).toInt(),
            blue = (b / n).toInt(),
        )
    }
}
