package com.music.sonariq.tv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.lyrics.LyricsRepository
import com.music.sonariq.data.lyrics.LyricsSource
import com.music.sonariq.data.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Sonariq TV player: big karaoke lyrics that follow the music, with
 * D-pad transport controls. Lyrics load automatically from every provider.
 */
@Composable
fun TvPlayerScreen(
    song: Song,
    controller: MediaController?,
    onBack: () -> Unit,
) {
    var lyrics by remember(song.videoId) { mutableStateOf<List<LyricLine>?>(null) }
    var loadingLyrics by remember(song.videoId) { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    val backFocus = remember { FocusRequester() }

    // Load lyrics automatically — every provider, best sync wins.
    LaunchedEffect(song.videoId) {
        loadingLyrics = true
        lyrics = runCatching {
            LyricsRepository.lyrics(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                durationMs = controller?.duration?.takeIf { it > 0 } ?: 0L,
                sources = LyricsSource.entries.toSet(),
                order = LyricsSource.entries,
                prioritizeSyllableSync = true,
            )?.lines
        }.getOrNull()
        loadingLyrics = false
    }

    // Follow playback position.
    LaunchedEffect(controller) {
        while (isActive) {
            positionMs = controller?.currentPosition ?: 0L
            isPlaying = controller?.isPlaying == true
            delay(200)
        }
    }

    LaunchedEffect(Unit) {
        backFocus.requestFocus()
    }

    val listState = rememberLazyListState()
    val lines = lyrics ?: emptyList()
    val currentIndex = lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)

    LaunchedEffect(currentIndex) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(48.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .focusRequester(backFocus),
            ) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = {
                    if (isPlaying) controller?.pause() else controller?.play()
                },
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape),
            ) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(48.dp),
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        when {
            loadingLyrics -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(64.dp))
                }
            }
            lines.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Lyrics not available",
                        fontSize = 28.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(lines, key = { i, line -> "$i-${line.timeMs}" }) { index, line ->
                        TvLyricLine(
                            line = line,
                            positionMs = positionMs,
                            isCurrent = index == currentIndex,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One lyric line at TV scale. The current line glows with the karaoke
 * word-sweep; sung lines are bright, upcoming lines are dimmed.
 */
@Composable
private fun TvLyricLine(
    line: LyricLine,
    positionMs: Long,
    isCurrent: Boolean,
) {
    if (line.text.isBlank()) {
        Spacer(Modifier.height(12.dp))
        return
    }
    val revealed = if (line.words.isEmpty()) {
        if (positionMs >= line.timeMs) line.text.length.toFloat() else 0f
    } else {
        line.revealedChars(positionMs)
    }
    val annotated = buildAnnotatedString {
        val cut = revealed.toInt().coerceIn(0, line.text.length)
        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) {
            append(line.text.take(cut))
        }
        withStyle(
            SpanStyle(
                color = if (isCurrent) Color.White.copy(alpha = 0.45f)
                else Color.White.copy(alpha = 0.28f),
            ),
        ) {
            append(line.text.drop(cut))
        }
    }
    Text(
        text = annotated,
        fontSize = if (isCurrent) 38.sp else 30.sp,
        lineHeight = 48.sp,
        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isCurrent) { },
    )
}
