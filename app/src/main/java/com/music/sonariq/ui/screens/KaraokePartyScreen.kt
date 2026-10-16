package com.music.sonariq.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.sonariq.R
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.playback.KaraokeScorer

/**
 * Karaoke party mode: the room takes turns singing, one song each, and every
 * turn is scored for real by [KaraokeScorer] — the same mic-based scoring the
 * player uses, sampling the microphone while the song plays and counting
 * sung words against the lyric timings.
 *
 * Nothing is simulated. A turn with no mic, no permission, scoring off, or
 * no lyrics records no score, and says exactly why. When the track changes
 * (or ends) mid-turn, the turn closes itself with the last live score seen.
 */
private enum class PartyPhase { SETUP, TURNS, LEADERBOARD }

private data class TurnResult(
    val name: String,
    val songTitle: String,
    val songArtist: String,
    val score: Int?,
    val unscoredReason: String?,
)

private data class ActiveTurn(
    val name: String,
    val songTitle: String,
    val songArtist: String,
    val startVideoId: String?,
)

@Composable
fun KaraokePartyScreen(
    song: Song?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scoringEnabled by AppSettings.karaokeScoring.collectAsStateWithLifecycle()
    val liveScore by KaraokeScorer.score.collectAsStateWithLifecycle()

    var phase by remember { mutableStateOf(PartyPhase.SETUP) }
    var players by remember { mutableStateOf(listOf<String>()) }
    var nameInput by remember { mutableStateOf("") }
    var turnIndex by remember { mutableStateOf(0) }
    var activeTurn by remember { mutableStateOf<ActiveTurn?>(null) }
    var lastSeenScore by remember { mutableStateOf<Int?>(null) }
    var results by remember { mutableStateOf(listOf<TurnResult>()) }

    // The mic permission is asked for here, the moment scoring is turned on —
    // never before. Denied, scoring stays off and the turns go unscored.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) AppSettings.setKaraokeScoring(true)
    }
    val enableScoring = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            AppSettings.setKaraokeScoring(true)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // Honest mic check: permission granted and the hardware actually opens.
    // KaraokeScorer samples at 16 kHz mono 16-bit; if the device can't open
    // that, it can't score either.
    val micReady = scoringEnabled &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED &&
        AudioRecord.getMinBufferSize(
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ) > 0

    @Composable
    fun unscoredReason(): String = when {
        !scoringEnabled -> stringResource(R.string.karaoke_party_why_scoring_off)
        !micReady -> stringResource(R.string.karaoke_party_why_no_mic)
        else -> stringResource(R.string.karaoke_party_why_no_score)
    }
    val reasonNow = unscoredReason()

    val endTurn = {
        val turn = activeTurn
        if (turn != null) {
            results = results + TurnResult(
                name = turn.name,
                songTitle = turn.songTitle,
                songArtist = turn.songArtist,
                score = lastSeenScore,
                unscoredReason = if (lastSeenScore == null) reasonNow else null,
            )
            activeTurn = null
            lastSeenScore = null
            if (turnIndex + 1 >= players.size) {
                phase = PartyPhase.LEADERBOARD
            } else {
                turnIndex += 1
            }
        }
        Unit
    }
    // The live score is cumulative (sung words so far), so the last non-null
    // value before the track changes is the turn's final score.
    LaunchedEffect(liveScore, activeTurn) {
        if (activeTurn != null && liveScore != null) lastSeenScore = liveScore
    }
    // A new track (or the queue running dry) ends the turn by itself — the
    // singer sang exactly one song. Fresh references without restarting the
    // effect: the turn starts after the last track change, not with one.
    val currentTurn by rememberUpdatedState(activeTurn)
    val finishTurn by rememberUpdatedState(endTurn)
    LaunchedEffect(song?.videoId) {
        val turn = currentTurn
        if (turn != null && turn.startVideoId != null && song?.videoId != turn.startVideoId) {
            finishTurn()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (phase) {
            PartyPhase.SETUP -> PartySetup(
                players = players,
                nameInput = nameInput,
                onNameInput = { nameInput = it },
                onAddPlayer = {
                    val name = nameInput.trim()
                    if (name.isNotEmpty() && players.none { it.equals(name, ignoreCase = true) }) {
                        players = players + name
                        nameInput = ""
                    }
                },
                onRemovePlayer = { players = players - it },
                scoringEnabled = scoringEnabled,
                micReady = micReady,
                onEnableScoring = enableScoring,
                onStartParty = {
                    if (players.isNotEmpty()) {
                        turnIndex = 0
                        results = emptyList()
                        activeTurn = null
                        lastSeenScore = null
                        phase = PartyPhase.TURNS
                    }
                },
            )
            PartyPhase.TURNS -> PartyTurns(
                players = players,
                turnIndex = turnIndex,
                song = song,
                activeTurn = activeTurn,
                liveScore = liveScore,
                results = results,
                onStartTurn = {
                    val current = song
                    if (current != null && activeTurn == null) {
                        lastSeenScore = null
                        activeTurn = ActiveTurn(
                            name = players[turnIndex],
                            songTitle = current.title,
                            songArtist = current.artist,
                            startVideoId = current.videoId,
                        )
                    }
                },
                onEndTurn = endTurn,
                onQuit = {
                    activeTurn = null
                    phase = PartyPhase.SETUP
                },
            )
            PartyPhase.LEADERBOARD -> PartyLeaderboard(
                results = results,
                onPartyAgain = {
                    turnIndex = 0
                    results = emptyList()
                    activeTurn = null
                    lastSeenScore = null
                    phase = PartyPhase.TURNS
                },
                onNewParty = {
                    players = emptyList()
                    turnIndex = 0
                    results = emptyList()
                    activeTurn = null
                    lastSeenScore = null
                    phase = PartyPhase.SETUP
                },
            )
        }
    }
}

@Composable
private fun PartySetup(
    players: List<String>,
    nameInput: String,
    onNameInput: (String) -> Unit,
    onAddPlayer: () -> Unit,
    onRemovePlayer: (String) -> Unit,
    scoringEnabled: Boolean,
    micReady: Boolean,
    onEnableScoring: () -> Unit,
    onStartParty: () -> Unit,
) {
    // Mic status: real scoring needs the scorer armed and a working mic.
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (micReady) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (micReady) Icons.Rounded.CheckCircle else Icons.Rounded.MicOff,
                contentDescription = null,
                tint = if (micReady) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = when {
                    micReady -> stringResource(R.string.karaoke_party_scoring_ready)
                    !scoringEnabled -> stringResource(R.string.karaoke_party_scoring_off)
                    else -> stringResource(R.string.karaoke_party_no_mic)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        if (!micReady) {
            Button(
                onClick = onEnableScoring,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.karaoke_party_enable_scoring))
            }
        }
    }

    Text(
        text = stringResource(R.string.karaoke_party),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = nameInput,
            onValueChange = onNameInput,
            label = { Text(stringResource(R.string.karaoke_party_player_name)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onAddPlayer, enabled = nameInput.isNotBlank()) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = stringResource(R.string.karaoke_party_add_player),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }
    }
    players.forEachIndexed { index, name ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemovePlayer(name) }) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Button(
        onClick = onStartParty,
        enabled = players.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.karaoke_party_start_party))
    }
    if (players.isEmpty()) {
        Text(
            text = stringResource(R.string.karaoke_party_need_players),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PartyTurns(
    players: List<String>,
    turnIndex: Int,
    song: Song?,
    activeTurn: ActiveTurn?,
    liveScore: Int?,
    results: List<TurnResult>,
    onStartTurn: () -> Unit,
    onEndTurn: () -> Unit,
    onQuit: () -> Unit,
) {
    val singer = players.getOrNull(turnIndex) ?: return
    Text(
        text = stringResource(
            R.string.karaoke_party_turn_progress,
            turnIndex + 1,
            players.size,
        ),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.tertiary,
    )
    // The singer's card.
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (activeTurn != null) {
                    stringResource(R.string.karaoke_party_now_singing)
                } else {
                    stringResource(R.string.karaoke_party_up_next)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = singer,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            if (activeTurn != null) {
                // The live mic score, big and coral.
                Text(
                    text = stringResource(R.string.karaoke_party_live_score),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${liveScore ?: 0}",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 72.sp,
                )
                Spacer(Modifier.height(8.dp))
                SongLine(title = activeTurn.songTitle, artist = activeTurn.songArtist)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onEndTurn,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.karaoke_party_end_turn))
                }
            } else {
                if (song != null) {
                    SongLine(title = song.title, artist = song.artist)
                } else {
                    Text(
                        text = stringResource(R.string.karaoke_party_play_song_first),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onStartTurn,
                    enabled = song != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.karaoke_party_start_turn))
                }
            }
        }
    }
    // Turns already sung this party.
    results.forEach { result ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = result.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = result.songTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = result.score?.toString()
                        ?: stringResource(R.string.karaoke_party_no_score),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (result.score != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
    TextButton(onClick = onQuit, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text(
            stringResource(R.string.karaoke_party_new_party),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SongLine(title: String, artist: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = artist,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PartyLeaderboard(
    results: List<TurnResult>,
    onPartyAgain: () -> Unit,
    onNewParty: () -> Unit,
) {
    Text(
        text = stringResource(R.string.karaoke_party_leaderboard),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    // Best score first; unscored singers land below every score, honestly.
    val ranked = results.sortedWith(
        compareBy<TurnResult> { it.score == null }.thenByDescending { it.score },
    )
    ranked.forEachIndexed { index, result ->
        val badgeColor = when (index) {
            0 -> MaterialTheme.colorScheme.primary
            1 -> MaterialTheme.colorScheme.secondary
            2 -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        val badgeContent = when (index) {
            0 -> MaterialTheme.colorScheme.onPrimary
            1 -> MaterialTheme.colorScheme.onSecondary
            2 -> MaterialTheme.colorScheme.onTertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (index == 0) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            ),
            shape = RoundedCornerShape(20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = badgeColor,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = badgeContent,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = result.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${result.songTitle} — ${result.songArtist}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (result.score == null && result.unscoredReason != null) {
                        Text(
                            text = result.unscoredReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = result.score?.toString()
                        ?: stringResource(R.string.karaoke_party_no_score),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = if (result.score != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Button(onClick = onPartyAgain, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.karaoke_party_again))
    }
    OutlinedButton(onClick = onNewParty, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.karaoke_party_new_party))
    }
}
