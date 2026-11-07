package com.music.sonariq.ui.screens

import com.music.sonariq.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.sonariq.data.model.HomeShelf
import com.music.sonariq.data.model.MoodGenre
import com.music.sonariq.data.model.MoodGenreSection
import com.music.sonariq.data.model.ShelfItem
import com.music.sonariq.data.model.UiState
import com.music.sonariq.ui.components.MessageState
import com.music.sonariq.ui.components.PAGE_GUTTER
import com.music.sonariq.ui.components.PullToRefresh
import com.music.sonariq.ui.components.ShimmerBox
import com.music.sonariq.ui.components.feedSkeleton
import com.music.sonariq.ui.icons.SonariqIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    state: UiState<List<MoodGenreSection>>,
    listState: LazyListState,
    onCategoryClick: (MoodGenre) -> Unit,
    /**
     * Holding a category card offers "Start Autoplay" for that category.
     * A host-only action — the autoplay machinery lives outside this
     * module — so null wherever the host offers none (the desktop).
     */
    onCategoryAutoplay: ((MoodGenre) -> Unit)? = null,
    /**
     * Starts a decade's autoplay (e.g. "90s") from the era chip row. Null
     * hides the row entirely, on the same host-only terms as
     * [onCategoryAutoplay].
     */
    onEraAutoplay: ((String) -> Unit)? = null,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** The big "Explore" heading; the desktop's pages carry none. */
    showTitle: Boolean = true,
) {
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = moodColumns(maxWidth)
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showTitle) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(
                                start = PAGE_GUTTER,
                                end = PAGE_GUTTER,
                                top = 8.dp,
                                bottom = 16.dp,
                            ),
                        ) {
                            Text(
                                text = stringResource(Res.string.explore),
                                style = MaterialTheme.typography.displayMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("Find a mood. Find your next favorite.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (onEraAutoplay != null) {
                    item { EraAutoplayRow(onEraAutoplay = onEraAutoplay) }
                }
                when (state) {
                    UiState.Loading -> item { ExploreSkeletonRows(columns) }
                    is UiState.Error -> item {
                        MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                    }
                    is UiState.Success -> {
                        // Moods and genres read as one grid; the server's
                        // grouping only decides the order.
                        val rows = state.data.flatMap(MoodGenreSection::items)
                            .distinctBy { it.browseId to it.params }
                            .chunked(columns)
                        itemsIndexed(rows, key = { _, row -> row.first().let { "${it.browseId}|${it.params}" } }) { index, row ->
                            MoodGenreRow(row = row, columns = columns, rowIndex = index, onCategoryClick = onCategoryClick, onCategoryAutoplay = onCategoryAutoplay)
                        }
                    }
                }
            }
        }
    }
}

/** The decades the era row offers, in the form the era seeds expect. */
private val ERA_DECADES = listOf("70s", "80s", "90s", "2000s", "2010s")

/**
 * The era row: a short heading and a horizontal strip of decade chips above
 * the mood and genre grid. The chips exist for one thing only — tapping one
 * starts that decade's autoplay through the host's [onEraAutoplay] — so the
 * row is part of the screen's heading, not of its category grid.
 */
@Composable
private fun EraAutoplayRow(onEraAutoplay: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = 16.dp),
    ) {
        Text(
            text = stringResource(Res.string.era),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ERA_DECADES, key = { it }) { decade ->
                // Same chip geometry as the Search filter row and the
                // Library chips — 12 dp corner, 14 by 8 dp padding, 8 dp
                // gaps — so tappable chips read as one system app-wide.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onEraAutoplay(decade) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = decade,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MoodGenreRow(
    row: List<MoodGenre>,
    columns: Int,
    rowIndex: Int,
    onCategoryClick: (MoodGenre) -> Unit,
    onCategoryAutoplay: ((MoodGenre) -> Unit)? = null,
) {
    // A regular grid: every card in the row takes the same share, so the
    // discovery grid reads as uniform rectangles rather than a mosaic.
    Row(
        horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = MOOD_SPACING),
    ) {
        row.forEach { item ->
            MoodGenreCard(
                item = item,
                onClick = { onCategoryClick(item) },
                onAutoplay = onCategoryAutoplay?.let { start -> { start(item) } },
                modifier = Modifier.weight(1f),
            )
        }
        // Short rows keep their cards the size of a full row's.
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * The category's cover filling the whole card at the uniform 12 dp corner,
 * a dark gradient rising from the foot so the title stays legible over any
 * artwork, and the title set at the card's bottom-left on the grid's own
 * 12 dp inset. Every card is the same height and shape, so the discovery
 * grid reads as one regular grid rather than a mosaic.
 *
 * [onAutoplay] is the card's held-press menu: "Start Autoplay" builds this
 * category's autoplay queue through the host. Null where the host offers no
 * autoplay, and the card keeps its plain tap.
 */
@Composable
private fun MoodGenreCard(
    item: MoodGenre,
    onClick: () -> Unit,
    onAutoplay: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val shape = UniformCardShape
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier = modifier.height(MOOD_CARD_HEIGHT).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .then(
                if (onAutoplay != null) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = { menuOpen = true },
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            ),
    ) {
        item.thumbnailUrl?.let { artwork ->
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.05f),
                    0.38f to Color.Black.copy(alpha = 0.10f),
                    1f to Color.Black.copy(alpha = 0.82f),
                ),
            ),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onAutoplay != null) {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.start_autoplay)) },
                    leadingIcon = {
                        Icon(
                            imageVector = SonariqIcons.Infinity,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onAutoplay()
                    },
                )
            }
        }
    }
}

/** Gap between mood cards, in both directions — the same 12 dp card gap the shelves and the Library grid use. */
private val MOOD_SPACING = 12.dp

/** Every mood card is the same height; the row's columns share the width equally. */
private val MOOD_CARD_HEIGHT = 104.dp

/**
 * The narrowest a mood card is let get before the row drops a column.
 *
 * Pitched so every phone still gets its two (the widest, 448dp, has room for
 * 1.8 of these), while a tablet or a window lays out as many as fit rather
 * than two cards each half a screen wide.
 */
private val MOOD_MIN_CARD_WIDTH = 220.dp

private const val MOOD_MAX_COLUMNS = 6

private fun moodColumns(available: Dp): Int {
    val row = available - PAGE_GUTTER * 2
    return ((row + MOOD_SPACING) / (MOOD_MIN_CARD_WIDTH + MOOD_SPACING)).toInt().coerceIn(2, MOOD_MAX_COLUMNS)
}

@Composable
private fun ExploreSkeletonRows(columns: Int) {
    Column(Modifier.padding(horizontal = PAGE_GUTTER)) {
        repeat(6) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
                modifier = Modifier.padding(bottom = MOOD_SPACING),
            ) {
                repeat(columns) {
                    // Same height and shape as the real MoodGenreCard, so
                    // the grid does not jump when the data lands.
                    ShimmerBox(Modifier.weight(1f).height(MOOD_CARD_HEIGHT), UniformCardShape)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodGenrePlaylistsScreen(
    title: String,
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
        }
        when (state) {
            UiState.Loading -> feedSkeleton()
            is UiState.Error -> item {
                MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
            }
            is UiState.Success -> items(state.data, key = { it.title }) { shelf ->
                Shelf(shelf = shelf, onItemClick = onItemClick)
            }
        }
    }
}
