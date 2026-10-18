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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.sonariq.R
import com.music.sonariq.data.library.M3uPlaylists
import com.music.sonariq.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A playlist as the transfer screen needs it: an id, a title, its songs. */
data class TransferPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
)

/**
 * m3u import and export through the Storage Access Framework — no storage
 * permission needed, the user picks both ends.
 *
 * Export writes one `.m3u` per playlist, with real file paths where the song
 * has one. Import parses the file, matches each entry against the library
 * (path first, then artist/title), and reports exactly which lines matched
 * and which didn't before anything is added anywhere. Adding the matched
 * songs is caller-wired ([onImportSongs]) because the destination playlist
 * belongs to the caller's navigation.
 *
 * @param playlists the local playlists available for export.
 * @param librarySongs everything import matching may match against.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M3uTransferScreen(
    playlists: List<TransferPlaylist>,
    librarySongs: List<Song>,
    onImportSongs: (songs: List<Song>, suggestedTitle: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportTarget by remember { mutableStateOf<TransferPlaylist?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var importReport by remember { mutableStateOf<ImportReport?>(null) }
    var busy by remember { mutableStateOf(false) }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { uri ->
        val playlist = exportTarget ?: return@rememberLauncherForActivityResult
        exportTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val text = M3uPlaylists.build(playlist.songs)
            exportStatus = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")
                        ?.use { it.write(text.toByteArray()) }
                        ?: error("Couldn't open that file for writing")
                    context.getString(R.string.library_m3u_exported, playlist.songs.size, playlist.title)
                }.getOrElse { it.message ?: context.getString(R.string.library_m3u_failed) }
            }
            busy = false
        }
    }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            importReport = withContext(Dispatchers.Default) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes().decodeToString() }
                        ?: error("Couldn't open that file")
                    val entries = M3uPlaylists.parse(text)
                    if (entries.isEmpty()) error(context.getString(R.string.library_m3u_empty))
                    val matches = M3uPlaylists.matchAll(entries, librarySongs)
                    val found = matches.filterIsInstance<M3uPlaylists.Match.Found>().map { it.song }
                    val missing = matches.filterIsInstance<M3uPlaylists.Match.Missing>().map { it.entry }
                    val name = uri.lastPathSegment
                        ?.substringAfterLast('/')
                        ?.substringBeforeLast('.')
                        ?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.library_m3u_imported_title)
                    ImportReport(found = found, missingTitles = missing.map { it.title }, suggestedTitle = name)
                }.getOrElse {
                    ImportReport(found = emptyList(), missingTitles = emptyList(), suggestedTitle = "", error = it.message)
                }
            }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_m3u_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "export-header") {
                SectionLabel(stringResource(R.string.library_m3u_export), Icons.Rounded.FileUpload)
            }
            if (playlists.isEmpty()) {
                item(key = "no-playlists") {
                    Text(
                        stringResource(R.string.library_m3u_no_playlists),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(playlists, key = { "pl:${it.id}" }) { playlist ->
                ListItem(
                    headlineContent = { Text(playlist.title) },
                    supportingContent = {
                        Text(stringResource(R.string.library_m3u_playlist_subtitle, playlist.songs.size))
                    },
                    modifier = Modifier.clickable(enabled = !busy) {
                        exportTarget = playlist
                        exportPicker.launch("${playlist.title}.m3u")
                    },
                )
            }
            exportStatus?.let {
                item(key = "export-status") {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            item(key = "import-header") {
                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.library_m3u_import), Icons.Rounded.FileDownload)
            }
            item(key = "import-button") {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        importPicker.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "*/*"))
                    },
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.library_m3u_pick_file)) },
                        supportingContent = { Text(stringResource(R.string.library_m3u_pick_file_subtitle)) },
                        leadingContent = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
                    )
                }
            }
            importReport?.let { report ->
                if (report.error != null) {
                    item(key = "import-error") {
                        Text(
                            report.error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    item(key = "import-summary") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(
                                    R.string.library_m3u_import_summary,
                                    report.found.size,
                                    report.missingTitles.size,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (report.missingTitles.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.library_m3u_import_missing) +
                                        report.missingTitles.take(5).joinToString(", ") +
                                        if (report.missingTitles.size > 5) " …" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (report.found.isNotEmpty()) {
                                TextButton(onClick = {
                                    onImportSongs(report.found, report.suggestedTitle)
                                    importReport = null
                                }) {
                                    Text(stringResource(R.string.library_m3u_import_add, report.found.size))
                                }
                            }
                        }
                    }
                }
            }
            item(key = "bottom-space") { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private data class ImportReport(
    val found: List<Song>,
    val missingTitles: List<String>,
    val suggestedTitle: String,
    val error: String? = null,
)

@Composable
private fun SectionLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}
