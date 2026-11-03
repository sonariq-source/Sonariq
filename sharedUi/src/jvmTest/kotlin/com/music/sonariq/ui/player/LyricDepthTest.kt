package com.music.sonariq.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's depth model: distance carried by dimming and a slight
 * shrink only. The model has no text-blur value to apply, so every line
 * stays sharp; the dimming keeps its floor (0.45 upcoming, 0.40 sung),
 * so no line ever disappears however far from the focus it sits. Lines
 * already sung fall at least as fast as lines still to come, and
 * upcoming lines brighten as they approach the focus. The model is a
 * continuous function of distance, not a step table.
 */
class LyricDepthTest {

    @Test
    fun `the playing line itself is crisp and full`() {
        val depth = LyricDepth.forDistance(0)
        assertEquals(1f, depth.alpha, 0f)
        assertEquals(1f, depth.scale, 0f)
    }

    @Test
    fun `the depth model carries no lyric text blur`() {
        val fieldNames = LyricDepth.Depth::class.java.declaredFields.map { it.name }
        assertFalse(
            "lyric depth fields must not carry blur: $fieldNames",
            fieldNames.any { it.contains("blur", ignoreCase = true) },
        )
        // Sharpness does not vary with distance either side of the focus:
        // distance changes only how bright and how large a line is.
        for (distance in -60..60) {
            val depth = LyricDepth.forDistance(distance)
            assertTrue("alpha at $distance", depth.alpha in 0.40f..1f)
            assertTrue("scale at $distance", depth.scale in 0.97f..1f)
        }
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
        // on alpha and scale, either side of the playing line.
        val halfUp = LyricDepth.forSignedDistance(0.5f)
        assertTrue(halfUp.alpha < 1f && halfUp.alpha > 0.70f)
        assertTrue(halfUp.scale < 1f && halfUp.scale > 0.97f)
        val halfSung = LyricDepth.forSignedDistance(-0.5f)
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
        // never a ghost.
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
    fun `near lines stay fully readable`() {
        // One away, upcoming: still bright and only a hair back. The
        // line just sung reads nearly as well.
        val next = LyricDepth.forDistance(1)
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
    fun `depth settles at its floors rather than accumulating with distance`() {
        // Past the last anchor there is nothing more to take away: a
        // line forty rows off reads exactly like one five rows off, on
        // both sides of the focus.
        for (distance in listOf(5, 6, 12, 40, 60)) {
            assertEquals(0.45f, LyricDepth.forDistance(distance).alpha, 0.0001f)
            assertEquals(0.40f, LyricDepth.forDistance(-distance).alpha, 0.0001f)
            assertEquals(0.97f, LyricDepth.forDistance(distance).scale, 0.0001f)
            assertEquals(0.97f, LyricDepth.forDistance(-distance).scale, 0.0001f)
        }
    }
}
