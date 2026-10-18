package com.music.sonariq.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.sonariq.R
import com.music.sonariq.data.library.StorageManager
import kotlinx.coroutines.launch

/**
 * What Sonariq keeps on disk, measured for real: downloads, the audio cache,
 * the image cache, and saved artwork — plus one-tap cache clearing.
 *
 * The numbers come from [StorageManager.measure]: directory walks and a
 * media-store query, never estimates. Clearing touches only the two caches,
 * which re-fetch themselves; downloads and artwork are left alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageManagerScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<StorageManager.Usage?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var clearedNote by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(context) {
        usage = StorageManager.measure(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_storage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        val current = usage
        if (current == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.library_storage_measuring))
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        StorageManager.formatBytes(current.totalBytes),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.library_storage_total),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            UsageRow(
                icon = { Icon(Icons.Rounded.MusicNote, contentDescription = null) },
                title = stringResource(R.string.library_storage_downloads),
                subtitle = stringResource(R.string.library_storage_downloads_subtitle, current.downloadFiles),
                bytes = current.downloadsBytes,
            )
            UsageRow(
                icon = { Icon(Icons.Rounded.AudioFile, contentDescription = null) },
                title = stringResource(R.string.library_storage_audio_cache),
                subtitle = stringResource(R.string.library_storage_audio_cache_subtitle),
                bytes = current.audioCacheBytes,
            )
            UsageRow(
                icon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                title = stringResource(R.string.library_storage_image_cache),
                subtitle = stringResource(R.string.library_storage_image_cache_subtitle),
                bytes = current.imageCacheBytes,
            )
            UsageRow(
                icon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
                title = stringResource(R.string.library_storage_artwork),
                subtitle = stringResource(R.string.library_storage_artwork_subtitle),
                bytes = current.artworkBytes,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { confirmClear = true },
                enabled = !clearing && current.clearableBytes > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text(
                    stringResource(
                        R.string.library_storage_clear,
                        StorageManager.formatBytes(current.clearableBytes),
                    ),
                )
            }
            clearedNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.library_storage_clear_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.library_storage_clear_title)) },
            text = { Text(stringResource(R.string.library_storage_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    clearing = true
                    scope.launch {
                        val cleared = StorageManager.clearCaches(context)
                        clearedNote = context.getString(
                            R.string.library_storage_cleared,
                            StorageManager.formatBytes(cleared.totalBytes),
                        )
                        usage = StorageManager.measure(context)
                        clearing = false
                    }
                }) { Text(stringResource(R.string.library_storage_clear_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun UsageRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    bytes: Long,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = icon,
        trailingContent = {
            Text(
                StorageManager.formatBytes(bytes),
                style = MaterialTheme.typography.titleMedium,
            )
        },
    )
}
