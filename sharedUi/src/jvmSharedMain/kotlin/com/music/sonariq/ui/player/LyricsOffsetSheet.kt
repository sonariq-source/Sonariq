package com.music.sonariq.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.sonariq.ui.utils.containSheetGestures
import com.music.sonariq.ui.components.optimizedHazeEffect
import com.music.sonariq.ui.theme.LocalArtworkGlassTint
import com.music.sonariq.ui.haptics.Haptic
import com.music.sonariq.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import java.util.Locale
import kotlin.math.roundToInt

private val DrawerShape = GlassTokens.drawerShape
private val ControlShape = GlassTokens.rowShape
private val ScrimColor = GlassTokens.scrim
private val DrawerMaxWidth = GlassTokens.drawerMaxWidth
private const val SliderStepMs = 10
private const val DismissDragFraction = 0.25f

/**
 * The lyrics timing drawer: one control, "Lyrics ms".
 *
 * The value is the listener's own correction for how late their output
 * runs, in milliseconds — positive shows the words later, negative
 * earlier. It is stored per output route (see OutputSync.kt), so the
 * value set on a wireless output never reaches the speaker, and
 * switching outputs switches the value on its own. It starts at 0 and
 * "Reset to 0" returns it there. Everything else that shifts lyrics —
 * the measured auto offset — is measurement, not a knob, and has no
 * control here.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun LyricsOffsetSheet(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The current route and its "Lyrics ms" value. The route is tracked
    // by the platform's output-sync source; where a platform does not
    // track routes it stays OTHER and there is simply the one value.
    val syncState by PlayerPlatform.host.outputSync.state
        .collectAsStateWithLifecycle()
    val routeCalibrations by PlayerSettings.lyricsRouteCalibrations
        .collectAsStateWithLifecycle()
    val lyricsMs = routeCalibrationMs(routeCalibrations, syncState.route)
    val haptics = rememberHaptics()

    fun applyLyricsMs(value: Int) {
        val clamped = value.coerceIn(
            -MAX_ROUTE_CALIBRATION_MS,
            MAX_ROUTE_CALIBRATION_MS,
        )
        // Zero is the absence of a value, not a stored 0: clearing keeps
        // the stored map to routes the listener actually adjusted.
        PlayerSettings.setLyricsRouteCalibration(
            syncState.route,
            clamped.takeIf { it != 0 },
        )
    }

    var drag by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(0) }
    val drawerOffset by animateFloatAsState(
        targetValue = drag,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "lyricsMsDrawerOffset",
    )
    val scrimAlpha = if (height > 0) {
        (1f - drawerOffset / height).coerceIn(0f, 1f)
    } else {
        1f
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val reduceDynamicBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val liquidGlass by PlayerSettings.liquidGlass.collectAsStateWithLifecycle()
    // The sheet tier tinted by the playing track's artwork, as in
    // [PlayerDrawer] — every sheet the player raises wears the same glass.
    val artworkTint = LocalArtworkGlassTint.current
    val glassMode = glassModeFor(
        enabled = liquidGlass,
        blurCapable = PlayerSettings.glassBlurCapable,
        reduceDynamicBlur = reduceDynamicBlur,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .containSheetGestures()
            .background(ScrimColor.copy(alpha = ScrimColor.alpha * scrimAlpha))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it },
            exit = slideOutVertically(tween(180)) { it },
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = DrawerMaxWidth)
                    .fillMaxWidth()
                    .onSizeChanged { height = it.height }
                    .offset { IntOffset(0, drawerOffset.roundToInt()) }
                    .clip(DrawerShape)
                    .glassEdge(DrawerShape)
                    .then(
                        if (glassMode == GlassMode.REAL) {
                            Modifier.optimizedHazeEffect(
                                state = hazeState,
                                style = HazeMaterials.regular(artworkTintedHazeTint(artworkTint)),
                            )
                        } else {
                            Modifier
                        }
                    )
                    .glassSurface(
                        shape = DrawerShape,
                        mode = glassMode,
                        tint = artworkTint,
                        tier = GlassTokens.sheetGlass,
                        tierAlpha = GlassTokens.sheetGlassAlpha,
                        solidColor = GlassTokens.sheetSurface,
                        rim = false,
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    )
                    .pointerInput(height) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (height > 0 && drag > height * DismissDragFraction) {
                                    onDismiss()
                                } else {
                                    drag = 0f
                                }
                            },
                            onDragCancel = { drag = 0f },
                        ) { _, delta -> drag = (drag + delta).coerceAtLeast(0f) }
                    }
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(top = 10.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .padding(bottom = 12.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(GlassTokens.handle),
                )
                Text(
                    text = "Lyrics ms",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                )
                Spacer(Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ControlShape)
                        .background(GlassTokens.panelSurface)
                        .glassEdge(ControlShape)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = formatLyricsMs(lyricsMs),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = Color.White,
                    )
                    Spacer(Modifier.height(10.dp))
                    ThinSlider(
                        value = lyricsMsToFraction(lyricsMs),
                        onValueChange = { applyLyricsMs(fractionToLyricsMs(it)) },
                        idleHeight = 6.dp,
                        activeHeight = 10.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (step in listOf(-100, -50, -10, 10, 50, 100)) {
                            LyricsMsStepButton(
                                step = step,
                                enabled = if (step < 0) {
                                    lyricsMs > -MAX_ROUTE_CALIBRATION_MS
                                } else {
                                    lyricsMs < MAX_ROUTE_CALIBRATION_MS
                                },
                                onClick = {
                                    haptics.play(Haptic.Select)
                                    applyLyricsMs(lyricsMs + step)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Text(
                    text = "Reset to 0",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = if (lyricsMs != 0) 0.8f else 0.35f),
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clip(CircleShape)
                        .clickable(enabled = lyricsMs != 0) {
                            haptics.play(Haptic.Tap)
                            PlayerSettings.setLyricsRouteCalibration(
                                syncState.route,
                                null,
                            )
                        }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/** One "Lyrics ms" step: small, labelled in milliseconds, one tap per step. */
@Composable
private fun LyricsMsStepButton(
    step: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.10f else 0.04f))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatLyricsMs(step),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = if (enabled) 0.9f else 0.3f),
            maxLines = 1,
        )
    }
}

private fun lyricsMsToFraction(lyricsMs: Int): Float =
    (lyricsMs + MAX_ROUTE_CALIBRATION_MS).toFloat() /
        (2 * MAX_ROUTE_CALIBRATION_MS)

private fun fractionToLyricsMs(fraction: Float): Int {
    val raw = -MAX_ROUTE_CALIBRATION_MS +
        fraction.coerceIn(0f, 1f) * (2 * MAX_ROUTE_CALIBRATION_MS)
    return (raw / SliderStepMs).roundToInt() * SliderStepMs
}

/** `0 ms` at rest; a signed value otherwise — the unit is always named. */
private fun formatLyricsMs(ms: Int): String =
    if (ms == 0) "0 ms" else String.format(Locale.getDefault(), "%+d ms", ms)
