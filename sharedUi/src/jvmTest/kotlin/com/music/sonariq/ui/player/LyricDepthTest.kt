package com.music.sonariq.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's depth model: distance carried by dimming, a slight
 * shrink, and a controlled cinematic blur. The blur ladder is gentle
 * and capped — the playing line is always perfectly sharp, one row
 * away is half a dp, and no row ever exceeds six — while dimming keeps
 * its floor (0.45 upcoming, 0.40 sung), so no line ever disappears
 * however far from the focus it sits. Lines already sung fall at
 * least as fast as lines still to come, and upcoming lines brighten
 * as they approach the focus. The model is a continuous function of
 * distance, not a step table.
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
    fun `blur follows the controlled ladder at every distance`() {
        // distance: blur in dp. One ladder for both sides of the focus,
        // capped at the last anchor however far the line sits.
        val expected = listOf(0f, 0.5f, 1.5f, 3.5f, 5f, 6f)
        for (distance in expected.indices) {
            assertEquals(
                "blur at $distance",
                expected[distance],
                LyricDepth.forDistance(distance).blurRadiusDp,
                0.0001f,
            )
            assertEquals(
                "blur at -$distance",
                expected[distance],
                LyricDepth.forDistance(-distance).blurRadiusDp,
                0.0001f,
            )
        }
        // The cap holds far past the end of the song, both directions.
        for (distance in listOf(6, 12, 40, 60)) {
            assertEquals(6f, LyricDepth.forDistance(distance).blurRadiusDp, 0f)
            assertEquals(6f, LyricDepth.forDistance(-distance).blurRadiusDp, 0f)
        }
        // And it never once exceeds the cap or goes negative anywhere.
        for (distance in -60..60) {
            val blur = LyricDepth.forDistance(distance).blurRadiusDp
            assertTrue("blur at $distance", blur in 0f..6f)
        }
    }

    @Test
    fun `blur grows monotonically with distance and stays continuous`() {
        // Fine steps never jump backwards, either side of the focus,
        // and a halfway distance lands strictly between its anchors.
        for (side in listOf(1f, -1f)) {
            var previousBlur = -1f
            var d = 0f
            while (d <= 6.001f) {
                val blur = LyricDepth.forSignedDistance(side * d).blurRadiusDp
                assertTrue("blur at $d", blur >= previousBlur)
                previousBlur = blur
                d += 0.05f
            }
        }
        val half = LyricDepth.forSignedDistance(1.5f)
        assertTrue(half.blurRadiusDp > 0.5f && half.blurRadiusDp < 1.5f)
        val halfFar = LyricDepth.forSignedDistance(-2.5f)
        assertTrue(halfFar.blurRadiusDp > 1.5f && halfFar.blurRadiusDp < 3.5f)
    }

    @Test
    fun `upcoming anchors follow the dimming ladder`() {
        // distance: alpha, scale. The alpha eases to its floor — depth
        // from dimming, not disappearance.
        val expected = listOf(
            1.00f to 1.00f,
            0.70f to 0.97f,
            0.58f to 0.97f,
            0.50f to 0.97f,
            0.47f to 0.97f,
            0.45f to 0.97f,
        )
        for (distance in expected.indices) {
            val depth = LyricDepth.forDistance(distance)
            val (alpha, scale) = expected[distance]
            assertEquals("alpha at $distance", alpha, depth.alpha, 0.0001f)
            assertEquals("scale at $distance", scale, depth.scale, 0.0001f)
        }
    }

    @Test
    fun `sung anchors fall at least as fast as upcoming ones`() {
        // The line just sung is dimmer than the first line below the
        // playing one, and every sung line dims at least as fast as the
        // upcoming line the same distance away.
        val justSung = LyricDepth.forDistance(-1)
        assertEquals(0.5f, justSung.blurRadiusDp, 0.0001f)
        assertEquals(0.62f, justSung.alpha, 0.0001f)
        assertEquals(0.97f, justSung.scale, 0.0001f)
        assertEquals(0.52f, LyricDepth.forDistance(-2).alpha, 0.0001f)
        assertEquals(0.46f, LyricDepth.forDistance(-3).alpha, 0.0001f)
        for (distance in 1..8) {
            val sung = LyricDepth.forDistance(-distance)
            val upcoming = LyricDepth.forDistance(distance)
            assertTrue(
                "sung alpha at $distance",
                sung.alpha <= upcoming.alpha,
            )
        }
    }

    @Test
    fun `depth is continuous between anchors, not stepped`() {
        // Halfway to the next line sits strictly between the two anchors
        // on alpha and scale, either side of the playing line — and the
        // blur travels smoothly through the same travel.
        val halfUp = LyricDepth.forSignedDistance(0.5f)
        assertTrue(halfUp.blurRadiusDp > 0f && halfUp.blurRadiusDp < 0.5f)
        assertTrue(halfUp.alpha < 1f && halfUp.alpha > 0.70f)
        assertTrue(halfUp.scale < 1f && halfUp.scale > 0.97f)
        val halfSung = LyricDepth.forSignedDistance(-0.5f)
        assertTrue(halfSung.blurRadiusDp > 0f && halfSung.blurRadiusDp < 0.5f)
        assertTrue(halfSung.alpha < 1f && halfSung.alpha > 0.62f)
        // Fine steps never jump and never reverse, either side.
        for (side in listOf(1f, -1f)) {
            var previousAlpha = Float.MAX_VALUE
            var previousScale = Float.MAX_VALUE
            var d = 0f
            while (d <= 6.001f) {
                val depth = LyricDepth.forSignedDistance(side * d)
                assertTrue("alpha at $d", depth.alpha <= previousAlpha)
                assertTrue("scale at $d", depth.scale <= previousScale)
                previousAlpha = depth.alpha
                previousScale = depth.scale
                d += 0.05f
            }
        }
    }

    @Test
    fun `far lines never disappear`() {
        // Past the anchors the alpha holds at its readability floor — the
        // no-disappear rule. A line at the far end of the song is quiet,
        // never a ghost, however soft its blur has made it.
        assertEquals(0.45f, LyricDepth.forDistance(5).alpha, 0.0001f)
        assertEquals(0.40f, LyricDepth.forDistance(-5).alpha, 0.0001f)
        assertEquals(
            LyricDepth.forDistance(40).alpha,
            LyricDepth.forDistance(12).alpha,
            0f,
        )
        assertEquals(
            LyricDepth.forDistance(-40).alpha,
            LyricDepth.forDistance(-12).alpha,
            0f,
        )
        for (distance in -60..60) {
            val depth = LyricDepth.forDistance(distance)
            assertTrue(
                "alpha at $distance",
                depth.alpha >= if (distance < 0) 0.40f else 0.45f,
            )
        }
    }

    @Test
    fun `lines stay readable far from the focus`() {
        // Distance dims a line toward its floor and no further: at four
        // away and beyond, a line sits at or near its floor and never
        // drops toward nothing.
        assertEquals(0.47f, LyricDepth.forDistance(4).alpha, 0.0001f)
        assertEquals(0.42f, LyricDepth.forDistance(-4).alpha, 0.0001f)
        assertTrue(
            LyricDepth.forDistance(5).alpha <=
                LyricDepth.forDistance(4).alpha,
        )
        assertTrue(
            LyricDepth.forDistance(-5).alpha <=
                LyricDepth.forDistance(-4).alpha,
        )
        assertTrue(LyricDepth.forDistance(5).alpha >= 0.45f)
        assertTrue(LyricDepth.forDistance(-5).alpha >= 0.40f)
    }

    @Test
    fun `near lines stay nearly sharp and fully readable`() {
        // One away, upcoming: half a dp of blur, still bright, a hair
        // back. The line just sung reads nearly as well.
        val next = LyricDepth.forDistance(1)
        assertEquals(0.5f, next.blurRadiusDp, 0.0001f)
        assertTrue(next.alpha >= 0.70f)
        assertEquals(0.97f, next.scale, 0.0001f)
        assertTrue(LyricDepth.forDistance(-1).alpha >= 0.60f)
    }

    @Test
    fun `between lines the nearest upcoming line stays near focus`() {
        // An instrumental gap anchors the depth on the row that started
        // last — the gap row itself — so the first line after the break
        // sits one step from the focus, not down the ladder with the rest.
        // Whatever the gap, that line and everything after it hold at or
        // above the floor.
        assertTrue(LyricDepth.forDistance(1).alpha >= 0.70f)
        for (distance in 1..12) {
            assertTrue(
                "upcoming alpha at $distance",
                LyricDepth.forDistance(distance).alpha >= 0.45f,
            )
        }
    }

    @Test
    fun `scale settles at the inactive value past the first step`() {
        for (distance in listOf(-40, -2, -1, 1, 2, 40)) {
            assertEquals(
                "scale at $distance",
                0.97f,
                LyricDepth.forDistance(distance).scale,
                0.0001f,
            )
        }
    }

    @Test
    fun `only rows inside the window carry a live blur`() {
        // The panel's performance contract: the window is a small,
        // fixed number of rows either side of the playing line, and the
        // blur at its edge is the strongest the panel ever applies live.
        assertEquals(4, LyricDepth.BLUR_WINDOW_DISTANCE)
        assertEquals(
            5f,
            LyricDepth.forDistance(LyricDepth.BLUR_WINDOW_DISTANCE).blurRadiusDp,
            0.0001f,
        )
    }
}
