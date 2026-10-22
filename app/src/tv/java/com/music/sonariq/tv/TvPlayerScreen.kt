package com.music.sonariq.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.lyrics.LyricsRepository
import com.music.sonariq.data.lyrics.LyricsSource
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.model.artworkAt
import com.music.sonariq.data.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Sonariq TV player: now-playing on the left, synced lyrics on the right.
 * D-pad transport controls. Lyrics load automatically from every provider.
 *
 * Built for weak TV hardware: the position ticker runs once a second (lyric
 * lines change far slower than that), the list jumps to the current line
 * instead of animating there, and only the current line recomputes its
 * word-reveal — past and future lines are static text.
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
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    val fullBleedArtwork by AppSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    val backFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

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

    // Follow playback position, once a second — lyric lines move slower.
    LaunchedEffect(controller) {
        while (isActive) {
            positionMs = controller?.currentPosition ?: 0L
            durationMs = controller?.duration?.takeIf { it > 0 } ?: 0L
            isPlaying = controller?.isPlaying == true
            delay(1000)
        }
    }

    LaunchedEffect(Unit) {
        playFocus.requestFocus()
    }

    val listState = rememberLazyListState()
    val lines = lyrics ?: emptyList()
    val currentIndex by remember(lines, positionMs) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0) }
    }

    // Jump straight to the current line — no scroll animation frames.
    LaunchedEffect(currentIndex) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Static full-cover artwork backdrop: one image, one scrim,
        // nothing animated — no drift, no crossfade, cheap enough for
        // weak TV hardware. The scrim is dark at every stop and
        // bottom-weighted, so even a mostly pale cover stays an ambient
        // field behind the sleeve and the lyrics and can never wash
        // the screen out to light grey.
        if (fullBleedArtwork) {
            song.artworkAt(1024)?.let { backdropArtwork ->
                AsyncImage(
                    model = backdropArtwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.68f),
                                    Color.Black.copy(alpha = 0.82f),
                                    Color.Black.copy(alpha = 0.90f),
                                ),
                            ),
                        ),
                )
            }
        }

        Row(
            Modifier
                .fillMaxSize()
                .padding(48.dp),
        ) {
            // Left: artwork, identity, transport.
            Column(
                modifier = Modifier
                    .weight(0.85f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.Start,
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
                Spacer(Modifier.height(24.dp))
                AsyncImage(
                    model = song.artworkAt(512),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(20.dp)),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(24.dp))
                // Transport: previous, play/pause, next — all D-pad reachable.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    IconButton(
                        onClick = { controller?.seekToPrevious() },
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape),
                    ) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            if (isPlaying) controller?.pause() else controller?.play()
                        },
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .focusRequester(playFocus),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(52.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(
                        onClick = { controller?.seekToNext() },
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape),
                    ) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                val progress = if (durationMs > 0) {
                    (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${formatMs(positionMs)} / ${formatMs(durationMs)}",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(64.dp))

            // Right: lyrics.
            Column(
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxHeight(),
            ) {
                Text(
                    text = "Lyrics",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                when {
                    loadingLyrics -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(56.dp))
                        }
                    }
                    lines.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Lyrics not available",
                                fontSize = 26.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(lines, key = { i, line -> "$i-${line.timeMs}" }) { index, line ->
                                TvLyricLine(
                                    line = line,
                                    // Only the current line animates its reveal;
                                    // everything else is static text.
                                    revealedChars = when {
                                        index < currentIndex -> line.text.length
                                        index == currentIndex -> revealCount(line, positionMs)
                                        else -> 0
                                    },
                                    isCurrent = index == currentIndex,
                                    isPast = index < currentIndex,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun revealCount(line: LyricLine, positionMs: Long): Int {
    if (line.text.isBlank()) return 0
    val revealed = if (line.words.isEmpty()) {
        if (positionMs >= line.timeMs) line.text.length.toFloat() else 0f
    } else {
        line.revealedChars(positionMs)
    }
    return revealed.toInt().coerceIn(0, line.text.length)
}

/**
 * One lyric line at TV scale. Past lines are bright and static, the current
 * line sweeps its words, upcoming lines are dimmed.
 */
@Composable
private fun TvLyricLine(
    line: LyricLine,
    revealedChars: Int,
    isCurrent: Boolean,
    isPast: Boolean,
) {
    if (line.text.isBlank()) {
        Spacer(Modifier.height(10.dp))
        return
    }
    val annotated = buildAnnotatedString {
        val cut = revealedChars.coerceIn(0, line.text.length)
        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) {
            append(line.text.take(cut))
        }
        withStyle(
            SpanStyle(
                color = when {
                    isCurrent -> Color.White.copy(alpha = 0.45f)
                    isPast -> Color.White.copy(alpha = 0.75f)
                    else -> Color.White.copy(alpha = 0.28f)
                },
            ),
        ) {
            append(line.text.drop(cut))
        }
    }
    Text(
        text = annotated,
        fontSize = if (isCurrent) 36.sp else 28.sp,
        lineHeight = 44.sp,
        fontWeight = if (isCurrent || isPast) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
