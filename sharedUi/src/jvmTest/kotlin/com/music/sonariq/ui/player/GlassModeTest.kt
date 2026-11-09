package com.music.sonariq.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassModeTest {

    @Test
    fun toggleOffIsAlwaysOff() {
        // Whatever the platform can do and whatever the blur setting says,
        // the switch being off means plain surfaces, byte for byte.
        for (blurCapable in listOf(false, true)) {
            for (reduceDynamicBlur in listOf(false, true)) {
                assertEquals(
                    GlassMode.OFF,
                    glassModeFor(
                        enabled = false,
                        blurCapable = blurCapable,
                        reduceDynamicBlur = reduceDynamicBlur,
                    ),
                )
            }
        }
    }

    @Test
    fun reducedDynamicBlurKeepsTheSolidPromise() {
        // "Reduce dynamic blur" promised a solid fill; that promise outranks
        // both the platform's blur support and the material.
        assertEquals(
            GlassMode.SOLID,
            glassModeFor(enabled = true, blurCapable = true, reduceDynamicBlur = true),
        )
        assertEquals(
            GlassMode.SOLID,
            glassModeFor(enabled = true, blurCapable = false, reduceDynamicBlur = true),
        )
    }

    @Test
    fun blurCapablePlatformGetsTheFullBlur() {
        assertEquals(
            GlassMode.FULL_BLUR,
            glassModeFor(enabled = true, blurCapable = true, reduceDynamicBlur = false),
        )
    }

    @Test
    fun tabletWithoutBlurStillGetsTheMaterial() {
        // The owner's tablet case: toggle on, no RenderEffect (Android 11
        // and below). Before the material existed this fell through to the
        // flat matte fallback and the switch visibly did nothing; it must
        // land on the material, never on OFF.
        assertEquals(
            GlassMode.MATERIAL_ONLY,
            glassModeFor(enabled = true, blurCapable = false, reduceDynamicBlur = false),
        )
    }

    @Test
    fun sheenBandStartsAndEndsOffSurface() {
        // The ends of the cycle are the pause: nothing of the band is over
        // the glass at phase 0 or phase 1.
        assertFalse(glassSheenVisible(0f))
        assertFalse(glassSheenVisible(1f))
        // Mid-cycle the band is crossing.
        assertTrue(glassSheenVisible(0.5f))
        assertEquals(0.5f, glassSheenCentre(0.5f), 0.001f)
    }

    @Test
    fun sheenRestsForPartOfEveryCycle() {
        // Sampled across the cycle, the band must be off the surface for a
        // real share of it — a band that is always crossing reads as a
        // loading shimmer, not as light moving over glass.
        var visibleSamples = 0
        val samples = 200
        for (i in 0..samples) {
            if (glassSheenVisible(i.toFloat() / samples)) visibleSamples++
        }
        assertTrue(
            "sheen visible for $visibleSamples of $samples samples",
            visibleSamples < samples * 0.75,
        )
        assertTrue(visibleSamples > 0)
    }
}
