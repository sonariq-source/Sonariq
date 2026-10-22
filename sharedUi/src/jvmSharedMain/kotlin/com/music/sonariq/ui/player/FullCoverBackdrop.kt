package com.music.sonariq.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/** How long one cover takes to crossfade into the next on a track change. */
private const val FULL_COVER_CROSSFADE_MS = 700

/** One leg of the backdrop's slow drift; it reverses at each end. */
private const val FULL_COVER_KEN_BURNS_MS = 24_000

/** The drift's reach: the backdrop eases between its base scale and this much more. */
private const val FULL_COVER_KEN_BURNS_RANGE = 0.10f

/**
 * Extra scale the ambient variant starts from, underneath the drift.
 *
 * A heavy blur samples past the picture's own edges; starting overscaled
 * keeps the softest, darkest part of that fall-off off screen, so the
 * frame's edge is blurred cover all the way out rather than a rim of
 * whatever the blur clamped to.
 */
private const val AMBIENT_BASE_SCALE = 1.18f

/** How far the ambient variant's cover is smeared. Static — never animated. */
private val AMBIENT_BLUR_RADIUS = 48.dp

/** Flat dark tint under the ambient variant's gradient. */
private const val AMBIENT_TINT_ALPHA = 0.52f

/**
 * The full-cover artwork backdrop: the track's cover filling the player
 * edge to edge, under a legibility scrim.
 *
 * It always uses the sleeve's own [ImageRequest], so it is the same decode
 * the sleeve already asked Coil for — a memory-cache hit, never a second
 * download and never a second decode of a different size.
 *
 * Two shapes, one per orientation:
 *
 * - Portrait ([ambient] false) is the poster itself: sharp, cropped to
 *   fill, exactly as designed. On the main player it sits in the same rect
 *   as the sleeve blown out into the same poster, so the two read as one
 *   picture; behind the lyrics and the queue it is what stays up.
 * - Landscape ([ambient] true) sits *behind a separate boxed sleeve* in
 *   the left column, so a sharp copy here would draw the same cover twice.
 *   Instead it is an ambient field: the cover under one static, heavy
 *   blur render effect and a flat dark tint, with the sleeve left as the
 *   one crisp copy on screen.
 *
 * The scrims are dark at every stop and bottom-weighted in both shapes.
 * There is deliberately no clear or transparent band: a mostly pale cover
 * under a clear band washes the whole surface out to light grey, controls
 * and all, and reads as a bug rather than a backdrop.
 *
 * Motion is transform-only and cheap. The artwork crossfades on track
 * changes (see [FULL_COVER_CROSSFADE_MS]) and drifts with a slow
 * Ken Burns scale, read inside the image's graphics layer so a frame of
 * drift costs a layer transform rather than a recomposition. The blur
 * itself never animates: it is one fixed render effect on the image.
 *
 * @param visible whether a cover is on screen at all — when false the image
 *   is not even mounted, and the layer fades out instead of popping.
 * @param scrimAlpha how much of the scrim to draw, 0 to 1. The portrait
 *   player drives this off the panel collapse: while the full-screen sleeve
 *   poster is up it carries its own gradient, and the hand-off is what keeps
 *   the two from stacking. Landscape keeps it at full strength.
 * @param ambient the landscape shape described above: heavily blurred and
 *   darkened, so it can never be mistaken for a second copy of the sleeve.
 */
@Composable
internal fun FullCoverArtworkBackdrop(
    request: ImageRequest?,
    visible: Boolean,
    scrimAlpha: () -> Float,
    modifier: Modifier = Modifier,
    ambient: Boolean = false,
) {
    val imageAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "fullCoverArtworkBackdrop",
    )
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()

    // The Ken Burns drift: 0 to 1 and back over [FULL_COVER_KEN_BURNS_MS]
    // a leg. Only the state is created here; the value is read in the
    // image's graphics layer below, so the drift never recomposes this
    // backdrop — each frame is a scale on an already-loaded, already
    // blurred layer.
    val drift = rememberInfiniteTransition(label = "fullCoverKenBurns")
    val driftT = drift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = FULL_COVER_KEN_BURNS_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "fullCoverKenBurnsT",
    )
    val baseScale = if (ambient) AMBIENT_BASE_SCALE else 1f

    if (visible || imageAlpha > 0f) {
        Box(
            modifier = modifier
                .background(Color.Black)
                .graphicsLayer { alpha = imageAlpha },
        ) {
            if (visible) {
                // Keyed on the request itself: the sleeve's request is a
                // stable instance per cover (see PlayerArtwork), so a new
                // instance *is* a new cover, and Crossfade keeps the old
                // one drawing under the new for the length of the fade —
                // no hard cut, and no frame of the previous cover flashing
                // back once the new one has landed.
                Crossfade(
                    targetState = request,
                    animationSpec = tween(
                        durationMillis = if (reduceAnimation) 0 else FULL_COVER_CROSSFADE_MS,
                        easing = FastOutSlowInEasing,
                    ),
                    label = "fullCoverArtworkCrossfade",
                    modifier = Modifier.fillMaxSize(),
                ) { currentRequest ->
                    if (currentRequest != null) {
                        AsyncImage(
                            model = currentRequest,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                // The drift, outermost so it transforms
                                // the blurred layer rather than re-running
                                // its render effect every frame.
                                .graphicsLayer {
                                    val scale = if (reduceAnimation) {
                                        baseScale
                                    } else {
                                        baseScale + FULL_COVER_KEN_BURNS_RANGE * driftT.value
                                    }
                                    scaleX = scale
                                    scaleY = scale
                                }
                                // One static render effect, landscape only:
                                // the portrait poster stays sharp.
                                .then(
                                    if (ambient) {
                                        Modifier.blur(AMBIENT_BLUR_RADIUS)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        val s = scrimAlpha().coerceIn(0f, 1f)
                        if (s > 0f) {
                            if (ambient) {
                                // The flat tint comes first: even the
                                // gradient's lightest stop then sits on a
                                // cover already halved in brightness, so a
                                // mostly white sleeve still reads dark
                                // across the whole landscape player.
                                drawRect(
                                    color = Color.Black.copy(alpha = AMBIENT_TINT_ALPHA),
                                    alpha = s,
                                )
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.42f),
                                        0.45f to Color.Black.copy(alpha = 0.48f),
                                        0.72f to Color.Black.copy(alpha = 0.62f),
                                        1f to Color.Black.copy(alpha = 0.82f),
                                    ),
                                    alpha = s,
                                )
                            } else {
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.36f),
                                        0.45f to Color.Black.copy(alpha = 0.30f),
                                        0.72f to Color.Black.copy(alpha = 0.52f),
                                        1f to Color.Black.copy(alpha = 0.74f),
                                    ),
                                    alpha = s,
                                )
                            }
                        }
                    },
            )
        }
    }
}
