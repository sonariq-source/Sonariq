package com.music.sonariq.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.sonariq.R
import com.music.sonariq.data.settings.AppSettings

/**
 * The auto-downloads and scheduled-downloads rows for the Downloads settings
 * group: a master switch, liked-songs switch, playlist picker, Wi-Fi-only
 * switch, and the overnight schedule with a time picker.
 *
 * All switches read and write [AppSettings] directly, following the existing
 * settings rows. The [AppSettings] keys used here are added in the
 * integration phase — see the integration manifest for the full list.
 *
 * The workers are (re)scheduled from the switch handlers: turning the master
 * switch on schedules the daily worker, turning it off cancels it, and the
 * same for the overnight schedule.
 *
 * @param playlists the local playlists the picker can offer, as id/title pairs.
 */
@Composable
fun AutoDownloadSettings(playlists: List<Pair<String, String>>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val autoEnabled by AppSettings.autoDownloadsEnabled.collectAsState()
    val likedEnabled by AppSettings.autoDownloadLikedSongs.collectAsState()
    val chosenIds by AppSettings.autoDownloadPlaylistIds.collectAsState()
    val wifiOnly by AppSettings.autoDownloadWifiOnly.collectAsState()
    val scheduledEnabled by AppSettings.scheduledDownloadsEnabled.collectAsState()
    val scheduledHour by AppSettings.scheduledDownloadHour.collectAsState()
    val scheduledMinute by AppSettings.scheduledDownloadMinute.collectAsState()
    val scheduledWifiOnly by AppSettings.scheduledDownloadWifiOnly.collectAsState()

    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val chosenNames = playlists.filter { it.first in chosenIds }.map { it.second }

    SettingsSwitchRow(
        title = stringResource(R.string.library_auto_downloads),
        subtitle = stringResource(R.string.library_auto_downloads_subtitle),
        checked = autoEnabled,
        onCheckedChange = { on ->
            AppSettings.setAutoDownloadsEnabled(on)
            if (on) com.music.sonariq.data.library.AutoDownloads.schedule(context)
            else com.music.sonariq.data.library.AutoDownloads.cancel(context)
        },
    )
    if (autoEnabled) {
        SettingsSwitchRow(
            title = stringResource(R.string.library_auto_download_liked),
            subtitle = stringResource(R.string.library_auto_download_liked_subtitle),
            checked = likedEnabled,
            onCheckedChange = AppSettings::setAutoDownloadLikedSongs,
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.library_auto_download_playlists)) },
            supportingContent = {
                Text(
                    if (chosenNames.isEmpty()) stringResource(R.string.library_auto_download_playlists_none)
                    else chosenNames.joinToString(", "),
                )
            },
            modifier = Modifier.clickable { showPlaylistPicker = true },
        )
        SettingsSwitchRow(
            title = stringResource(R.string.library_auto_download_wifi_only),
            subtitle = stringResource(R.string.library_auto_download_wifi_only_subtitle),
            checked = wifiOnly,
            onCheckedChange = { on ->
                AppSettings.setAutoDownloadWifiOnly(on)
                // Constraints changed: re-schedule so the worker picks them up.
                com.music.sonariq.data.library.AutoDownloads.schedule(context)
            },
        )
    }
    SettingsSwitchRow(
        title = stringResource(R.string.library_scheduled_downloads),
        subtitle = stringResource(R.string.library_scheduled_downloads_subtitle),
        checked = scheduledEnabled,
        onCheckedChange = { on ->
            AppSettings.setScheduledDownloadsEnabled(on)
            if (on) com.music.sonariq.data.library.ScheduledDownloads.schedule(context)
            else com.music.sonariq.data.library.ScheduledDownloads.cancel(context)
        },
    )
    if (scheduledEnabled) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.library_scheduled_time)) },
            supportingContent = {
                Text(
                    stringResource(
                        R.string.library_scheduled_time_value,
                        scheduledHour,
                        scheduledMinute.toString().padStart(2, '0'),
                    ),
                )
            },
            modifier = Modifier.clickable { showTimePicker = true },
        )
        SettingsSwitchRow(
            title = stringResource(R.string.library_auto_download_wifi_only),
            subtitle = stringResource(R.string.library_auto_download_wifi_only_subtitle),
            checked = scheduledWifiOnly,
            onCheckedChange = { on ->
                AppSettings.setScheduledDownloadWifiOnly(on)
                com.music.sonariq.data.library.ScheduledDownloads.schedule(context)
            },
        )
        Text(
            stringResource(R.string.library_scheduled_downloads_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    if (showPlaylistPicker) {
        PlaylistMultiSelectDialog(
            playlists = playlists,
            checked = chosenIds,
            onDismiss = { showPlaylistPicker = false },
            onConfirm = { ids ->
                AppSettings.setAutoDownloadPlaylistIds(ids)
                showPlaylistPicker = false
            },
        )
    }
    if (showTimePicker) {
        TimePickerDialog(
            hour = scheduledHour,
            minute = scheduledMinute,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                AppSettings.setScheduledDownloadHour(hour)
                AppSettings.setScheduledDownloadMinute(minute)
                com.music.sonariq.data.library.ScheduledDownloads.schedule(context)
                showTimePicker = false
            },
        )
    }
}

/** A plain switch row, matching the shape of the existing settings rows. */
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        modifier = Modifier.clickable { onCheckedChange(!checked) },
    )
}

@Composable
private fun PlaylistMultiSelectDialog(
    playlists: List<Pair<String, String>>,
    checked: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(checked) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_auto_download_playlists)) },
        text = {
            if (playlists.isEmpty()) {
                Text(stringResource(R.string.library_m3u_no_playlists))
            } else {
                LazyColumn {
                    items(playlists, key = { it.first }) { (id, title) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                selected = if (id in selected) selected - id else selected + id
                            },
                        ) {
                            Checkbox(
                                checked = id in selected,
                                onCheckedChange = { on ->
                                    selected = if (on) selected + id else selected - id
                                },
                            )
                            Text(title, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    hour: Int,
    minute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val state: TimePickerState = rememberTimePickerState(initialHour = hour, initialMinute = minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_scheduled_time)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
