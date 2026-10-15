package com.music.sonariq.ui.screens

import com.music.sonariq.ui.components.PAGE_GUTTER
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import com.music.sonariq.R

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.model.UiState
import com.music.sonariq.data.model.isSameTrackAs
import com.music.sonariq.ui.components.MessageState
import com.music.sonariq.ui.components.PlayingAccent
import com.music.sonariq.ui.components.songListSkeleton

/**
 * What the account has been listening to, most recent first.
 *
 * A history page with a quiet editorial header and a clean chronological list:
 * no single cover stands for the whole listening history.
 *
 * Tapping a row plays it with the rest of the history behind it, so the list
 * doubles as a queue that has already been approved once. That is the useful
 * reading of a history: not a receipt, but everything worth playing again.
 */
@Composable
fun HistoryScreen(
    state: UiState<List<Song>>,
    listState: LazyListState,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "history:header") {
            Column(Modifier.padding(horizontal = PAGE_GUTTER, vertical = 12.dp)) {
                Text("Your listening", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(5.dp))
                Text(stringResource(R.string.history), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(3.dp))
                Text("Pick up where you left off.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (state) {
            is UiState.Loading -> songListSkeleton(count = 10, keyPrefix = "skeleton:history")

            is UiState.Error -> item(key = "history:message") {
                MessageState(
                    message = state.message,
                    actionLabel = stringResource(R.string.try_again),
                    onAction = onRetry,
                )
            }

            is UiState.Success -> {
                val songs = state.data
                // The videoId alone is not a key here: the feed is deduplicated
                // on it, but a list keyed on something that could repeat is one
                // bad response away from a crash. The index makes it total.
                itemsIndexed(songs) { index, song ->
                    GlassSongTile(
                        song = song,
                        onClick = { onSongClick(songs, index) },
                        onLongPress = { onSongLongPress(song) },
                        onSwipeToQueue = { onSongSwipe(song) },
                        isCurrent = song.isSameTrackAs(currentSong),
                        isPlaying = song.isSameTrackAs(currentSong) && isPlaying,
                        activeTint = PlayingAccent,
                    )
                }
            }
        }
    }
}

/** Keyed on both, for the reason given at the call site. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    songs: List<Song>,
    row: @Composable (Int, Song) -> Unit,
) = items(
    count = songs.size,
    key = { "${songs[it].videoId}:$it" },
) { index -> row(index, songs[index]) }
