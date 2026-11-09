package com.music.sonariq.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest

/**
 * A cover that travels: on a track change the outgoing sleeve slides away
 * at a parallax fraction of the trip while the incoming one slides in over
 * it, both on the one spring ([LiquidMotion.artworkSlide]) the rest of the
 * player's track-change motion rides, so the cover, the backdrop's
 * crossfade and the glass tint's shift land as a single movement.
 *
 * The pair arrangement is the backdrop's own (see
 * [FullArtworkBlurBackdrop]): the cover on screen is held until the next
 * one can travel over it, a change arriving mid-flight promotes whatever
 * was arriving to be the base the new one travels from, and a track whose
 * cover is the same instance as the last (the same sleeve again) never
 * travels at all. Under reduced animation the incoming cover simply
 * replaces the outgoing one — no slide, no fade, the cut the setting
 * promises everywhere else.
 *
 * [request] is compared by instance: the player builds one request per
 * cover and keeps it stable (see PlayerArtwork), so a new instance *is* a
 * new cover.
 *
 * [onState] hears only the newest cover's load state — a state from a
 * cover on its way out must not mark the new one loaded or failed.
 * [layerModifier] is applied to each layer's image (the landscape sleeve's
 * draw guard against painting under a playing clip is the one user).
 */
@Composable
internal fun SlidingArtworkImage(
    request: ImageRequest,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
    layerModifier: Modifier = Modifier,
) {
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val direction = LocalTrackSlideDirection.current
    val target by rememberUpdatedState(request)
    val currentOnState by rememberUpdatedState(onState)

    var shown by remember { mutableStateOf(request) }
    var incoming by remember { mutableStateOf<ImageRequest?>(null) }
    var travel by remember { mutableStateOf(TrackSlideDirection.FORWARD) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(request) {
        val next = request
        if (incoming == null && next == shown) return@LaunchedEffect
        // Mid-flight: what was arriving becomes the base the new cover
        // travels from, so rapid skips never restart from two covers back.
        incoming?.let { shown = it }
        incoming = null
        val current = shown
        if (next == current || reduceAnimation) {
            shown = next
            return@LaunchedEffect
        }
        travel = direction
        incoming = next
        progress.snapTo(0f)
        progress.animateTo(1f, LiquidMotion.artworkSlide)
        shown = next
        incoming = null
    }

    val sign = if (travel == TrackSlideDirection.FORWARD) 1f else -1f
    val travelling = incoming != null

    Box(modifier = modifier.clipToBounds()) {
        ArtworkLayer(
            request = shown,
            isTarget = shown == target,
            onState = currentOnState,
            layerModifier = layerModifier,
            contentScale = contentScale,
        ) {
            // Read in the layer, not in composition: a frame of the slide
            // is a layer transform, never a recomposition — the same
            // arrangement as the backdrop's crossfade.
            if (travelling) {
                val p = progress.value
                translationX = -sign * p * size.width * LiquidMotion.ARTWORK_PARALLAX
                alpha = 1f - p * 0.5f
            }
        }
        incoming?.let { next ->
            ArtworkLayer(
                request = next,
                isTarget = next == target,
                onState = currentOnState,
                layerModifier = layerModifier,
                contentScale = contentScale,
            ) {
                val p = progress.value
                translationX = sign * (1f - p) * size.width
                alpha = 0.25f + 0.75f * p
            }
        }
    }
}

/** One cover of [SlidingArtworkImage]: the image, transformed by [layer]. */
@Composable
private fun ArtworkLayer(
    request: ImageRequest,
    isTarget: Boolean,
    onState: ((AsyncImagePainter.State) -> Unit)?,
    layerModifier: Modifier,
    contentScale: ContentScale,
    layer: GraphicsLayerScope.() -> Unit,
) {
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = contentScale,
        onState = { state -> if (isTarget) onState?.invoke(state) },
        modifier = Modifier
            .fillMaxSize()
            .then(layerModifier)
            .graphicsLayer(layer),
    )
}
