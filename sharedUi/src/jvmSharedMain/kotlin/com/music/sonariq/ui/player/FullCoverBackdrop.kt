package com.music.sonariq.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * The full-cover artwork backdrop: the track's cover, sharp and cropped to
 * fill the player edge to edge, under a legibility scrim.
 *
 * It always uses the sleeve's own [ImageRequest], so it is the same decode
 * the sleeve already asked Coil for — a memory-cache hit, never a second
 * download and never a second decode of a different size.
 *
 * Nothing here animates per frame. The image crossfades on track changes via
 * [visible]; the scrim is read through [scrimAlpha] in the draw phase, so
 * driving it off the sleeve's collapse costs no recomposition.
 *
 * @param visible whether a cover is on screen at all — when false the image
 *   is not even mounted, and the layer fades out instead of popping.
 * @param scrimAlpha how much of the scrim to draw, 0 to 1. The portrait
 *   player drives this off the panel collapse: while the full-screen sleeve
 *   poster is up it carries its own gradient, and the hand-off is what keeps
 *   the two from stacking. Landscape keeps it at full strength.
 */
@Composable
internal fun FullCoverArtworkBackdrop(
    request: ImageRequest?,
    visible: Boolean,
    scrimAlpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    val imageAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "fullCoverArtworkBackdrop",
    )
    if (visible || imageAlpha > 0f) {
        Box(
            modifier = modifier
                .background(Color.Black)
                .graphicsLayer { alpha = imageAlpha },
        ) {
            if (visible) {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        val s = scrimAlpha().coerceIn(0f, 1f)
                        if (s > 0f) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.22f),
                                    0.32f to Color.Transparent,
                                    0.58f to Color.Black.copy(alpha = 0.32f),
                                    1f to Color.Black.copy(alpha = 0.64f),
                                ),
                                alpha = s,
                            )
                        }
                    },
            )
        }
    }
}
