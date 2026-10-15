package com.music.sonariq.ui.theme

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Dominant-color extraction from album artwork for the dynamic theme.
 *
 * Pure function with no dependencies: the artwork is downscaled to ~48x48 so
 * the histogram stays tiny, each pixel is bucketed to 4 bits per channel (a
 * 12-bit, 4096-entry histogram), and the most frequent bucket that isn't
 * near-black, near-white, or desaturated wins. The winner gets a slight
 * saturation boost so flat or hazy covers still read as an accent.
 *
 * Never throws: a null-safe, empty, or fully grayscale bitmap yields the
 * brand lime rather than crashing the theme.
 */
fun dominantColor(bitmap: Bitmap): Int = runCatching {
    val size = 48
    val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
    try {
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        // Two passes: first rejects desaturated buckets, second keeps them so
        // a grayscale cover still tints the theme instead of falling back.
        dominantBucket(pixels, rejectDesaturated = true)
            ?: dominantBucket(pixels, rejectDesaturated = false)
            ?: FALLBACK_ARGB
    } finally {
        if (scaled !== bitmap) scaled.recycle()
    }
}.getOrDefault(FALLBACK_ARGB)

/** Most frequent 12-bit bucket, or null when every pixel was filtered out. */
private fun dominantBucket(pixels: IntArray, rejectDesaturated: Boolean): Int? {
    val counts = HashMap<Int, Int>(256)
    var bestBucket = 0
    var bestCount = 0
    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val mx = max(max(r, g), b)
        val mn = min(min(r, g), b)
        // Skip near-black (shadows, letterboxing) and near-white (glare,
        // paper sleeves): they win on area but read as no accent at all.
        if (mx < NEAR_BLACK || mn > NEAR_WHITE) continue
        // Skip grays and mud: an accent has to carry some hue.
        if (rejectDesaturated && mx - mn < MIN_SATURATION_SPREAD) continue
        val bucket = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
        val n = (counts[bucket] ?: 0) + 1
        counts[bucket] = n
        if (n > bestCount) {
            bestCount = n
            bestBucket = bucket
        }
    }
    if (bestCount == 0) return null
    // Expand the 4-bit nibbles back to full channels.
    val r = (bestBucket shr 8) and 0xF
    val g = (bestBucket shr 4) and 0xF
    val b = bestBucket and 0xF
    val argb = Color.argb(
        0xFF,
        (r shl 4) or r,
        (g shl 4) or g,
        (b shl 4) or b,
    )
    return boostSaturation(argb)
}

/**
 * A slight saturation lift with the brightness kept inside a band that stays
 * legible as a theme primary in both dark and light mode (the theme itself
 * darkens it further for light mode — see Theme.kt).
 */
private fun boostSaturation(argb: Int): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(argb, hsv)
    hsv[1] = min(1f, hsv[1] * 1.25f + 0.05f)
    hsv[2] = hsv[2].coerceIn(0.35f, 0.95f)
    return Color.HSVToColor(argb ushr 24, hsv)
}

/** Channel ceiling below which a pixel counts as near-black. */
private const val NEAR_BLACK = 0x28

/** Channel floor above which a pixel counts as near-white. */
private const val NEAR_WHITE = 0xD8

/** Minimum max-min channel spread for a bucket to count as chromatic. */
private const val MIN_SATURATION_SPREAD = 0x30

/** Brand lime, when the artwork has no usable color. */
private val FALLBACK_ARGB = 0xFFFFB020.toInt()
