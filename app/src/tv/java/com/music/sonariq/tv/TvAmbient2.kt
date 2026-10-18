package com.music.sonariq.tv

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import com.music.sonariq.R
import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.lyrics.LyricsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sonariq TV ambient mode, second style: a full lyric-view screensaver.
 *
 * The first ambient style ([TvAmbientOverlay]) shows one lyric line under the
 * track title. This one is the karaoke view: a three-line synced lyric block
 * (previous line faded, current line lit, next line dimmed), a clock, a
 * progress bar, and the up-next list off the controller's timeline — the
 * things a glance across the room actually asks for.
 *
 * Like its predecessor it is deliberately non-interactive: no focusable
 * elements, so D-pad keypresses keep reaching the UI underneath and the
 * activity's `onUserInteraction()` wakes the TV straight back up.
 *
 * Phase 2 picks the style off the new `AppSettings.tvAmbient2Enabled` key
 * (Boolean, default false — the existing style stays the default): in
 * TvActivity's ambient branch, `if (AppSettings.tvAmbient2Enabled.value)
 * TvAmbient2Overlay(...) else TvAmbientOverlay(...)`.
 */
@Composable
internal fun TvAmbient2Overlay(
    track: AmbientTrack,
    controller: MediaController?,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var positionMs by remember(track.videoId) { mutableLongStateOf(0L) }
    var durationMs by remember(track.videoId) { mutableLongStateOf(0L) }
    var upNext by remember { mutableStateOf<List<AmbientTrack>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    LaunchedEffect(track.videoId, controller) {
        while (isActive) {
            val c = controller
            positionMs = c?.currentPosition ?: 0L
            durationMs = c?.duration?.takeIf { it > 0 } ?: 0L
            upNext = c?.let { readUpNext(it, max = 3) } ?: emptyList()
            delay(500)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080910)),
    ) {
        if (!track.artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = track.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f)),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 96.dp, vertical = 64.dp),
        ) {
            // Clock + progress, top.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatClock(nowMs),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.9f),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatPosition(positionMs, durationMs),
                    fontSize = 26.sp,
                    color = Color.White.copy(alpha = 0.65f),
                )
            }
            Spacer(Modifier.height(16.dp))
            val progress = if (durationMs > 0) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Color(0xFFFF6B5E),
                trackColor = Color.White.copy(alpha = 0.18f),
            )

            Spacer(Modifier.height(48.dp))

            // Track identity.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!track.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = track.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                    Spacer(Modifier.width(32.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        fontSize = 52.sp,
                        lineHeight = 60.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = track.artist,
                        fontSize = 30.sp,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(40.dp))

            // The lyric block: three synced lines.
            Ambient2LyricBlock(track = track, positionMs = positionMs)

            Spacer(Modifier.weight(1f))

            // Up next, off the real timeline.
            if (upNext.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.ux_tv_ambient2_up_next),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.55f),
                )
                Spacer(Modifier.height(12.dp))
                upNext.forEach { next ->
                    Text(
                        text = "${next.title} — ${next.artist}",
                        fontSize = 24.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 40.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Text(
                text = stringResource(R.string.ux_tv_ambient2_wake_hint),
                fontSize = 20.sp,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The tracks queued after the current one, from the live controller timeline. */
private fun readUpNext(controller: MediaController, max: Int): List<AmbientTrack> {
    val current = controller.currentMediaItemIndex
    if (current < 0) return emptyList()
    val out = ArrayList<AmbientTrack>(max)
    var i = current + 1
    while (i < controller.mediaItemCount && out.size < max) {
        val item = controller.getMediaItemAt(i)
        val title = item.mediaMetadata.title?.toString().orEmpty()
        if (title.isNotBlank()) {
            out += AmbientTrack(
                videoId = item.mediaId,
                title = title,
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                artworkUrl = item.mediaMetadata.artworkUri?.toString(),
            )
        }
        i++
    }
    return out
}

/**
 * Three synced lyric lines — the line before faded, the current one lit, the
 * next one dimmed — from the same shared [LyricsRepository] the first ambient
 * style uses, so this is free when the player screen already fetched them.
 */
@Composable
private fun Ambient2LyricBlock(track: AmbientTrack, positionMs: Long) {
    var lines by remember(track.videoId) { mutableStateOf<List<LyricLine>?>(null) }

    LaunchedEffect(track.videoId) {
        lines = runCatching {
            LyricsRepository.lyrics(
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                durationMs = 0L,
            )?.lines
        }.getOrNull()
    }

    val timed = lines?.filter { it.text.isNotBlank() }.orEmpty()
    val currentIndex = timed.indexOfLast { it.timeMs <= positionMs }
    if (timed.isEmpty() || currentIndex < 0) return

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        if (currentIndex > 0) {
            Ambient2LyricLine(text = timed[currentIndex - 1].text, alpha = 0.38f, size = 30)
        }
        Ambient2LyricLine(text = timed[currentIndex].text, alpha = 1f, size = 44)
        if (currentIndex + 1 < timed.size) {
            Ambient2LyricLine(text = timed[currentIndex + 1].text, alpha = 0.55f, size = 30)
        }
    }
}

@Composable
private fun Ambient2LyricLine(text: String, alpha: Float, size: Int) {
    Text(
        text = "“$text”",
        fontSize = size.sp,
        lineHeight = (size + 10).sp,
        fontWeight = if (alpha >= 1f) FontWeight.SemiBold else FontWeight.Normal,
        color = Color.White.copy(alpha = alpha),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun formatClock(nowMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMs))

private fun formatPosition(positionMs: Long, durationMs: Long): String {
    fun fmt(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
    return if (durationMs > 0) "${fmt(positionMs)} / ${fmt(durationMs)}" else fmt(positionMs)
}
