package com.music.sonariq.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.sonariq.R
import com.music.sonariq.data.library.LibraryBackup
import kotlinx.coroutines.launch

/**
 * Library backup and restore: one file carrying settings, listening history,
 * search history, playlists, folders, smart folders, and the download
 * record.
 *
 * Both directions go through the Storage Access Framework — the user picks
 * where the file lives, and no storage permission is needed. Importing
 * replaces the library, so it asks for confirmation first and reports exactly
 * what the file contained afterwards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryBackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            exportStatus = LibraryBackup.exportTo(context, target).fold(
                onSuccess = { count ->
                    context.getString(R.string.library_backup_exported, count)
                },
                onFailure = { it.message ?: context.getString(R.string.library_backup_failed) },
            )
            busy = false
        }
    }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            importStatus = LibraryBackup.importFrom(context, source).fold(
                onSuccess = { summary ->
                    context.getString(
                        R.string.library_backup_imported,
                        summary.playlists,
                        summary.folders,
                        summary.downloads,
                    )
                },
                onFailure = { it.message ?: context.getString(R.string.library_backup_failed) },
            )
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.library_backup_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.library_backup_export)) },
                        supportingContent = {
                            Text(exportStatus ?: stringResource(R.string.library_backup_export_subtitle))
                        },
                        leadingContent = { Icon(Icons.Rounded.FileUpload, contentDescription = null) },
                        modifier = Modifier.clickable(enabled = !busy) {
                            exportPicker.launch(LibraryBackup.suggestedName())
                        },
                    )
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.library_backup_import)) },
                        supportingContent = {
                            Text(importStatus ?: stringResource(R.string.library_backup_import_subtitle))
                        },
                        leadingContent = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
                        modifier = Modifier.clickable(enabled = !busy) { confirmImport = true },
                    )
                }
            }
            Text(
                stringResource(R.string.library_backup_downloads_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.library_backup_import_confirm_title)) },
            text = { Text(stringResource(R.string.library_backup_import_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    importPicker.launch(arrayOf("application/json"))
                }) { Text(stringResource(R.string.library_backup_import_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
