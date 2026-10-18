package com.music.sonariq.ui.discovery

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.sonariq.R
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.stats.ListeningStats
import com.music.sonariq.data.stats.MilestoneKind
import com.music.sonariq.data.stats.ReplayPeriod
import com.music.sonariq.data.stats.ReplaySummary
import com.music.sonariq.ui.replay.formatMinutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Listening insights: streaks, genre breakdown, top tracks and top artists —
 * every one of them shareable as a card.
 *
 * ## Data
 *
 * Everything here is read from [ListeningStats]: the period summary for the
 * charts and genres, the milestones for the current streak. Nothing is
 * estimated and nothing is sampled — a section with nothing to say shows its
 * empty state instead of a guess.
 *
 * ## Playback and navigation
 *
 * Song rows report taps through [onPlaySong]; the host wires that to the real
 * player (Phase 2). The screen itself never plays anything and never
 * navigates — it is a page of numbers with share buttons.
 */
@Composable
fun Stats2Screen(
    onBack: () -> Unit,
    onPlaySong: (Song) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf(ReplayPeriod.THIS_MONTH) }
    var summary by remember { mutableStateOf<ReplaySummary?>(null) }
    var streakDays by remember { mutableStateOf<Int?>(null) }
    var sharing by remember { mutableStateOf(false) }

    LaunchedEffect(period) {
        summary = null
        streakDays = null
        val loaded = withContext(Dispatchers.IO) { ListeningStats.summary(period) }
        summary = loaded
        streakDays = withContext(Dispatchers.IO) {
            ListeningStats.milestones()
                .firstOrNull { it.kind == MilestoneKind.STREAK }
                ?.current?.toInt()
        }
    }

    fun share(render: suspend () -> Bitmap, name: String) {
        scope.launch {
            sharing = true
            // try/catch rather than runCatching: the render is suspending and
            // runCatching's block is not.
            try {
                shareDiscoveryCard(context, render(), name)
            } catch (_: Exception) {
                // The card keeps its empty state; a failed render shares nothing.
            }
            sharing = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.discovery_back),
                )
            }
            Text(
                text = stringResource(R.string.discovery_insights_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
            )
        }

        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ReplayPeriod.entries) { entry ->
                FilterChip(
                    selected = period == entry,
                    onClick = { period = entry },
                    label = { Text(entry.chip) },
                )
            }
        }

        val current = summary
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Streak hero.
            InsightSection(
                title = stringResource(R.string.discovery_streak_title),
                onShare = { share({ renderStreakCard(context) }, "streak-card.png") },
                shareLabel = stringResource(R.string.discovery_share_streak),
            ) {
                val days = streakDays
                if (days == null) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                } else if (days == 0) {
                    Text(
                        text = stringResource(R.string.discovery_no_streak_yet),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = days.toString(),
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.discovery_streak_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
            }

            // Genre breakdown.
            InsightSection(
                title = stringResource(R.string.discovery_genres_title),
                onShare = { share({ renderGenreCard(context, period) }, "genres-card.png") },
                shareLabel = stringResource(R.string.discovery_share_genres),
            ) {
                val genres = current.genres.take(8)
                if (genres.isEmpty()) {
                    Text(
                        text = stringResource(R.string.discovery_genres_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val total = genres.sumOf { it.ms }.coerceAtLeast(1L)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        genres.forEach { genre ->
                            Column {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = genre.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "${formatMinutes(genre.ms)} min",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Box(
                                    Modifier.fillMaxWidth().height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                        ),
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(
                                                (genre.ms.toFloat() / total.toFloat())
                                                    .coerceIn(0.02f, 1f),
                                            )
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.primary),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Top tracks.
            InsightSection(
                title = stringResource(R.string.discovery_top_tracks_title),
                onShare = { share({ renderTopTracksCard(context, period) }, "top-tracks-card.png") },
                shareLabel = stringResource(R.string.discovery_share_tracks),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    current.songs.take(10).forEachIndexed { index, ranked ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPlaySong(ranked.song) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = (index + 1).toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(32.dp),
                            )
                            AsyncImage(
                                model = ranked.song.thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = ranked.song.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = ranked.song.artist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = "${formatMinutes(ranked.ms)} min",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Top artists.
            InsightSection(
                title = stringResource(R.string.discovery_top_artists_title),
                onShare = { share({ renderTopArtistsCard(context, period) }, "top-artists-card.png") },
                shareLabel = stringResource(R.string.discovery_share_artists),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    current.artists.take(10).forEachIndexed { index, ranked ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = (index + 1).toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(32.dp),
                            )
                            AsyncImage(
                                model = ranked.artworkUrl,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                                    .clip(RoundedCornerShape(24.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = ranked.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${formatMinutes(ranked.ms)} min · ${ranked.plays} plays",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (sharing) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

/** One insight block: a title, a share button, and the content. */
@Composable
private fun InsightSection(
    title: String,
    onShare: () -> Unit,
    shareLabel: String,
    content: @Composable () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onShare) {
                Icon(
                    Icons.Rounded.IosShare,
                    contentDescription = shareLabel,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}
