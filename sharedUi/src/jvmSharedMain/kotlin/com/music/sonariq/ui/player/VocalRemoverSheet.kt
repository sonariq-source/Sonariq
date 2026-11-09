package com.music.sonariq.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.sonariq.sharedui.resources.*
import com.music.sonariq.ui.haptics.Haptic
import com.music.sonariq.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The Vocal Remover's drawer: one slider for the vocal level in the playing
 * stereo mix, drawn in the shared [PlayerDrawer] glass shell.
 *
 * Two layers sit behind the one slider, and the status block under the
 * description says which of them is live for the track on screen:
 *
 *  - **AI separation**, when the track's stem has been prepared: a model
 *    separated the instrumental ahead of playback, and the slider is a
 *    true vocal fader over it (the vocal is the mix minus the stem).
 *  - **Standard DSP**, otherwise: a real-time stage scales the stereo
 *    centre channel, where lead vocals usually sit. Results vary by mix —
 *    anything else mixed to the centre softens too — which the standard
 *    copy says out loud.
 *
 * The platform reports the standing through
 * [PlayerSettingsSource.vocalAiStatus]; lowering the slider is also the
 * cue ([PlayerSettingsSource.onVocalSliderLowered]) for the platform to
 * prepare the current track's stem, so the AI layer can take over the
 * same slider mid-song once it is ready. The entry point only exists
 * where the platform reports the feature
 * ([PlayerSettingsSource.vocalRemoverAvailable]), so this sheet never
 * fronts a control nothing is listening to.
 *
 * The slider reads as the vocal level itself: all the way right is the
 * original mix untouched (the default), all the way left is the vocal
 * fully removed. Reset returns to the original.
 */
@Composable
internal fun VocalRemoverSheet(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val level by PlayerSettings.vocalLevel.collectAsStateWithLifecycle()
    val aiStatus by PlayerSettings.vocalAiStatus.collectAsStateWithLifecycle()
    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.vocal_remover),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GlassTokens.rowShape)
                .background(GlassTokens.panelSurface)
                .glassEdge(GlassTokens.rowShape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ThinSlider(
                value = level,
                onValueChange = { newLevel ->
                    PlayerSettings.setVocalLevel(newLevel)
                    // Lowering the slider is the listener asking for the
                    // AI layer on this track: the platform prepares its
                    // stem now (its own gates and dedupe decide the rest).
                    if (newLevel < MAX_VOCAL_LEVEL) PlayerSettings.onVocalSliderLowered()
                },
                idleHeight = 6.dp,
                activeHeight = 10.dp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(level * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Color.White,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.vocal_remover_description),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    haptics.play(Haptic.Tick)
                    PlayerSettings.setVocalLevel(DEFAULT_VOCAL_LEVEL)
                },
                enabled = level < MAX_VOCAL_LEVEL,
            ) {
                Text(
                    text = stringResource(Res.string.vocal_remover_reset),
                    color = if (level < MAX_VOCAL_LEVEL) {
                        Color.White
                    } else {
                        Color.White.copy(alpha = 0.4f)
                    },
                )
            }
        }
        // The AI layer's standing for the track on screen, in plain words.
        // Hardcoded English, following the Admin screens' precedent: these
        // are live status lines with progress values, not catalogue copy,
        // and no string key exists for them.
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GlassTokens.rowShape)
                .background(GlassTokens.panelSurface)
                .glassEdge(GlassTokens.rowShape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = when (aiStatus.stage) {
                    VocalAiStatus.Stage.AI_READY ->
                        if (aiStatus.highQuality) {
                            "AI separation active · High quality"
                        } else {
                            "AI separation active"
                        }
                    VocalAiStatus.Stage.PREPARING ->
                        "Preparing this song…" + percentSuffix(aiStatus.progress)
                    VocalAiStatus.Stage.MODEL_DOWNLOADING ->
                        "Preparing AI model…" + percentSuffix(aiStatus.progress)
                    VocalAiStatus.Stage.MODEL_READY ->
                        "AI model ready. Songs are separated as they are prepared."
                    VocalAiStatus.Stage.STANDARD ->
                        "Standard mode. The AI model is included with the app — set it up for true stem separation."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f),
            )
            if (aiStatus.modelAbsent && aiStatus.stage != VocalAiStatus.Stage.MODEL_DOWNLOADING) {
                TextButton(
                    onClick = {
                        haptics.play(Haptic.Tick)
                        PlayerSettings.requestVocalModelDownload()
                    },
                ) {
                    Text(
                        text = "Set up AI model",
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/** " · 42%" for a known progress, "" while a stage has none worth quoting. */
private fun percentSuffix(progress: Float?): String =
    progress?.let { " · ${(it.coerceIn(0f, 1f) * 100).roundToInt()}%" } ?: ""
