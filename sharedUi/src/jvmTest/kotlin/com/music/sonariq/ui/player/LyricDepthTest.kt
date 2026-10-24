package com.music.sonariq.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's depth model: distance depth-of-field, the way the reference
 * view does it — blur, dimming and a slight shrink travelling together,
 * never a hard window and never a strong blur on a near line. The playing
 * line is sharp, full and full size; the line next to it is only slightly
 * softened and fully readable; further lines defocus more than they dim,
 * because dimming has a floor — no line ever disappears, however far from
 * the focus it sits. Lines already sung fall at least as fast as lines
 * still to come, and upcoming lines brighten as they approach the focus.
 * The model is a continuous function of distance, not a step table.
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
    fun `upcoming anchors follow the reference ladder`() {
        // distance: blur, alpha, scale. The blur climbs steeply while the
        // alpha eases to its floor — depth from defocus, not disappearance.
        val expected = listOf(
            Triple(0f, 1.00f, 1.00f),
            Triple(1.5f, 0.70f, 0.97f),
            Triple(4f, 0.58f, 0.97f),
            Triple(7f, 0.50f, 0.97f),
            Triple(9f, 0.47f, 0.97f),
            Triple(10f, 0.45f, 0.97f),
        )
        for (distance in expected.indices) {
            val depth = LyricDepth.forDistance(distance)
            val (blur, alpha, scale) = expected[distance]
            assertEquals("blur at $distance", blur, depth.blurRadiusDp, 0.0001f)
            assertEquals("alpha at $distance", alpha, depth.alpha, 0.0001f)
            assertEquals("scale at $distance", scale, depth.scale, 0.0001f)
        }
    }

    @Test
    fun `sung anchors fall at least as fast as upcoming ones`() {
        // The line just sung is already as soft as the second line below
        // the playing one, and dimmer than the first line below it.
        val justSung = LyricDepth.forDistance(-1)
        assertEquals(4f, justSung.blurRadiusDp, 0.0001f)
        assertEquals(0.62f, justSung.alpha, 0.0001f)
        assertEquals(0.97f, justSung.scale, 0.0001f)
        assertEquals(6f, LyricDepth.forDistance(-2).blurRadiusDp, 0.0001f)
        assertEquals(0.52f, LyricDepth.forDistance(-2).alpha, 0.0001f)
        assertEquals(8f, LyricDepth.forDistance(-3).blurRadiusDp, 0.0001f)
        assertEquals(0.46f, LyricDepth.forDistance(-3).alpha, 0.0001f)
        for (distance in 1..8) {
            val sung = LyricDepth.forDistance(-distance)
            val upcoming = LyricDepth.forDistance(distance)
            assertTrue(
                "sung blur at $distance",
                sung.blurRadiusDp >= upcoming.blurRadiusDp,
            )
            assertTrue(
                "sung alpha at $distance",
                sung.alpha <= upcoming.alpha,
            )
        }
    }

    @Test
    fun `depth is continuous between anchors, not stepped`() {
        // Halfway to the next line sits strictly between the two anchors
        // on every value, either side of the playing line.
        val halfUp = LyricDepth.forSignedDistance(0.5f)
        assertTrue(halfUp.blurRadiusDp > 0f && halfUp.blurRadiusDp < 1.5f)
        assertTrue(halfUp.alpha < 1f && halfUp.alpha > 0.70f)
        assertTrue(halfUp.scale < 1f && halfUp.scale > 0.97f)
        val halfSung = LyricDepth.forSignedDistance(-0.5f)
        assertTrue(halfSung.blurRadiusDp > 0f && halfSung.blurRadiusDp < 4f)
        assertTrue(halfSung.alpha < 1f && halfSung.alpha > 0.62f)
        // Fine steps never jump and never reverse, either side.
        for (side in listOf(1f, -1f)) {
            var previousBlur = -1f
            var previousAlpha = Float.MAX_VALUE
            var previousScale = Float.MAX_VALUE
            var d = 0f
            while (d <= 6.001f) {
                val depth = LyricDepth.forSignedDistance(side * d)
                assertTrue("blur at $d", depth.blurRadiusDp >= previousBlur)
                assertTrue("alpha at $d", depth.alpha <= previousAlpha)
                assertTrue("scale at $d", depth.scale <= previousScale)
                previousBlur = depth.blurRadiusDp
                previousAlpha = depth.alpha
                previousScale = depth.scale
                d += 0.05f
            }
        }
    }

    @Test
    fun `far lines never disappear and blur caps out`() {
        // Past the anchors the alpha holds at its readability floor — the
        // no-disappear rule. A line at the far end of the song is soft and
        // quiet, never a ghost.
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
                "blur at $distance",
                depth.blurRadiusDp <= 10f,
            )
            assertTrue(
                "alpha at $distance",
                depth.alpha >= if (distance < 0) 0.40f else 0.45f,
            )
        }
    }

    @Test
    fun `lines stay readable at and past the blur window edge`() {
        // The window bounds the live blur, not the line's presence: at its
        // edge and beyond, a line sits at or near its floor and never
        // drops toward nothing.
        val window = LyricDepth.BLUR_WINDOW_DISTANCE
        assertEquals(0.47f, LyricDepth.forDistance(window).alpha, 0.0001f)
        assertEquals(0.42f, LyricDepth.forDistance(-window).alpha, 0.0001f)
        assertTrue(
            LyricDepth.forDistance(window + 1).alpha <=
                LyricDepth.forDistance(window).alpha,
        )
        assertTrue(
            LyricDepth.forDistance(-window - 1).alpha <=
                LyricDepth.forDistance(-window).alpha,
        )
        assertTrue(LyricDepth.forDistance(window + 1).alpha >= 0.45f)
        assertTrue(LyricDepth.forDistance(-window - 1).alpha >= 0.40f)
    }

    @Test
    fun `near lines stay fully readable`() {
        // One away, upcoming: only slightly softened, still bright, a hair
        // back — the reference's "slight softening only, fully readable".
        // The line just sung reads nearly as well.
        val next = LyricDepth.forDistance(1)
        assertTrue(next.blurRadiusDp <= 2f)
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
}
