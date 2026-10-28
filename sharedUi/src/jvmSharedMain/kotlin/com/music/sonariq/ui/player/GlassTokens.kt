package com.music.sonariq.ui.player

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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

    /** The widest a bottom sheet gets, however wide the window behind it is. */
    val drawerMaxWidth = 640.dp

    /** The tallest a bottom sheet gets before its content scrolls inside it. */
    val drawerMaxHeight = 560.dp

    /** The dim behind a sheet, at full strength. */
    val scrim = Color.Black.copy(alpha = 0.5f)

    /** The sheet surface: near-black plum-dark, shared by the solid fallback. */
    val surface = Color(0xFF121212)

    /** The haze tint the frosted sheet material is built over [surface] with. */
    val hazeTint = Color(0xFF141414)

    /** How much of [surface] stays over the frosted sample in the glass state. */
    const val surfaceAlpha = 0.9f

    /** A control's fill on top of a glass surface. */
    val controlTint = Color.White.copy(alpha = 0.05f)

    /** The hairline that separates a glass surface from what is behind it. */
    val hairline = Color.White.copy(alpha = 0.08f)
    val hairlineWidth = 1.dp

    /** A sheet's grab handle. */
    val handle = Color.White.copy(alpha = 0.25f)
}
