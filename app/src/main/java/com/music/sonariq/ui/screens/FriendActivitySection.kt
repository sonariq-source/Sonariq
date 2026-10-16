package com.music.sonariq.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.sonariq.R
import com.music.sonariq.data.listentogether.FriendActivityEntry
import com.music.sonariq.data.listentogether.PartyActivityRepository

/**
 * The "Friends listening" section on Home.
 *
 * Shown only when there is something real to show — friends in the live party
 * or in parties this device has recently been in, resolved against the party
 * server. Like the moment station, it stays hidden rather than showing an
 * empty or invented feed.
 *
 * Prism styling follows the feed's other sections: a titleLarge heading and a
 * surfaceVariant card with 20.dp corners, coral for the Join action.
 */
@Composable
fun FriendActivitySection(
    entries: List<FriendActivityEntry>,
    onJoin: (FriendActivityEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        PartyActivityRepository.refresh()
    }
    if (entries.isEmpty()) return

    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.friend_activity_title),
            style = MaterialTheme.typography.titleLarge,
            color = colors.onBackground,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceVariant),
        ) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) RowDivider()
                FriendActivityRow(
                    entry = entry,
                    onJoin = { onJoin(entry) },
                )
            }
        }
    }
}

@Composable
private fun FriendActivityRow(
    entry: FriendActivityEntry,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.friendAvatarUrl != null) {
                AsyncImage(
                    model = entry.friendAvatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(CircleShape),
                )
            } else {
                Text(
                    text = entry.friendName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.primary,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.friendName,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = friendActivitySubtitle(entry),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            entry.joinable && !entry.isFull -> {
                Button(
                    onClick = onJoin,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    ),
                    modifier = Modifier.width(84.dp),
                ) {
                    Text(
                        text = stringResource(R.string.friend_activity_join),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
            }
            entry.joinable && entry.isFull -> {
                Text(
                    text = stringResource(R.string.friend_activity_full),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
            }
            else -> {
                Text(
                    text = stringResource(R.string.friend_activity_listening_together),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun friendActivitySubtitle(entry: FriendActivityEntry): String {
    val track = entry.trackTitle?.takeIf { it.isNotBlank() }
    return when {
        track != null && entry.trackIsLive -> {
            val artist = entry.trackArtist?.takeIf { it.isNotBlank() }
            if (artist != null) "$track — $artist" else track
        }
        track != null -> stringResource(R.string.friend_activity_last_played, track)
        entry.partyHostName.isNotBlank() ->
            stringResource(R.string.friend_activity_party_of, entry.partyHostName)
        else -> stringResource(R.string.friend_activity_no_track)
    }
}
