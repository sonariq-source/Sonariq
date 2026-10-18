package com.music.sonariq.ui.discovery

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.music.sonariq.data.discovery.MonthlyRecap
import com.music.sonariq.data.discovery.recapForMonth
import com.music.sonariq.data.discovery.recapMonths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

/**
 * The monthly recap: one month of listening as headline numbers and charts,
 * shareable as a card.
 *
 * ## Data
 *
 * The month is picked from [recapMonths] — the months with listening on this
 * device — and reduced by [recapForMonth] from that month's own stored bucket.
 * A month with no bucket cannot be picked, so there is no empty state to
 * invent one for; the "no data" card only appears when a bucket exists but
 * holds nothing countable.
 */
@Composable
fun MonthlyRecapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var months by remember { mutableStateOf<List<YearMonth>>(emptyList()) }
    var selected by remember { mutableStateOf<YearMonth?>(null) }
    var recap by remember { mutableStateOf<MonthlyRecap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var sharing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val available = withContext(Dispatchers.IO) { recapMonths() }
        months = available
        selected = available.firstOrNull()
        loading = false
    }
    LaunchedEffect(selected) {
        val month = selected
        recap = null
        if (month != null) {
            recap = withContext(Dispatchers.IO) { recapForMonth(month) }
        }
    }

    fun share() {
        val current = recap ?: return
        scope.launch {
            sharing = true
            // try/catch rather than runCatching: the render is suspending and
            // runCatching's block is not.
            try {
                val bitmap = renderMonthlyRecapCard(context, current)
                shareDiscoveryCard(context, bitmap, "recap-${current.month}.png")
            } catch (_: Exception) {
                // A failed render shares nothing.
            }
            sharing = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
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
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.discovery_recap_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = stringResource(R.string.discovery_recap_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = ::share, enabled = recap != null && !sharing) {
                Icon(
                    Icons.Rounded.IosShare,
                    contentDescription = stringResource(R.string.discovery_recap_share),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        val current = recap
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.discovery_recap_no_data),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Month picker.
            Box {
                Text(
                    text = current.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                        .clickable { menuOpen = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    months.forEach { month ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (month == selected) "● ${monthLabel(month)}"
                                    else monthLabel(month),
                                )
                            },
                            onClick = {
                                selected = month
                                menuOpen = false
                            },
                        )
                    }
                }
            }

            // Headline numbers.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RecapStat(
                    value = current.minutes.toString(),
                    label = stringResource(R.string.discovery_recap_minutes),
                    modifier = Modifier.weight(1f),
                )
                RecapStat(
                    value = current.totalPlays.toString(),
                    label = stringResource(R.string.discovery_recap_plays),
                    modifier = Modifier.weight(1f),
                )
                RecapStat(
                    value = current.streakDays.toString(),
                    label = stringResource(R.string.discovery_recap_streak),
                    modifier = Modifier.weight(1f),
                )
            }

            // Top track / artist / genre.
            current.topTracks.firstOrNull()?.let { track ->
                RecapHighlight(
                    caption = stringResource(R.string.discovery_recap_top_track),
                    title = track.title,
                    subtitle = track.artist,
                    art = track.art,
                )
            }
            current.topArtists.firstOrNull()?.let { artist ->
                RecapHighlight(
                    caption = stringResource(R.string.discovery_recap_top_artist),
                    title = artist.name,
                    subtitle = null,
                    art = artist.art,
                )
            }
            current.topGenre?.let { genre ->
                RecapHighlight(
                    caption = stringResource(R.string.discovery_recap_top_genre),
                    title = genre,
                    subtitle = null,
                    art = null,
                )
            }

            // Top tracks chart.
            Text(
                text = stringResource(R.string.discovery_top_tracks_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                current.topTracks.forEachIndexed { index, track ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
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
                            model = track.art,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = "${track.plays} plays",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

private fun monthLabel(month: YearMonth): String {
    val name = month.month.name.lowercase()
        .replaceFirstChar { it.uppercase() }
    return "$name ${month.year}"
}

@Composable
private fun RecapStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecapHighlight(
    caption: String,
    title: String,
    subtitle: String?,
    art: String?,
) {
    Column {
        Text(
            text = caption.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
