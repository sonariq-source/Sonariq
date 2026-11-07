package com.music.sonariq.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import com.music.sonariq.data.YtMusicRepository
import com.music.sonariq.data.listentogether.ListenTogether
import com.music.sonariq.data.model.SearchFilter
import com.music.sonariq.data.model.SearchResult
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.model.artworkAt
import com.music.sonariq.playback.playSongs
import com.music.sonariq.util.DeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sonariq TV home: search for music and play it. D-pad navigable.
 *
 * Two-pane layout: search and the on-screen keyboard on the left, results
 * on the right. Thumbnails load at 128px — a 64dp row needs no more, and on
 * a low-end TV the smaller decode keeps scrolling smooth.
 *
 * Reconstructed geometry: one sans type system (the theme's Inter), bold
 * section headers, consistent TV gutters, and results presented as a shelf
 * of fixed-height rows — small uniform-corner artwork, title/artist stacked,
 * duration as trailing meta, and an emerald focus ring with a slight scale.
 */
@Composable
fun TvHomeScreen(
    controller: MediaController?,
    onPlaySong: (Song) -> Unit,
    onOpenParty: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val keyboardFocus = remember { FocusRequester() }

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

    fun type(char: String) {
        query += char
        runSearch(query)
    }

    LaunchedEffect(Unit) {
        keyboardFocus.requestFocus()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 40.dp),
    ) {
        // Header: brand left, party right.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Sonariq TV",
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 40.sp),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            val partyState by ListenTogether.state.collectAsStateWithLifecycle()
            TvTextButton(
                label = partyState.code?.let { "Party $it" } ?: "Listen Together",
                onClick = onOpenParty,
            )
        }
        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // Left pane: query + keyboard.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Text(
                    text = query.ifEmpty { "Type to search…" },
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                TvKeyboard(
                    onKey = ::type,
                    onBackspace = {
                        if (query.isNotEmpty()) {
                            query = query.dropLast(1)
                            runSearch(query)
                        }
                    },
                    onClear = {
                        query = ""
                        runSearch("")
                    },
                    onDone = { runSearch(query) },
                    firstKeyFocus = keyboardFocus,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = DeviceInfo.displayName,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }

            Spacer(Modifier.width(48.dp))

            // Right pane: results.
            Column(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight(),
            ) {
                // Bold sans section header for the results shelf.
                Text(
                    text = if (searching) "Searching…"
                    else if (results.isNotEmpty()) "${results.size} results"
                    else "Results",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))
                if (searching && results.isEmpty()) {
                    CircularProgressIndicator()
                } else if (results.isEmpty() && query.isNotBlank()) {
                    Text(
                        text = "No results",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 20.sp,
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
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
    }
}

@Composable
private fun TvSongRow(
    song: Song,
    onClick: () -> Unit,
) {
    // Same clickable/focus path as before; the interaction source only
    // drives the new focus presentation (emerald ring + slight scale).
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val rowShape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .scale(if (focused) 1.02f else 1f)
            .clip(rowShape)
            .background(
                if (focused) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color(0xFF32D979) else Color.Transparent,
                shape = rowShape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
    ) {
        // Small rounded-square artwork, uniform 12dp corners.
        AsyncImage(
            model = song.artworkAt(128),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(16.dp))
        // Title + artist stacked, taking the flexible middle of the row.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Trailing meta: the track duration, when the row stated one.
        song.durationText?.takeIf { it.isNotBlank() }?.let { duration ->
            Spacer(Modifier.width(16.dp))
            Text(
                text = duration,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (focused) Color(0xFF32D979)
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
