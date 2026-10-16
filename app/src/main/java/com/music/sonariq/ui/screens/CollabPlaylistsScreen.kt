package com.music.sonariq.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.sonariq.R
import com.music.sonariq.data.YtMusicRepository
import com.music.sonariq.data.collab.CollabContribution
import com.music.sonariq.data.collab.CollabMember
import com.music.sonariq.data.collab.CollabPlaylist
import com.music.sonariq.data.collab.CollabPlaylistStore
import com.music.sonariq.data.listentogether.ListenTogether
import com.music.sonariq.data.model.SearchFilter
import com.music.sonariq.data.model.SearchResult
import com.music.sonariq.data.model.Song
import com.music.sonariq.ui.components.PillTextField
import kotlinx.coroutines.delay

/**
 * The "Collaborative playlists" group on the Listen Together page.
 *
 * Playlists persist on this device across parties, so the group is always
 * visible; creating one needs the party it syncs through, so the create row
 * is only enabled while in a party.
 */
@Composable
fun CollabPlaylistsGroup(
    inParty: Boolean,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val playlists by CollabPlaylistStore.playlists.collectAsStateWithLifecycle()
    SettingsGroup(
        header = stringResource(R.string.collab_playlists_title),
    ) {
        if (playlists.isEmpty()) {
            Text(
                text = stringResource(R.string.collab_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ROW_INSET, vertical = 12.dp),
            )
            RowDivider()
        } else {
            playlists.forEachIndexed { index, playlist ->
                if (index > 0) RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.QueueMusic,
                    title = playlist.title,
                    subtitle = stringResource(
                        R.string.collab_songs_members,
                        playlist.contributions.size,
                        playlist.members.size,
                    ),
                    onClick = { onOpen(playlist.id) },
                )
            }
            RowDivider()
        }
        SettingsRow(
            icon = Icons.Rounded.Add,
            title = stringResource(R.string.collab_new_playlist),
            subtitle = if (inParty) null else stringResource(R.string.collab_new_playlist_needs_party),
            enabled = inParty,
            onClick = onCreate,
        )
    }
}

/** The name prompt for a new collaborative playlist, raised over the party page. */
@Composable
fun CreateCollabPlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.collab_new_playlist),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            PillTextField(
                value = name,
                onValueChange = { name = it.take(120) },
                placeholder = stringResource(R.string.collab_playlist_name_hint),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(
                    text = stringResource(R.string.collab_create),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/**
 * A collaborative playlist, full page: members, who added what, search-and-add,
 * play-through-the-party, and delete.
 *
 * Drawn over the Listen Together page by its caller; [onBack] returns to it.
 */
@Composable
fun CollabPlaylistDetailPage(
    playlistId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val playlists by CollabPlaylistStore.playlists.collectAsStateWithLifecycle()
    val partyState by ListenTogether.state.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    if (playlist == null) {
        // Deleted from elsewhere; nothing to show.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val inOwnParty = partyState.inParty && partyState.code == playlist.partyCode
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = 20.dp, top = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = colors.onBackground,
                )
            }
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp)) {
            Text(
                text = stringResource(
                    R.string.collab_songs_members,
                    playlist.contributions.size,
                    playlist.members.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (inOwnParty) {
                    stringResource(R.string.collab_sync_in_party, playlist.partyCode.orEmpty())
                } else {
                    stringResource(R.string.collab_sync_offline)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (inOwnParty) colors.primary else colors.onSurfaceVariant,
            )
        }

        if (playlist.members.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp),
            ) {
                playlist.members.forEach { member ->
                    CollabMemberChip(member)
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
        ) {
            Button(
                onClick = {
                    if (!CollabPlaylistStore.playInParty(playlist.id)) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.collab_sync_offline),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                enabled = inOwnParty && playlist.contributions.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.collab_play_in_party))
            }
            OutlinedButton(onClick = { adding = !adding }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.collab_add_songs))
            }
        }

        if (adding) {
            CollabSongSearch(
                playlist = playlist,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
            )
        }

        if (playlist.contributions.isEmpty()) {
            Text(
                text = stringResource(R.string.collab_no_songs),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surfaceVariant),
            ) {
                playlist.contributions.forEachIndexed { index, contribution ->
                    if (index > 0) RowDivider()
                    CollabSongRow(
                        contribution = contribution,
                        onRemove = {
                            CollabPlaylistStore.removeSong(playlist.id, contribution.videoId)
                        },
                    )
                }
            }
        }

        TextButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.padding(start = 12.dp, top = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.collab_delete),
                color = colors.error,
            )
        }
        Spacer(Modifier.height(32.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(
                    text = stringResource(R.string.collab_delete_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.collab_delete_message, playlist.title),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        CollabPlaylistStore.deletePlaylist(playlist.id)
                        onBack()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.collab_delete),
                        color = colors.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = colors.surface,
        )
    }
}

@Composable
private fun CollabMemberChip(member: CollabMember) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.surfaceVariant)
            .padding(end = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (member.avatarUrl != null) {
                AsyncImage(
                    model = member.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(32.dp).clip(CircleShape),
                )
            } else {
                Text(
                    text = member.displayName.take(1).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                )
            }
        }
        Text(
            text = member.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CollabSongRow(
    contribution: CollabContribution,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (contribution.thumbnailUrl != null) {
                AsyncImage(
                    model = contribution.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contribution.title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val artist = contribution.artist.takeIf { it.isNotBlank() }
            val addedBy = contribution.addedByName?.takeIf { it.isNotBlank() }
            Text(
                text = listOfNotNull(
                    artist,
                    if (addedBy != null) {
                        stringResource(R.string.collab_added_by, addedBy)
                    } else {
                        stringResource(R.string.collab_added_via_party)
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.collab_remove),
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Search-and-add for a collaborative playlist: the same debounced YouTube
 * Music song search the party's own add-songs sheet uses, with an Add button
 * per row that attributes the song to this device's party identity.
 */
@Composable
private fun CollabSongSearch(
    playlist: CollabPlaylist,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            results = emptyList()
            searching = false
            error = null
            return@LaunchedEffect
        }
        delay(400)
        if (query.trim() != trimmed) return@LaunchedEffect
        searching = true
        error = null
        YtMusicRepository.search(trimmed, SearchFilter.SONGS)
            .onSuccess { rows ->
                results = rows.mapNotNull { row ->
                    when (row) {
                        is SearchResult.TopTrack -> row.song
                        is SearchResult.Track -> row.song
                        is SearchResult.Browse -> null
                    }
                }
                    .filter { it.videoId.isNotBlank() }
                    .distinctBy { it.videoId }
            }
            .onFailure { failure ->
                results = emptyList()
                error = failure.message
            }
        searching = false
    }

    Column(modifier = modifier.fillMaxWidth()) {
        PillTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.collab_search_hint),
            container = colors.surfaceVariant,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        Spacer(Modifier.height(8.dp))
        when {
            searching -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 10.dp),
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = colors.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.collab_searching),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            error != null -> {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            else -> {
                val existing = playlist.contributions.map { it.videoId }.toSet()
                results.forEach { song ->
                    val alreadyAdded = song.videoId in existing
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(
                            onClick = {
                                if (CollabPlaylistStore.addSong(playlist.id, song)) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.collab_added_toast, playlist.title),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            enabled = !alreadyAdded,
                        ) {
                            Text(
                                text = stringResource(R.string.collab_add),
                                color = if (alreadyAdded) colors.onSurfaceVariant else colors.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}
