package com.music.sonariq.ui.discovery

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.sonariq.R
import com.music.sonariq.data.YtMusicRepository
import com.music.sonariq.data.model.BrowseItem
import com.music.sonariq.data.model.BrowseType
import com.music.sonariq.data.model.HomeShelf
import com.music.sonariq.data.model.SearchFilter
import com.music.sonariq.data.model.SearchResult
import com.music.sonariq.data.model.ShelfItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The release radar: new music from followed artists, plus the catalogue's
 * own new releases.
 *
 * ## Data
 *
 * Watched artists come from [FollowedArtists] merged with the month's top
 * listened artists; each artist's page is read for its latest-releases shelf
 * (see [ReleaseRadar]). The "New releases" section reads the catalogue's own
 * new-releases page. Every row carries a browse id, and taps report through
 * [onOpenRelease]/[onOpenArtist] for the host to navigate (Phase 2) — this
 * screen never navigates on its own. [onOpenRelease] carries the whole
 * [ShelfItem] so the host has the title, subtitle and artwork the detail
 * page needs, not just the browse id.
 *
 * ## Following
 *
 * The search box finds artists through the catalogue's artist search; the
 * follow button writes to [FollowedArtists] and nudges the catalogue
 * subscription where the page named a channel. Unfollowing is the chip's tap.
 */
@Composable
fun RadarScreen(
    onBack: () -> Unit,
    onOpenRelease: (item: ShelfItem) -> Unit,
    onOpenArtist: (browseId: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val follows by FollowedArtists.follows.collectAsState()
    var releases by remember { mutableStateOf<List<RadarArtistRelease>?>(null) }
    var general by remember { mutableStateOf<List<HomeShelf>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var artistHits by remember { mutableStateOf<List<BrowseItem>>(emptyList()) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun refresh() {
        scope.launch {
            releases = null
            releases = withContext(Dispatchers.IO) { ReleaseRadar.radar() }
            general = withContext(Dispatchers.IO) { ReleaseRadar.generalNewReleases() }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    // A follow changing the watch list re-reads the radar.
    LaunchedEffect(follows) {
        if (releases != null) refresh()
    }

    fun searchArtists(text: String) {
        query = text
        searchJob?.cancel()
        if (text.isBlank()) {
            artistHits = emptyList()
            searching = false
            return
        }
        searching = true
        searchJob = scope.launch {
            delay(450)
            val hits = withContext(Dispatchers.IO) {
                YtMusicRepository.search(text, SearchFilter.ARTISTS)
                    .getOrNull()
                    .orEmpty()
                    .filterIsInstance<SearchResult.Browse>()
                    .map { it.item }
                    .filter { it.type == BrowseType.ARTIST }
            }
            artistHits = hits
            searching = false
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
                    text = stringResource(R.string.discovery_radar_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = stringResource(R.string.discovery_radar_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = ::searchArtists,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.discovery_radar_search_hint)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                    )
                    if (searching) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    }
                    artistHits.forEach { hit ->
                        ArtistFollowRow(
                            name = hit.title,
                            subtitle = hit.subtitle,
                            artworkUrl = hit.thumbnailUrl,
                            followed = follows.any { it.browseId == hit.browseId },
                            onToggle = {
                                if (follows.any { it.browseId == hit.browseId }) {
                                    FollowedArtists.unfollow(hit.browseId)
                                } else {
                                    FollowedArtists.follow(
                                        FollowedArtist(
                                            name = hit.title,
                                            browseId = hit.browseId,
                                            thumbnailUrl = hit.thumbnailUrl,
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }

            item {
                Column {
                    Text(
                        text = stringResource(R.string.discovery_radar_following),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    if (follows.isEmpty()) {
                        Text(
                            text = stringResource(R.string.discovery_radar_no_follows),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(follows, key = { it.browseId }) { artist ->
                                FilterChip(
                                    selected = true,
                                    onClick = { FollowedArtists.unfollow(artist.browseId) },
                                    label = { Text(artist.name, maxLines = 1) },
                                    trailingIcon = {
                                        Icon(Icons.Rounded.Check, contentDescription = null)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            val radarReleases = releases
            if (radarReleases == null) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.discovery_radar_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                if (radarReleases.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.discovery_radar_nothing_new),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                items(radarReleases, key = { it.artistBrowseId }) { release ->
                    Column {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onOpenArtist(release.artistBrowseId) }
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = release.artistThumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = release.artistName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = release.shelfTitle +
                                        (release.year?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(release.items, key = { it.browseId ?: it.title }) { item ->
                                ReleaseCard(
                                    item = item,
                                    onClick = {
                                        if (item.browseId != null) onOpenRelease(item)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (general.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.discovery_radar_new_releases),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                general.forEach { shelf ->
                    item {
                        Column {
                            Text(
                                text = shelf.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(shelf.items, key = { it.browseId ?: it.title }) { item ->
                                    ReleaseCard(
                                        item = item,
                                        onClick = { if (item.browseId != null) onOpenRelease(item) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistFollowRow(
    name: String,
    subtitle: String,
    artworkUrl: String?,
    followed: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = artworkUrl,
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onToggle) {
            Icon(
                if (followed) Icons.Rounded.Check else Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(
                    if (followed) R.string.discovery_radar_unfollow
                    else R.string.discovery_radar_follow,
                ),
            )
        }
    }
}

@Composable
private fun ReleaseCard(item: ShelfItem, onClick: () -> Unit) {
    Column(
        Modifier.width(140.dp).clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.size(140.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.subtitle.isNotBlank()) {
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
