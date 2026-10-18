package com.music.sonariq

import androidx.compose.ui.graphics.Color
import com.music.sonariq.ui.player.samplePaletteColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canvas palette sampler: swatches spread over the frame, each the mean
 * of its window.
 *
 * This is the pure half of the animated canvas — [samplePaletteColors] takes
 * raw pixels, not an [androidx.compose.ui.graphics.ImageBitmap], so the whole
 * thing runs on the JVM. What matters is that the first swatches disagree
 * with each other the way a cover's regions do (corners first), and that each
 * swatch actually averages rather than point-sampling.
 */
class CanvasPaletteTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A frame split into four solid quadrants: red, green, blue, white. */
    private fun quadrants(size: Int = 48): Pair<IntArray, Int> {
        val pixels = IntArray(size * size)
        val half = size / 2
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = when {
                    x < half && y < half -> argb(255, 0, 0)
                    x >= half && y < half -> argb(0, 255, 0)
                    x < half && y >= half -> argb(0, 0, 255)
                    else -> argb(255, 255, 255)
                }
            }
        }
        return pixels to size
    }

    private fun Color.channelSums(): Triple<Int, Int, Int> =
        Triple((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

    @Test
    fun `four swatches land in the four corners`() {
        val (pixels, size) = quadrants()

        val palette = samplePaletteColors(pixels, size, size, 4)

        assertEquals(4, palette.size)
        val (r0, g0, b0) = palette[0].channelSums()
        val (r1, g1, b1) = palette[1].channelSums()
        val (r2, g2, b2) = palette[2].channelSums()
        val (r3, g3, b3) = palette[3].channelSums()
        // Top-left red, bottom-right white, top-right green, bottom-left blue.
        assertTrue("swatch 0 red: $r0,$g0,$b0", r0 > 200 && g0 < 60 && b0 < 60)
        assertTrue("swatch 1 white: $r1,$g1,$b1", r1 > 200 && g1 > 200 && b1 > 200)
        assertTrue("swatch 2 green: $r2,$g2,$b2", g2 > 200 && r2 < 60 && b2 < 60)
        assertTrue("swatch 3 blue: $r3,$g3,$b3", b3 > 200 && r3 < 60 && g3 < 60)
    }

    @Test
    fun `a solid frame yields one colour everywhere`() {
        val size = 32
        val pixels = IntArray(size * size) { argb(10, 200, 90) }

        val palette = samplePaletteColors(pixels, size, size, 4)

        palette.forEach { colour ->
            val (r, g, b) = colour.channelSums()
            assertTrue("expected ~(10,200,90), got $r,$g,$b", r < 40 && g > 170 && b in 60..120)
        }
    }

    @Test
    fun `a single swatch comes from the centre`() {
        // Left half black, right half white: the centre straddles the boundary.
        val size = 48
        val pixels = IntArray(size * size) { index ->
            if (index % size < size / 2) argb(0, 0, 0) else argb(255, 255, 255)
        }

        val palette = samplePaletteColors(pixels, size, size, 1)

        assertEquals(1, palette.size)
        val (r, g, b) = palette[0].channelSums()
        assertTrue("centre should be grey, got $r,$g,$b", r in 80..175 && g in 80..175 && b in 80..175)
    }

    @Test
    fun `empty input falls back without crashing`() {
        val palette = samplePaletteColors(IntArray(0), 0, 0, 4)

        assertEquals(4, palette.size)
    }
}
