package com.music.sonariq

import com.music.sonariq.data.scrobbling.PrivateSession
import com.music.sonariq.ui.player.PlayerArtworkGestures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateSessionTest {

    @Test
    fun `the gate allows recording when the session is off`() {
        val gate = PrivateSession.Gate { false }
        assertTrue(gate.mayRecord())
    }

    @Test
    fun `the gate blocks every write while the session is on`() {
        val gate = PrivateSession.Gate { true }
        assertFalse(gate.mayRecord())
    }

    @Test
    fun `the gate follows the supplier, so a toggle mid track flips it`() {
        var suspended = false
        val gate = PrivateSession.Gate { suspended }
        assertTrue(gate.mayRecord())
        suspended = true
        assertFalse(gate.mayRecord())
        suspended = false
        assertTrue(gate.mayRecord())
    }
}

class PlayerArtworkGesturesTest {

    @Test
    fun `a double tap on the right half seeks forward`() {
        assertEquals(
            PlayerArtworkGestures.DOUBLE_TAP_SEEK_MS,
            PlayerArtworkGestures.doubleTapSeekDelta(offsetX = 750f, widthPx = 1000f),
        )
    }

    @Test
    fun `a double tap on the left half seeks back`() {
        assertEquals(
            -PlayerArtworkGestures.DOUBLE_TAP_SEEK_MS,
            PlayerArtworkGestures.doubleTapSeekDelta(offsetX = 250f, widthPx = 1000f),
        )
    }

    @Test
    fun `the exact middle seeks forward`() {
        assertEquals(
            PlayerArtworkGestures.DOUBLE_TAP_SEEK_MS,
            PlayerArtworkGestures.doubleTapSeekDelta(offsetX = 500f, widthPx = 1000f),
        )
    }

    @Test
    fun `an unknown width never seeks forward`() {
        assertEquals(
            -PlayerArtworkGestures.DOUBLE_TAP_SEEK_MS,
            PlayerArtworkGestures.doubleTapSeekDelta(offsetX = 500f, widthPx = 0f),
        )
    }

    @Test
    fun `the step is ten seconds`() {
        assertEquals(10_000L, PlayerArtworkGestures.DOUBLE_TAP_SEEK_MS)
    }
}
