package com.music.sonariq.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.lerp

/**
 * How a glass surface renders, decided once per surface from the three
 * facts that govern it — see [glassModeFor].
 *
 * The split that matters is [MATERIAL_ONLY] versus the old behaviour:
 * before this, "no backdrop blur available" (Android 11 and below, where
 * the platform has no RenderEffect) meant "no glass at all" — the toggle
 * was even disabled in Settings — so a whole class of devices got flat
 * matte cards with the switch on. The material is the glass; the blur is
 * only its deepest tier. A device that cannot blur still gets the tinted,
 * lit, rimmed material, which is what reads as glass on a dark page.
 */
enum class GlassMode {
    /** Liquid Glass off: the surface is its plain filled self, unchanged. */
    OFF,

    /**
     * On, but "reduce dynamic blur" is set: the promised solid fill —
     * tinted, rimmed, but no gradients in motion and nothing sampled.
     */
    SOLID,

    /** On, with the full material but no backdrop blur underneath. */
    MATERIAL_ONLY,

    /**
     * On, on a platform that can sample a backdrop: the real glass tier.
     *
     * What "real" renders as depends on where the surface sits. The app
     * chrome that floats over the recorded page — the navigation bar, the
     * mini player, the top bar — renders it as true refracting glass:
     * vibrancy, blur and a lens over the page behind it (the drawBackdrop
     * glass in the app module). A surface that lives inside the recorded
     * page, or inside a sheet window the page's backdrop recording never
     * reaches, cannot sample that backdrop; it and the desktop's surfaces
     * render this tier as the material laid over their own sampled blur,
     * which is what [glassSurface] does with it.
     */
    REAL,
}

/**
 * The one decision every glass surface makes, kept pure so it can be
 * pinned by tests: the toggle first, then the reduced-blur promise, then
 * what the platform can actually do.
 */
fun glassModeFor(
    enabled: Boolean,
    blurCapable: Boolean,
    reduceDynamicBlur: Boolean,
): GlassMode = when {
    !enabled -> GlassMode.OFF
    reduceDynamicBlur -> GlassMode.SOLID
    blurCapable -> GlassMode.REAL
    else -> GlassMode.MATERIAL_ONLY
}

/**
 * One full sweep of the glass sheen: nine seconds, most of it rest. The
 * band crosses in the first two thirds and is off the surface for the
 * remainder, which is the pause — light moving over glass is occasional,
 * not a marquee.
 */
const val GLASS_SHEEN_CYCLE_MS = 9_000

/** How bright the travelling band burns at its centre. Kept low on purpose. */
const val GLASS_SHEEN_ALPHA = 0.055f

/** The band's half-width, as a fraction of the surface's width. */
const val GLASS_SHEEN_HALF_WIDTH = 0.30f

/**
 * Where the sheen band's centre sits for a cycle [phase] in 0..1, in
 * surface widths: it starts fully off the leading edge and ends fully off
 * the trailing one, so the ends of the cycle are the pause.
 */
fun glassSheenCentre(phase: Float): Float = -0.65f + phase * 2.3f

/** Whether any of the band is over the surface at [phase]. */
fun glassSheenVisible(phase: Float): Boolean {
    val centre = glassSheenCentre(phase)
    return centre + GLASS_SHEEN_HALF_WIDTH > 0f && centre - GLASS_SHEEN_HALF_WIDTH < 1f
}

/**
 * The shared clock for the travelling sheen, as a draw-phase value: the
 * phase is read inside draw scope, so a ticking sheen invalidates the
 * draw of the surfaces wearing it and nothing else — no recomposition,
 * ever. Inactive (off, reduced motion, television) the phase is a fixed
 * off-surface value and no transition runs at all.
 */
@Composable
fun rememberGlassSheenPhase(active: Boolean): State<Float> {
    if (!active) return remember { mutableFloatStateOf(-1f) }
    val transition = rememberInfiniteTransition(label = "glass sheen")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(GLASS_SHEEN_CYCLE_MS, easing = LinearEasing),
        ),
        label = "glass sheen phase",
    )
}

/** The travelling band itself: a soft slanted wash of light, drawn over the surface. */
private fun ContentDrawScope.drawGlassSheen(phase: Float) {
    if (!glassSheenVisible(phase)) return
    val centreX = glassSheenCentre(phase) * size.width
    val half = GLASS_SHEEN_HALF_WIDTH * size.width
    val slant = size.height * 0.35f
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = GLASS_SHEEN_ALPHA),
                Color.Transparent,
            ),
            start = Offset(centreX - half, size.height + slant),
            end = Offset(centreX + half, -slant),
        ),
    )
}

/**
 * The glass material itself, shared by every surface that wears it.
 *
 * Layers, back to front: a translucent fill graded from a lightened top
 * to a deepened bottom (the grade is what keeps the surface from reading
 * flat where there is no blur beneath it), a static sheen of light
 * falling from the top edge, a shade pooled at the bottom edge, the
 * tier's rim via [glassEdge] where [rim] is asked for, and — on the
 * chrome surfaces that pass a [sheenPhase] — the travelling band over
 * everything, content included, at an alpha low enough to read as light
 * on glass rather than as something moving.
 *
 * The backdrop blur is NOT applied here: [GlassMode.REAL] callers that
 * render this tier through the material lay their haze effect into the
 * chain before this modifier, the same order the bars have always used,
 * so the material paints over the sample. (The app chrome renders
 * [GlassMode.REAL] as refracting glass instead and never reaches this
 * modifier in that mode.) [tier]/[tierAlpha] are the surface's own glass tier (see
 * [GlassTokens]); with a [tint] in reach the tier's colour gives way to
 * the artwork's, as everywhere else the glass is tinted. Without a blur
 * underneath the fill goes near-opaque ([materialAlpha]) — translucency
 * over a dark page with nothing sampled into it is invisible, and the
 * material has to carry the look on its own.
 *
 * [GlassMode.OFF] paints [solidColor] flat, exactly the surface the
 * caller had before glass; [GlassMode.SOLID] paints the same fill,
 * artwork-tinted where a tint is in reach, with the rim.
 */
fun Modifier.glassSurface(
    shape: Shape,
    mode: GlassMode,
    tint: Color?,
    tier: Color,
    tierAlpha: Float,
    solidColor: Color,
    materialAlpha: Float = 0.97f,
    rim: Boolean = true,
    sheenPhase: State<Float>? = null,
): Modifier = when (mode) {
    GlassMode.OFF -> background(solidColor, shape)
    GlassMode.SOLID -> this
        .clip(shape)
        .background(artworkTintedSurface(tint, solidColor, solidColor.alpha), shape)
        .then(if (rim) Modifier.glassEdge(shape) else Modifier)

    GlassMode.MATERIAL_ONLY, GlassMode.REAL -> {
        val alpha = if (mode == GlassMode.REAL) tierAlpha else materialAlpha
        this
            .clip(shape)
            .drawBehind {
                val base = tint ?: tier
                drawRect(
                    Brush.verticalGradient(
                        listOf(
                            lerp(base, Color.White, 0.17f).copy(alpha = alpha),
                            base.copy(alpha = alpha),
                            lerp(base, Color.Black, 0.20f).copy(alpha = alpha),
                        ),
                    ),
                )
                // Light falling from the top edge, gone by halfway down.
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.5f,
                    ),
                )
                // The shade that pools at the bottom edge of a thick pane.
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.14f)),
                        startY = size.height * 0.78f,
                        endY = size.height,
                    ),
                )
            }
            .then(if (rim) Modifier.glassEdge(shape) else Modifier)
            .then(
                if (sheenPhase != null) {
                    Modifier.drawWithContent {
                        drawContent()
                        drawGlassSheen(sheenPhase.value)
                    }
                } else {
                    Modifier
                },
            )
    }
}
