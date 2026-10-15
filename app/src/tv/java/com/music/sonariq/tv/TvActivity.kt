package com.music.sonariq.tv

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.playback.rememberMediaController
import com.music.sonariq.ui.theme.SonariqTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

/**
 * Sonariq TV: the same music engine as the phone app, with a 10-foot UI.
 * Plays music on the TV itself — it does not mirror the phone.
 */
class TvActivity : ComponentActivity() {

    /**
     * Last user interaction, monotonic ms. Updated on every key/touch event
     * via [onUserInteraction]; the ambient screensaver watches this to know
     * when the TV has gone idle.
     */
    private val _lastInteractionMs = MutableStateFlow(SystemClock.elapsedRealtime())
    internal val lastInteractionMs: StateFlow<Long> = _lastInteractionMs

    override fun onUserInteraction() {
        super.onUserInteraction()
        _lastInteractionMs.value = SystemClock.elapsedRealtime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
            val accent by AppSettings.accentColor.collectAsStateWithLifecycle()
            val darkTheme = when (theme) {
                com.music.sonariq.data.settings.ThemeMode.SYSTEM ->
                    androidx.compose.foundation.isSystemInDarkTheme()
                com.music.sonariq.data.settings.ThemeMode.LIGHT -> false
                com.music.sonariq.data.settings.ThemeMode.DARK -> true
            }
            SonariqTheme(darkTheme = darkTheme, accent = accent) {
                TvApp(lastInteractionMs = lastInteractionMs)
            }
        }
    }
}

@Composable
private fun TvApp(lastInteractionMs: StateFlow<Long>) {
    val controller = rememberMediaController()
    var playerSong by remember { mutableStateOf<Song?>(null) }

    // --- Ambient mode (idle screensaver) ----------------------------------
    // Wakes the moment the user touches anything: onUserInteraction() bumps
    // lastInteractionMs, which recomposes this immediately. The 15s ticker only
    // flips it on after TV_AMBIENT_IDLE_MS of quiet, and re-checks playback.
    val lastInteraction by lastInteractionMs.collectAsStateWithLifecycle()
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var isPlaying by remember { mutableStateOf(false) }
    var ambientTrack by remember { mutableStateOf<AmbientTrack?>(null) }
    LaunchedEffect(controller) {
        while (isActive) {
            nowMs = SystemClock.elapsedRealtime()
            isPlaying = controller?.isPlaying == true
            ambientTrack = controller?.currentMediaItem?.let { item ->
                val metadata = item.mediaMetadata
                val title = metadata.title?.toString().orEmpty()
                if (title.isBlank()) null else AmbientTrack(
                    videoId = item.mediaId,
                    title = title,
                    artist = metadata.artist?.toString().orEmpty(),
                    artworkUrl = metadata.artworkUri?.toString(),
                )
            }
            delay(TV_AMBIENT_POLL_MS)
        }
    }
    val showAmbient = isPlaying &&
        ambientTrack != null &&
        nowMs - lastInteraction >= TV_AMBIENT_IDLE_MS
    // ----------------------------------------------------------------------

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (playerSong != null) {
            TvPlayerScreen(
                song = playerSong!!,
                controller = controller,
                onBack = { playerSong = null },
            )
        } else {
            TvHomeScreen(
                controller = controller,
                onPlaySong = { song -> playerSong = song },
            )
        }
        if (showAmbient) {
            TvAmbientOverlay(track = ambientTrack!!, controller = controller)
        }
    }
}
