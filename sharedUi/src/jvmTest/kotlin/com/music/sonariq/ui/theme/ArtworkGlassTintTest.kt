package com.music.sonariq.ui.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.graphics.Color
import com.music.sonariq.ui.player.GlassTokens
import com.music.sonariq.ui.player.artworkTintedHazeTint
import com.music.sonariq.ui.player.artworkTintedSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ArtworkGlassTintTest {

    @Test
    fun neutralArtworkTintsTheGlassNeutral() {
        // A grey sleeve has no hue to give: the tint must stay grey rather
        // than acquiring one from a saturation floor.
        val tint = glassTintColor(Color(0xFF808080), dark = true)
        assertTrue(abs(tint.red - tint.green) < 0.01f)
        assertTrue(abs(tint.green - tint.blue) < 0.01f)
    }

    @Test
    fun chromaticArtworkTintsTheGlassWithItsOwnColour() {
        val tint = glassTintColor(Color(0xFFCC2222), dark = true)
        assertTrue(tint.red > tint.green + 0.05f)
        assertTrue(tint.red > tint.blue + 0.05f)
    }

    @Test
    fun theDarkTintStaysDeepEnoughForWhiteContent() {
        // The glass carries white glyphs in dark mode: whatever the sleeve,
        // the dark tint must stay a dark colour.
        val tint = glassTintColor(Color(0xFFF2C230), dark = true)
        val luminance = 0.2126f * tint.red + 0.7152f * tint.green + 0.0722f * tint.blue
        assertTrue(luminance < 0.30f)
    }

    @Test
    fun theLightTintIsAPaleWashOfTheSameColour() {
        val dark = glassTintColor(Color(0xFF2255CC), dark = true)
        val light = glassTintColor(Color(0xFF2255CC), dark = false)
        assertTrue(light.blue > dark.blue + 0.4f)
        assertTrue(light.blue > light.red)
    }

    @Test
    fun reducedAnimationCutsTheTintShift() {
        assertTrue(glassTintAnimationSpec(reduceAnimation = true) is SnapSpec<*>)
        assertTrue(glassTintAnimationSpec(reduceAnimation = false) is TweenSpec<*>)
    }

    @Test
    fun noTintLeavesTheTierColourExactlyAsItWas() {
        assertEquals(
            GlassTokens.playbackSurface,
            artworkTintedSurface(
                tint = null,
                fallback = GlassTokens.playbackSurface,
                alpha = GlassTokens.playbackGlassAlpha,
            ),
        )
        assertEquals(GlassTokens.hazeTint, artworkTintedHazeTint(null))
    }

    @Test
    fun aTintTakesTheTierAlphaAndKeepsItsOwnColour() {
        val tint = Color(0xFF7A1FA2)
        val surface = artworkTintedSurface(
            tint = tint,
            fallback = GlassTokens.playbackSurface,
            alpha = GlassTokens.playbackGlassAlpha,
        )
        assertEquals(tint.copy(alpha = GlassTokens.playbackGlassAlpha), surface)
        assertEquals(tint, artworkTintedHazeTint(tint))
    }
}
