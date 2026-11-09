package com.music.sonariq.ui.player

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The player's liquid motion: the springs every part of a track change or
 * a page change rides, kept in one place so the artwork's slide, the
 * backdrop's crossfade and the glass tint's shift read as one movement
 * rather than three effects that happen to start together.
 *
 * Springs throughout, never fixed-duration tweens: a spring interrupted
 * mid-flight — a second skip landing while the first is still travelling —
 * retargets from where it actually is, so rapid skipping stays fluid
 * instead of restarting a clock.
 */
object LiquidMotion {

    /**
     * The artwork's slide on a track change. Lightly under-damped: the
     * incoming cover settles with one soft arrival rather than a bounce,
     * and at this stiffness the trip takes about half a second.
     */
    val artworkSlide: SpringSpec<Float> = spring(
        dampingRatio = 0.92f,
        stiffness = 300f,
    )

    /** The page crossfade's incoming half, in milliseconds. */
    const val PAGE_FADE_IN_MS = 260

    /** The page crossfade's outgoing half, in milliseconds. */
    const val PAGE_FADE_OUT_MS = 200

    /**
     * How far a page travels as it changes, as a fraction of its width:
     * a third. A page is not a cover — it does not cross the whole screen,
     * it gives way, and a third of the width is enough travel to feel the
     * direction without the incoming page's first frames landing empty.
     */
    const val PAGE_SLIDE_FRACTION = 3

    /**
     * How far the outgoing cover travels against the incoming one, as a
     * fraction of the incoming cover's full trip: the parallax between
     * the two. The old cover is behind, so it moves less — a third of the
     * trip — and dims as it goes.
     */
    const val ARTWORK_PARALLAX = 0.35f
}

/** Which way the queue is travelling, for the artwork's slide. */
enum class TrackSlideDirection { FORWARD, BACKWARD }

/**
 * The slide direction for a track change, read off the queue: a later
 * position is a forward trip, an earlier one is back the way we came.
 * A first track (no previous position) and an unchanged position both
 * read as forward — there is no "back" they could mean.
 */
fun trackSlideDirection(previousIndex: Int, newIndex: Int): TrackSlideDirection =
    if (newIndex < previousIndex) TrackSlideDirection.BACKWARD else TrackSlideDirection.FORWARD

/** The slide direction the artwork under it travels — see [trackSlideDirection]. */
val LocalTrackSlideDirection = staticCompositionLocalOf { TrackSlideDirection.FORWARD }

/** The player's pages, in the order the page dots lay them out. */
enum class PlayerPage { PLAYER, LYRICS, QUEUE }

/**
 * The sign of a page change's travel: +1 toward a later page (content
 * enters from the right), -1 toward an earlier one, 0 for no change.
 * Pure ordinal distance — the pages are a row, and a change slides along
 * the row the way the dots are drawn.
 */
fun playerPageSlideSign(from: PlayerPage, to: PlayerPage): Int = when {
    to.ordinal > from.ordinal -> 1
    to.ordinal < from.ordinal -> -1
    else -> 0
}

/**
 * One page change, as both of the player's shapes (the portrait middle
 * and the landscape right column) perform it: the incoming page slides in
 * from the side the change is travelling toward on the slide transitions'
 * own spring, crossfading as it comes, and the outgoing page leaves the
 * opposite way at the same fraction — the two read as one sheet of glass
 * being pushed along, not as a cut dressed up with a fade.
 */
fun playerPageTransition(from: PlayerPage, to: PlayerPage): ContentTransform {
    val sign = playerPageSlideSign(from, to)
    return (
        slideInHorizontally { width -> sign * width / LiquidMotion.PAGE_SLIDE_FRACTION } +
            fadeIn(tween(LiquidMotion.PAGE_FADE_IN_MS))
        ) togetherWith (
        slideOutHorizontally { width -> -sign * width / LiquidMotion.PAGE_SLIDE_FRACTION } +
            fadeOut(tween(LiquidMotion.PAGE_FADE_OUT_MS))
        )
}
