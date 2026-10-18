package com.music.sonariq.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.sonariq.R
import com.music.sonariq.data.settings.EqPresetLink
import com.music.sonariq.data.settings.UserEqualizerPreset
import com.music.sonariq.playback.EqLayout

/**
 * The incoming EQ preset link dialog.
 *
 * Shown when a `sonariq://eq/…` or `https://sonariq.giize.com/eq/…` link
 * arrives: the preset's name, a small rendering of its curve, and the choice
 * to import it. Importing saves it through [EqPresetLink.import] — the same
 * path the EQ screen's own save uses, which also applies the curve — so the
 * preset behaves exactly like one made on the device.
 *
 * Wiring (Phase 2): in MainActivity, collect [EqPresetLink.pending] the way
 * [JamInviteLink][com.music.sonariq.data.listentogether.JamInviteLink.pending]
 * is collected, show this dialog, and call [EqPresetLink.handled] after the
 * choice. See the integration manifest.
 */
@Composable
fun EqPresetImportDialog(
    preset: UserEqualizerPreset,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.social_eq_import_title))
        },
        text = {
            Column {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                EqCurvePreview(bandsDb = preset.bandsDb)
            }
        },
        confirmButton = {
            TextButton(onClick = onImport) {
                Text(text = stringResource(R.string.social_eq_import_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.social_eq_cancel))
            }
        },
    )
}

/** A small bar rendering of the seven EQ bands, low to high. */
@Composable
private fun EqCurvePreview(bandsDb: List<Float>) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val range = EqLayout.MANUAL_RANGE_DB
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        val n = EqLayout.MANUAL_COUNT
        val slotW = size.width / n
        val barW = slotW * 0.45f
        val zeroY = size.height / 2f
        // Track
        drawLine(
            color = track,
            start = Offset(0f, zeroY),
            end = Offset(size.width, zeroY),
            strokeWidth = 2.dp.toPx(),
        )
        for (i in 0 until n) {
            val gain = bandsDb.getOrElse(i) { 0f }.coerceIn(-range, range)
            val cx = slotW * i + slotW / 2f
            val half = size.height / 2f - 4.dp.toPx()
            val y = zeroY - (gain / range) * half
            drawLine(
                color = primary,
                start = Offset(cx, zeroY),
                end = Offset(cx, y),
                strokeWidth = barW,
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "−${range.toInt()} dB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = "+${range.toInt()} dB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp),
        )
    }
}
