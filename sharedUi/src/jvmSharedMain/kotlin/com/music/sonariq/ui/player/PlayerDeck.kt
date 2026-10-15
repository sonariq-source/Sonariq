package com.music.sonariq.ui.player

/**
 * The Now Playing lower deck, split out of NowPlayingScreen.kt (Sonariq
 * stage 2): the deck scaffold is self-contained — everything it needs
 * arrives as parameters — so it lives here while the screen keeps its
 * artwork / credits / controls choreography.
 *
 * Fork of https://github.com/kushagrasinghx/BitChord (GPL-3.0).
 */

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/** One shared travel time keeps the deck, credits and stats moving as a unit. */
internal const val SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS = 420
/** The deck's fade back in on returning from lyrics or the queue. */
internal const val PLAYER_DECK_FADE_IN_MS = 700
// The lower glass and the controls use one piece of motion. Keeping the slide
// specification here prevents a busy frame from exposing slightly different
// timings between the surface and the player drawn over it.
internal fun playerDeckSlideIn() = slideInVertically(
    animationSpec = tween(
        SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
        easing = FastOutSlowInEasing,
    ),
    initialOffsetY = { it },
)

internal fun playerDeckSlideOut() = slideOutVertically(
    animationSpec = tween(
        SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
        easing = FastOutSlowInEasing,
    ),
    targetOffsetY = { it },
)

/**
 * One lower-deck component, with one animation value owning both its pixels
 * and the space it occupies.
 *
 * A slide-only AnimatedVisibility kept the full height until its last frame;
 * combining slide and size transitions fixed that but let two transition
 * layers overlap when the presentation mode changed while returning to main.
 * This layout reports a fraction of the deck's real height instead. Since the
 * weighted player/panel above it consumes the remainder, the deck's top and
 * everything drawn from it move together. The subtree is removed only after
 * the exit reaches zero, retaining neither an invisible player nor a duplicate.
 *
 * [fadeIn], read at the moment [visible] turns true, reveals the deck at its
 * full height at once and fades it up instead. Returning to the main player
 * with the deck stood down, the sleeve sized itself against the deck's missing
 * height and was then squeezed as the deck slid up under it — the artwork
 * swelling to fill the screen and shrinking back over one 420ms trip.
 */
@Composable
internal fun SlidingPlayerDeck(
    visible: Boolean,
    reveal: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
    fadeIn: () -> Boolean = { false },
    content: @Composable () -> Unit,
) {
    var mounted by remember { mutableStateOf(visible) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(visible) {
        if (visible) {
            if (fadeIn() && reveal.value < 1f) {
                alpha.snapTo(0f)
                reveal.snapTo(1f)
                mounted = true
                // Slower than the slide, and linear: on FastOutSlowIn the deck
                // was most of the way up within a few frames and read as
                // simply being there.
                alpha.animateTo(
                    1f,
                    tween(PLAYER_DECK_FADE_IN_MS, easing = LinearEasing),
                )
            } else {
                // Hidden part-way through a fade: the slide starts from
                // wherever the height is, with the pixels whole again.
                alpha.snapTo(1f)
                mounted = true
                reveal.animateTo(
                    1f,
                    tween(
                        SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        } else {
            reveal.animateTo(
                0f,
                tween(
                    SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
            mounted = false
        }
    }

    if (mounted) {
        Box(
            modifier = modifier
                .graphicsLayer { this.alpha = alpha.value }
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0))
                    val animatedHeight = (placeable.height * reveal.value)
                        .roundToInt()
                        .coerceIn(constraints.minHeight, constraints.maxHeight)
                    layout(placeable.width, animatedHeight) {
                        // No second translation: the weighted sibling above moves
                        // this component's origin as its reported height changes.
                        placeable.placeRelative(0, 0)
                    }
                },
        ) {
            content()
        }
    }
}
