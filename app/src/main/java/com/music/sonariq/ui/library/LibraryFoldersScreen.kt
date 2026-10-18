package com.music.sonariq.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.music.sonariq.data.library.PlaylistFolder
import com.music.sonariq.data.library.PlaylistFolders
import com.music.sonariq.data.library.SmartFolder

/**
 * Playlist folders and smart folders.
 *
 * Plain folders group playlists; smart folders fill themselves from rules
 * every time they open. Creating and deleting here calls straight into
 * [PlaylistFolders] — no intermediate state, no fake rows.
 *
 * @param playlistTitles resolves a playlist id to its title for the
 *   "N playlists" subtitle lines.
 * @param onOpenSmartFolder opens a smart folder's contents (wired by the
 *   caller to whatever shows a track list).
 * @param onEditSmartFolder opens the rule editor for a smart folder, or null
 *   to create a new one.
 * @param onBack closes the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryFoldersScreen(
    playlistTitles: Map<String, String>,
    onOpenSmartFolder: (SmartFolder) -> Unit,
    onEditSmartFolder: (SmartFolder?) -> Unit,
    onBack: () -> Unit,
) {
    val folders by PlaylistFolders.folders.collectAsState()
    val smartFolders by PlaylistFolders.smartFolders.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var showCreateSmart by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<PlaylistFolder?>(null) }
    var confirmDeleteSmart by remember { mutableStateOf<SmartFolder?>(null) }

    androidx.compose.runtime.LaunchedEffect(showCreateSmart) {
        if (showCreateSmart) {
            // The rule editor (caller side) creates the folder on save;
            // nothing is persisted here, so backing out leaves no dead
            // folder behind.
            onEditSmartFolder(null)
            showCreateSmart = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_folders_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(onClick = { showCreateSmart = true }) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = stringResource(R.string.library_new_smart_folder))
                }
                FloatingActionButton(onClick = { showCreate = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.library_new_folder))
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (smartFolders.isNotEmpty()) {
                item(key = "smart-header") {
                    SectionHeader(stringResource(R.string.library_smart_folders))
                }
                items(smartFolders, key = { "smart:${it.id}" }) { folder ->
                    val ruleCount = folder.rules.size
                    ListItem(
                        headlineContent = { Text(folder.title) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.library_smart_folder_subtitle,
                                    ruleCount,
                                    if (folder.matchAll) stringResource(R.string.library_rule_match_all)
                                    else stringResource(R.string.library_rule_match_any),
                                ),
                            )
                        },
                        leadingContent = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                        trailingContent = {
                            IconButton(onClick = { confirmDeleteSmart = folder }) {
                                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete))
                            }
                        },
                        modifier = Modifier.clickable {
                            if (folder.rules.isNotEmpty()) onOpenSmartFolder(folder)
                            else onEditSmartFolder(folder)
                        },
                    )
                }
                item(key = "smart-edit-hint") {
                    Text(
                        stringResource(R.string.library_smart_folder_edit_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            item(key = "folders-header") {
                SectionHeader(stringResource(R.string.library_folders))
            }
            if (folders.isEmpty() && smartFolders.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.library_folders_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(folders, key = { "folder:${it.id}" }) { folder ->
                val names = folder.playlistIds.mapNotNull { playlistTitles[it] }
                ListItem(
                    headlineContent = { Text(folder.title) },
                    supportingContent = {
                        Text(
                            if (names.isEmpty()) stringResource(R.string.library_folder_empty)
                            else names.take(3).joinToString(", ") +
                                if (names.size > 3) " +" + (names.size - 3) else "",
                        )
                    },
                    leadingContent = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                    trailingContent = {
                        IconButton(onClick = { confirmDelete = folder }) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    },
                )
            }
            item(key = "bottom-space") { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showCreate) {
        NameDialog(
            title = stringResource(R.string.library_new_folder),
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                PlaylistFolders.createFolder(name)
                showCreate = false
            },
        )
    }
    confirmDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.library_delete_folder_title, folder.title)) },
            text = { Text(stringResource(R.string.library_delete_folder_text)) },
            confirmButton = {
                TextButton(onClick = {
                    PlaylistFolders.deleteFolder(folder.id)
                    confirmDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    confirmDeleteSmart?.let { folder ->
        AlertDialog(
            onDismissRequest = { confirmDeleteSmart = null },
            title = { Text(stringResource(R.string.library_delete_folder_title, folder.title)) },
            text = { Text(stringResource(R.string.library_delete_folder_text)) },
            confirmButton = {
                TextButton(onClick = {
                    PlaylistFolders.deleteSmartFolder(folder.id)
                    confirmDeleteSmart = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSmart = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** A single-field name dialog, shared by the folder screens. */
@Composable
fun NameDialog(
    title: String,
    initial: String = "",
    confirmLabel: String = stringResource(R.string.library_action_create),
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
