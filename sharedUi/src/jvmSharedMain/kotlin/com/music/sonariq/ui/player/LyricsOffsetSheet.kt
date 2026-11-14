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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.sonariq.sharedui.resources.*
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
private const val StepMs = 100
private const val DismissDragFraction = 0.25f

/** A player drawer for shifting all synced lyrics against the playback clock. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun LyricsOffsetSheet(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** The song being offset; null keeps the sheet to the global offset only. */
    videoId: String? = null,
) {
    val globalOffsetMs by PlayerSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    val overrides by PlayerSettings.lyricsOffsetOverrides.collectAsStateWithLifecycle()
    // The output side of the shift (see OutputSync.kt): the current
    // route, its measured latency where the platform has one, and the
    // listener's calibration for that route. Collected unconditionally;
    // the section below only renders where the platform supports it.
    val outputSyncSource = PlayerPlatform.host.outputSync
    val syncState by outputSyncSource.state.collectAsStateWithLifecycle()
    val routeCalibrations by PlayerSettings.lyricsRouteCalibrations
        .collectAsStateWithLifecycle()
    val calibrationMs = routeCalibrationMs(routeCalibrations, syncState.route)
    val syncShiftMs = outputSyncShiftMs(routeCalibrations, syncState)
    val haptics = rememberHaptics()
    // Per-track mode starts on when the song already carries its own offset.
    // Turning it off leaves a stored override in place — it simply stops
    // applying; Reset is what clears it.
    var perTrack by remember(videoId) {
        mutableStateOf(videoId != null && overrides.containsKey(videoId))
    }
    val editingTrack = perTrack && videoId != null
    val hasOverride = videoId != null && overrides.containsKey(videoId)
    val offsetMs = resolveLyricsOffsetMs(overrides, globalOffsetMs, videoId.takeIf { editingTrack })

    fun applyOffset(value: Int) {
        val id = videoId
        if (editingTrack && id != null) {
            PlayerSettings.setLyricsOffsetForTrack(id, value)
        } else {
            PlayerSettings.setLyricsOffsetMs(value)
        }
    }
    var drag by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(0) }
    val drawerOffset by animateFloatAsState(
        targetValue = drag,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "lyricsOffsetDrawerOffset",
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
                    text = stringResource(Res.string.lyrics_offset),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                )
                Text(
                    text = stringResource(Res.string.lyrics_offset_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 3.dp, bottom = 14.dp),
                )

                if (videoId != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ControlShape)
                            .background(GlassTokens.panelSurface)
                            .glassEdge(ControlShape)
                            .clickable {
                                haptics.play(Haptic.Select)
                                perTrack = !perTrack
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.lyrics_offset_per_track),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = perTrack,
                            onCheckedChange = {
                                haptics.play(Haptic.Select)
                                perTrack = it
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                // SonariqLime, mirrored here because sharedUi
                                // cannot see the app module's theme.
                                checkedTrackColor = Color(0xFF1DB954),
                                uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                                uncheckedTrackColor = Color.White.copy(alpha = 0.18f),
                                uncheckedBorderColor = Color.Transparent,
                            ),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

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
                        text = formatOffset(offsetMs),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OffsetButton(
                            label = "−",
                            contentDescription = stringResource(Res.string.decrease_lyrics_offset),
                            enabled = offsetMs > MIN_LYRICS_OFFSET_MS,
                        ) {
                            haptics.play(Haptic.Select)
                            applyOffset(offsetMs - StepMs)
                        }
                        ThinSlider(
                            value = offsetToFraction(offsetMs),
                            onValueChange = { applyOffset(fractionToOffset(it)) },
                            idleHeight = 6.dp,
                            activeHeight = 10.dp,
                            modifier = Modifier.weight(1f),
                        )
                        OffsetButton(
                            label = "+",
                            contentDescription = stringResource(Res.string.increase_lyrics_offset),
                            enabled = offsetMs < MAX_LYRICS_OFFSET_MS,
                        ) {
                            haptics.play(Haptic.Select)
                            applyOffset(offsetMs + StepMs)
                        }
                    }
                }

                val resetEnabled = if (editingTrack) hasOverride else offsetMs != 0
                Text(
                    text = stringResource(Res.string.lyrics_offset_reset),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = if (resetEnabled) 0.8f else 0.35f),
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clip(CircleShape)
                        .clickable(enabled = resetEnabled) {
                            haptics.play(Haptic.Tap)
                            if (editingTrack) {
                                PlayerSettings.setLyricsOffsetForTrack(videoId, null)
                            } else {
                                PlayerSettings.setLyricsOffsetMs(0)
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )

                if (outputSyncSource.supported) {
                    Spacer(Modifier.height(14.dp))
                    OutputSyncSection(
                        route = syncState.route,
                        estimatedLatencyMs = syncState.estimatedLatencyMs,
                        calibrationMs = calibrationMs,
                        // The shift the playhead is applying for this
                        // track: the offset above with the output side
                        // composed over it.
                        effectiveMs = effectiveLyricsShiftMs(offsetMs, syncShiftMs),
                        onStep = { step ->
                            haptics.play(Haptic.Select)
                            PlayerSettings.setLyricsRouteCalibration(
                                syncState.route,
                                (calibrationMs + step).coerceIn(
                                    -MAX_ROUTE_CALIBRATION_MS,
                                    MAX_ROUTE_CALIBRATION_MS,
                                ),
                            )
                        },
                        onReset = {
                            haptics.play(Haptic.Tap)
                            PlayerSettings.setLyricsRouteCalibration(
                                syncState.route,
                                null,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OffsetButton(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White.copy(alpha = if (enabled) 0.85f else 0.25f),
        )
    }
}

private fun offsetToFraction(offsetMs: Int): Float =
    (offsetMs - MIN_LYRICS_OFFSET_MS).toFloat() /
        (MAX_LYRICS_OFFSET_MS - MIN_LYRICS_OFFSET_MS)

private fun fractionToOffset(fraction: Float): Int {
    val raw = MIN_LYRICS_OFFSET_MS +
        fraction.coerceIn(0f, 1f) *
        (MAX_LYRICS_OFFSET_MS - MIN_LYRICS_OFFSET_MS)
    return (raw / StepMs).roundToInt() * StepMs
}

private fun formatOffset(offsetMs: Int): String = String.format(
    Locale.getDefault(),
    "%+.1f s",
    offsetMs / 1_000f,
)

private fun formatMs(ms: Long): String = String.format(Locale.getDefault(), "%+d ms", ms)

/**
 * The output-sync half of the sheet: what the current output adds to
 * the lyrics shift, and the per-route calibration that corrects it.
 *
 * The latency line is honest about measurability — where the platform
 * cannot measure the route it says so, rather than dressing a guess up
 * as a reading. The calibration belongs to the route alone: steps taken
 * here are stored against [route] and never reach another output.
 */
@Composable
private fun OutputSyncSection(
    route: AudioOutputKind,
    estimatedLatencyMs: Long?,
    calibrationMs: Int,
    effectiveMs: Int,
    onStep: (Int) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(GlassTokens.panelSurface)
            .glassEdge(ControlShape)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.lyrics_output_sync),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(Res.string.lyrics_output_route) +
                    ": " + routeLabel(route),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        Text(
            text = stringResource(Res.string.lyrics_output_sync_description),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 3.dp),
        )
        Spacer(Modifier.height(10.dp))
        SyncValueRow(
            label = stringResource(Res.string.lyrics_output_latency),
            value = estimatedLatencyMs?.let { formatMs(it) },
            unavailable = stringResource(Res.string.lyrics_output_latency_unavailable),
        )
        SyncValueRow(
            label = stringResource(Res.string.lyrics_route_calibration),
            value = formatMs(calibrationMs.toLong()),
            unavailable = null,
        )
        SyncValueRow(
            label = stringResource(Res.string.lyrics_effective_offset),
            value = formatMs(effectiveMs.toLong()),
            unavailable = null,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (step in listOf(-100, -50, -10, 10, 50, 100)) {
                CalibrationStepButton(
                    step = step,
                    enabled = if (step < 0) {
                        calibrationMs > -MAX_ROUTE_CALIBRATION_MS
                    } else {
                        calibrationMs < MAX_ROUTE_CALIBRATION_MS
                    },
                    onClick = { onStep(step) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            text = stringResource(Res.string.lyrics_output_reset_automatic),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = if (calibrationMs != 0) 0.8f else 0.35f),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 10.dp)
                .clip(CircleShape)
                .clickable(enabled = calibrationMs != 0) { onReset() }
                .padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

/** One label-and-value line of the output-sync section; a null [value] reads as [unavailable]. */
@Composable
private fun SyncValueRow(label: String, value: String?, unavailable: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value ?: unavailable.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = if (value != null) 0.95f else 0.55f),
        )
    }
}

/** One calibration step: small, labelled in milliseconds, one tap per step. */
@Composable
private fun CalibrationStepButton(
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
            text = formatMs(step.toLong()),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = if (enabled) 0.9f else 0.3f),
            maxLines = 1,
        )
    }
}

@Composable
private fun routeLabel(route: AudioOutputKind): String = stringResource(
    when (route) {
        AudioOutputKind.PHONE -> Res.string.lyrics_output_route_speaker
        AudioOutputKind.WIRED -> Res.string.lyrics_output_route_wired
        AudioOutputKind.BLUETOOTH -> Res.string.lyrics_output_route_bluetooth
        AudioOutputKind.USB -> Res.string.lyrics_output_route_usb
        AudioOutputKind.HDMI -> Res.string.lyrics_output_route_hdmi
        AudioOutputKind.OTHER -> Res.string.lyrics_output_route_other
    },
)
