package com.music.sonariq.ui.screens

import android.os.Build
import com.music.sonariq.R

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.music.sonariq.ui.components.longPressMenuClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.graphics.luminance
import com.music.sonariq.data.model.LibraryState
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.delay
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.music.sonariq.data.canvas.AppleArtistArt
import com.music.sonariq.data.canvas.AppleArtistArtRepository
import com.music.sonariq.data.canvas.keyColors
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import com.music.sonariq.data.canvas.CanvasArtwork
import com.music.sonariq.data.canvas.CanvasRepository
import com.music.sonariq.data.model.BrowseType
import com.music.sonariq.data.model.DetailPage
import com.music.sonariq.data.model.CARD_ART_PX
import com.music.sonariq.data.model.HEADER_ART_PX
import com.music.sonariq.data.model.ROW_ART_PX
import com.music.sonariq.data.model.HomeShelf
import com.music.sonariq.data.model.ShelfItem
import com.music.sonariq.data.settings.SongSort
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.model.SubscriptionState
import com.music.sonariq.data.model.UiState
import com.music.sonariq.data.model.artworkAt
import com.music.sonariq.data.model.durationMillis
import com.music.sonariq.data.model.isSameTrackAs
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.ui.components.DownloadedBadge
import com.music.sonariq.ui.components.ExplicitSongTitle
import com.music.sonariq.ui.components.LIBRARY_GRID_SPACING
import com.music.sonariq.ui.components.MessageState
import com.music.sonariq.ui.components.PAGE_GUTTER
import com.music.sonariq.ui.components.NUMBERED_ROW_DIVIDER_INSET
import com.music.sonariq.ui.components.RowMoreButton
import com.music.sonariq.ui.components.ROW_DIVIDER_INSET
import com.music.sonariq.ui.components.SHELF_CARD_WIDTH
import com.music.sonariq.ui.components.SongRow
import com.music.sonariq.ui.components.SearchPlayingBars
import com.music.sonariq.ui.components.libraryGrid
import com.music.sonariq.ui.components.lightweightLiquidGlass
import com.music.sonariq.ui.components.thumbnailBorder
import com.music.sonariq.ui.components.detailSkeleton
import com.music.sonariq.ui.components.topBarContentPadding
import com.music.sonariq.ui.haptics.Haptic
import com.music.sonariq.ui.haptics.rememberHaptics
import com.music.sonariq.ui.icons.SonariqIcons
import com.music.sonariq.ui.player.CanvasArtworkPlayer
import com.music.sonariq.ui.theme.ArtworkPalette
import com.music.sonariq.ui.theme.SonariqLime
import com.music.sonariq.ui.theme.rememberArtworkPalette
import kotlin.math.roundToInt
import java.util.Locale

private const val MAX_ARTIST_SONGS = 20
private const val ARTIST_ROW_MAX_ITEMS = 5
/** Related-artist circles run a touch narrower than the release cards. */
private val RELATED_ARTIST_CARD_WIDTH = 132.dp

/** The artist photo, very slightly taller than it is wide. */
private const val ARTIST_PHOTO_RATIO = 0.95f

/** A release's sleeve, given a little more height than the artist photo. */
private const val SLEEVE_RATIO = 0.92f

/** The sleeve on a release page, as a fraction of the page width. */
private const val SLEEVE_FRACTION = 0.80f

private val SLEEVE_SHAPE = RoundedCornerShape(12.dp)
private val PILL_SHAPE = RoundedCornerShape(12.dp)

/**
 * Where the search field sits once it is open — directly under the header,
 * which is item zero. The one place that has to know, so that opening the
 * search can carry the page up to it.
 */
private const val SEARCH_ITEM_INDEX = 1

/** The inset the header text and the action pills share. */
private val HEADER_GUTTER = PAGE_GUTTER + 8.dp

/** How many of an artist's top tracks show before the "Show more" toggle. */
private const val POPULAR_PREVIEW_COUNT = 5

/** Tabs on an artist page — About / Albums / Similar artists. */
internal enum class ArtistDetailTab(
    @androidx.annotation.StringRes val labelRes: Int,
) {
    ABOUT(R.string.tab_about),
    ALBUMS(R.string.tab_albums),
    SIMILAR_ARTISTS(R.string.tab_similar_artists),
}

/**
 * Which artist tabs the page earns from its data. Every tab is data-gated: an
 * artist with no bio gets no About tab, one with no release shelves gets no
 * Albums tab, and Similar artists appears only when the response actually
 * carries an artist shelf — never from anything estimated or invented.
 */
internal fun artistDetailTabs(
    hasAbout: Boolean,
    hasReleases: Boolean,
    hasSimilarArtists: Boolean,
): List<ArtistDetailTab> = buildList {
    if (hasAbout) add(ArtistDetailTab.ABOUT)
    if (hasReleases) add(ArtistDetailTab.ALBUMS)
    if (hasSimilarArtists) add(ArtistDetailTab.SIMILAR_ARTISTS)
}

/** Tabs on a release page — Tracks always, About only when there's a blurb. */
internal enum class ReleaseDetailTab(
    @androidx.annotation.StringRes val labelRes: Int,
) {
    TRACKS(R.string.tab_tracks),
    ABOUT(R.string.tab_about),
}

/**
 * Which release tabs the page earns. Playlists never carry an editorial blurb
 * (see [DetailPage.description]), so they get a single tab and no tab bar.
 */
internal fun releaseDetailTabs(hasAbout: Boolean): List<ReleaseDetailTab> =
    if (hasAbout) listOf(ReleaseDetailTab.TRACKS, ReleaseDetailTab.ABOUT)
    else listOf(ReleaseDetailTab.TRACKS)

/** Extra breathing room for the editorial copy on album and artist pages. */
private val ABOUT_GUTTER = PAGE_GUTTER + 6.dp

/** The artist bio and everything below it: the page edge every page shares. */
private val ARTIST_CONTENT_GUTTER = PAGE_GUTTER

/**
 * How far past the foot of the artwork the title block is allowed to hang.
 *
 * Sat flush to the bottom of the picture it lands wherever the picture happens
 * to be busy, and on a sleeve with anything going on down there the title reads
 * as part of the artwork rather than as a caption to it. Dropped clear, it sits
 * on the blurred colour instead, which has nothing in it to compete.
 */
private val HEADER_DROP = 44.dp

/** How far the artist name/logo and everything under it sit above the usual [HEADER_DROP]. */
private val ARTIST_HEADER_LIFT = 64.dp

/** The artist action row: a large Play flanked by two smaller glass circles. */
private val ARTIST_PLAY_BUTTON = 70.dp

/** A larger share of a smaller circle than the release headers' 0.44. */
private const val ARTIST_PLAY_ICON_SCALE = 0.56f
private val ARTIST_SIDE_BUTTON = 52.dp
private val ARTIST_ACTION_GAP = 24.dp

/** The title logo's widest share of the page, and tallest share of the photo. */
private const val ARTIST_LOGO_WIDTH = 0.82f
private const val ARTIST_LOGO_MAX_HEIGHT = 0.30f

private const val LOGO_RETRIES = 2
private const val LOGO_RETRY_DELAY_MS = 800L

private val CREDIT_SEPARATOR = Regex(""",\s|\s&\s|\s(?:x|and|feat\.?|ft\.?|with)\s""", RegexOption.IGNORE_CASE)

/** Whether this reads as several artists credited together rather than one name. */
private fun String.looksLikeCredit() = CREDIT_SEPARATOR.containsMatchIn(this)

/**
 * Album / artist / playlist page. Rendered inside the main content area
 * rather than as a sheet, so the tab bar and mini player stay visible.
 *
 * The page paints itself in the artwork's own colours — one solid tint behind
 * everything, the artwork itself across the top of it, and an accent taken off
 * the sleeve for the credit line and the Play/Shuffle pair. See
 * [rememberArtworkPalette] for how those are derived and kept legible.
 *
 * Two layers: [PageBackground] — the artwork, dissolving into the tint, and
 * nothing you can read — and the list of titles, buttons and rows over it.
 * Nothing in either is re-blurred while the page scrolls.
 */
@Composable
fun DetailScreen(
    page: DetailPage,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onSectionItemClick: (ShelfItem) -> Unit,
    onArtistClick: (String, String) -> Unit,
    onAddSuggested: (Song) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    activeShelf: HomeShelf? = null,
    onActiveShelfChange: (HomeShelf?) -> Unit = {},
    /**
     * Holding one of the album cards on an artist page — the same menu the
     * shelves on every other tab open, so a release can be queued from
     * wherever it is seen rather than only from its own page.
     */
    onSectionItemLongPress: ((ShelfItem) -> Unit)? = null,
    /**
     * The header's overflow: everything this page can do to its whole track
     * list that isn't already one of the buttons beside it — downloading it
     * among them, see [com.music.sonariq.ui.components.BrowseActionsSheet].
     * Null on the pages with no list to act on.
     */
    onMore: ((List<Song>) -> Unit)? = null,
    /**
     * Saves this release to the account's library, or takes it out —
     * [DetailPage.library] says which way round. Null hides the control
     * entirely, which is the answer for a guest and for the pages YouTube never
     * offers to save; there is nothing to show a signed-out user here that
     * wouldn't just be refused.
     */
    onToggleLibrary: (() -> Unit)? = null,
    /**
     * Subscribes to this artist's channel, or unsubscribes —
     * [DetailPage.subscription] says which way round. The artist page's answer
     * to [onToggleLibrary], and null in the same circumstances: a guest, or a
     * page whose header never offered the button.
     */
    onToggleSubscription: (() -> Unit)? = null,
    /** Library state of releases shown as cards on this page, by browse id. */
    releaseLibrary: Map<String, LibraryState> = emptyMap(),
    onLoadReleaseLibrary: ((String) -> Unit)? = null,
    /** Null where saving isn't offered — a guest. */
    onToggleReleaseLibrary: ((String) -> Unit)? = null,
    /**
     * How the track list is ordered — the release's own running order by
     * default, or alphabetical. Owned by the caller rather than this page
     * because the control for it lives in the top bar, alongside the account
     * photo, not in this header.
     */
    songSort: SongSort = SongSort.DEFAULT,
) {
    val rawSongs = (page.songs as? UiState.Success)?.data.orEmpty()
    val songs = remember(rawSongs, songSort) { rawSongs.sortedForDetail(songSort) }
    // What a numbered row shows regardless of [songSort] — an album's track
    // numbers are the sleeve's own and must not relabel themselves to match
    // wherever a sort put the row.
    val originalTrackNumbers = remember(rawSongs) {
        rawSongs.withIndex().associate { (i, s) -> s.videoId to i + 1 }
    }
    val isArtist = page.type == BrowseType.ARTIST
    // Whether the top release card's album is already saved isn't on the shelf
    // item; it is read off the album once, as soon as the card is known.
    val topReleaseId = if (isArtist) page.sections.topRelease()?.browseId else null
    LaunchedEffect(topReleaseId) { topReleaseId?.let { onLoadReleaseLibrary?.invoke(it) } }
    // Apple Music's hero photo and title logo for this artist, found by name.
    // Null until (and unless) it arrives; the YouTube header stands in meanwhile.
    var appleArt by remember(page.browseId) {
        mutableStateOf(if (isArtist) AppleArtistArtRepository.cached(page.title) else null)
    }
    // Opened from a track, the title is the whole credit ("A, B & C") until the
    // page loads and swaps in the one artist's name. Searching a credit finds
    // nobody, so a title that reads as several artists waits for that swap; any
    // other name starts the moment the page opens.
    val nameSettled = !page.title.looksLikeCredit() || page.songs !is UiState.Loading
    LaunchedEffect(page.browseId, page.title, nameSettled) {
        if (isArtist && nameSettled) appleArt = AppleArtistArtRepository.artFor(page.title) ?: appleArt
    }
    val palette = rememberArtworkPalette(
        imageUrl = appleArt?.heroUrl ?: page.thumbnailUrl,
        keyColors = appleArt?.keyColors(),
    )

    // Narrowing the running order in place — the release equivalent of the
    // filter box on the Local Music tab, and the one thing a long track list
    // needs that scrolling can't give it. Off by default and reset with the
    // page: a filter left on an album that was closed and reopened would be a
    // page that appears to have lost most of its tracks.
    var searching by rememberSaveable(page.browseId) { mutableStateOf(false) }
    var query by rememberSaveable(page.browseId) { mutableStateOf("") }
    // Whether the field still owes the keyboard an appearance. Held here rather
    // than in the field, which is a row in a lazy list: scrolled out of sight it
    // is disposed, and a field that asks for focus every time it is composed
    // would throw the keyboard back up each time it scrolled into view.
    var focusSearch by remember(page.browseId) { mutableStateOf(false) }
    val closeSearch = {
        searching = false
        query = ""
    }
    // Back closes the search first — this handler is registered after the one
    // that pops the page, so it is the one that answers while it's enabled.
    BackHandler(enabled = searching) { closeSearch() }

    // Each surviving row still knows where it sat in the full running order, so
    // an album's track numbers stay the album's rather than becoming positions
    // in the filtered list.
    val matches = remember(songs, query) { songs.matching(query) }
    // What a tap plays: the filtered set of tracks currently standing in the
    // list. When no filter is active, matches contains the full running order
    // and plays the complete release/playlist from the tapped position.
    val queue = remember(matches) { matches.map { it.value } }
    val suggested = remember(page.suggestedSongs, query) {
        page.suggestedSongs.matching(query).map { it.value }
    }

    // What marks a row as already downloaded, tinted from the sleeve like the
    // rest of the page. Null on any page that is itself a reading of this
    // device — the Downloads folder, one downloaded playlist — where every row
    // qualifies and the badge would be decoration rather than information.
    val downloadedTint = palette.accent.takeUnless { page.browseId.startsWith("local:") }

    // Animated cover art on the header, the same feature the player has.
    // Albums only: a playlist's artwork is a collage and an artist page's is a
    // photograph, and neither is something a label publishes a canvas for.
    val canvasEnabled by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val prioritizeSpotifyCanvas by AppSettings.prioritizeSpotifyCanvas.collectAsStateWithLifecycle()
    // The credit line the header shows is the artist as far as the catalogue
    // services are concerned. A browse card's subtitle sometimes omits it, in
    // which case the tracks themselves know who it is.
    val credit = page.headerLines(songs.size).first.ifBlank { songs.firstOrNull()?.artist.orEmpty() }
    var canvas by remember(page.browseId) { mutableStateOf<CanvasArtwork?>(null) }
    LaunchedEffect(page.browseId, page.title, credit, canvasEnabled, prioritizeSpotifyCanvas) {
        // An artist's clip is set below, by the lookup that finds it.
        if (isArtist) return@LaunchedEffect
        if (!canvasEnabled || page.type != BrowseType.ALBUM) {
            canvas = null
            return@LaunchedEffect
        }
        // As on the player: the credit fills in once the tracks load, so this
        // can run twice. Keep a clip that is already playing if the second
        // pass comes back empty.
        canvas = CanvasRepository.canvasForAlbum(page.title, credit) ?: canvas
    }
    // An artist's clip comes with the same Apple lookup as its photograph.
    val artistVideo = appleArt?.videoUrl
    LaunchedEffect(artistVideo, canvasEnabled) {
        if (isArtist) canvas = artistVideo?.takeIf { canvasEnabled }?.let { CanvasArtwork(url = it) }
    }

    // Opening the search carries the page up to it, so the field lands just
    // clear of the frosted bar with the tracks under it rather than at the foot
    // of a screen still filled with artwork. Done as an effect rather than in
    // the tap, so the row it scrolls to is already in the list by the time it
    // runs.
    val searchStop = with(LocalDensity.current) { topBarContentPadding().roundToPx() }
    LaunchedEffect(searching) {
        if (searching) listState.animateScrollToItem(SEARCH_ITEM_INDEX, -searchStop)
    }

    AnimatedContent(
        targetState = activeShelf,
        transitionSpec = {
            fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
        },
        label = "artist_shelf_transition",
        modifier = modifier.fillMaxSize(),
    ) { targetShelf ->
        if (targetShelf == null) {
            BoxWithConstraints(Modifier.fillMaxSize().background(palette.wash)) {
                // The artwork is drawn behind the list rather than in it, so both need
                // to agree on its height without being able to ask each other. The
                // width is the page's, so the ratio decides it and both can work it out
                // alone.
                //
                // Measured rather than read off the window. The player runs
                // full-screen at every window size, so this page's own width in
                // landscape is the *whole* window. Straight off that width, the ratio hands back
                // a hero taller than the window itself — the artwork and track list
                // end up scrolled out of sight beneath what reads as a blank page.
                // Capping against the window's own height is what keeps the ratio's
                // math honest once the width it's fed is no longer guaranteed to be
                // the narrow one it was written for.
                val artHeight = (maxWidth / if (isArtist) ARTIST_PHOTO_RATIO else SLEEVE_RATIO)
                    .coerceAtMost(maxHeight * 0.6f)

                PageBackground(
                    page = page,
                    palette = palette,
                    canvas = canvas,
                    artHeight = artHeight,
                    listState = listState,
                    heroUrl = appleArt?.heroUrl,
                    modifier = Modifier.matchParentSize(),
                )
            val description = page.description
            // Which tabs each page earns from its data — every tab is gated on
            // the response actually carrying what it shows; see
            // [artistDetailTabs] and [releaseDetailTabs].
            val hasStats = isArtist &&
                (page.subscriberCountText != null || page.monthlyListenerCount != null)
            // The first release on the shelves below, pulled out as a card of
            // its own at the top of the Albums tab.
            val topRelease = if (isArtist) page.sections.topRelease() else null
            val artistTabs = remember(description, page.sections, topRelease) {
                artistDetailTabs(
                    hasAbout = !description.isNullOrBlank(),
                    hasReleases = topRelease != null ||
                        page.sections.any { shelf ->
                            !shelf.isArtistShelf() && shelf.items.isNotEmpty()
                        },
                    hasSimilarArtists = page.sections.any { it.isArtistShelf() },
                )
            }
            var selectedArtistTab by rememberSaveable(page.browseId) {
                mutableStateOf<ArtistDetailTab?>(null)
            }
            val currentArtistTab =
                selectedArtistTab?.takeIf { it in artistTabs } ?: artistTabs.firstOrNull()
            val releaseTabs = remember(description, page.type) {
                releaseDetailTabs(
                    hasAbout = page.type == BrowseType.ALBUM && !description.isNullOrBlank(),
                )
            }
            var selectedReleaseTab by rememberSaveable(page.browseId) {
                mutableStateOf(ReleaseDetailTab.TRACKS)
            }
            val currentReleaseTab =
                selectedReleaseTab.takeIf { it in releaseTabs } ?: releaseTabs.first()


                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // Both artist photos and release artwork run edge-to-edge up under
                    // the glass bar — the image is the top of the page, not a card on it.
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                ) {
            item(key = "header") {
                if (isArtist) {
                    ArtistHeader(page = page, palette = palette, artHeight = artHeight, appleArt = appleArt)
                } else {
                    ReleaseHeader(
                        page = page,
                        palette = palette,
                        artHeight = artHeight,
                        trackCount = songs.size,
                        songs = songs,
                        onArtistClick = onArtistClick,
                    )
                }
            }

            if (searching) {
                item(key = "search") {
                    DetailSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        onClose = closeSearch,
                        autoFocus = focusSearch,
                        onFocused = { focusSearch = false },
                        palette = palette,
                        type = page.type,
                    )
                }
            }


            if (songs.isNotEmpty() && isArtist) {
                item(key = "actions") {
                    ActionRow(
                        palette = palette,
                        onPlay = { onSongClick(songs, 0) },
                        onShuffle = { onShuffle(songs) },
                        subscription = page.subscription?.takeIf { onToggleSubscription != null },
                        onToggleSubscription = onToggleSubscription,
                        bottomSpace = 22.dp,
                        playColor = appleArt?.keyColor?.let { Color(it) },
                    )
                }
            }

            // The artist's own counts, as chips under the actions. Both are
            // YouTube's figures off the page header (see
            // [DetailPage.subscriberCountText]) — real counts or nothing at
            // all, never estimated.
            if (hasStats) {
                item(key = "stats") {
                    ArtistStatsRow(
                        subscriberCountText = page.subscriberCountText,
                        monthlyListenerCount = page.monthlyListenerCount,
                        palette = palette,
                    )
                }
            }

            if (!isArtist) {
                // An album or playlist: Play and Shuffle as pills, then the
                // small glass circles — the library toggle where offered, the
                // in-list search, the overflow — in a quieter row of their own.
                if (songs.isNotEmpty()) {
                    item(key = "actions") {
                        ReleaseActions(
                            palette = palette,
                            songs = songs,
                            library = page.library?.takeIf { onToggleLibrary != null },
                            onToggleLibrary = onToggleLibrary,
                            onPlay = { onSongClick(songs, 0) },
                            onShuffle = { onShuffle(songs) },
                            searching = searching,
                            onSearch = {
                                if (searching) {
                                    closeSearch()
                                } else {
                                    searching = true
                                    focusSearch = true
                                }
                            },
                            onMore = onMore,
                        )
                    }
                }
                // A playlist never carries an editorial blurb, so it earns a
                // single tab and no tab bar — see [releaseDetailTabs].
                if (releaseTabs.size > 1) {
                    item(key = "release-tabs") {
                        DetailTabBar(
                            labels = releaseTabs.map { stringResource(it.labelRes) },
                            selectedIndex = releaseTabs.indexOf(currentReleaseTab),
                            onSelect = { selectedReleaseTab = releaseTabs[it] },
                        )
                    }
                }
            }

            when (val state = page.songs) {
                is UiState.Loading -> detailSkeleton(isArtist)
                is UiState.Error -> item { MessageState(state.message) }
                is UiState.Success -> if (isArtist) {
                    // The artist's top tracks as one vertical list in a glass
                    // panel, rather than the sideways pager — five rows up
                    // front, the rest behind "Show more", so the tabs below
                    // stay in reach. A tap plays from the full top list, not
                    // just what's visible.
                    item(key = "popular") {
                        val top = state.data.take(MAX_ARTIST_SONGS)
                        var expanded by rememberSaveable(page.browseId) {
                            mutableStateOf(false)
                        }
                        val visible = if (expanded) top else top.take(POPULAR_PREVIEW_COUNT)
                        val panelShape = RoundedCornerShape(20.dp)
                        Column {
                            SectionHeading(
                                title = stringResource(R.string.top_songs),
                                palette = palette,
                                horizontalPadding = ARTIST_CONTENT_GUTTER,
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = ARTIST_CONTENT_GUTTER)
                                    .clip(panelShape)
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                        panelShape,
                                    )
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                                        panelShape,
                                    )
                                    .padding(vertical = 6.dp),
                            ) {
                                visible.forEachIndexed { index, song ->
                                    CompactSongRow(
                                        song = song,
                                        palette = palette,
                                        onClick = { onSongClick(top, top.indexOf(song)) },
                                        onLongPress = { onSongLongPress(song) },
                                        downloadedTint = downloadedTint,
                                        isCurrent = song.isSameTrackAs(currentSong),
                                        isPlaying = song.isSameTrackAs(currentSong) && isPlaying,
                                        horizontalPadding = 16.dp,
                                    )
                                    if (index < visible.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 84.dp, end = 16.dp),
                                            thickness = 0.5.dp,
                                            color = palette.divider,
                                        )
                                    }
                                }
                                if (top.size > POPULAR_PREVIEW_COUNT) {
                                    Text(
                                        text = stringResource(
                                            if (expanded) R.string.show_fewer_tracks
                                            else R.string.show_more_tracks,
                                        ),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = palette.accent,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { expanded = !expanded }
                                            .padding(vertical = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                } else if (currentReleaseTab == ReleaseDetailTab.TRACKS) {
                    // Every row on an album carries the same sleeve, which is
                    // already the largest thing on the page — Apple Music
                    // numbers those rows instead, and so does this.
                    val numbered = page.type == BrowseType.ALBUM
                    if (matches.isEmpty() && state.data.isNotEmpty()) {
                        item(key = "no-matches") {
                            MessageState(stringResource(R.string.nothing_matches, query))
                        }
                    }
                    itemsIndexed(
                        items = matches,
                        key = { _, entry -> "${entry.index}_${entry.value.videoId}" },
                    ) { position, entry ->
                        val song = entry.value
                        val isCurrent = song.isSameTrackAs(currentSong)
                        SongRow(
                            song = if (numbered) {
                                song
                            } else {
                                song.copy(thumbnailUrl = song.thumbnailUrl ?: page.thumbnailUrl)
                            },
                            onClick = {
                                onSongClick(queue, position)
                            },
                            onLongPress = { onSongLongPress(song) },
                            onSwipeToQueue = { onSongSwipe(song) },
                            rowBackground = Color.Transparent,
                            // The track's place on the release, not its place in
                            // what the filter — or a sort — left standing.
                            trackNumber = originalTrackNumbers[song.videoId].takeIf { numbered },
                            subtitleColor = palette.onBackgroundVariant,
                            downloadedTint = downloadedTint,
                            isCurrent = isCurrent,
                            isPlaying = isCurrent && isPlaying,
                            activeTint = palette.accent,
                            searchPlayingStyle = true,
                        )
                        if (position < matches.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(
                                    start = if (numbered) NUMBERED_ROW_DIVIDER_INSET else ROW_DIVIDER_INSET,
                                ),
                                thickness = 0.5.dp,
                                color = palette.divider,
                            )
                        }
                    }
                }
            }

            // Tracks YouTube offers to round the playlist out, never folded
            // into the list above — see [DetailPage.suggestedSongs]. On a
            // release page they belong to the Tracks tab, so the About tab
            // shows nothing but the blurb.
            if (suggested.isNotEmpty() &&
                (isArtist || currentReleaseTab == ReleaseDetailTab.TRACKS)
            ) {
                item(key = "suggested-heading") {
                    SectionHeading(stringResource(R.string.suggested), palette)
                }
                itemsIndexed(
                    suggested,
                    key = { _, song -> "suggested-${song.videoId}" },
                ) { index, song ->
                    SuggestedSongRow(
                        song = song,
                        palette = palette,
                        onClick = { onSongClick(suggested, index) },
                        onLongPress = { onSongLongPress(song) },
                        onAdd = { onAddSuggested(song) },
                        downloadedTint = downloadedTint,
                        isCurrent = song.isSameTrackAs(currentSong),
                        isPlaying = song.isSameTrackAs(currentSong) && isPlaying,
                    )
                    if (index < suggested.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                            thickness = 0.5.dp,
                            color = palette.divider,
                        )
                    }
                }
            }

            if (isArtist) {
                // About / Albums / Similar artists. Every tab is data-gated —
                // a page whose response has no artist shelf shows no Similar
                // artists tab at all rather than a section built from anything
                // else — and a lone tab renders its content with no tab bar.
                if (artistTabs.size > 1 && currentArtistTab != null) {
                    item(key = "artist-tabs") {
                        DetailTabBar(
                            labels = artistTabs.map { stringResource(it.labelRes) },
                            selectedIndex = artistTabs.indexOf(currentArtistTab),
                            onSelect = { selectedArtistTab = artistTabs[it] },
                        )
                    }
                }
                when (currentArtistTab) {
                    ArtistDetailTab.ABOUT -> if (!description.isNullOrBlank()) {
                        item(key = "about") {
                            AboutSection(
                                title = stringResource(R.string.about_artist),
                                text = description,
                                palette = palette,
                                horizontalPadding = ARTIST_CONTENT_GUTTER,
                            )
                        }
                    }
                    ArtistDetailTab.ALBUMS -> {
                        // The newest release, pulled out as a card of its own.
                        if (topRelease != null) {
                            item(key = "top-release") {
                                TopReleaseCard(
                                    item = topRelease,
                                    palette = palette,
                                    onClick = { onSectionItemClick(topRelease) },
                                    onLongPress = onSectionItemLongPress?.let { { it(topRelease) } },
                                    saved = topRelease.browseId?.let { releaseLibrary[it]?.saved },
                                    onToggleSaved = onToggleReleaseLibrary?.let { toggle ->
                                        topRelease.browseId?.let { id -> { toggle(id) } }
                                    },
                                )
                            }
                        }
                        page.sections.filter { !it.isArtistShelf() }.forEach { shelf ->
                            releaseShelf(
                                shelf = shelf,
                                palette = palette,
                                onSectionItemClick = onSectionItemClick,
                                onSectionItemLongPress = onSectionItemLongPress,
                                onActiveShelfChange = onActiveShelfChange,
                            )
                        }
                    }
                    ArtistDetailTab.SIMILAR_ARTISTS -> {
                        page.sections.filter { it.isArtistShelf() }.forEach { shelf ->
                            item(key = "similar-${shelf.title}") {
                                RelatedArtistsSection(
                                    shelf = shelf,
                                    palette = palette,
                                    onArtistClick = onSectionItemClick,
                                    onArtistLongPress = onSectionItemLongPress,
                                    onShowAll = { onActiveShelfChange(shelf) },
                                )
                            }
                        }
                    }
                    null -> Unit
                }
            } else if (currentReleaseTab == ReleaseDetailTab.ABOUT) {
                item(key = "about") {
                    AboutSection(
                        title = stringResource(R.string.about_album),
                        text = description,
                        palette = palette,
                    )
                }
            }
        }

    }
} else {
    ArtistShelfGridPage(
        shelf = targetShelf,
        palette = palette,
        onItemClick = onSectionItemClick,
        onItemLongPress = onSectionItemLongPress,
        contentPadding = contentPadding,
        circularArt = targetShelf.isArtistShelf(),
    )
}
}
}

/**
 * An album or playlist: the title, credit and meta that sit over the foot of
 * the artwork. The artwork itself is not here — [PageBackground] draws it,
 * pinned behind the list while this scrolls over it. What this item holds in
 * its place is a spacer of exactly the picture's height, which is what keeps
 * the two in step: the list reserves the room, the background fills it.
 *
 * The actions live in their own item below ([ReleaseActions]) so the tab bar
 * can sit between them and the track list.
 */
@Composable
private fun ReleaseHeader(
    page: DetailPage,
    palette: ArtworkPalette,
    artHeight: Dp,
    trackCount: Int,
    songs: List<Song>,
    onArtistClick: (String, String) -> Unit,
) {
    val (credit, meta) = page.headerLines(trackCount, songs.playtime())
    // Every row on a release carries the same credit — see [pageCredit] — so
    // the first one speaks for the whole page, the same source the rows'
    // own long-press "Open artist" already reads from.
    val artist = songs.firstOrNull()

    // The outer Box just needs to be as tall as its content — we don't force
    // an aspect ratio here so the action buttons can extend below the artwork.
    Box(Modifier.fillMaxWidth()) {

        Spacer(Modifier.fillMaxWidth().height(artHeight + HEADER_DROP))

        // Text + action row stacked, pinned to the bottom of the Box.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineMedium,
                color = palette.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = HEADER_GUTTER),
            )
            // Artist / credit line
            if (credit.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = credit,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.accent,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = HEADER_GUTTER)
                        .let { m ->
                            val id = artist?.artistId
                            if (id == null) {
                                m
                            } else {
                                m.clip(RoundedCornerShape(6.dp))
                                    .clickable { onArtistClick(id, artist.artist) }
                            }
                        },
                )
            }
            // Metadata (kind • year • count)
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
                    color = palette.onBackgroundVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = HEADER_GUTTER),
                )
            }
            // Smart Downloads pages hold only auto-downloaded tracks; the
            // badge says so, so they are never mistaken for manual downloads.
            if (page.browseId == "local:smart-downloads") {
                Spacer(Modifier.height(8.dp))
                SmartDownloadBadge()
            }
        }
    }
}

/**
 * An album or playlist's actions: Play and Shuffle as pills, then the small
 * glass circles — the library toggle where offered, the in-list search, the
 * overflow — in a quieter row of their own, clear of the artwork.
 */
@Composable
private fun ReleaseActions(
    palette: ArtworkPalette,
    songs: List<Song>,
    /** The release's library state where YouTube offers saving; null hides it. */
    library: LibraryState?,
    onToggleLibrary: (() -> Unit)?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
    onMore: ((List<Song>) -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HEADER_GUTTER),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayPill(onClick = onPlay)
            SecondaryPill(
                label = stringResource(R.string.shuffle),
                icon = SonariqIcons.Shuffle,
                onClick = onShuffle,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (library != null) {
                CircleIconButton(
                    // A tick, not a filled-in plus: the pair reads as
                    // "not yet / done", which is what the state is.
                    icon = if (library.saved) SonariqIcons.Check else SonariqIcons.Plus,
                    contentDescription = if (library.saved) {
                        stringResource(R.string.remove_from_library)
                    } else {
                        stringResource(R.string.add_to_library)
                    },
                    palette = palette,
                    onClick = { onToggleLibrary?.invoke() },
                    haptic = if (library.saved) Haptic.ToggleOff else Haptic.ToggleOn,
                    size = 44.dp,
                )
            }
            // Downloading a release is a thing done once and then not thought
            // about; finding a track on a long playlist is a thing done while
            // reading the page, so it is the one that earns a button and the
            // download moved to the overflow beside it.
            CircleIconButton(
                icon = if (searching) Icons.Rounded.Close else SonariqIcons.Search,
                contentDescription = stringResource(
                    if (searching) R.string.close_search else R.string.search_this_list,
                ),
                palette = palette,
                onClick = onSearch,
                size = 44.dp,
            )
            onMore?.let { more ->
                CircleIconButton(
                    icon = Icons.Rounded.MoreHoriz,
                    contentDescription = stringResource(R.string.more),
                    palette = palette,
                    onClick = { more(songs) },
                    size = 44.dp,
                )
            }
        }
    }
    Spacer(Modifier.height(18.dp))
}

/** The glass answer to [PlayPill]'s labeled pill — Shuffle beside Play. */
@Composable
private fun SecondaryPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = modifier
            .height(50.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), CircleShape)
            .clickable {
                haptics.play(Haptic.Resume)
                onClick()
            }
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SonariqLime,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The segmented tab bar under the actions on artist and album pages — a glass
 * container with a lime segment for the selected tab. Shown only when the
 * page earned more than one tab; a lone tab renders its content with no bar.
 */
@Composable
private fun DetailTabBar(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ARTIST_CONTENT_GUTTER, vertical = 4.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), shape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .then(if (selected) Modifier.background(SonariqLime) else Modifier)
                    .clickable {
                        haptics.play(Haptic.Tap)
                        onSelect(index)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * One release shelf on an artist page — the Albums / Singles & EPs carousels
 * that live under the Albums tab.
 */
private fun LazyListScope.releaseShelf(
    shelf: HomeShelf,
    palette: ArtworkPalette,
    onSectionItemClick: (ShelfItem) -> Unit,
    onSectionItemLongPress: ((ShelfItem) -> Unit)?,
    onActiveShelfChange: (HomeShelf) -> Unit,
) {
    val canShowAll = shelf.items.size > ARTIST_ROW_MAX_ITEMS
    val displayItems = if (canShowAll) shelf.items.take(ARTIST_ROW_MAX_ITEMS) else shelf.items
    item(key = "shelf-${shelf.title}") {
        Column(Modifier.padding(top = 22.dp)) {
            SectionHeading(
                title = shelf.title,
                palette = palette,
                onShowAll = if (canShowAll) { { onActiveShelfChange(shelf) } } else null,
                horizontalPadding = ARTIST_CONTENT_GUTTER,
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = ARTIST_CONTENT_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(displayItems) { item ->
                    SectionCard(
                        item = item,
                        palette = palette,
                        onClick = { onSectionItemClick(item) },
                        onLongPress = onSectionItemLongPress?.let { { it(item) } },
                    )
                }
            }
        }
    }
}

/**
 * The filter box, shown under the header while the search circle is lit.
 *
 * Live rather than submit-on-enter, and for the same reason the Local Music
 * one is (see `LocalSearchField`): the list it narrows is already in memory, so
 * there is nothing for a submit action to wait for.
 *
 * Glass rather than a filled field — it is one of the header's controls that
 * happens to be typed into, and it sits close enough to the circles that a
 * Material text field beside them would read as a different app's furniture.
 */
@Composable
private fun DetailSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    autoFocus: Boolean,
    onFocused: () -> Unit,
    palette: ArtworkPalette,
    type: BrowseType,
) {
    // Opened by a tap on a button, which is as clear a statement of intent as
    // the keyboard is going to get — so it comes up with the field rather than
    // making the tap land twice. Once only: see [autoFocus]'s owner.
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focus.requestFocus()
            onFocused()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HEADER_GUTTER)
            .padding(bottom = 10.dp)
            .height(46.dp)
            .clip(PILL_SHAPE)
            // Green glass: the field is one of the header's controls, lit
            // lime while the filter is open.
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, SonariqLime.copy(alpha = 0.35f), PILL_SHAPE)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = SonariqIcons.Search,
            contentDescription = null,
            tint = SonariqLime,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_this_list),
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.onBackground),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                // Clearing and closing are the same gesture: an empty filter
                // showing an unfiltered list is a row of furniture with nothing
                // left to do.
                .clickable { if (query.isEmpty()) onClose() else onQueryChange("") },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(
                    if (query.isEmpty()) R.string.close_search else R.string.clear_search,
                ),
                tint = palette.onBackgroundVariant,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

private fun List<Song>.sortedForDetail(sort: SongSort): List<Song> = when (sort) {
    SongSort.DEFAULT -> this
    SongSort.TITLE_ASC -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    SongSort.TITLE_DESC -> sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
    // A catalogue row carries no added date, so its place in the running
    // order stands in for one: a playlist is appended to as songs are added,
    // and reversed that puts the most recent addition on top. Rows that do
    // carry a MediaStore timestamp — device tracks, downloads — are dated
    // properly, with the position order left to break the ties.
    SongSort.DATE_ADDED_ASC -> withIndex()
        .sortedWith(
            compareBy<IndexedValue<Song>> { (_, song) ->
                song.localDateAddedSeconds ?: Long.MAX_VALUE
            }.thenBy { (position, _) -> position },
        )
        .map { it.value }
    SongSort.DATE_ADDED_DESC -> withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<Song>> { (_, song) ->
                song.localDateAddedSeconds ?: Long.MIN_VALUE
            }.thenByDescending { (position, _) -> position },
        )
        .map { it.value }
}

/**
 * The rows a typed query leaves standing, each still carrying its place in the
 * full list — see the track numbers on an album, which are the release's own
 * and not positions in whatever the filter left.
 */
internal fun List<Song>.matching(query: String): List<IndexedValue<Song>> {
    val all = withIndex().toList()
    if (query.isBlank()) return all
    return all.filter { (_, song) ->
        song.title.contains(query, ignoreCase = true) ||
            song.artist.contains(query, ignoreCase = true) ||
            song.albumName?.contains(query, ignoreCase = true) == true
    }
}

/**
 * An artist: their name across the foot of the photo [PageBackground] is
 * drawing behind this. See [ReleaseHeader] for why the picture isn't here.
 */
@Composable
private fun ArtistHeader(
    page: DetailPage,
    palette: ArtworkPalette,
    artHeight: Dp,
    appleArt: AppleArtistArt?,
) {
    Box(Modifier.fillMaxWidth()) {
        Spacer(Modifier.fillMaxWidth().height(artHeight + HEADER_DROP - ARTIST_HEADER_LIFT))
        val logoUrl = appleArt?.logoUrl
        // Whether the logo has actually been drawn. Until it has — a first fetch
        // of an Apple logo is a few hundred KB the CDN may still be resizing — the
        // name stands in for it, so the header is never left with nothing in it.
        var logoShown by remember(logoUrl) { mutableStateOf(false) }
        if (appleArt != null && logoUrl != null) {
            val context = LocalPlatformContext.current
            var attempt by remember(logoUrl) { mutableIntStateOf(0) }
            var failed by remember(logoUrl) { mutableStateOf(false) }
            // Remembered, and at the size the URL already names: nothing about the
            // load waits on layout to decide how big to decode, and a recomposition
            // can't hand Coil a new request that cancels the one in flight.
            val request = remember(logoUrl, attempt) {
                ImageRequest.Builder(context).data(logoUrl).size(coil3.size.Size.ORIGINAL).build()
            }
            // A dropped fetch gets two more tries before the name is left in place.
            LaunchedEffect(failed) {
                if (failed && attempt < LOGO_RETRIES) {
                    delay(LOGO_RETRY_DELAY_MS)
                    failed = false
                    attempt++
                }
            }
            // Apple's own title logo stands in for the name, laid over its photo.
            // Capped in height as well as width: a logo set on two or three
            // lines is nearly square, and by width alone it would swallow the
            // photograph it is meant to sit on.
            val aspect = appleArt.logoAspect.coerceIn(0.4f, 6f)
            val maxLogoHeight = artHeight * ARTIST_LOGO_MAX_HEIGHT
            AsyncImage(
                model = request,
                contentDescription = page.title,
                contentScale = ContentScale.Fit,
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Success -> logoShown = true
                        is AsyncImagePainter.State.Error -> failed = true
                        else -> Unit
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(top = 14.dp, bottom = 20.dp)
                    // Sized from the incoming width in layout rather than through a
                    // BoxWithConstraints, which would subcompose the image.
                    .layout { measurable, constraints ->
                        val widest = constraints.maxWidth * ARTIST_LOGO_WIDTH
                        val height = minOf(widest / aspect, maxLogoHeight.toPx()).roundToInt()
                        val width = (height * aspect).roundToInt()
                        val placeable = measurable.measure(Constraints.fixed(width, height))
                        layout(width, height) { placeable.place(0, 0) }
                    },
            )
        }
        if (!logoShown) Text(
            text = page.title,
            style = MaterialTheme.typography.displayLarge,
            color = palette.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Bottom is half the top inset — the stats pills (or, absent
                // those, the action row) sit closer under the name than the
                // name sits under the artwork.
                .padding(start = HEADER_GUTTER, end = HEADER_GUTTER, top = 14.dp, bottom = 7.dp),
        )
    }
}

/**
 * Everything on a detail page that is colour rather than words: the artwork,
 * with its foot dissolved into the solid [ArtworkPalette.wash] the page is
 * painted in.
 *
 * A layer of its own so the list can be scrolled over it rather than carry it.
 * It follows the scroll instead of being scrolled: the list owns the gesture
 * and reserves the room, and the picture is offset to follow whatever the list
 * did with item zero. Read in a placement block, so a scroll moves it without
 * recomposing anything.
 *
 * The join used to be hidden by a live blur laid across it, re-run on every
 * frame of every scroll. A blurred copy of the sleeve does the same job here
 * drawn once: nothing about it changes as the page moves, so it is rasterised
 * on the first frame and only composited after that.
 */
@Composable
private fun PageBackground(
    page: DetailPage,
    palette: ArtworkPalette,
    canvas: CanvasArtwork?,
    artHeight: Dp,
    listState: LazyListState,
    heroUrl: String? = null,
    modifier: Modifier = Modifier,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    // Not under a canvas: a still blurred over the foot of a moving clip would
    // freeze the bottom of the video into the wrong picture.
    val softenFoot = canvas == null && !reduceDynamicBlur &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val art = heroUrl ?: page.thumbnailUrl.artworkAt(HEADER_ART_PX)

    Box(modifier.clipToBounds()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(artHeight)
                .offset { IntOffset(0, listState.headerTop(artHeight.toPx()).roundToInt()) },
        ) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .background(palette.elevated),
            )

            // A lime-lit glow behind the hero on artist pages — the green
            // liquid-glass signature. Under the scrim, so it settles into the
            // page rather than sitting on the photograph; kept off release
            // sleeves, whose own artwork stays the whole story.
            if (page.type == BrowseType.ARTIST) {
                Box(
                    Modifier
                        .matchParentSize()
                        .drawWithContent {
                            drawContent()
                            val radius = size.minDimension * 0.85f
                            drawRect(
                                Brush.radialGradient(
                                    0f to SonariqLime.copy(alpha = 0.20f),
                                    0.65f to SonariqLime.copy(alpha = 0.06f),
                                    1f to Color.Transparent,
                                    center = Offset(size.width / 2f, size.height * 0.30f),
                                    radius = radius,
                                ),
                            )
                        },
                )
            }

            // Above the still art but below the scrim, so the scrim that
            // settles the header into the page still sits over it. Always
            // running: unlike the player's sleeve there is no transport here
            // to follow, and the page is only up while it's being read.
            canvas?.let { clip ->
                CanvasArtworkPlayer(
                    canvas = clip,
                    isPlaying = true,
                    modifier = Modifier.matchParentSize(),
                )
            }

            // The same sleeve, blurred and faded in over the lower half, so the
            // picture loses its detail before it loses its colour — a merge
            // rather than a fade to a flat tint. The same request as the sharp
            // copy, so it is a memory-cache hit and lands on the same frame.
            if (softenFoot) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        // Offscreen so the mask cuts the blurred result rather
                        // than each draw beneath it.
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(SOFT_FOOT_MASK, blendMode = BlendMode.DstIn)
                        }
                        // Rectangle keeps the edges clamped to the picture's own
                        // colour rather than fading to transparent at the foot.
                        .blur(SOFT_FOOT_BLUR, BlurredEdgeTreatment.Rectangle),
                )
            }

            // Ends on the page colour at full strength, so there is no edge
            // left where the artwork stops. Eased rather than run straight: a
            // gradient that changes slope at a stop shows a line at that stop,
            // however close the colours either side of it are.
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            0.65f to palette.wash.copy(alpha = 0.30f),
                            0.82f to palette.wash.copy(alpha = 0.72f),
                            0.94f to palette.wash.copy(alpha = 0.95f),
                            1.00f to palette.wash,
                        ),
                    ),
            )
        }
    }
}

/**
 * Where the top of the artwork currently is.
 *
 * The list is the one being scrolled; the background only has to agree with it.
 * While the header is item zero and on screen, how far it has been scrolled off
 * the top is exactly the offset the picture behind it needs. Once it isn't,
 * there is nothing to agree with, and the picture parks two artwork-heights up
 * — far enough that no part of it comes back down.
 */
private fun LazyListState.headerTop(artHeightPx: Float): Float =
    if (firstVisibleItemIndex == 0) -firstVisibleItemScrollOffset.toFloat() else -artHeightPx * 2f

/** Where the blurred copy starts to show and where it has fully taken over. */
private val SOFT_FOOT_MASK = Brush.verticalGradient(
    0.35f to Color.Transparent,
    0.75f to Color.Black,
)

/**
 * Wide enough that no shapes survive where the blurred copy is at full
 * strength — a blur that leaves them reads as a blurred photograph, and a
 * blurred photograph next to a flat colour is still two surfaces.
 */
private val SOFT_FOOT_BLUR = 48.dp

/**
 * Shuffle • Play • Star — the artist page's action row: a large Play circle
 * with a small glass circle either side.
 *
 * The star is the page's existing subscribe toggle. Where the page offers none
 * (a guest) an empty slot of the same size keeps Play in the middle.
 */
@Composable
private fun ActionRow(
    palette: ArtworkPalette,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    bottomSpace: Dp = 22.dp,
    /** The artist header's subscribe state, or null where it isn't offered. */
    subscription: SubscriptionState? = null,
    onToggleSubscription: (() -> Unit)? = null,
    /** Apple's fill for the Play circle; null leaves it white. */
    playColor: Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HEADER_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(ARTIST_ACTION_GAP, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(
            icon = SonariqIcons.Shuffle,
            contentDescription = stringResource(R.string.shuffle),
            palette = palette,
            onClick = onShuffle,
            haptic = Haptic.Resume,
            size = ARTIST_SIDE_BUTTON,
        )

        PlayPill(
            onClick = onPlay,
            iconOnly = true,
            size = ARTIST_PLAY_BUTTON,
            iconScale = ARTIST_PLAY_ICON_SCALE,
            containerColor = playColor ?: SonariqLime,
            cutout = true,
        )

        if (subscription != null) {
            CircleIconButton(
                icon = if (subscription.subscribed) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = stringResource(
                    if (subscription.subscribed) R.string.unsubscribe else R.string.subscribe,
                ),
                palette = palette,
                onClick = { onToggleSubscription?.invoke() },
                haptic = if (subscription.subscribed) Haptic.ToggleOff else Haptic.ToggleOn,
                size = ARTIST_SIDE_BUTTON,
            )
        } else {
            Spacer(Modifier.size(ARTIST_SIDE_BUTTON))
        }
    }
    Spacer(Modifier.height(bottomSpace))
}

/**
 * The prominent Play control that anchors the action row. Releases and the
 * artist page both use its icon-only circle; the labeled pill is the default.
 * Both use a fixed Sonariq-lime surface with a luminance-picked content
 * color so the primary action survives every palette.
 */
@Composable
private fun PlayPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 32.dp,
    iconOnly: Boolean = false,
    size: Dp = 50.dp,
    iconScale: Float = 0.44f,
    containerColor: Color = SonariqLime,
    /** Punch the triangle out of the circle, so the page shows through it. */
    cutout: Boolean = false,
) {
    // Dark ink on the lime fill and on the light fills an artist page can
    // pick, white on the rare dark artwork-picked fill.
    val contentColor = if (containerColor.luminance() > 0.35f) Color.Black else Color.White
    // Resume rather than a flat tap: this button starts a queue, and the rising
    // pair says so.
    val haptics = rememberHaptics()
    if (iconOnly && cutout) {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .clickable {
                    haptics.play(Haptic.Resume)
                    onClick()
                }
                // Offscreen so the clear below cuts the circle drawn under it,
                // not whatever the page has behind.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawCircle(containerColor)
                    val box = this.size.minDimension * iconScale
                    val unit = box / 24f
                    val origin = Offset((this.size.width - box) / 2f, (this.size.height - box) / 2f)
                    val triangle = androidx.compose.ui.graphics.Path().apply {
                        moveTo(origin.x + 6.8f * unit, origin.y + 4.8f * unit)
                        lineTo(origin.x + 19.2f * unit, origin.y + 12f * unit)
                        lineTo(origin.x + 6.8f * unit, origin.y + 19.2f * unit)
                        close()
                    }
                    drawPath(triangle, Color.Black, blendMode = BlendMode.Clear)
                    // The same round-joined pen the icon is drawn with.
                    drawPath(
                        triangle,
                        Color.Black,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 2f * unit,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        ),
                        blendMode = BlendMode.Clear,
                    )
                }
                .semantics { contentDescription = "Play" },
        )
        return
    }
    Row(
        modifier = modifier
            .then(if (iconOnly) Modifier.size(size) else Modifier.height(size))
            .clip(CircleShape)
            .background(containerColor)
            .clickable {
                haptics.play(Haptic.Resume)
                onClick()
            }
            .then(if (iconOnly) Modifier else Modifier.padding(horizontal = horizontalPadding)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = SonariqIcons.Play,
            contentDescription = if (iconOnly) stringResource(R.string.play) else null,
            tint = contentColor,
            modifier = Modifier.size(if (iconOnly) size * iconScale else 18.dp),
        )
        if (!iconOnly) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.play),
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
            )
        }
    }
}

/**
 * Small circular icon-only button — used for Shuffle and Download flanking the
 * Play pill. Dark glass fill in Sonariq's neon-lime language, on-surface icon.
 */
@Composable
private fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    haptic: Haptic = Haptic.Tap,
    size: Dp = 50.dp,
) {
    val haptics = rememberHaptics()
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), CircleShape)
            .clickable {
                haptics.play(haptic)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(size * 0.44f),
        )
    }
}

/**
 * "1.2M subscribers" and "3.4M monthly listeners", as chips under the
 * artist's action row.
 *
 * Both figures are YouTube's own, read off the artist header's response (see
 * [DetailPage.subscriberCountText]) — real counts or nothing at all; a page
 * whose response carries neither shows no chips.
 */
@Composable
private fun ArtistStatsRow(
    subscriberCountText: String?,
    monthlyListenerCount: String?,
    palette: ArtworkPalette,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ARTIST_CONTENT_GUTTER)
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // YouTube's own count text already reads "1.2M subscribers" in full,
        // so only the number is kept and the label re-said in the app's own
        // words — the one way to fit both stats on one line on a narrow
        // screen without either wrapping into two.
        subscriberCountText?.let {
            StatChip(
                icon = Icons.Rounded.Person,
                text = stringResource(R.string.subscribers, it.substringBefore(' ')),
                palette = palette,
            )
        }
        monthlyListenerCount?.let {
            StatChip(
                icon = Icons.Rounded.GraphicEq,
                text = stringResource(R.string.monthly_listeners, it.substringBefore(' ')),
                palette = palette,
            )
        }
    }
}

@Composable
private fun StatChip(icon: ImageVector, text: String, palette: ArtworkPalette) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .lightweightLiquidGlass(
                shape = CircleShape,
                fallbackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.onBackgroundVariant,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = palette.onBackgroundVariant,
        )
    }
}

/**
 * YouTube's own editorial note for a release or an artist, collapsed to a
 * few lines with a tap to read the rest — the same "About" block Apple
 * Music and YouTube Music itself show under the header.
 *
 * Whether there's anything to expand is only knowable once the text has
 * been laid out at the collapsed line count, so the "More" toggle is held
 * back until that measurement says the clipped text actually lost
 * something — otherwise a two-line bio would show a toggle with nothing
 * behind it to reveal.
 */
@Composable
private fun AboutSection(
    title: String,
    text: String?,
    palette: ArtworkPalette,
    horizontalPadding: Dp = ABOUT_GUTTER,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    var clipped by remember(text) { mutableStateOf(false) }
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.onBackground,
            modifier = Modifier.padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 2.dp,
                bottom = 6.dp,
            ),
        )
        if (text != null) Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = palette.onBackgroundVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> if (!expanded) clipped = result.hasVisualOverflow },
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = horizontalPadding)
                .let { m -> if (clipped || expanded) m.clickable { expanded = !expanded } else m },
        )
        if (clipped || expanded) {
            Text(
                text = stringResource(if (expanded) R.string.less else R.string.more),
                style = MaterialTheme.typography.labelLarge,
                color = palette.accent,
                modifier = Modifier
                    .padding(horizontal = horizontalPadding, vertical = 4.dp)
                    .clickable { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun SectionHeading(
    title: String,
    palette: ArtworkPalette,
    onShowAll: (() -> Unit)? = null,
    horizontalPadding: Dp = PAGE_GUTTER,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 10.dp,
                bottom = 8.dp,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = palette.onBackground,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onShowAll != null) {
            Text(
                text = stringResource(R.string.show_all),
                style = MaterialTheme.typography.titleSmall,
                color = palette.accent,
                modifier = Modifier
                    .clickable(onClick = onShowAll)
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

/** Compact row for the artist's top-tracks panel; no swipe, to keep the
 *  vertical list's gestures unambiguous. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactSongRow(
    song: Song,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    downloadedTint: Color? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    horizontalPadding: Dp = 0.dp,
) {
    val artShape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = horizontalPadding, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp)) {
            AsyncImage(
                model = song.artworkAt(ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(artShape)
                    .thumbnailBorder(artShape)
                    .background(palette.elevated),
            )
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            ExplicitSongTitle(
                song = song,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) palette.accent else palette.onBackground,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (downloadedTint != null) {
            DownloadedBadge(song.videoId, downloadedTint)
        }
        RowMoreButton(onClick = onLongPress, tint = palette.onBackgroundVariant)
    }
}

/**
 * A row under "Suggested" — a track YouTube offers to round the playlist
 * out but that was never added. [onAdd] is the point of the row, so it gets
 * the trailing spot a track already on the playlist spends on "more"; the
 * long-press sheet is still one gesture away for anything else.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestedSongRow(
    song: Song,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onAdd: () -> Unit,
    downloadedTint: Color? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp)) {
            AsyncImage(
                model = song.artworkAt(ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .thumbnailBorder(RoundedCornerShape(12.dp))
                    .background(palette.elevated),
            )
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            ExplicitSongTitle(
                song = song,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) palette.accent else palette.onBackground,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (downloadedTint != null) {
            DownloadedBadge(song.videoId, downloadedTint)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(palette.accent.copy(alpha = 0.16f))
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = stringResource(R.string.add_to_playlist),
                tint = palette.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The newest release on an artist's shelves — an album, or a single or EP that
 * came out after it. Playlists, videos and related artists share these shelves
 * and are skipped: only an `MPRE…` browse id is a release.
 *
 * Newest by the year in the card's subtitle, which is all a shelf item says
 * about when. Ties and releases with no year keep YouTube's own order, which
 * lists albums first.
 */
private fun List<HomeShelf>.topRelease(): ShelfItem? =
    asSequence()
        .flatMap { it.items.asSequence() }
        .filter { it.browseId?.startsWith("MPRE") == true }
        .withIndex()
        .maxWithOrNull(
            compareBy<IndexedValue<ShelfItem>> { it.value.releaseYear() ?: 0 }
                .thenByDescending { it.index },
        )?.value

private val RELEASE_YEAR = Regex("""\b(19|20)\d{2}\b""")

private fun ShelfItem.releaseYear(): Int? =
    RELEASE_YEAR.find(subtitle)?.value?.toIntOrNull()

/**
 * Whether this shelf is a row of artists — "Fans might also like" and its
 * siblings — rather than releases. YouTube bills artists by channel id, so a
 * shelf whose every item browses to one is an artist shelf whatever the
 * heading happens to say in this locale.
 *
 * Purely data-driven: true only when the response actually carries such a
 * shelf, so a page without one renders no related-artists section at all
 * rather than a section built from anything else.
 */
internal fun HomeShelf.isArtistShelf(): Boolean =
    items.isNotEmpty() && items.all { it.browseId?.startsWith("UC") == true }

/**
 * The credit line and the metadata line a detail header shows separately,
 * split out of the one subtitle a browse row hands over.
 *
 * Pure — the localized labels are resolved by the caller — so the split and
 * the join are unit-testable without resources.
 */
internal data class HeaderParts(
    val credit: String,
    /** The word naming what the page *is* ("album", "playlist", …), if any. */
    val kind: String?,
    /** A four-digit year, if the subtitle carries one. */
    val year: String?,
)

/**
 * Splits the one subtitle a browse row hands over — "Album • Travis Scott •
 * 2023", or sometimes just "Travis Scott" — into its parts. Everything is
 * optional, because every caller supplies a different amount of it: the player
 * knows an album's artist but not its year, search knows both, and a home card
 * frequently knows neither.
 */
internal fun String.splitHeaderParts(): HeaderParts {
    val parts = split("•", "·").map { it.trim() }.filter { it.isNotEmpty() }
    val year = parts.lastOrNull { it.length == 4 && it.all(Char::isDigit) }
    val kindRaw = parts.firstOrNull { it.lowercase(Locale.ROOT) in KIND_WORDS }
    val kind = kindRaw?.lowercase(Locale.ROOT)
    val credit = parts.filter { it != year && it != kindRaw }.joinToString(", ")
    return HeaderParts(credit, kind, year)
}

/**
 * Joins the header's metadata line — "ALBUM • 2023 • 12 TRACKS • 41 MIN" —
 * from its parts, skipping whatever the page didn't supply. Uppercased with
 * the device default locale, matching the header's editorial styling.
 */
internal fun formatDetailMeta(
    kindLabel: String?,
    year: String?,
    trackCountText: String?,
    playtime: String?,
): String =
    listOfNotNull(kindLabel, year, trackCountText, playtime)
        .joinToString(" • ")
        .uppercase(Locale.getDefault())

/** "Recent Single" and "Recent EP" where the card says so, otherwise "Recent Album". */
@androidx.annotation.StringRes
private fun ShelfItem.recentLabel(): Int {
    val kind = subtitle.lowercase()
    return when {
        kind.contains("single") -> R.string.recent_single
        Regex("""\bep\b""").containsMatchIn(kind) -> R.string.recent_ep
        else -> R.string.recent_album
    }
}

/**
 * The artist page's top-release card: sleeve, what it is and when, and its
 * title, in a rounded glass panel the width of the page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopReleaseCard(
    item: ShelfItem,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    /** Whether the album is in the library; null while that is still being read. */
    saved: Boolean?,
    /** Null hides the button. */
    onToggleSaved: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(28.dp)
    val coverShape = RoundedCornerShape(12.dp)
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ARTIST_CONTENT_GUTTER)
            .padding(bottom = 22.dp)
            // Dark glass in Sonariq's neon-lime language, replacing the
            // light veil: the page is dark, and a dark panel reads as a
            // surface rather than a hole cut out of it.
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), shape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), shape)
            .clip(shape)
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(88.dp)
                .clip(coverShape)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), coverShape)
                .background(palette.elevated),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleLarge,
                color = palette.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(item.recentLabel()),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
            )
        }
        if (onToggleSaved != null && saved != null) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), CircleShape)
                    .clickable {
                        haptics.play(if (saved) Haptic.ToggleOff else Haptic.ToggleOn)
                        onToggleSaved()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (saved) Icons.Rounded.Check else Icons.Rounded.Add,
                    contentDescription = stringResource(
                        if (saved) R.string.remove_from_library else R.string.add_to_library,
                    ),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * The related-artists shelf — "Fans might also like" — when the artist page's
 * response carries one. Artists as circles, the way artist imagery is drawn
 * everywhere else, under the heading YouTube itself gave the shelf.
 *
 * A tap opens that artist's page through the same [onArtistClick] the release
 * cards use: the `UC…` browse id resolves to an artist page on the way in
 * (see `browseTypeOf`), so no separate navigation path is needed.
 */
@Composable
private fun RelatedArtistsSection(
    shelf: HomeShelf,
    palette: ArtworkPalette,
    onArtistClick: (ShelfItem) -> Unit,
    onArtistLongPress: ((ShelfItem) -> Unit)?,
    onShowAll: () -> Unit,
) {
    val canShowAll = shelf.items.size > ARTIST_ROW_MAX_ITEMS
    val displayItems = remember(shelf.items) {
        if (canShowAll) shelf.items.take(ARTIST_ROW_MAX_ITEMS) else shelf.items
    }
    Column(Modifier.padding(top = 22.dp)) {
        SectionHeading(
            title = shelf.title,
            palette = palette,
            onShowAll = if (canShowAll) onShowAll else null,
            horizontalPadding = ARTIST_CONTENT_GUTTER,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = ARTIST_CONTENT_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(displayItems) { item ->
                RelatedArtistCard(
                    item = item,
                    palette = palette,
                    onClick = { onArtistClick(item) },
                    onLongPress = onArtistLongPress?.let { { it(item) } },
                )
            }
        }
    }
}

/** One artist in a related-artists shelf: a circle, with the name under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RelatedArtistCard(
    item: ShelfItem,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier.width(RELATED_ARTIST_CARD_WIDTH),
) {
    Column(
        modifier = modifier.longPressMenuClickable(onClick = onClick, onLongClick = onLongPress),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .thumbnailBorder(CircleShape)
                .background(palette.elevated),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SectionCard(
    item: ShelfItem,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(SHELF_CARD_WIDTH),
    onLongPress: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .thumbnailBorder(RoundedCornerShape(20.dp))
                .background(palette.elevated),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistShelfGridPage(
    shelf: HomeShelf,
    palette: ArtworkPalette,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** Artist shelves draw circles, the way the shelf itself does. */
    circularArt: Boolean = false,
) {
    val gridState = rememberLazyGridState()
    BoxWithConstraints(modifier.fillMaxSize().background(palette.background)) {
        val grid = libraryGrid(maxWidth - PAGE_GUTTER * 2)
        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columns),
            state = gridState,
            contentPadding = PaddingValues(
                top = topBarContentPadding(),
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PAGE_GUTTER),
        ) {
            items(shelf.items, key = { it.browseId ?: it.title }) { item ->
                if (circularArt) {
                    RelatedArtistCard(
                        item = item,
                        palette = palette,
                        onClick = { onItemClick(item) },
                        onLongPress = onItemLongPress?.let { { it(item) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    SectionCard(
                        item = item,
                        palette = palette,
                        onClick = { onItemClick(item) },
                        onLongPress = onItemLongPress?.let { { it(item) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * The credit line and the metadata line the header shows separately — the
 * resource half of the split; see [splitHeaderParts] and [formatDetailMeta]
 * for the pure halves, which the unit tests pin down.
 */
@Composable
private fun DetailPage.headerLines(trackCount: Int, playtime: String? = null): Pair<String, String> {
    val parts = subtitle.splitHeaderParts()
    val meta = formatDetailMeta(
        kindLabel = parts.kind ?: type.localizedLabel(),
        year = parts.year,
        trackCountText = trackCount.takeIf { it > 0 }?.let {
            pluralStringResource(R.plurals.track_count_plural, it, it)
        },
        playtime = playtime,
    )
    return parts.credit to meta
}

/** Subtitle words that name what a page *is* rather than who made it. */
private val KIND_WORDS = setOf(
    "album", "single", "ep", "playlist", "artist", "podcast", "episode", "song", "video",
)

@Composable
private fun BrowseType.localizedLabel(): String? = when (this) {
        BrowseType.ALBUM -> stringResource(R.string.album)
        BrowseType.PLAYLIST -> stringResource(R.string.playlist)
        BrowseType.ARTIST -> stringResource(R.string.artist)
        BrowseType.OTHER -> null
    }

/**
 * How long the page plays for — "41 min", "1h 25m" — summed over the rows
 * on it, or null when none of them carry a duration. A playlist still filling
 * in counts up with it, so the figure is never ahead of the list it sits over.
 */
@Composable
private fun List<Song>.playtime(): String? {
    val minutes = sumOf { it.durationMillis() } / 60_000
    return when {
        minutes <= 0 -> null
        minutes < 60 -> stringResource(R.string.minutes_short, minutes.toInt())
        else -> stringResource(R.string.hours_minutes_short, (minutes / 60).toInt(), (minutes % 60).toInt())
    }
}

/**
 * The small "Auto-downloaded" pill on Smart Downloads page headers.
 *
 * Same pill language as the settings screens' count chips — filled with the
 * theme accent, label in on-accent — so it reads as a marker, not a button.
 */
@Composable
private fun SmartDownloadBadge() {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                shape,
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.smart_downloads_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
