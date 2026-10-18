package com.music.sonariq.ui.player

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Player artwork gestures.
 *
 * Swiping the artwork for next/previous already exists — it is
 * `skipSwipeGesture` in NowPlayingScreen (sharedUi), with the damped
 * spring-back and the same haptics as the transport buttons. This file adds
 * the one gesture the player was missing without touching that one:
 * **double-tap the artwork's left or right half to seek** ∓10 seconds.
 *
 * Layering contract (Phase 2 appends [artworkDoubleTapSeek] *after*
 * `.then(skipSwipeGesture)` on the portrait sleeve, around line 2295 of
 * NowPlayingScreen.kt): the horizontal-drag detector keeps winning once the
 * finger moves past slop, so a swipe still skips and never seeks, and the
 * double-tap detector only fires on taps the drag detector left alone — the
 * standard two-detector stack. The single-tap artwork behaviour (the
 * visualiser toggle) is registered on the sleeve itself and is unaffected:
 * this detector consumes nothing on a single tap.
 *
 * New AppSettings key (Phase 2 adds, same pattern as the existing settings):
 * - `artworkGesturesEnabled`: Boolean, default `true`
 */
object PlayerArtworkGestures {

    /** Seek step for a double-tap: ten seconds either way. */
    const val DOUBLE_TAP_SEEK_MS = 10_000L

    /**
     * Which way a double-tap at [offsetX] seeks on artwork [widthPx] wide:
     * the left half rewinds, the right half fast-forwards. Pure, so the
     * boundary is pinned by tests rather than by thumb.
     */
    fun doubleTapSeekDelta(offsetX: Float, widthPx: Float): Long =
        if (widthPx > 0f && offsetX >= widthPx / 2f) DOUBLE_TAP_SEEK_MS else -DOUBLE_TAP_SEEK_MS
}

/**
 * Double-tap the artwork's sides to seek. [onSeekBy] receives the signed
 * millisecond delta; the host adds it to the current position and clamps —
 * this layer only decides the sign, never the playhead.
 */
@Composable
fun Modifier.artworkDoubleTapSeek(
    enabled: Boolean,
    onSeekBy: (deltaMs: Long) -> Unit,
): Modifier {
    if (!enabled) return this
    var widthPx by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    return this
        .onSizeChanged { widthPx = it.width.toFloat() }
        .pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = { offset ->
                    val delta = PlayerArtworkGestures.doubleTapSeekDelta(offset.x, widthPx)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSeekBy(delta)
                },
            )
        }
}
