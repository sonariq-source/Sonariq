package com.music.sonariq.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.sonariq.data.listentogether.ListenTogether
import kotlinx.coroutines.launch

/**
 * Sonariq TV Listen Together: host a party or join one with a code, see who's
 * listening and what's playing, and leave. D-pad navigable, 10-foot text.
 *
 * This is a thin UI over the shared [ListenTogether] client — the same one the
 * phone uses. Playback itself follows the party without any UI code: PartySync
 * in PlaybackService obeys ListenTogether.state, and this is the same service
 * the TV's MediaController is bound to.
 */
@Composable
fun TvListenTogetherScreen(onBack: () -> Unit) {
    val state by ListenTogether.state.collectAsStateWithLifecycle()
    var enteringCode by remember { mutableStateOf(false) }

    // Faster server health checks while this screen is up, like the phone.
    DisposableEffect(Unit) {
        ListenTogether.setScreenActive(true)
        onDispose { ListenTogether.setScreenActive(false) }
    }
    BackHandler {
        if (enteringCode) enteringCode = false else onBack()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (enteringCode) enteringCode = false else onBack() },
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape),
            ) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.width(24.dp))
            Text(
                text = "Listen Together",
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 40.sp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(32.dp))

        when {
            state.inParty -> TvPartyScreen(state = state)
            enteringCode -> TvJoinCodeScreen()
            else -> TvPartyLobby(onJoinCode = { enteringCode = true })
        }
    }
}

/** Lobby: host a new party or go to code entry. */
@Composable
private fun TvPartyLobby(onJoinCode: () -> Unit) {
    val state by ListenTogether.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val canJoin = ListenTogether.canJoin()
    val hasServer = ListenTogether.hasServer
    val hostFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { hostFocus.requestFocus() }

    Text(
        text = "Press play together, wherever you are.",
        fontSize = 24.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(32.dp))

    if (!canJoin) {
        Text(
            text = "Sign in to host or join a party.",
            fontSize = 24.sp,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
    } else if (!hasServer) {
        Text(
            text = "No party server reachable right now.",
            fontSize = 24.sp,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
    }

    TvTextButton(
        label = "Host party",
        enabled = canJoin && hasServer && !busy,
        focusRequester = hostFocus,
        onClick = {
            scope.launch {
                busy = true
                // Same call the phone's create sheet makes; success flips
                // state.inParty and this screen switches to the party view.
                ListenTogether.createParty()
                busy = false
            }
        },
    )
    Spacer(Modifier.height(16.dp))
    TvTextButton(
        label = "Join with code",
        enabled = canJoin && hasServer && !busy,
        onClick = onJoinCode,
    )
    Spacer(Modifier.height(24.dp))
    if (busy) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
    }
    state.error?.let { error ->
        Text(
            text = error,
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Code entry: the TV on-screen keyboard, then the phone's join call. */
@Composable
private fun TvJoinCodeScreen() {
    val state by ListenTogether.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // Typing is the retry: a fresh attempt's error hides while editing.
    var typedSinceError by remember { mutableStateOf(false) }
    val canJoin = ListenTogether.canJoin()
    val keyboardFocus = remember { FocusRequester() }

    fun submit() {
        if (code.length != ListenTogether.CODE_LENGTH || busy || !canJoin) return
        typedSinceError = false
        scope.launch {
            busy = true
            // Same call the phone's join sheet makes; success flips
            // state.inParty and this screen switches to the party view.
            ListenTogether.joinParty(code)
            busy = false
        }
    }

    LaunchedEffect(Unit) { keyboardFocus.requestFocus() }

    Text(
        text = "Enter the party code",
        fontSize = 28.sp,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(16.dp))
    // The code as typed, with dots for the missing characters.
    Text(
        text = code.padEnd(ListenTogether.CODE_LENGTH, '•').toList().joinToString(" "),
        fontSize = 56.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(24.dp))
    TvKeyboard(
        onKey = { char ->
            code = (code + char).filter(Char::isLetterOrDigit)
                .uppercase()
                .take(ListenTogether.CODE_LENGTH)
            typedSinceError = true
        },
        onBackspace = {
            code = code.dropLast(1)
            typedSinceError = true
        },
        onClear = {
            code = ""
            typedSinceError = true
        },
        onDone = { submit() },
        firstKeyFocus = keyboardFocus,
    )
    Spacer(Modifier.height(24.dp))
    TvTextButton(
        label = "Join party",
        enabled = code.length == ListenTogether.CODE_LENGTH && canJoin && !busy,
        onClick = ::submit,
    )
    Spacer(Modifier.height(16.dp))
    if (busy) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
    }
    if (!typedSinceError) {
        state.error?.let { error ->
            Text(
                text = error,
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** In a party: big code, members, current track, leave. */
@Composable
private fun TvPartyScreen(state: ListenTogether.State) {
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    val leaveFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { leaveFocus.requestFocus() }

    val track = state.playback.track

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
    Text(
        text = "Party code",
        fontSize = 24.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = state.code?.toList()?.joinToString(" ") ?: "",
        fontSize = 72.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(24.dp))

    if (track != null) {
        Text(
            text = if (state.playback.isPlaying) "Now playing" else "Paused",
            fontSize = 24.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = track.title,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = track.artist,
            fontSize = 26.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            text = "Nothing playing yet",
            fontSize = 26.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(24.dp))

    Text(
        text = "Listening (${state.members.size} of ${state.maxMembers})",
        fontSize = 24.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.weight(1f),
    ) {
        items(state.members, key = { it.memberId }) { member ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = member.displayName.ifBlank { "Guest" },
                    fontSize = 26.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (member.isHost) {
                    Text(
                        text = "  ·  Host",
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (member.memberId == state.you?.memberId) {
                    Text(
                        text = "  ·  You",
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (state.connection != ListenTogether.Connection.LIVE) {
        Text(
            text = "Connecting…",
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
    state.error?.let { error ->
        Text(
            text = error,
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
    }
    TvTextButton(
        label = "Leave party",
        enabled = !leaving,
        focusRequester = leaveFocus,
        onClick = {
            scope.launch {
                leaving = true
                ListenTogether.leaveParty()
                leaving = false
            }
        },
    )
    }
}

/**
 * A big D-pad-focusable button in the TV style: idle on surfaceVariant,
 * glowing primary when focused. Shared with [TvHomeScreen].
 */
@Composable
fun TvTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    focused -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .padding(horizontal = 32.dp),
    ) {
        Text(
            text = label,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                !enabled -> Color.Gray
                focused -> Color.White
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
