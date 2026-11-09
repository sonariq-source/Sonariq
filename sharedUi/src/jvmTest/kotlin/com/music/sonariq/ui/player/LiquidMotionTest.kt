package com.music.sonariq.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LiquidMotionTest {

    @Test
    fun advancingInTheQueueSlidesForward() {
        assertEquals(TrackSlideDirection.FORWARD, trackSlideDirection(previousIndex = 2, newIndex = 3))
        assertEquals(TrackSlideDirection.FORWARD, trackSlideDirection(previousIndex = 0, newIndex = 9))
    }

    @Test
    fun goingBackInTheQueueSlidesBackward() {
        assertEquals(TrackSlideDirection.BACKWARD, trackSlideDirection(previousIndex = 3, newIndex = 2))
        assertEquals(TrackSlideDirection.BACKWARD, trackSlideDirection(previousIndex = 9, newIndex = 0))
    }

    @Test
    fun aFirstTrackAndAnUnchangedPositionReadAsForward() {
        // No previous position exists for the first track, and a repeated
        // track has not travelled at all — neither has a "back" to mean.
        assertEquals(TrackSlideDirection.FORWARD, trackSlideDirection(previousIndex = -1, newIndex = 0))
        assertEquals(TrackSlideDirection.FORWARD, trackSlideDirection(previousIndex = 2, newIndex = 2))
    }

    @Test
    fun pageChangesSlideAlongTheDotsOrder() {
        assertEquals(1, playerPageSlideSign(PlayerPage.PLAYER, PlayerPage.LYRICS))
        assertEquals(1, playerPageSlideSign(PlayerPage.LYRICS, PlayerPage.QUEUE))
        assertEquals(1, playerPageSlideSign(PlayerPage.PLAYER, PlayerPage.QUEUE))
        assertEquals(-1, playerPageSlideSign(PlayerPage.QUEUE, PlayerPage.LYRICS))
        assertEquals(-1, playerPageSlideSign(PlayerPage.LYRICS, PlayerPage.PLAYER))
        assertEquals(-1, playerPageSlideSign(PlayerPage.QUEUE, PlayerPage.PLAYER))
        assertEquals(0, playerPageSlideSign(PlayerPage.LYRICS, PlayerPage.LYRICS))
    }
}
