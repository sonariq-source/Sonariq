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
 * The slider drives a real DSP stage in the playback pipeline — it scales
 * the stereo centre channel, where lead vocals usually sit — and the copy
 * in here says exactly that and no more: results vary by mix, and it is not
 * stem separation. The entry point only exists where the platform reports
 * the stage ([PlayerSettingsSource.vocalRemoverAvailable]), so this sheet
 * never fronts a control nothing is listening to.
 *
 * The slider reads as the vocal level itself: all the way right is the
 * original mix untouched (the default), all the way left is the centre
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
                .background(GlassTokens.controlTint)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ThinSlider(
                value = level,
                onValueChange = { PlayerSettings.setVocalLevel(it) },
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
    }
}
