package com.music.sonariq.ui.replay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.sonariq.R
import com.music.sonariq.data.model.ROW_ART_PX
import com.music.sonariq.data.model.artworkAt
import com.music.sonariq.data.stats.ListeningStats
import com.music.sonariq.data.stats.ReplayPeriod
import com.music.sonariq.data.stats.ReplaySummary
import com.music.sonariq.ui.player.MeshGradientBackground
import com.music.sonariq.ui.player.rememberArtworkColors
import com.music.sonariq.ui.stats.renderWrappedCard
import com.music.sonariq.ui.stats.shareStatsCard
import com.music.sonariq.ui.theme.AccentRed
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Annual Wrapped: the calendar year as a tap-through story — total
 * minutes, top songs, top artists and top genres, all from the real
 * [ListeningStats] year summary.
 *
 * The story grammar is the Replay's (tap right to advance, left to go back,
 * hold to pause, auto-advance, cross to leave) and the last card shares the
 * Wrapped picture rendered by [renderWrappedCard]. A year with no listening
 * gets an honest empty state, never fabricated numbers.
 */
@Composable
fun WrappedScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val year = remember { LocalDate.now().year }
    var summary by remember { mutableStateOf<ReplaySummary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var sharing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        summary = withContext(Dispatchers.IO) { ListeningStats.summary(ReplayPeriod.THIS_YEAR) }
        loading = false
        // The history can be wiped while the story is open — reload rather
        // than keep showing numbers that no longer exist.
        ListeningStats.cleared.drop(1).collect {
            summary = withContext(Dispatchers.IO) { ListeningStats.summary(ReplayPeriod.THIS_YEAR) }
        }
    }

    fun share() {
        if (sharing) return
        sharing = true
        scope.launch {
            runCatching {
                val bitmap = renderWrappedCard(context, year)
                shareStatsCard(context, bitmap)
            }
            sharing = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF17171A)),
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
            }
            summary == null || summary!!.isEmpty -> WrappedEmpty(year, onClose)
            else -> WrappedStory(
                summary = summary!!,
                year = year,
                sharing = sharing,
                onClose = onClose,
                onShare = ::share,
            )
        }
    }
}

/** The honest state: a year with no listening has no Wrapped to show. */
@Composable
private fun WrappedEmpty(year: Int, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.wrapped),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.W700,
            color = Color.White.copy(alpha = 0.55f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = year.toString(),
            style = MaterialTheme.typography.displayLarge,
            fontSize = 64.sp,
            fontWeight = FontWeight.W800,
            color = Color.White,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.wrapped_empty_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.W800,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.wrapped_empty_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onClose,
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentRed,
                contentColor = Color.White,
            ),
        ) {
            Text(stringResource(R.string.close_wrapped))
        }
    }
}

private enum class WrappedPage { INTRO, MINUTES, SONGS, ARTISTS, GENRES, SHARE }

@Composable
private fun WrappedStory(
    summary: ReplaySummary,
    year: Int,
    sharing: Boolean,
    onClose: () -> Unit,
    onShare: () -> Unit,
) {
    val pages = remember(summary) {
        WrappedPage.entries.filter { page ->
            when (page) {
                WrappedPage.GENRES -> summary.genres.isNotEmpty()
                else -> true
            }
        }
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    var held by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val current by remember { derivedStateOf { pagerState.settledPage } }

    fun goTo(target: Int, animate: Boolean) {
        val next = target.coerceIn(0, pages.lastIndex)
        scope.launch {
            progress.snapTo(0f)
            if (animate) pagerState.animateScrollToPage(next) else pagerState.scrollToPage(next)
        }
    }

    fun step(forward: Boolean) {
        val settled = pagerState.settledPage
        // On the last card, forward tap closes rather than staying put — the
        // same reading the Replay stories make.
        if (forward && settled == pages.lastIndex) {
            scope.launch { onClose() }
            return
        }
        goTo(settled + if (forward) 1 else -1, animate = false)
    }

    LaunchedEffect(current) { progress.snapTo(0f) }
    LaunchedEffect(current, held, sharing) {
        if (held || sharing) return@LaunchedEffect
        if (current == pages.lastIndex) {
            progress.animateTo(1f, tween(PAGE_MILLIS.toInt(), easing = LinearEasing))
            scope.launch { onClose() }
            return@LaunchedEffect
        }
        val remaining = ((1f - progress.value) * PAGE_MILLIS).toInt().coerceAtLeast(0)
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        goTo(current + 1, animate = true)
    }

    val page = pages.getOrElse(current) { WrappedPage.INTRO }
    // The year's own colours: the mesh is drawn from the top song's artwork,
    // the way the Replay page is washed in its lead track.
    val palette = rememberArtworkColors(summary.songs.firstOrNull()?.song?.thumbnailUrl)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF17171A))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        held = true
                        tryAwaitRelease()
                        held = false
                    },
                    // Deliberately empty: without it every press-and-release
                    // reports as a tap, so holding to pause would turn the page
                    // the moment the finger came off.
                    onLongPress = {},
                    onTap = { offset -> step(offset.x >= size.width * BACK_ZONE) },
                )
            },
    ) {
        MeshGradientBackground(palette = palette, trackKey = page.name, animated = false)
        // Ink for the white type, weighted to the headline and the controls.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.45f),
                        0.35f to Color.Black.copy(alpha = 0.10f),
                        1.0f to Color.Black.copy(alpha = 0.34f),
                    ),
                ),
        )

        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
            WrappedStoryPage(
                page = pages[index],
                summary = summary,
                year = year,
                sharing = sharing,
                onShare = onShare,
            )
        }

        WrappedChrome(
            year = year,
            count = pages.size,
            current = current,
            progress = progress.value,
            onClose = onClose,
        )
    }
}

/** The furniture that doesn't move between cards: the cross, the segments, the title. */
@Composable
private fun WrappedChrome(
    year: Int,
    count: Int,
    current: Int,
    progress: Float,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.close_wrapped),
                tint = Color.White,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .padding(4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(count) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(2.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.28f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(
                                when {
                                    index < current -> 1f
                                    index > current -> 0f
                                    else -> progress
                                }.coerceIn(0f, 1f),
                            )
                            .height(2.5.dp)
                            .background(Color.White),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Wrapped '${year % 100}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W700,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(width = 26.dp, height = 17.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = "Sonariq",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W700,
                color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

// ── One card ────────────────────────────────────────────────────────────────

@Composable
private fun WrappedStoryPage(
    page: WrappedPage,
    summary: ReplaySummary,
    year: Int,
    sharing: Boolean,
    onShare: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = CHROME_HEIGHT, bottom = 18.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize()) {
                when (page) {
                    WrappedPage.INTRO -> WrappedIntro(summary, year)
                    WrappedPage.MINUTES -> WrappedMinutes(summary)
                    WrappedPage.SONGS -> WrappedSongs(summary, year)
                    WrappedPage.ARTISTS -> WrappedArtists(summary, year)
                    WrappedPage.GENRES -> WrappedGenres(summary, year)
                    WrappedPage.SHARE -> WrappedShareCard(summary, sharing, onShare)
                }
            }
        }
        if (page != WrappedPage.SHARE) {
            // The share button is on every card, not only the last one: the
            // card somebody wants to send is whichever one surprised them.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable(enabled = !sharing, onClick = onShare),
                    contentAlignment = Alignment.Center,
                ) {
                    if (sharing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.IosShare,
                            contentDescription = stringResource(R.string.share_wrapped),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.height(44.dp))
        }
    }
}

/** The sentence at the top of every card: the number set bold inside dim prose. */
@Composable
private fun WrappedHeadline(parts: List<HeadlineRun>) {
    Text(
        text = buildAnnotatedString {
            parts.forEach { run ->
                withStyle(
                    SpanStyle(
                        fontWeight = if (run.bold) FontWeight.W800 else FontWeight.W600,
                        color = if (run.bold) Color.White else Color.White.copy(alpha = 0.62f),
                    ),
                ) { append(run.text) }
            }
        },
        style = MaterialTheme.typography.displayLarge,
        fontSize = 30.sp,
        lineHeight = 37.sp,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun headline(vararg parts: Pair<String, Boolean>): List<HeadlineRun> =
    parts.map { HeadlineRun(it.first, it.second) }

@Composable
private fun ColumnScope.WrappedIntro(summary: ReplaySummary, year: Int) {
    val context = LocalContext.current
    WrappedHeadline(
        headline(
            context.getString(R.string.wrapped_intro_start) to false,
            context.getString(R.string.wrapped_intro_title, year % 100) to true,
            context.getString(R.string.wrapped_intro_end) to false,
        ),
    )
    Spacer(Modifier.weight(1f))
    WrappedCollage(summary)
    Spacer(Modifier.weight(1f))
    Text(
        text = stringResource(R.string.replay_private_counting),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.5f),
    )
}

@Composable
private fun ColumnScope.WrappedMinutes(summary: ReplaySummary) {
    val context = LocalContext.current
    WrappedHeadline(
        headline(
            context.getString(R.string.wrapped_minutes_start) to false,
            grouped(summary.minutes) to true,
            context.getString(R.string.wrapped_minutes_end) to false,
        ),
    )
    Spacer(Modifier.weight(1f))
    WrappedBigStat(grouped(summary.minutes), stringResource(R.string.minutes_listened))
    WrappedBigStat(
        context.resources.getQuantityString(
            R.plurals.replay_play_count,
            summary.totalPlays,
            grouped(summary.totalPlays.toLong()),
        ),
        stringResource(R.string.top_songs),
    )
    summary.peakHour?.let {
        WrappedBigStat(formatHour(context, it), stringResource(R.string.when_you_listen_the_most))
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun WrappedBigStat(value: String, label: String) {
    Column(Modifier.padding(bottom = 22.dp)) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.W800,
            color = Color.White,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.58f),
        )
    }
}

/**
 * A number one with its runners-up: the hero is the answer, the list is the
 * evidence. The same card for songs and artists — only the noun changes.
 */
@Composable
private fun ColumnScope.WrappedLeaderboard(
    parts: List<HeadlineRun>,
    rows: List<ReplayRow>,
    circular: Boolean,
) {
    val context = LocalContext.current
    val lead = rows.firstOrNull() ?: return
    val shape = if (circular) CircleShape else RoundedCornerShape(10.dp)
    WrappedHeadline(parts)
    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        WrappedCover(lead.artworkUrl, 108.dp, shape)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = lead.title,
                style = MaterialTheme.typography.headlineMedium,
                fontSize = 26.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.W800,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            lead.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${formatListening(context, lead.ms)} · " + context.resources.getQuantityString(
                    R.plurals.replay_play_count,
                    lead.plays,
                    grouped(lead.plays.toLong()),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
    Spacer(Modifier.weight(1f))
    rows.drop(1).forEach { row ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WrappedRankBadge(row.rank)
            if (circular || row.artworkUrl != null) {
                WrappedCover(row.artworkUrl, 36.dp, shape)
                Spacer(Modifier.width(12.dp))
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.W600,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatListening(context, row.ms),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun ColumnScope.WrappedSongs(summary: ReplaySummary, year: Int) {
    val context = LocalContext.current
    WrappedLeaderboard(
        parts = headline(
            context.getString(R.string.wrapped_songs_start) to false,
            context.getString(R.string.wrapped_songs_mid) to true,
            context.getString(R.string.wrapped_songs_end, year) to false,
        ),
        rows = summary.songRows(5),
        circular = false,
    )
}

@Composable
private fun ColumnScope.WrappedArtists(summary: ReplaySummary, year: Int) {
    val context = LocalContext.current
    WrappedLeaderboard(
        parts = headline(
            context.getString(R.string.wrapped_artists_start) to false,
            context.getString(R.string.wrapped_artists_mid) to true,
            context.getString(R.string.wrapped_artists_end, year) to false,
        ),
        rows = summary.artistRows(5),
        circular = true,
    )
}

@Composable
private fun ColumnScope.WrappedGenres(summary: ReplaySummary, year: Int) {
    val context = LocalContext.current
    val rows = summary.genreRows(5)
    val lead = rows.firstOrNull() ?: return
    WrappedHeadline(
        headline(
            context.getString(R.string.wrapped_genres_start) to false,
            context.getString(R.string.wrapped_genres_mid) to true,
            context.getString(R.string.wrapped_genres_end, year) to false,
        ),
    )
    Spacer(Modifier.weight(1f))
    Text(
        text = lead.title,
        style = MaterialTheme.typography.displayLarge,
        fontSize = 56.sp,
        lineHeight = 58.sp,
        fontWeight = FontWeight.W800,
        color = Color.White,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = formatListening(context, lead.ms),
        style = MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = 0.6f),
    )
    Spacer(Modifier.height(22.dp))
    rows.drop(1).forEach { row ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WrappedRankBadge(row.rank)
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W600,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatListening(context, row.ms),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.45f),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ColumnScope.WrappedShareCard(
    summary: ReplaySummary,
    sharing: Boolean,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    WrappedHeadline(
        headline(
            context.getString(R.string.wrapped_share_start) to false,
            context.getString(R.string.wrapped_share_mid) to true,
            context.getString(R.string.wrapped_share_end) to false,
        ),
    )
    Spacer(Modifier.height(20.dp))
    WrappedRecapLine(stringResource(R.string.minutes), formatMinutes(summary.totalMs))
    summary.songs.firstOrNull()?.let { WrappedRecapLine(stringResource(R.string.top_song), it.song.title) }
    summary.artists.firstOrNull()?.let { WrappedRecapLine(stringResource(R.string.top_artist), it.title) }
    summary.genres.firstOrNull()?.let { WrappedRecapLine(stringResource(R.string.top_genre), it.title) }
    Spacer(Modifier.weight(1f))
    Text(
        text = stringResource(R.string.wrapped_share_hint),
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White.copy(alpha = 0.55f),
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onShare,
        enabled = !sharing,
        colors = ButtonDefaults.buttonColors(
            containerColor = AccentRed,
            contentColor = Color.White,
        ),
        modifier = Modifier.fillMaxWidth().height(54.dp),
    ) {
        if (sharing) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.IosShare,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.share_wrapped),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W700,
            )
        }
    }
}

@Composable
private fun WrappedRecapLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.W700,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Pieces ──────────────────────────────────────────────────────────────────

@Composable
private fun WrappedRankBadge(rank: Int) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(AccentRed.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.W800,
            color = Color.White,
        )
    }
    Spacer(Modifier.width(12.dp))
}

@Composable
private fun WrappedCover(url: String?, size: Dp, shape: Shape, modifier: Modifier = Modifier) {
    val base = modifier
        .size(size)
        .clip(shape)
    if (url != null) {
        AsyncImage(
            model = url.artworkAt(ROW_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = base,
        )
    } else {
        Box(base.background(Color.White.copy(alpha = 0.10f)))
    }
}

/**
 * The scatter of covers and faces the intro card is built around — a pile of
 * records, hand-placed. The anchors are the Replay intro's, which are already
 * proven to land inside the frame on the narrowest phone.
 */
@Composable
private fun WrappedCollage(summary: ReplaySummary) {
    val covers = remember(summary) {
        summary.songs.mapNotNull { it.song.thumbnailUrl }.distinct().take(3)
    }
    val faces = remember(summary) {
        summary.artists.mapNotNull { it.artworkUrl }.distinct()
            .filterNot { it in covers }
            .take(3)
    }
    if (covers.isEmpty() && faces.isEmpty()) return

    BoxWithConstraints(Modifier.fillMaxWidth().height(280.dp)) {
        val w = maxWidth
        val h = maxHeight
        val squares = listOf(
            Triple(0.26f to 0.34f, 168.dp, -3f),
            Triple(0.05f to 0.10f, 88.dp, -9f),
            Triple(0.62f to 0.04f, 72.dp, 7f),
        )
        val circles = listOf(
            Triple(0.02f to 0.62f, 62.dp, 0f),
            Triple(0.70f to 0.34f, 76.dp, 0f),
            Triple(0.44f to 0.78f, 66.dp, 0f),
        )
        covers.forEachIndexed { index, url ->
            val (position, size, angle) = squares[index]
            WrappedCover(
                url = url,
                size = size,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .offset(x = w * position.first, y = h * position.second)
                    .rotate(angle),
            )
        }
        faces.forEachIndexed { index, url ->
            val (position, size, _) = circles[index]
            WrappedCover(
                url = url,
                size = size,
                shape = CircleShape,
                modifier = Modifier.offset(x = w * position.first, y = h * position.second),
            )
        }
    }
}

/** How much room [WrappedChrome] takes above a card's content. */
private val CHROME_HEIGHT = 96.dp

/** How long a card holds before moving on, unless a finger is on the screen. */
private const val PAGE_MILLIS = 14_000f

/** The share of the width that means "back" — the left edge, as everywhere else. */
private const val BACK_ZONE = 0.32f
