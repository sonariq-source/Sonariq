package com.music.sonariq.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import com.music.sonariq.R
import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.lyrics.LyricsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Idle time before the TV ambient screensaver takes over while music plays. */
internal const val TV_AMBIENT_IDLE_MS = 5 * 60 * 1000L

/** How often the idle/playback check runs. */
internal const val TV_AMBIENT_POLL_MS = 15_000L

// Prism palette for the ambient glow: coral / orchid / cyan on midnight.
private val AmbientMidnight = Color(0xFF080910)
private val AmbientCoral = Color(0xFFFF6B5E)
private val AmbientOrchid = Color(0xFFDA70D6)
private val AmbientCyan = Color(0xFF22D3EE)

/** What the ambient overlay shows, read off the controller's current media item. */
internal data class AmbientTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
)

/**
 * Sonariq TV ambient mode: a full-screen idle screensaver shown when the TV
 * has been untouched for [TV_AMBIENT_IDLE_MS] while music is playing.
 *
 * Deliberately non-interactive — it holds no focusable elements, so D-pad
 * keypresses keep reaching the UI underneath and the activity's
 * `onUserInteraction()` wakes the TV straight back up.
 */
@Composable
internal fun TvAmbientOverlay(
    track: AmbientTrack,
    controller: MediaController?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmbientMidnight),
    ) {
        // No full-screen artwork: a full-res decode every track is the
        // heaviest thing this screen did on weak TV hardware. The static
        // glow below carries the visual instead.

        // A calm static glow behind the dim — no animation. The old version ran
        // three infinite transitions here; on a weak TV GPU that is constant
        // redraw for a screen that sits idle for hours.
        AmbientGlowStatic()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 96.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = track.title,
                fontSize = 56.sp,
                lineHeight = 64.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = track.artist,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AmbientLyricLine(track = track, controller = controller)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 56.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Text(
                text = stringResource(R.string.tv_ambient_wake_hint),
                fontSize = 20.sp,
                color = Color.White.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Three soft Prism orbs, frozen in place — a calm static glow. Radial
 * gradients only, no blur, no animation: this screen sits idle for hours
 * on weak TV hardware, so nothing here may redraw on its own.
 */
@Composable
private fun AmbientGlowStatic() {
    Box(modifier = Modifier.fillMaxSize()) {
        AmbientOrb(
            color = AmbientCoral,
            diameter = 460.dp,
            alignment = Alignment.TopStart,
            alpha = 0.22f,
        )
        AmbientOrb(
            color = AmbientOrchid,
            diameter = 560.dp,
            alignment = Alignment.BottomEnd,
            alpha = 0.18f,
        )
        AmbientOrb(
            color = AmbientCyan,
            diameter = 320.dp,
            alignment = Alignment.Center,
            alpha = 0.16f,
        )
    }
}

@Composable
private fun AmbientOrb(
    color: Color,
    diameter: Dp,
    alignment: Alignment,
    alpha: Float,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = alignment,
    ) {
        Box(
            modifier = Modifier
                .size(diameter)
                .background(
                    brush = Brush.radialGradient(
                        0f to color.copy(alpha = alpha),
                        1f to Color.Transparent,
                    ),
                    shape = CircleShape,
                ),
        )
    }
}

/**
 * The current lyric line, when lyrics are available. Reuses the shared
 * [LyricsRepository] (disk-cached, so this is free when the player screen
 * already fetched them) — no new lyrics pipeline.
 */
@Composable
private fun AmbientLyricLine(
    track: AmbientTrack,
    controller: MediaController?,
) {
    var lines by remember(track.videoId) { mutableStateOf<List<LyricLine>?>(null) }
    var positionMs by remember(track.videoId) { mutableLongStateOf(0L) }

    LaunchedEffect(track.videoId) {
        lines = runCatching {
            LyricsRepository.lyrics(
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                durationMs = controller?.duration?.takeIf { it > 0 } ?: 0L,
            )?.lines
        }.getOrNull()
    }
    LaunchedEffect(track.videoId, controller) {
        while (isActive) {
            positionMs = controller?.currentPosition ?: 0L
            delay(500)
        }
    }

    val currentLine = lines
        ?.lastOrNull { it.timeMs <= positionMs }
        ?.text
        ?.takeIf { it.isNotBlank() }
    if (currentLine != null) {
        Spacer(Modifier.height(36.dp))
        Text(
            text = "“$currentLine”",
            fontSize = 30.sp,
            lineHeight = 40.sp,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
