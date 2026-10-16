package com.music.sonariq.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.sonariq.R
import com.music.sonariq.data.YtMusicRepository
import com.music.sonariq.data.listentogether.ListenTogether
import com.music.sonariq.data.listentogether.PartyPreview
import com.music.sonariq.data.model.SearchFilter
import com.music.sonariq.data.model.SearchResult
import com.music.sonariq.data.model.Song
import com.music.sonariq.playback.toPartyTrack
import com.music.sonariq.ui.components.PillTextField
import kotlinx.coroutines.delay

/**
 * Which of the party sheets is up, if any.
 *
 * One nullable value rather than a boolean each: they are alternatives — a
 * create sheet and a join sheet can never both be open — and a set of flags
 * that must not both be true is a bug waiting for the one path that sets the
 * second without clearing the first.
 */
internal sealed interface PartySheet {
    /** Name and size, then create. */
    data object Create : PartySheet

    /** Six cells, to look a party up. */
    data object JoinCode : PartySheet

    /**
     * Who is in the party this device is about to commit a slot to.
     *
     * [server] rides along because an invite may point at somebody's own
     * server, and the join that follows has to go to the same place the
     * preview came from.
     */
    data class Confirm(val preview: PartyPreview, val server: String?) : PartySheet

    /** A link and a square, for getting somebody else in. */
    data object Invite : PartySheet

    /** Song search for putting tracks in the party's shared queue. */
    data object AddSongs : PartySheet
}

/**
 * This device's own face, lit from behind.
 *
 * The glow is the whole of the decoration on the landing screen, and it is one
 * blurred circle rather than a pattern: the listener's picture is the subject,
 * and a field of shapes around it — the obvious thing to reach for — competes
 * with the one element that is actually about them.
 *
 * It breathes on a long, offset pair of animations so the rise and the fade do
 * not turn over together, which is what a single looping scale reads as: a
 * pulse rather than something lit.
 */
@Composable
internal fun GlowingAvatar(
    url: String?,
    modifier: Modifier = Modifier,
    size: Dp = 104.dp,
    glow: Color = MaterialTheme.colorScheme.primary,
) {
    val breathe = rememberInfiniteTransition(label = "partyGlow")
    val spread by breathe.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "partyGlowSpread",
    )
    val intensity by breathe.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            // Deliberately not the spread's period: equal periods make the
            // brightest frame always the widest one, which reads as one object
            // throbbing instead of light moving.
            animation = tween(3_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "partyGlowIntensity",
    )

    Box(
        modifier = modifier.size(size * 2.1f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size * 1.75f)
                .scale(spread)
                // Blurred as well as faded to transparent: a radial gradient
                // alone bands visibly on the dark background at this size.
                //
                // Unbounded, which is the whole point: the default edge
                // treatment clips the blur to the layout rectangle, so a glow
                // that grows past its own box gets a straight edge sliced
                // across it — a round light inside a visible square, breathing
                // in and out of its own corners.
                .blur(28.dp, BlurredEdgeTreatment.Unbounded)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glow.copy(alpha = intensity),
                            glow.copy(alpha = intensity * 0.35f),
                            Color.Transparent,
                        ),
                    ),
                    shape = CircleShape,
                ),
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.12f), CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(2.dp, Color.White.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(size * 0.45f),
                )
            }
        }
    }
}

/**
 * What the screen is when there is no party yet.
 *
 * Two doors rather than one, and they are not equals: creating is the common
 * case and gets the filled button; joining is what somebody does who already
 * has a code, and a text button is enough for a person who arrived looking for
 * it.
 */
@Composable
internal fun PartyLanding(
    avatarUrl: String?,
    enabled: Boolean,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        GlowingAvatar(url = avatarUrl)
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.listen_together_landing_title),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.listen_together_landing_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onCreate,
            enabled = enabled,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                text = stringResource(R.string.listen_together_create_action),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = onJoin,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(
                text = stringResource(R.string.listen_together_join_existing),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The shared head of every sheet here: a title, and an optional line under it. */
@Composable
private fun SheetHeader(title: String, subtitle: String? = null) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    if (subtitle != null) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Why the last attempt from this sheet did not work, said inside the sheet.
 *
 * On the page behind it there is nothing to read: a drawer covers the bottom
 * half of the screen and a line of red underneath it is a line nobody sees,
 * which is how "no party with that code" turned into a button that appeared to
 * do nothing at all. The message belongs next to the control that produced it.
 */
@Composable
private fun SheetError(message: String?) {
    if (message.isNullOrBlank()) return
    Spacer(Modifier.height(14.dp))
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Name and size, asked once, before a party exists.
 *
 * Both are changeable afterwards from the party's own page — this is not the
 * only chance to set them — but they are the two things somebody starting a
 * party tends to have an opinion about, and asking here costs one sheet rather
 * than a trip into settings after the fact.
 */
@Composable
internal fun CreatePartySheet(
    avatarUrl: String?,
    nickname: String,
    onNicknameChange: (String) -> Unit,
    maxMembers: Int,
    onMaxMembersChange: (Int) -> Unit,
    busy: Boolean,
    enabled: Boolean,
    error: String?,
    onCreate: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SheetHeader(
            title = stringResource(R.string.listen_together_create),
            subtitle = stringResource(R.string.listen_together_create_sheet_subtitle),
        )
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PartySheetAvatar(url = avatarUrl)
            Spacer(Modifier.width(ICON_GAP))
            PillTextField(
                value = nickname,
                onValueChange = onNicknameChange,
                placeholder = stringResource(R.string.listen_together_nickname_hint),
                container = MaterialTheme.colorScheme.surfaceVariant,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.listen_together_party_size),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.listen_together_party_size_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StepperButton("−", enabled = enabled && maxMembers > 2) {
                onMaxMembersChange(maxMembers - 1)
            }
            Text(
                "$maxMembers",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.Center,
            )
            StepperButton("+", enabled = enabled && maxMembers < 10) {
                onMaxMembersChange(maxMembers + 1)
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onCreate,
            enabled = enabled && !busy,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                text = stringResource(
                    if (busy) R.string.listen_together_creating else R.string.listen_together_create_action,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        SheetError(error)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The avatar as the sheets draw it — larger than a settings row's glyph. */
@Composable
private fun PartySheetAvatar(url: String?, size: Dp = 40.dp) {
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/**
 * Six characters, and the button that looks them up.
 *
 * Looking up rather than joining: what this sheet hands back is a party to
 * *consider*, and [JoinConfirmSheet] is where the slot is actually taken.
 */
@Composable
internal fun JoinPartySheet(
    code: String,
    onCodeChange: (String) -> Unit,
    busy: Boolean,
    enabled: Boolean,
    error: String?,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SheetHeader(
            title = stringResource(R.string.listen_together_join),
            subtitle = stringResource(R.string.listen_together_code_hint),
        )
        Spacer(Modifier.height(22.dp))
        PartyCodeField(
            code = code,
            onCodeChange = onCodeChange,
            enabled = enabled && !busy,
            onSubmit = onSubmit,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onSubmit,
            enabled = enabled && !busy && code.length == ListenTogether.CODE_LENGTH,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                text = stringResource(
                    if (busy) R.string.listen_together_joining else R.string.listen_together_join_action,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        SheetError(error)
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The faces already in a party, overlapped, with a count for the rest.
 *
 * Three and a number rather than all of them: the row has to stay the same
 * width at two members and at ten, and past three faces nobody is identifying
 * anyone — they are reading "a few people", which the number says better.
 */
@Composable
internal fun MemberAvatarStack(
    members: List<Pair<String, String?>>,
    total: Int,
    modifier: Modifier = Modifier,
    faceSize: Dp = 52.dp,
    shown: Int = 3,
) {
    val faces = members.take(shown)
    val extra = (total - faces.size).coerceAtLeast(0)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy((-12).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        faces.forEach { (name, avatar) ->
            Box(
                modifier = Modifier
                    .size(faceSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (avatar != null) {
                    AsyncImage(
                        model = avatar,
                        contentDescription = null,
                        modifier = Modifier.size(faceSize).clip(CircleShape),
                    )
                } else {
                    Text(
                        text = name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (extra > 0) {
            Box(
                modifier = Modifier
                    .size(faceSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$extra",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * The last step before a device slot is committed: who is in there, and a way
 * out that is not the back button.
 */
@Composable
internal fun JoinConfirmSheet(
    preview: PartyPreview,
    avatarUrl: String?,
    nickname: String,
    onNicknameChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MemberAvatarStack(
            members = preview.members.map { it.displayName to it.avatarUrl },
            total = preview.memberCount,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = preview.hostName.takeIf { it.isNotBlank() }
                ?.let { stringResource(R.string.listen_together_join_question, it) }
                ?: stringResource(R.string.listen_together_join_question_unnamed),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Only when the server actually told us. A party looked up on a server
        // with no preview endpoint comes back as a code and nothing else, and
        // "0 of 5 listening" would be this screen inventing a fact about a
        // party it cannot see into. See ListenTogether.previewParty.
        if (preview.memberCount > 0) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        R.string.listen_together_listening,
                        preview.memberCount,
                        preview.maxMembers,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PartySheetAvatar(url = avatarUrl)
            Spacer(Modifier.width(ICON_GAP))
            PillTextField(
                value = nickname,
                onValueChange = onNicknameChange,
                placeholder = stringResource(R.string.listen_together_nickname_hint),
                container = MaterialTheme.colorScheme.surfaceVariant,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onJoin,
            enabled = !busy && !preview.isFull,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                text = stringResource(
                    when {
                        preview.isFull -> R.string.listen_together_full
                        busy -> R.string.listen_together_joining
                        else -> R.string.listen_together_join_action
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        SheetError(error)
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(
                text = stringResource(R.string.listen_together_not_now),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.listen_together_preview_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * How somebody else gets in: just the party code, big and copyable.
 *
 * The share-link button and QR code were removed — reading the code out
 * (or pasting it) is the whole flow now.
 */
@Composable
internal fun InviteSheet(
    code: String,
    onCopyCode: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SheetHeader(title = stringResource(R.string.listen_together_invite_title))
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.listen_together_code_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        TextButton(
            onClick = onCopyCode,
            contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.listen_together_tap_to_copy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** @see ListenTogether.myAvatarUrl */
@Composable
internal fun rememberMyAvatar(): String? = ListenTogether.myAvatarUrl()

/**
 * Song search for adding to the shared queue.
 *
 * One sheet, one job: search the song catalogue and put a track in the
 * party's running order. The add goes out as [ListenTogether.queueAdd] — the
 * same control the playback layer uses — and the queue-frame echo is what
 * makes it appear for everyone, so this list never invents an entry of its
 * own. [hostOnlyControl][ListenTogether.State.controlsLocked] is honoured by
 * never opening this sheet while the controls are locked: the server would
 * refuse the control anyway, and a refused add with no visible reason reads as
 * a broken button.
 */
@Composable
internal fun AddSongsSheet() {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchedFor by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // Debounced so every keystroke is not a request. The search answers in a
    // few hundred milliseconds, and the typeahead feel survives a short wait —
    // a storm of overlapping requests, each racing the last, does not.
    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.length < ADD_SONG_MIN_QUERY) {
            results = emptyList()
            searchedFor = ""
            searching = false
            error = null
            return@LaunchedEffect
        }
        delay(ADD_SONG_SEARCH_DEBOUNCE_MS)
        // Something was typed while waiting — that newer effect owns the
        // search, so this one stands down rather than overwriting it.
        if (query.trim() != trimmed) return@LaunchedEffect
        searching = true
        error = null
        YtMusicRepository.search(trimmed, SearchFilter.SONGS)
            .onSuccess { page ->
                results = page.mapNotNull { result ->
                    when (result) {
                        is SearchResult.TopTrack -> result.song
                        is SearchResult.Track -> result.song
                        is SearchResult.Browse -> null
                    }
                }
                    .filter { it.videoId.isShareableId() }
                    .distinctBy { it.videoId }
                searchedFor = trimmed
            }
            .onFailure { failure ->
                results = emptyList()
                error = failure.message
            }
        searching = false
    }

    val addSong: (Song) -> Unit = { song ->
        ListenTogether.queueAdd(listOf(song.toPartyTrack(0L)))
        // Marked as this device's own addition so the queue's remove buttons
        // know whose songs they may offer to take back out. The protocol
        // carries no adder — see ListenTogether.ownQueueAdditions.
        ListenTogether.markOwnQueueAdditions(listOf(song.videoId))
        Toast.makeText(
            context,
            context.getString(R.string.listen_together_queue_added),
            Toast.LENGTH_SHORT,
        ).show()
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    ) {
        SheetHeader(title = stringResource(R.string.listen_together_queue_search_title))
        Spacer(Modifier.height(18.dp))
        PillTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.listen_together_queue_search_hint),
            container = MaterialTheme.colorScheme.surfaceVariant,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        Spacer(Modifier.height(8.dp))
        when {
            searching -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 14.dp),
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.listen_together_queue_searching),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            error != null -> {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            }

            results.isNotEmpty() -> {
                // Bounded: a sheet's content scrolls as a whole, and an
                // unbounded lazy list inside it would fight that scroll rather
                // than join it.
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(results, key = { it.videoId }) { song ->
                        AddSongRow(song = song, onAdd = { addSong(song) })
                    }
                }
            }

            query.trim().length >= ADD_SONG_MIN_QUERY -> {
                Text(
                    text = stringResource(
                        R.string.listen_together_queue_no_results,
                        searchedFor.ifBlank { query.trim() },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** One search hit, and the add that puts it in the shared queue. */
@Composable
private fun AddSongRow(song: Song, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (song.thumbnailUrl != null) {
                AsyncImage(
                    model = song.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onAdd) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = stringResource(R.string.listen_together_queue_add),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * What may be shared: a real catalogue id.
 *
 * A media-store or file URI names a row on this device and nothing at all
 * anywhere else — see PartySync's own guard — so it is filtered here rather
 * than sent and refused. Search results never carry one; this is the belt
 * rather than the braces.
 */
private fun String.isShareableId(): Boolean =
    isNotBlank() && !startsWith("content://") && !startsWith("file://")

/** Fewer characters than this and the search is all noise. */
private const val ADD_SONG_MIN_QUERY = 2

private const val ADD_SONG_SEARCH_DEBOUNCE_MS = 400L
