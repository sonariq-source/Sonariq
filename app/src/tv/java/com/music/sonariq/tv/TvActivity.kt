package com.music.sonariq.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import com.music.sonariq.data.listentogether.ListenTogether
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.playback.rememberMediaController
import com.music.sonariq.playback.toSong
import com.music.sonariq.ui.theme.SonariqTheme

/**
 * Sonariq TV: the same music engine as the phone app, with a 10-foot UI.
 * Plays music on the TV itself — it does not mirror the phone.
 */
class TvActivity : ComponentActivity() {

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
                TvApp()
            }
        }
    }
}

@Composable
private fun TvApp() {
    val controller = rememberMediaController()
    var playerSong by remember { mutableStateOf<Song?>(null) }
    var showParty by remember { mutableStateOf(false) }
    val partyState by ListenTogether.state.collectAsStateWithLifecycle()

    // The TV follows the party's track: when it changes to something new,
    // open it in the karaoke player. The audio needs no UI code — PartySync
    // in PlaybackService already obeys ListenTogether.state, and this is the
    // same playback service the TV's MediaController is bound to, so playback
    // (track, play/pause, position) follows the party on its own.
    LaunchedEffect(partyState.inParty, partyState.playback.track?.videoId) {
        val track = partyState.playback.track
        if (partyState.inParty && track != null && playerSong?.videoId != track.videoId) {
            playerSong = track.toSong()
            showParty = false
        }
    }

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
        } else if (showParty) {
            TvListenTogetherScreen(
                onBack = { showParty = false },
            )
        } else {
            TvHomeScreen(
                controller = controller,
                onPlaySong = { song -> playerSong = song },
                onOpenParty = { showParty = true },
            )
        }
    }
}
