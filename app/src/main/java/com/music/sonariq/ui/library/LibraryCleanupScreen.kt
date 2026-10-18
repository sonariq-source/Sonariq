package com.music.sonariq.ui.library

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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.sonariq.R
import com.music.sonariq.data.library.LibraryCleanup
import com.music.sonariq.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Library cleanup: duplicate songs found by real title/artist/duration
 * comparison, and tracks with missing metadata plus what the file name says
 * the missing field should be.
 *
 * Nothing here is applied silently. Each duplicate group names the copy to
 * keep and offers removal of the rest; each metadata gap shows the suggested
 * fix and applies it only on tap. Both actions are caller-wired
 * ([onRemoveDuplicates], [onApplyFix]) because deleting a track or rewriting
 * its tags belongs to the store that owns it.
 *
 * @param songs the library to scan: local playlists, downloads and device
 *   tracks, supplied by the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryCleanupScreen(
    songs: List<Song>,
    onSongClick: (Song) -> Unit,
    onRemoveDuplicates: (LibraryCleanup.DuplicateGroup) -> Unit,
    onApplyFix: (LibraryCleanup.MetadataGap) -> Unit,
    onBack: () -> Unit,
) {
    var scanning by remember { mutableStateOf(true) }
    var duplicates by remember { mutableStateOf<List<LibraryCleanup.DuplicateGroup>>(emptyList()) }
    var gaps by remember { mutableStateOf<List<LibraryCleanup.MetadataGap>>(emptyList()) }

    LaunchedEffect(songs) {
        scanning = true
        val (dups, metadata) = withContext(Dispatchers.Default) {
            LibraryCleanup.findDuplicates(songs) to LibraryCleanup.findMetadataGaps(songs)
        }
        duplicates = dups
        gaps = metadata
        scanning = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_cleanup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        if (scanning) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.library_cleanup_scanning))
            }
            return@Scaffold
        }
        if (duplicates.isEmpty() && gaps.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp),
                )
                Text(
                    stringResource(R.string.library_cleanup_clean),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.library_cleanup_clean_subtitle, songs.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (duplicates.isNotEmpty()) {
                item(key = "dups-header") {
                    CleanupHeader(
                        icon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                        title = stringResource(R.string.library_cleanup_duplicates, duplicates.size),
                    )
                }
                items(duplicates, key = { it.songs.first().videoId + it.songs.size }) { group ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text(
                                group.reason,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            group.songs.forEach { song ->
                                val isKeep = song.videoId == group.keep.videoId
                                ListItem(
                                    headlineContent = { Text(song.title.ifBlank { song.videoId }) },
                                    supportingContent = {
                                        Text(
                                            (if (song.artist.isBlank()) "" else song.artist + " • ") +
                                                (song.durationText ?: "") +
                                                if (isKeep) " • " + stringResource(R.string.library_cleanup_keep) else "",
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { onRemoveDuplicates(group) }) {
                                    Text(stringResource(R.string.library_cleanup_remove_others))
                                }
                            }
                        }
                    }
                }
            }
            if (gaps.isNotEmpty()) {
                item(key = "gaps-header") {
                    CleanupHeader(
                        icon = { Icon(Icons.Rounded.Tag, contentDescription = null) },
                        title = stringResource(R.string.library_cleanup_metadata, gaps.size),
                    )
                }
                items(gaps, key = { it.song.videoId + it.field }) { gap ->
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(
                                    R.string.library_cleanup_gap_title,
                                    gap.field,
                                    gap.song.title.ifBlank { gap.song.videoId },
                                ),
                            )
                        },
                        supportingContent = {
                            Text(
                                gap.suggestion?.let {
                                    stringResource(R.string.library_cleanup_gap_suggestion, it)
                                } ?: stringResource(R.string.library_cleanup_gap_no_suggestion),
                            )
                        },
                        trailingContent = {
                            if (gap.suggestion != null) {
                                TextButton(onClick = { onApplyFix(gap) }) {
                                    Text(stringResource(R.string.library_cleanup_apply))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item(key = "bottom-space") { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun CleanupHeader(icon: @Composable () -> Unit, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    ) {
        icon()
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}
