package com.music.sonariq.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's depth model: the words are never blurred at any distance —
 * the blur lives in the backdrop behind the panel, and a line is carried
 * by alpha and scale alone. These are the properties the panel's look
 * rests on, so they are pinned here rather than left to the eye.
 */
class LyricDepthTest {

    @Test
    fun `the playing line itself is crisp and full`() {
        val depth = LyricDepth.forDistance(0)
        assertEquals(0f, depth.blurRadiusDp, 0f)
        assertEquals(1f, depth.alpha, 0f)
        assertEquals(1f, depth.scale, 0f)
    }

    @Test
    fun `depth is symmetric either side of the playing line`() {
        for (distance in -8..8) {
            assertEquals(
                "distance $distance",
                LyricDepth.forDistance(distance),
                LyricDepth.forDistance(-distance),
            )
        }
    }

    @Test
    fun `no line is ever blurred`() {
        // The whole of the rule, at every distance the list can produce:
        // the words stay crisp and only the backdrop behind them is soft.
        for (distance in -60..60) {
            assertEquals(
                "distance $distance",
                0f,
                LyricDepth.forDistance(distance).blurRadiusDp,
                0f,
            )
        }
    }

    @Test
    fun `alpha falls monotonically to its floor`() {
        var previous = Float.MAX_VALUE
        for (distance in 0..40) {
            val alpha = LyricDepth.forDistance(distance).alpha
            assertTrue("distance $distance", alpha <= previous)
            assertTrue("distance $distance", alpha > 0f)
            previous = alpha
        }
        // The floor is reached and then held, never crossed.
        assertEquals(
            LyricDepth.forDistance(40).alpha,
            LyricDepth.forDistance(12).alpha,
            0f,
        )
        assertTrue(LyricDepth.forDistance(40).alpha >= 0.35f)
    }

    @Test
    fun `scale falls monotonically to the inactive floor`() {
        var previous = Float.MAX_VALUE
        for (distance in 0..40) {
            val scale = LyricDepth.forDistance(distance).scale
            assertTrue("distance $distance", scale <= previous)
            assertTrue("distance $distance", scale < 1f || distance == 0)
            previous = scale
        }
        assertEquals(0.98f, LyricDepth.forDistance(40).scale, 0.0001f)
    }

    @Test
    fun `near lines stay legible`() {
        // The two lines around the playing one are for reading ahead and
        // behind: most of their brightness survives, and they sit near
        // enough full size that the fall reads as dimming, not shrinking.
        for (distance in 1..2) {
            val depth = LyricDepth.forDistance(distance)
            assertTrue("distance $distance", depth.alpha >= 0.65f)
            assertTrue("distance $distance", depth.scale >= 0.99f)
        }
    }
}

/**
 * The landing of a sung word or syllable: a gentle brighten — a glow
 * pulse that starts as its span begins, settles inside the span, and then
 * holds — it never creeps through the pause after the word, and nothing
 * glows before the word is sung at all. The words themselves never move
 * or scale for it; only the light on the bloom copy changes.
 */
class LyricWordLandingTest {

    private val start = 10_000L
    private val end = 11_000L

    @Test
    fun `nothing lands before the piece starts`() {
        assertEquals(0f, LyricWordLanding.progress(start - 1, start, end), 0f)
        assertEquals(0f, LyricWordLanding.glowPulseAt(start - 1, start, end), 0f)
    }

    @Test
    fun `the pulse starts full as the piece begins`() {
        assertEquals(1f, LyricWordLanding.glowPulseAt(start, start, end), 0f)
    }

    @Test
    fun `progress rises monotonically and completes inside the piece`() {
        var previous = 0f
        var at = start
        while (at <= end) {
            val progress = LyricWordLanding.progress(at, start, end)
            assertTrue("at $at", progress >= previous)
            assertTrue("at $at", progress <= 1f)
            previous = progress
            at += 20
        }
        // A one-second piece has long landed by its end.
        assertEquals(1f, LyricWordLanding.progress(end - 1, start, end), 0f)
    }

    @Test
    fun `the pulse settles as the piece lands`() {
        var previous = Float.MAX_VALUE
        var at = start
        while (at < end) {
            val pulse = LyricWordLanding.glowPulseAt(at, start, end)
            assertTrue("at $at", pulse <= previous)
            assertTrue("at $at", pulse >= 0f)
            previous = pulse
            at += 20
        }
        assertEquals(0f, LyricWordLanding.glowPulseAt(end - 1, start, end), 0f)
    }

    @Test
    fun `a finished piece holds at rest through the pause after it`() {
        for (at in listOf(end, end + 1, end + 500, end + 60_000)) {
            assertEquals("at $at", 1f, LyricWordLanding.progress(at, start, end), 0f)
            assertEquals("at $at", 0f, LyricWordLanding.glowPulseAt(at, start, end), 0f)
        }
    }

    @Test
    fun `a short piece lands over its own span, not a longer window`() {
        val shortStart = 20_000L
        val shortEnd = 20_100L
        // Halfway through a 100 ms piece the landing is halfway done, and
        // by its end it has completed exactly.
        assertEquals(0.5f, LyricWordLanding.progress(20_050L, shortStart, shortEnd), 0.0001f)
        assertEquals(1f, LyricWordLanding.progress(shortEnd, shortStart, shortEnd), 0f)
    }

    @Test
    fun `a held piece lands early and then holds`() {
        val heldStart = 30_000L
        val heldEnd = 33_000L
        // Long before a three-second hold ends, the landing is over.
        assertEquals(1f, LyricWordLanding.progress(heldStart + 1_000, heldStart, heldEnd), 0f)
        assertEquals(0f, LyricWordLanding.glowPulseAt(heldStart + 1_000, heldStart, heldEnd), 0f)
    }
}
