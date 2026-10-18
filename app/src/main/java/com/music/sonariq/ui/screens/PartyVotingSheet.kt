package com.music.sonariq.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HowToVote
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.sonariq.R
import com.music.sonariq.data.listentogether.ListenTogether
import com.music.sonariq.data.listentogether.PartyVoting

/**
 * The party voting sheet: every upcoming track with its tallied votes and a
 * vote button.
 *
 * A vote is not a poll that somebody later acts on — tapping it sends a real
 * queue move that nudges the track one slot closer to "next" on the server,
 * so the running order every member sees is the votes having happened. The
 * counts are what this device tallied from the queue frames it observed (see
 * [PartyVoting]); in a host-only party listeners see the locked note instead
 * of vote buttons.
 *
 * Wiring (Phase 2): a "Vote on what's next" row in the party queue section
 * sets a boolean that shows this sheet. See the integration manifest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartyVotingSheet(onDismiss: () -> Unit) {
    val state by ListenTogether.state.collectAsStateWithLifecycle()
    val votes by PartyVoting.votes.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { PartyVoting.init() }

    val queue = state.queue
    val locked = state.controlsLocked
    val upcoming = queue.items.withIndex()
        .filter { (index, _) -> index > queue.index }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.social_vote_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.social_vote_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            if (upcoming.isEmpty()) {
                Text(
                    text = stringResource(R.string.social_vote_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(upcoming, key = { (_, track) -> track.videoId }) { (index, track) ->
                        val count = votes[track.videoId] ?: 0
                        val isNext = index == queue.index + 1
                        VoteRow(
                            title = track.title.ifBlank { track.videoId },
                            artist = track.artist,
                            thumbnailUrl = track.thumbnailUrl,
                            voteCount = count,
                            enabled = !locked && !isNext,
                            lockedNote = locked,
                            isNext = isNext,
                            onVote = { PartyVoting.vote(track.videoId) },
                        )
                    }
                }
            }
            if (locked) {
                Text(
                    text = stringResource(R.string.social_vote_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun VoteRow(
    title: String,
    artist: String,
    thumbnailUrl: String?,
    voteCount: Int,
    enabled: Boolean,
    lockedNote: Boolean,
    isNext: Boolean,
    onVote: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbnailUrl != null) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    isNext -> stringResource(R.string.social_vote_already_next)
                    voteCount == 1 -> stringResource(R.string.social_vote_one_vote)
                    voteCount > 1 -> stringResource(R.string.social_vote_votes, voteCount)
                    else -> stringResource(R.string.social_vote_no_votes)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (isNext) colors.primary else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist.isNotBlank()) {
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!lockedNote && !isNext) {
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onVote,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.HowToVote,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(text = stringResource(R.string.social_vote_action))
            }
        }
    }
}
