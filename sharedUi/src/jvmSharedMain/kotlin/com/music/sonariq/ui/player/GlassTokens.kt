package com.music.sonariq.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.music.sonariq.ui.theme.LocalArtworkGlassTint

/**
 * The player's shared Liquid Glass tokens: the surface tint, hairline,
 * shapes and scrim every floating player surface — the bottom sheets, the
 * drawers, the floating control pills — is built from, so a change to the
 * glass look lands in one place instead of per sheet.
 *
 * The material itself is deliberately boring: a frosted sample of what is
 * behind the surface (the drawer's haze) or, where live sampling is off
 * ("reduce dynamic blur") or unavailable, the same translucent tint over a
 * hairline border — the fallback the tokens describe, with no dependence
 * on any one platform blur API. Text and artwork are never the blurred
 * thing: only these small floating surfaces sample a backdrop, and nothing
 * here is applied per frame or full-screen, which keeps the television
 * build — where these sheets do not run — untouched by it.
 */
object GlassTokens {
    /** A bottom sheet's outline: square to the screen edge, 26 dp top corners. */
    val drawerShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

    /** A row or control inset inside a glass surface. */
    val rowShape = RoundedCornerShape(16.dp)

    /** A floating pill control over artwork (search, lyrics appearance). */
    val pillShape = RoundedCornerShape(20.dp)

    /** Content cards: one uniform corner on artwork cards app-wide. */
    val cardShape = RoundedCornerShape(12.dp)

    /** Large glass panels: mini player, control panels, sheets' inner cards. */
    val panelShape = RoundedCornerShape(20.dp)

    /** The widest a bottom sheet gets, however wide the window behind it is. */
    val drawerMaxWidth = 640.dp

    /** The tallest a bottom sheet gets before its content scrolls inside it. */
    val drawerMaxHeight = 560.dp

    /** The dim behind a sheet, at full strength. */
    val scrim = Color.Black.copy(alpha = 0.5f)

    /** The sheet surface: deep green-black glass, shared by the solid fallback. */
    val surface = Color(0xFF17251D)

    /** The haze tint the frosted sheet material is built over [surface] with. */
    val hazeTint = Color(0xFF121D17)

    /** How much of [surface] stays over the frosted sample in the glass state. */
    const val surfaceAlpha = 0.9f

    /** The emerald action colour inside glass surfaces. */
    val accent = Color(0xFF1DB954)

    /** The active-navigation emerald. */
    val activeNav = Color(0xFF32D979)

    /** The mint highlight reserved for small emphasis on glass. */
    val mint = Color(0xFF8EF5BD)

    /** A control's fill on top of a glass surface. */
    val controlTint = Color.White.copy(alpha = 0.05f)

    /** The hairline that separates a glass surface from what is behind it. */
    val hairline = Color.White.copy(alpha = 0.15f)
    val hairlineWidth = 1.dp

    /** A sheet's grab handle. */
    val handle = Color.White.copy(alpha = 0.25f)

    // ------------------------------------------------------------------
    // Living Sound material tiers.
    //
    // One glass recipe at four depths, so a surface's transparency says
    // what it is before anything is read: navigation is the most solid
    // thing on screen, playback controls floating over artwork are the
    // lightest, and sheets sit between. Each tier is a tint plus the
    // alpha that tint is laid at — the tiers are colours, not blur:
    // nothing here samples or blurs a backdrop per frame, so the tiers
    // cost the same to draw as the flat tints they replace.
    // ------------------------------------------------------------------

    /** Navigation glass: the docked bar and the desktop rails. Most opaque. */
    val navGlass = Color(0xFF0D1511)
    const val navGlassAlpha = 0.96f

    /** Panel glass: cards, panels, section surfaces. */
    val panelGlass = Color(0xFF121D17)
    const val panelGlassAlpha = 0.86f

    /** Playback glass: transport and pills floating over artwork. Most translucent. */
    val playbackGlass = Color(0xFF17251D)
    const val playbackGlassAlpha = 0.52f

    /** Sheet glass: dialogs, menus and bottom sheets. Nearly solid. */
    val sheetGlass = Color(0xFF17251D)
    const val sheetGlassAlpha = 0.92f

    /** The tier colours at their tier alphas, ready to paint with. */
    val navSurface: Color get() = navGlass.copy(alpha = navGlassAlpha)
    val panelSurface: Color get() = panelGlass.copy(alpha = panelGlassAlpha)
    val playbackSurface: Color get() = playbackGlass.copy(alpha = playbackGlassAlpha)
    val sheetSurface: Color get() = sheetGlass.copy(alpha = sheetGlassAlpha)

    /**
     * The edge light every glass surface carries on its top edge: a 1 dp
     * line of white at low alpha, brighter than the hairline around the
     * rest of the outline, so the surface reads as a physical sheet with
     * light falling on it rather than a translucent rectangle.
     */
    val edgeLight = Color.White.copy(alpha = 0.20f)

    /** The edge light at the strength used on the smallest controls. */
    val edgeLightSoft = Color.White.copy(alpha = 0.10f)

    /** Feature and hero artwork: the large Living Sound corner. */
    val heroShape = RoundedCornerShape(24.dp)

    /** The echo offset: how far a hero artwork's echo layer sits behind it. */
    val echoOffset = 12.dp

    /** The feature-card spine: a thin emerald rule on a card's leading edge. */
    val spineWidth = 3.dp
    val spineColor: Color get() = accent
}

/**
 * A glass tier's colour, tinted by the playing track's artwork where a
 * tint is in reach: the tier keeps its own alpha (a tier is a depth, and
 * the tint must not change how much of the backdrop a surface lets
 * through), only its colour gives way. With no tint the tier's own colour
 * stands, byte for byte — the untinted material is the fallback, not an
 * approximation of it.
 */
fun artworkTintedSurface(tint: Color?, fallback: Color, alpha: Float): Color =
    tint?.copy(alpha = alpha) ?: fallback

/** The haze material's tint, artwork-tinted the same way as [artworkTintedSurface]. */
fun artworkTintedHazeTint(tint: Color?): Color = tint ?: GlassTokens.hazeTint

/**
 * The playback tier's surface as the player's floating controls wear it:
 * tinted by the current track's artwork (see [LocalArtworkGlassTint])
 * wherever the player has provided one, the plain tier colour everywhere
 * else.
 */
@Composable
fun tintedPlaybackSurface(): Color = artworkTintedSurface(
    tint = LocalArtworkGlassTint.current,
    fallback = GlassTokens.playbackSurface,
    alpha = GlassTokens.playbackGlassAlpha,
)

/**
 * The Living Sound glass edge: a hairline border whose top edge carries
 * the tier's edge light. Implemented as a vertical-gradient border plus
 * a single drawn top line — static geometry, drawn once per layout, no
 * backdrop sampling and nothing per frame.
 */
@Stable
fun Modifier.glassEdge(
    shape: Shape,
    topAlpha: Float = 0.20f,
    restAlpha: Float = 0.08f,
): Modifier = this
    .border(
        BorderStroke(
            GlassTokens.hairlineWidth,
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = topAlpha),
                    Color.White.copy(alpha = restAlpha),
                ),
            ),
        ),
        shape,
    )
    .drawWithContent {
        drawContent()
        // The gradient border alone rounds its bright segment into the
        // corners; a straight 1 dp line across the top keeps the light
        // reading as an edge, not a glow.
        val y = GlassTokens.hairlineWidth.toPx() / 2f
        drawLine(
            color = Color.White.copy(alpha = topAlpha),
            start = Offset(size.width * 0.08f, y),
            end = Offset(size.width * 0.92f, y),
            strokeWidth = GlassTokens.hairlineWidth.toPx(),
        )
    }
