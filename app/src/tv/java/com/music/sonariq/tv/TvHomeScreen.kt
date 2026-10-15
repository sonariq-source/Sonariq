package com.music.sonariq.tv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import com.music.sonariq.data.YtMusicRepository
import com.music.sonariq.data.model.SearchFilter
import com.music.sonariq.data.model.SearchResult
import com.music.sonariq.data.model.Song
import com.music.sonariq.playback.playSongs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sonariq TV home: search for music and play it. D-pad navigable —
 * the search field takes focus first, results below it.
 */
@Composable
fun TvHomeScreen(
    controller: MediaController?,
    onPlaySong: (Song) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val searchFocus = remember { FocusRequester() }

    fun runSearch(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) {
            results = emptyList()
            searching = false
            return
        }
        searching = true
        searchJob = scope.launch {
            delay(500) // Debounce the TV keyboard.
            val page = runCatching {
                YtMusicRepository.searchPage(q, SearchFilter.SONGS)
            }.getOrNull()?.getOrNull()
            results = page?.rows?.mapNotNull {
                when (it) {
                    is SearchResult.TopTrack -> it.song
                    is SearchResult.Track -> it.song
                    else -> null
                }
            } ?: emptyList()
            searching = false
        }
    }

    LaunchedEffect(Unit) {
        searchFocus.requestFocus()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(48.dp),
    ) {
        Text(
            text = "Sonariq TV",
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 40.sp),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                runSearch(it)
            },
            label = { Text("Search songs") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch(query) }),
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .focusRequester(searchFocus),
        )
        Spacer(Modifier.height(24.dp))
        if (searching) {
            CircularProgressIndicator()
        } else if (results.isEmpty() && query.isNotBlank()) {
            Text(
                text = "No results",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 20.sp,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(results, key = { it.videoId }) { song ->
                    TvSongRow(
                        song = song,
                        onClick = {
                            scope.launch {
                                controller?.playSongs(listOf(song), 0)
                                onPlaySong(song)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvSongRow(
    song: Song,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        AsyncImage(
            model = song.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
