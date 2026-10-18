package com.music.sonariq.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.sonariq.R
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.data.settings.AudioQuality
import com.music.sonariq.playback.AudioOutputStatus
import com.music.sonariq.playback.AudioRouting
import com.music.sonariq.playback.DeviceEqController
import com.music.sonariq.playback.deviceKey
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Sound & data: the settings that did not fit the main audio section.
 *
 * - Per-device equalizer: a separate EQ curve per output, swapped
 *   automatically by [DeviceEqController] as the route changes.
 * - Mono audio + left/right balance, applied in the DSP chain.
 * - Animated canvas: the looping artwork-colour backdrop for tracks with no
 *   video canvas.
 * - Data saver: a master quality cap plus Wi-Fi-only streaming.
 *
 * Every control here is live: toggles write [AppSettings] and the playback
 * service picks them up through the collectors it already runs.
 */
@Composable
fun AudioExtrasScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val perDeviceEq by AppSettings.perDeviceEqEnabled.collectAsStateWithLifecycle()
    val monoAudio by AppSettings.monoAudioEnabled.collectAsStateWithLifecycle()
    val balance by AppSettings.equalizerBalance.collectAsStateWithLifecycle()
    val canvasEnabled by AppSettings.canvasBackdropEnabled.collectAsStateWithLifecycle()
    val dataSaver by AppSettings.dataSaverEnabled.collectAsStateWithLifecycle()
    val saverQuality by AppSettings.dataSaverQuality.collectAsStateWithLifecycle()
    val wifiOnlyStreaming by AppSettings.wifiOnlyStreaming.collectAsStateWithLifecycle()
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()

    val routeKind = outputStatus.routeKind
    val deviceName = outputStatus.deviceName
    val deviceLabel = when {
        deviceName.isNotBlank() && deviceName != "System default" -> deviceName
        routeKind == AudioRouting.Kind.PHONE ->
            stringResource(R.string.per_device_eq_this_phone)
        else -> routeKind.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Text(
                text = stringResource(R.string.sound_and_data),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Column(Modifier.verticalScroll(rememberScrollState())) {
            ExtrasSectionTitle(stringResource(R.string.per_device_eq))
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.per_device_eq),
                subtitle = stringResource(R.string.per_device_eq_subtitle),
                trailing = {
                    Switch(
                        checked = perDeviceEq,
                        onCheckedChange = AppSettings::setPerDeviceEqEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setPerDeviceEqEnabled(!perDeviceEq) },
            )
            SettingsRow(
                title = stringResource(R.string.per_device_eq_current),
                value = deviceLabel,
                enabled = perDeviceEq,
            )
            SettingsRow(
                icon = Icons.Rounded.Delete,
                title = stringResource(R.string.per_device_eq_forget),
                enabled = perDeviceEq,
                onClick = {
                    DeviceEqController.forgetCurrentDevice(context, routeKind, deviceName)
                },
            )

            ExtrasSectionTitle(stringResource(R.string.mono_audio))
            SettingsRow(
                icon = Icons.Rounded.Speaker,
                title = stringResource(R.string.mono_audio),
                subtitle = stringResource(R.string.mono_audio_subtitle),
                trailing = {
                    Switch(
                        checked = monoAudio,
                        onCheckedChange = AppSettings::setMonoAudioEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setMonoAudioEnabled(!monoAudio) },
            )
            SliderRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.balance),
                subtitle = stringResource(R.string.balance_subtitle),
                value = balanceLabel(balance),
                sliderValue = balance,
                onSliderValue = { AppSettings.setEqualizerBalance(it) },
                valueRange = -1f..1f,
                steps = 0,
            )

            ExtrasSectionTitle(stringResource(R.string.canvas_backdrop))
            SettingsRow(
                icon = Icons.Rounded.Animation,
                title = stringResource(R.string.canvas_backdrop),
                subtitle = stringResource(R.string.canvas_backdrop_subtitle),
                trailing = {
                    Switch(
                        checked = canvasEnabled,
                        onCheckedChange = AppSettings::setCanvasBackdropEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setCanvasBackdropEnabled(!canvasEnabled) },
            )

            ExtrasSectionTitle(stringResource(R.string.data_saver))
            SettingsRow(
                icon = Icons.Rounded.CloudOff,
                title = stringResource(R.string.data_saver),
                subtitle = stringResource(R.string.data_saver_subtitle),
                trailing = {
                    Switch(
                        checked = dataSaver,
                        onCheckedChange = AppSettings::setDataSaverEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setDataSaverEnabled(!dataSaver) },
            )
            if (dataSaver) {
                Text(
                    text = stringResource(R.string.data_saver_quality),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 52.dp, top = 4.dp, bottom = 4.dp),
                )
                AudioQuality.entries.forEach { quality ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { AppSettings.setDataSaverQuality(quality) }
                            .padding(horizontal = 52.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = quality.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = "${quality.detail} · ${quality.hourly}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        RadioButton(
                            selected = saverQuality == quality,
                            onClick = null,
                        )
                    }
                }
            }
            SettingsRow(
                icon = Icons.Rounded.CloudOff,
                title = stringResource(R.string.wifi_only_streaming),
                subtitle = stringResource(R.string.wifi_only_streaming_subtitle),
                trailing = {
                    Switch(
                        checked = wifiOnlyStreaming,
                        onCheckedChange = AppSettings::setWifiOnlyStreaming,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setWifiOnlyStreaming(!wifiOnlyStreaming) },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ExtrasSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 52.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun balanceLabel(balance: Float): String {
    if (abs(balance) < 0.02f) return stringResource(R.string.balance_center)
    val side = stringResource(
        if (balance < 0f) R.string.balance_left else R.string.balance_right,
    )
    return "$side ${(abs(balance) * 100).roundToInt()}%"
}

/** For previews and tests: the storage key behind the current device row. */
internal fun currentDeviceKeyForPreview(kind: AudioRouting.Kind, name: String): String =
    deviceKey(kind, name)
