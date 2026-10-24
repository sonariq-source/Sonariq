package com.music.sonariq.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's depth model: blur is bounded to a window around the playing
 * line and quantised to a few fixed radii, and past the window a line is
 * carried by alpha and scale alone. These are the properties the panel's
 * frame cost rests on, so they are pinned here rather than left to the
 * eye.
 */
class LyricDepthTest {

    @Test
    fun `the playing line itself is crisp and full`() {
        val depth = LyricDepth.forDistance(0)
        assertEquals(0f, depth.blurRadiusDp, 0f)
        assertEquals(1f, depth.alpha, 0f)
        assertEquals(1f, depth.scale, 0f)
        assertTrue(depth.inBlurWindow)
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
    fun `blur stays inside the window and is zero outside it`() {
        for (distance in -LyricDepth.BLUR_WINDOW..LyricDepth.BLUR_WINDOW) {
            assertTrue("distance $distance", LyricDepth.forDistance(distance).inBlurWindow)
        }
        for (distance in listOf(
            LyricDepth.BLUR_WINDOW + 1,
            LyricDepth.BLUR_WINDOW + 2,
            12,
            60,
            -LyricDepth.BLUR_WINDOW - 1,
            -60,
        )) {
            val depth = LyricDepth.forDistance(distance)
            assertFalse("distance $distance", depth.inBlurWindow)
            assertEquals("distance $distance", 0f, depth.blurRadiusDp, 0f)
        }
    }

    @Test
    fun `blur rises gently through the window and caps at a whisper`() {
        val radii = (0..LyricDepth.BLUR_WINDOW).map { LyricDepth.forDistance(it).blurRadiusDp }
        // The exact curve: a whisper beside the playing line, never a smear.
        assertEquals(listOf(0f, 0.8f, 1.6f, 2.8f, 4.5f), radii)
        // Strictly rising through the window.
        for (index in 1 until radii.size) {
            assertTrue("step $index", radii[index] > radii[index - 1])
        }
        // And rising gently: the steps widen only slowly, never steeply.
        for (index in 2 until radii.size) {
            assertTrue(
                "step $index",
                radii[index] - radii[index - 1] >= radii[index - 1] - radii[index - 2],
            )
        }
        // The hard cap holds at every distance, inside the window or out:
        // no line is ever blurred past the point of readability.
        for (distance in -40..40) {
            assertTrue(
                "distance $distance",
                LyricDepth.forDistance(distance).blurRadiusDp <= 4.5f,
            )
        }
    }

    @Test
    fun `blur takes only a handful of discrete values`() {
        val radii = (-40..40).map { LyricDepth.forDistance(it).blurRadiusDp }.toSet()
        // The zero outside the window plus one step per distance inside it:
        // a small distance change can never land on an in-between radius.
        assertEquals(LyricDepth.BLUR_WINDOW + 1, radii.size)
        assertTrue(0f in radii)
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
        // behind: most of their brightness survives and their blur is a
        // whisper at most.
        for (distance in 1..2) {
            val depth = LyricDepth.forDistance(distance)
            assertTrue("distance $distance", depth.alpha >= 0.65f)
            assertTrue("distance $distance", depth.blurRadiusDp <= 2f)
            assertTrue("distance $distance", depth.scale >= 0.99f)
        }
        assertEquals(0.8f, LyricDepth.forDistance(1).blurRadiusDp, 0f)
        assertEquals(1.6f, LyricDepth.forDistance(2).blurRadiusDp, 0f)
    }
}

/**
 * The landing of a sung word or syllable: a pop that starts as its span
 * begins, completes inside the span, and then holds — it never creeps
 * through the pause after the word, and nothing moves before the word is
 * sung at all.
 */
class LyricWordLandingTest {

    private val start = 10_000L
    private val end = 11_000L

    @Test
    fun `nothing lands before the piece starts`() {
        assertEquals(0f, LyricWordLanding.progress(start - 1, start, end), 0f)
        assertEquals(0f, LyricWordLanding.glowPulseAt(start - 1, start, end), 0f)
        // The scale rests at its start value rather than somewhere mid-pop.
        assertEquals(
            LyricWordLanding.START_SCALE,
            LyricWordLanding.scaleAt(start - 1, start, end),
            0f,
        )
    }

    @Test
    fun `the pop starts small and the pulse starts full`() {
        assertEquals(LyricWordLanding.START_SCALE, LyricWordLanding.scaleAt(start, start, end), 0f)
        assertEquals(1f, LyricWordLanding.glowPulseAt(start, start, end), 0f)
    }

    @Test
    fun `scale rises monotonically and reaches one inside the piece`() {
        var previous = 0f
        var at = start
        while (at <= end) {
            val scale = LyricWordLanding.scaleAt(at, start, end)
            assertTrue("at $at", scale >= previous)
            assertTrue("at $at", scale <= 1f)
            previous = scale
            at += 20
        }
        // A one-second piece has long landed by its end.
        assertEquals(1f, LyricWordLanding.scaleAt(end - 1, start, end), 0f)
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
            assertEquals("at $at", 1f, LyricWordLanding.scaleAt(at, start, end), 0f)
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
        val midway = LyricWordLanding.scaleAt(20_050L, shortStart, shortEnd)
        assertTrue(midway > LyricWordLanding.START_SCALE && midway < 1f)
    }

    @Test
    fun `a held piece lands early and then holds`() {
        val heldStart = 30_000L
        val heldEnd = 33_000L
        // Long before a three-second hold ends, the pop is over.
        assertEquals(1f, LyricWordLanding.progress(heldStart + 1_000, heldStart, heldEnd), 0f)
        assertEquals(0f, LyricWordLanding.glowPulseAt(heldStart + 1_000, heldStart, heldEnd), 0f)
    }
}
