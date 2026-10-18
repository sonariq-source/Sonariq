package com.music.sonariq.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.sonariq.R
import com.music.sonariq.data.listentogether.PartyRecap
import com.music.sonariq.data.listentogether.PartyRecapCollector
import com.music.sonariq.data.listentogether.RecapAddedTrack
import com.music.sonariq.data.listentogether.RecapPlayedTrack
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The party recap: what the last party amounted to.
 *
 * Everything shown was observed live by this device while it was in the
 * party — the top tracks from the party-state frames' track transitions, the
 * "added by" names from the protocol's own per-track attribution
 * ([PartyPlayback.startedByName][com.music.sonariq.data.listentogether.PartyPlayback.startedByName])
 * and the server's queue-add broadcasts. A track whose picker the protocol
 * never named shows no name rather than a guess.
 *
 * Wiring (Phase 2): show after a party ends — e.g. a "Party recap" row on the
 * Listen Together screen, visible when [PartyRecapCollector.latest] is
 * non-null. See the integration manifest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartyRecapScreen(onBack: () -> Unit) {
    val recap by PartyRecapCollector.latest.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.social_recap_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { padding ->
        val current = recap
        if (current == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.social_recap_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            RecapContent(recap = current, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun RecapContent(recap: PartyRecap, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            RecapHeader(recap = recap)
        }
        if (recap.playedTracks.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.social_recap_top_tracks),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground,
                )
            }
            items(recap.playedTracks, key = { it.videoId }) { track ->
                TopTrackRow(track = track)
            }
        }
        val contributors = recap.memberNames.sorted()
        if (contributors.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.social_recap_who_added),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground,
                )
            }
            items(contributors, key = { it }) { name ->
                ContributorRow(
                    name = name,
                    recap = recap,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RecapHeader(recap: PartyRecap) {
    val colors = MaterialTheme.colorScheme
    val date = recapDate(recap.startedAtMs)
    val minutes = ((recap.endedAtMs - recap.startedAtMs).coerceAtLeast(0)) / 60_000
    val members = recap.memberNames.size
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceVariant)
            .padding(16.dp),
    ) {
        Text(
            text = date,
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append(
                    if (members == 1) stringResource(R.string.social_recap_one_member)
                    else stringResource(R.string.social_recap_members, members),
                )
                if (minutes > 0) {
                    append(" · ")
                    append(stringResource(R.string.social_recap_duration_mins, minutes))
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun recapDate(startedAtMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault())
    return Instant.ofEpochMilli(startedAtMs)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}

@Composable
private fun TopTrackRow(track: RecapPlayedTrack) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildString {
                if (track.artist.isNotBlank()) append(track.artist)
                append(if (isNotEmpty()) " · " else "")
                append(
                    if (track.plays == 1) stringResource(R.string.social_recap_one_play)
                    else stringResource(R.string.social_recap_plays, track.plays),
                )
                val adder = track.addedBy
                if (!adder.isNullOrBlank()) {
                    append(" · ")
                    append(stringResource(R.string.social_recap_added_by, adder))
                }
            }
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ContributorRow(name: String, recap: PartyRecap) {
    val colors = MaterialTheme.colorScheme
    val adds: List<RecapAddedTrack> = recap.addedTracks.filter { it.by == name }
    val picked: List<RecapPlayedTrack> = recap.playedTracks.filter { it.addedBy == name }
    // Titles this member is known to have queued: titled adds, plus tracks
    // the protocol attributes to them that never went through the add log.
    val titled = (adds.mapNotNull { it.title } +
        picked.map { it.title }.filter { it !in adds.mapNotNull { a -> a.title } })
        .distinct()
    val queuedCount = recap.addedCount(name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onPrimary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = buildString {
                if (queuedCount > 0) {
                    append(stringResource(R.string.social_recap_queued_by, queuedCount, name))
                }
                if (titled.isNotEmpty()) {
                    if (isNotEmpty()) append(" — ")
                    append(titled.take(3).joinToString(", "))
                    if (titled.size > 3) append("…")
                }
                if (isEmpty()) {
                    append(stringResource(R.string.social_recap_unknown_adder))
                }
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
