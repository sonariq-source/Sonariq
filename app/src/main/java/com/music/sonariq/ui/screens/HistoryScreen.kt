package com.music.sonariq.ui.screens

import com.music.sonariq.ui.components.PAGE_GUTTER
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.music.sonariq.ui.components.SongRow
import com.music.sonariq.ui.components.songListSkeleton

/**
 * What the account has been listening to, most recent first.
 *
 * A history page with its heading in a glass card and a clean chronological
 * list of glass rows under it: no single cover stands for the whole listening
 * history. The rows carry no day grouping — the history feed gives each song
 * no played-at timestamp to group by, so the list stays in the feed's own
 * most-recent-first order rather than inventing one.
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
            val headerShape = RoundedCornerShape(20.dp)
            Column(
                Modifier
                    .padding(horizontal = PAGE_GUTTER, vertical = 12.dp)
                    .clip(headerShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, headerShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), headerShape)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Text("Your listening", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(5.dp))
                Text(stringResource(R.string.history), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
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
                    HistoryRow(
                        song = song,
                        onClick = { onSongClick(songs, index) },
                        onLongPress = { onSongLongPress(song) },
                        onSwipeToQueue = { onSongSwipe(song) },
                        isCurrent = song.isSameTrackAs(currentSong),
                        isPlaying = song.isSameTrackAs(currentSong) && isPlaying,
                    )
                }
            }
        }
    }
}

/**
 * One history entry: a glass tile in the glass tone with 16dp corners,
 * holding the shared [SongRow] — rounded-square artwork, title over artist,
 * and the track's duration and actions at the trailing edge — with the same
 * tap, long-press and swipe-to-queue wiring the list has always had.
 */
@Composable
private fun HistoryRow(
    song: Song,
    onClick: () -> Unit,
    onLongPress: (Song) -> Unit,
    onSwipeToQueue: (Song) -> Unit,
    isCurrent: Boolean,
    isPlaying: Boolean,
) {
    val tileShape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(tileShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, tileShape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), tileShape),
    ) {
        SongRow(
            song = song,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            onLongPress = { onLongPress(song) },
            onSwipeToQueue = { onSwipeToQueue(song) },
            rowBackground = Color.Transparent,
            isCurrent = isCurrent,
            isPlaying = isPlaying,
            activeTint = PlayingAccent,
            searchPlayingStyle = true,
        )
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
