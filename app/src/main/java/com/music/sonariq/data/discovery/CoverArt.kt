package com.music.sonariq.data.discovery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.sin

/**
 * Canvas-rendered playlist cover art: a gradient, a motif, the title.
 *
 * ## What this is and is not
 *
 * This is not generative art and not a fetched image. It is a deterministic
 * drawing: the palette, the motif and the words the user chose, laid out by
 * rules in this file. The same spec always draws the same cover, which is
 * what makes the preview honest — what is on screen is what gets saved.
 *
 * Everything the tests can check without a device lives in the pure parts:
 * [COVER_PALETTES], [coverFileName], [displayTitle]. [renderCover] itself
 * needs android.graphics and runs on the device.
 */
enum class CoverMotif { GLOW, WAVES, RINGS }

/** A two-stop gradient plus the ink drawn on it. All colours are ARGB ints. */
data class CoverPalette(
    val name: String,
    val start: Int,
    val end: Int,
    val ink: Int = 0xFFFFFFFF.toInt(),
    val inkDim: Int = 0xB3FFFFFF.toInt(),
)

/**
 * The house palettes.
 *
 * All eight keep white ink legible: every end stop is dark enough that the
 * title never needs a shadow to be read.
 */
val COVER_PALETTES = listOf(
    CoverPalette("Midnight", 0xFF1A1033.toInt(), 0xFF0B0714.toInt()),
    CoverPalette("Ember", 0xFF7C2D12.toInt(), 0xFF1C0A06.toInt()),
    CoverPalette("Abyss", 0xFF0B3B4F.toInt(), 0xFF04121A.toInt()),
    CoverPalette("Moss", 0xFF1E4620.toInt(), 0xFF0A1A0C.toInt()),
    CoverPalette("Orchid", 0xFF4A154B.toInt(), 0xFF170A1E.toInt()),
    CoverPalette("Copper", 0xFF5C3A1E.toInt(), 0xFF1E1206.toInt()),
    CoverPalette("Slate", 0xFF2B3440.toInt(), 0xFF0D1117.toInt()),
    CoverPalette("Crimson", 0xFF5E1224.toInt(), 0xFF1C060D.toInt()),
)

/** Everything a cover is drawn from. */
data class CoverSpec(
    val palette: CoverPalette = COVER_PALETTES.first(),
    val title: String = "",
    val subtitle: String? = null,
    val motif: CoverMotif = CoverMotif.GLOW,
) {
    /** The title as drawn — trimmed, one line, never blank. */
    val drawnTitle: String get() = displayTitle(title)
}

/**
 * The title as it appears on the cover.
 *
 * One line: covers are squares and a wrapped title is a poster, not a cover.
 * Overlong titles are cut with an ellipsis rather than shrunk into
 * illegibility — the shrink pass in [renderCover] only goes so far.
 */
fun displayTitle(title: String): String {
    val single = title.trim().replace(Regex("\\s+"), " ")
    if (single.isEmpty()) return "Untitled"
    return if (single.length > MAX_TITLE_CHARS) {
        single.take(MAX_TITLE_CHARS - 1).trimEnd() + "…"
    } else {
        single
    }
}

private const val MAX_TITLE_CHARS = 28

/**
 * Draws [spec] into a square bitmap.
 *
 * The layout is resolution-independent: every coordinate is a fraction of
 * [sizePx], so the 256px preview and the 1024px saved file are the same
 * drawing at different densities.
 *
 * @param sizePx side length in pixels; 1024 is what gets saved.
 */
fun renderCover(spec: CoverSpec, sizePx: Int = COVER_SIZE_PX): Bitmap {
    val size = sizePx.coerceIn(128, 2048).toFloat()
    val bitmap = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // The ground: a diagonal two-stop gradient.
    val ground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, size, size,
            spec.palette.start, spec.palette.end,
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, size, size, ground)

    when (spec.motif) {
        CoverMotif.GLOW -> drawGlow(canvas, size)
        CoverMotif.WAVES -> drawWaves(canvas, size)
        CoverMotif.RINGS -> drawRings(canvas, size)
    }

    // A soft darkening at the foot, so the title sits on quiet ground
    // whatever the motif did above it.
    val foot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, size * 0.55f, 0f, size,
            0x00000000, 0x99000000.toInt(),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, size, size, foot)

    drawTitle(canvas, size, spec)

    return bitmap
}

/** Two overlapping radial-feeling glows, faked with translucent circles. */
private fun drawGlow(canvas: Canvas, size: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val glow = 0x59FFFFFF
    listOf(
        Triple(size * 0.78f, size * 0.24f, size * 0.42f),
        Triple(size * 0.22f, size * 0.72f, size * 0.36f),
    ).forEach { (cx, cy, radius) ->
        paint.shader = android.graphics.RadialGradient(
            cx, cy, radius,
            glow, 0x00FFFFFF,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)
    }
}

/** Three sine waves rolling across the square. */
private fun drawWaves(canvas: Canvas, size: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x40FFFFFF
    }
    val path = Path()
    val waves = 3
    for (w in 0 until waves) {
        path.reset()
        val baseY = size * (0.30f + 0.20f * w)
        val amplitude = size * 0.06f
        val alpha = 0x40 - w * 0x10
        paint.alpha = alpha.coerceAtLeast(0x18)
        paint.strokeWidth = size * 0.012f
        var x = 0f
        path.moveTo(x, baseY)
        while (x <= size) {
            val y = baseY + amplitude * sin((x / size) * Math.PI * 4 + w).toFloat()
            path.lineTo(x, y)
            x += size / 120f
        }
        canvas.drawPath(path, paint)
    }
}

/** Concentric rings, off-centre, like a ripple. */
private fun drawRings(canvas: Canvas, size: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x4DFFFFFF
    }
    val cx = size * 0.68f
    val cy = size * 0.34f
    for (i in 1..6) {
        paint.alpha = (0x55 - i * 8).coerceAtLeast(0x18)
        paint.strokeWidth = size * 0.010f
        canvas.drawCircle(cx, cy, size * 0.07f * i, paint)
    }
}

/** The title, bottom-left, shrinking to fit rather than overflowing. */
private fun drawTitle(canvas: Canvas, size: Float, spec: CoverSpec) {
    val title = spec.drawnTitle
    val pad = size * 0.08f
    val maxWidth = size - pad * 2f

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = spec.palette.ink
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    // Start big; shrink until it fits or the floor is reached.
    var textSize = size * 0.115f
    paint.textSize = textSize
    while (paint.measureText(title) > maxWidth && textSize > size * 0.05f) {
        textSize *= 0.94f
        paint.textSize = textSize
    }

    val subtitle = spec.subtitle?.trim()?.takeIf { it.isNotEmpty() }
    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = spec.palette.inkDim
        typeface = Typeface.DEFAULT
        textSize = size * 0.048f
        textAlign = Paint.Align.LEFT
    }

    val titleBaseline = size - pad - if (subtitle != null) size * 0.075f else 0f
    canvas.drawText(title, pad, titleBaseline, paint)
    subtitle?.let {
        val sub = if (it.length > 40) it.take(39).trimEnd() + "…" else it
        canvas.drawText(sub, pad, size - pad, subPaint)
    }
}

/** The side length of a saved cover. */
const val COVER_SIZE_PX = 1024

/**
 * A filesystem-safe name for a playlist's cover file.
 *
 * The browse id is the identity; everything outside `[A-Za-z0-9_-]` becomes
 * an underscore so the name is a plain filename on every filesystem.
 */
fun coverFileName(playlistBrowseId: String): String {
    val safe = playlistBrowseId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        .takeIf { it.isNotBlank() } ?: "playlist"
    return "$safe.png"
}
