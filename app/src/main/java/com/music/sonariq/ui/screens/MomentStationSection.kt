package com.music.sonariq.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.sonariq.R
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.model.artworkAt
import com.music.sonariq.data.mood.MomentSlot
import com.music.sonariq.data.mood.MomentStations
import com.music.sonariq.data.stats.ListeningStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The "For this moment" section on Home: one time-aware station card for the
 * current slice of the day.
 *
 * The station is built in the background when Home appears — seeded from the
 * listener's own plays in this slot's hours (falling back to their
 * most-played, then to real search results) — and tapping the card plays the
 * already-built station outright, no menu, no intermediate screen. When
 * nothing real can be built (no history and no network), the section stays
 * hidden rather than showing an empty or invented card.
 *
 * The built station is cached process-wide per slot and history version, so
 * scrolling Home in and out of view does not rebuild — and re-hit the
 * network — every time.
 */
@Composable
fun MomentStationSection(
    onPlayStation: (List<Song>, MomentSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var slot by remember { mutableStateOf(MomentSlot.current()) }
    var station by remember { mutableStateOf<MomentStations.Station?>(null) }
    var building by remember { mutableStateOf(true) }

    // The slot turns as the day does; re-check on the minute.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            MomentSlot.current().takeIf { it != slot }?.let { slot = it }
        }
    }
    // History can be cleared or re-imported under Settings; rebuild then too.
    val historyVersion by ListeningStats.cleared.collectAsState()
    LaunchedEffect(slot, historyVersion) {
        val cached = momentCache.takeIf {
            it.slot == slot && it.historyVersion == historyVersion
        }?.station
        if (cached != null) {
            station = cached
            building = false
        } else {
            building = true
            val built = withContext(Dispatchers.IO) {
                runCatching { MomentStations.buildStation(context.applicationContext, slot) }
                    .getOrNull()
            }
            if (built != null) {
                momentCache = MomentCache(slot, historyVersion, built)
            }
            station = built
            building = false
        }
    }

    val songs = station?.songs.orEmpty()
    if (!building && songs.isEmpty()) return

    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            text = stringResource(R.string.moment_title),
            style = MaterialTheme.typography.titleLarge,
            color = colors.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 10.dp),
        )
        if (building) {
            MomentBuildingCard()
        } else {
            val current = station ?: return@Column
            MomentStationCard(
                slot = slot,
                station = current,
                onPlay = { onPlayStation(current.songs, slot) },
            )
        }
    }
}

/** Process-wide cache: one built station per slot and history version. */
private data class MomentCache(
    val slot: MomentSlot,
    val historyVersion: Int,
    val station: MomentStations.Station,
)

private var momentCache: MomentCache? = null

private const val MOMENT_ART_PX = 256

@Composable
private fun MomentStationCard(
    slot: MomentSlot,
    station: MomentStations.Station,
    onPlay: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val subtitle = stringResource(
        if (station.personal) R.string.moment_subtitle_personal
        else R.string.moment_subtitle_fresh,
    )
    val count = context.resources.getQuantityString(
        R.plurals.song_count_plural, station.songs.size, station.songs.size,
    )
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceVariant)
            .clickable { onPlay() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val artUrl: String? = station.songs.firstOrNull()?.artworkAt(MOMENT_ART_PX)
        if (artUrl != null) {
            AsyncImage(
                model = artUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(slot.greetingRes),
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(slot.labelRes),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$subtitle · $count",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(colors.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = stringResource(R.string.moment_play_station),
                tint = colors.onPrimary,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun MomentBuildingCard() {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceVariant)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = colors.primary,
        )
        Text(
            text = stringResource(R.string.mood_building),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
    }
}
