package com.music.sonariq.tv

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import coil3.compose.AsyncImage
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
        // Big dimmed artwork backdrop.
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
                .background(Color.Black.copy(alpha = 0.66f)),
        )

        // Slow drifting Prism glow — the only motion on screen.
        AmbientGlowField()

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
 * Three soft Prism orbs drifting slowly behind the artwork dim — a calm,
 * never-repeating glow. Radial gradients only, no blur (minSdk 26).
 */
@Composable
private fun AmbientGlowField() {
    val transition = rememberInfiniteTransition(label = "ambient-glow")
    val driftX by transition.animateFloat(
        initialValue = -140f,
        targetValue = 140f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 26000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "driftX",
    )
    val driftY by transition.animateFloat(
        initialValue = -90f,
        targetValue = 90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 31000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "driftY",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        AmbientOrb(
            color = AmbientCoral,
            diameter = 460.dp,
            alignment = Alignment.TopStart,
            offsetX = driftX,
            offsetY = driftY,
            scale = pulse,
            alpha = 0.30f,
        )
        AmbientOrb(
            color = AmbientOrchid,
            diameter = 560.dp,
            alignment = Alignment.BottomEnd,
            offsetX = -driftX,
            offsetY = -driftY,
            scale = 1.86f - pulse,
            alpha = 0.26f,
        )
        AmbientOrb(
            color = AmbientCyan,
            diameter = 320.dp,
            alignment = Alignment.Center,
            offsetX = driftY,
            offsetY = driftX,
            scale = pulse,
            alpha = 0.22f,
        )
    }
}

@Composable
private fun AmbientOrb(
    color: Color,
    diameter: Dp,
    alignment: Alignment,
    offsetX: Float,
    offsetY: Float,
    scale: Float,
    alpha: Float,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = alignment,
    ) {
        Box(
            modifier = Modifier
                .offset(x = offsetX.dp, y = offsetY.dp)
                .size(diameter * scale)
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
