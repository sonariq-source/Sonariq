package com.music.sonariq.ui.screens

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.music.sonariq.R
import com.music.sonariq.data.settings.AppSettings

/**
 * A full color picker for the app accent, in Prism styling.
 *
 * A hue slider, a saturation/value field and a hex input. The color is applied
 * *live* while it is being picked — every change writes straight through to
 * [AppSettings.setCustomAccentArgb], so the app behind the dialog re-themes as
 * the finger moves. Cancelling (or dismissing the dialog any other way) puts
 * back whatever was there when it opened; Done keeps the pick.
 */
@Composable
fun ColorPickerDialog(
    /** Opaque ARGB the picker starts from: the custom color, or the preset. */
    initialArgb: Int,
    onDismiss: () -> Unit,
) {
    val original = remember { AppSettings.customAccentArgb.value }
    val startHsv = remember {
        FloatArray(3).also { AndroidColor.colorToHSV(initialArgb, it) }
    }
    var hue by remember { mutableFloatStateOf(startHsv[0]) }
    var saturation by remember { mutableFloatStateOf(startHsv[1]) }
    var value by remember { mutableFloatStateOf(startHsv[2].coerceAtLeast(0.05f)) }
    var hex by remember { mutableStateOf(argbToHex(initialArgb)) }

    // Live: the app re-themes under the dialog as the color moves.
    LaunchedEffect(hue, saturation, value) {
        val argb = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))
        AppSettings.setCustomAccentArgb(argb)
        hex = argbToHex(argb)
    }

    fun restoreAndClose() {
        AppSettings.setCustomAccentArgb(original)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::restoreAndClose,
        title = { Text(stringResource(R.string.pick_a_color)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PreviewRow(
                    current = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))),
                    original = original?.let { Color(it) },
                )
                SaturationValueField(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onPick = { s, v ->
                        saturation = s
                        value = v
                    },
                )
                HueSlider(hue = hue, onHue = { hue = it })
                HexInput(
                    hex = hex,
                    onHex = { newHex ->
                        hex = newHex
                        parseHex(newHex)?.let { argb ->
                            val hsv = FloatArray(3)
                            AndroidColor.colorToHSV(argb, hsv)
                            hue = hsv[0]
                            saturation = hsv[1]
                            value = hsv[2].coerceAtLeast(0.05f)
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = ::restoreAndClose) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** The new color beside what was there before it, so the change reads at a glance. */
@Composable
private fun PreviewRow(current: Color, original: Color?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(current)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        if (original != null) {
            Text(
                text = "→",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(original)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.custom_color_live_preview),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The saturation/value square: hue across the bottom edge from the slider,
 * white fading in from the left, black from the bottom.
 */
@Composable
private fun SaturationValueField(
    hue: Float,
    saturation: Float,
    value: Float,
    onPick: (saturation: Float, value: Float) -> Unit,
) {
    val pure = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset -> onPick(offset.x / size.width, 1f - offset.y / size.height) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onPick(
                        (change.position.x / size.width).coerceIn(0f, 1f),
                        (1f - change.position.y / size.height).coerceIn(0f, 1f),
                    )
                }
            },
    ) {
        Box(Modifier.fillMaxSize().background(pure))
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(Color.White, Color.Transparent))),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))),
        )
        val density = LocalDensity.current
        val thumbX = with(density) { (saturation * maxWidth.toPx()).toDp() }
        val thumbY = with(density) { ((1f - value) * maxHeight.toPx()).toDp() }
        Box(
            Modifier
                .size(22.dp)
                .offset { IntOffset(thumbX.roundToPx() - 11.dp.roundToPx(), thumbY.roundToPx() - 11.dp.roundToPx()) }
                .clip(CircleShape)
                .background(Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))))
                .border(2.5.dp, Color.White, CircleShape),
        )
    }
}

/** The rainbow strip the hue comes from. */
@Composable
private fun HueSlider(hue: Float, onHue: (Float) -> Unit) {
    val stops = remember {
        (0..360 step 30).map { Color(AndroidColor.HSVToColor(floatArrayOf(it.toFloat(), 1f, 1f))) }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Brush.horizontalGradient(stops))
            .pointerInput(Unit) {
                detectTapGestures { offset -> onHue((offset.x / size.width * 360f).coerceIn(0f, 360f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onHue((change.position.x / size.width * 360f).coerceIn(0f, 360f))
                }
            },
    ) {
        val density = LocalDensity.current
        val thumbX = with(density) { (hue / 360f * maxWidth.toPx()).toDp() }
        Box(
            Modifier
                .size(30.dp)
                .offset { IntOffset(thumbX.roundToPx() - 15.dp.roundToPx(), 0) }
                .clip(CircleShape)
                .background(Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f))))
                .border(2.5.dp, Color.White, CircleShape),
        )
    }
}

/** Typed hex, `#RRGGBB`. Anything that isn't six hex digits is shown but not applied. */
@Composable
private fun HexInput(hex: String, onHex: (String) -> Unit) {
    OutlinedTextField(
        value = hex,
        onValueChange = onHex,
        label = { Text(stringResource(R.string.hex_color)) },
        prefix = { Text("#", fontWeight = FontWeight.W600) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun argbToHex(argb: Int): String = "%06X".format(0xFFFFFF and argb)

private fun parseHex(hex: String): Int? {
    val digits = hex.trim().removePrefix("#")
    if (!digits.matches(Regex("[0-9a-fA-F]{6}"))) return null
    return runCatching { AndroidColor.parseColor("#$digits") }.getOrNull()
}
