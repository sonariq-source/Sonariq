package com.music.sonariq.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.sonariq.R
import com.music.sonariq.data.YtMusicRepository
import com.music.sonariq.sharedui.resources.Res
import com.music.sonariq.sharedui.resources.spotify_logo
import com.music.sonariq.data.model.ShelfItem
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.data.stats.SmartPlaylists
import com.music.sonariq.download.Downloads
import com.music.sonariq.download.SavedCollection
import com.music.sonariq.ui.components.PAGE_GUTTER
import com.music.sonariq.ui.components.ReplayCardRowSkeleton
import com.music.sonariq.ui.replay.ReplayCardRow
import com.music.sonariq.ui.replay.ReplayHeroCard
import com.music.sonariq.ui.replay.ReplayStoryPage

// The phone's halves of the shared Library page: its list of folders, what
// sits on its "On device" shelf, and the way in to Replay at its head.

/** The glyph a library row shows; resolved to an ImageVector at the call site. */
enum class LibraryRowIcon {
    Songs,
    Albums,
    Artists,
    Playlists,
    Liked,
    Downloads,
    SmartDownloads,
    History,
    Cached,
    WebDav,
    Smb,
    Spotify,
}

/**
 * A library row's second line: literal text, or a genuine count from the data
 * layer. Only [SongCount] carries a number, and it is only ever built from a
 * real one — see [libraryRowModels] — so a row with nothing honest to say
 * shows a plain descriptor instead of a made-up figure.
 */
sealed interface LibraryRowSubtitle {
    data class Text(val value: String) : LibraryRowSubtitle
    data class SongCount(val count: Int) : LibraryRowSubtitle
}

/** One of the Library's grouped rows, as pure data — see [libraryRowModels]. */
data class LibraryRowModel(
    val icon: LibraryRowIcon,
    val title: String,
    val subtitle: LibraryRowSubtitle,
    val browseId: String,
    /** Draws the Spotify mark in place of [icon]. */
    val logo: Boolean = false,
)

/** Every resolved string [libraryRowModels] needs, so the pure builder stays testable. */
data class LibraryRowStrings(
    val allSongs: String,
    val allSongsSubtitle: String,
    val albums: String,
    val albumsSubtitle: String,
    val artists: String,
    val artistsSubtitle: String,
    val playlists: String,
    val playlistsSubtitle: String,
    val likedSongs: String,
    val likedSongsSubtitle: String,
    val downloads: String,
    val smartDownloads: String,
    val smartDownloadsSubtitle: String,
    val recentlyPlayed: String,
    val recentlyPlayedSubtitle: String,
    val cachedSongs: String,
    val cachedSongsSubtitle: String,
    val webdav: String,
    val webdavSubtitle: String,
    val smb: String,
    val smbSubtitle: String,
    val spotify: String,
    val spotifySubtitle: String,
)

/**
 * The Library's grouped rows as pure data: the collection shortcuts (All
 * Songs, Albums, Artists, Playlists, Liked Songs, Downloads, Recently
 * Played), then the conditional device and remote folders.
 *
 * Every row opens a real destination — a device folder, the liked playlist, a
 * feed shelf's "Show all" grid ([LIBRARY_SHELF_ROW_PREFIX]), or the history
 * page ([HISTORY_BROWSE_ID]). The account's rows are gated on [signedIn]
 * because signed out they'd be dead ends; Albums and Artists are additionally
 * gated on their feed shelf existing, because an empty collection has no grid
 * to open. (Playlists is always drawn when signed in — its empty state is the
 * tile that creates the first playlist — so sign-in alone gates it.)
 *
 * The only count on any row is Downloads': [Downloads.saved]'s size, the one
 * number this screen can state exactly. Everything else carries a descriptor.
 */
fun libraryRowModels(
    signedIn: Boolean,
    shelfTitles: Set<String>,
    downloadsCount: Int,
    showSmartDownloads: Boolean,
    showCacheFolder: Boolean,
    webdavConfigured: Boolean,
    smbConfigured: Boolean,
    spotifyConnected: Boolean,
    strings: LibraryRowStrings,
): List<LibraryRowModel> = buildList {
    add(
        LibraryRowModel(
            icon = LibraryRowIcon.Songs,
            title = strings.allSongs,
            subtitle = LibraryRowSubtitle.Text(strings.allSongsSubtitle),
            browseId = "local:all",
        ),
    )
    if (signedIn) {
        if (ALBUMS_SHELF_TITLE in shelfTitles) {
            add(
                LibraryRowModel(
                    icon = LibraryRowIcon.Albums,
                    title = strings.albums,
                    subtitle = LibraryRowSubtitle.Text(strings.albumsSubtitle),
                    browseId = shelfRowBrowseId(ALBUMS_SHELF_TITLE),
                ),
            )
        }
        if (ARTISTS_SHELF_TITLE in shelfTitles) {
            add(
                LibraryRowModel(
                    icon = LibraryRowIcon.Artists,
                    title = strings.artists,
                    subtitle = LibraryRowSubtitle.Text(strings.artistsSubtitle),
                    browseId = shelfRowBrowseId(ARTISTS_SHELF_TITLE),
                ),
            )
        }
        add(
            LibraryRowModel(
                icon = LibraryRowIcon.Playlists,
                title = strings.playlists,
                subtitle = LibraryRowSubtitle.Text(strings.playlistsSubtitle),
                browseId = shelfRowBrowseId(YtMusicRepository.PLAYLISTS_SHELF),
            ),
        )
        add(
            LibraryRowModel(
                icon = LibraryRowIcon.Liked,
                title = strings.likedSongs,
                subtitle = LibraryRowSubtitle.Text(strings.likedSongsSubtitle),
                browseId = YtMusicRepository.LIKED_MUSIC,
            ),
        )
    }
    add(
        LibraryRowModel(
            icon = LibraryRowIcon.Downloads,
            title = strings.downloads,
            subtitle = LibraryRowSubtitle.SongCount(downloadsCount),
            browseId = "local:downloads",
        ),
    )
    if (showSmartDownloads) {
        add(
            LibraryRowModel(
                icon = LibraryRowIcon.SmartDownloads,
                title = strings.smartDownloads,
                subtitle = LibraryRowSubtitle.Text(strings.smartDownloadsSubtitle),
                browseId = "local:smart-downloads",
            ),
        )
    }
    add(
        LibraryRowModel(
            icon = LibraryRowIcon.History,
            title = strings.recentlyPlayed,
            subtitle = LibraryRowSubtitle.Text(strings.recentlyPlayedSubtitle),
            browseId = HISTORY_BROWSE_ID,
        ),
    )
    // Opt-in from Settings → Storage: what the song cache is holding from
    // YouTube and JioSaavn. See [com.music.sonariq.playback.AudioCache.cachedSongs].
    if (showCacheFolder) {
        add(
            LibraryRowModel(
                icon = LibraryRowIcon.Cached,
                title = strings.cachedSongs,
                subtitle = LibraryRowSubtitle.Text(strings.cachedSongsSubtitle),
                browseId = CACHE_FOLDER_BROWSE_ID,
            ),
        )
    }
    // Only once set up in Sources; unconfigured, they'd just be dead ends.
    if (webdavConfigured) {
        add(
            LibraryRowModel(
                icon = LibraryRowIcon.WebDav,
                title = strings.webdav,
                subtitle = LibraryRowSubtitle.Text(strings.webdavSubtitle),
                browseId = com.music.sonariq.data.webdav.WebDavConfig.BROWSE_ID,
            ),
        )
    }
    if (smbConfigured) {
        add(
            LibraryRowModel(
                icon = LibraryRowIcon.Smb,
                title = strings.smb,
                subtitle = LibraryRowSubtitle.Text(strings.smbSubtitle),
                browseId = com.music.sonariq.data.smb.SmbConfig.BROWSE_ID,
            ),
        )
    }
    // Only while signed in to Spotify in Settings → Accounts; it opens that
    // account's playlists rather than a browse page.
    if (spotifyConnected) {
        add(
            LibraryRowModel(
                icon = LibraryRowIcon.Spotify,
                title = strings.spotify,
                subtitle = LibraryRowSubtitle.Text(strings.spotifySubtitle),
                browseId = SPOTIFY_BROWSE_ID,
                logo = true,
            ),
        )
    }
}

/**
 * The phone's folders and collection shortcuts, drawn as the Library's grouped
 * rows under the Replay cards. See [libraryRowModels] for what each row opens
 * and why each one is shown.
 */
@Composable
fun libraryLinks(signedIn: Boolean, shelfTitles: Set<String>): List<LibraryLink> {
    val webdavConfigured by AppSettings.webdavUrl.collectAsStateWithLifecycle()
    val smbHost by AppSettings.smbHost.collectAsStateWithLifecycle()
    val smbShare by AppSettings.smbShare.collectAsStateWithLifecycle()
    val showCacheFolder by AppSettings.showCacheFolder.collectAsStateWithLifecycle()
    val spotifyConnected by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    val downloadsSaved by Downloads.saved.collectAsStateWithLifecycle()
    val strings = LibraryRowStrings(
        allSongs = stringResource(R.string.all_songs),
        allSongsSubtitle = stringResource(R.string.audio_files_on_device),
        albums = stringResource(R.string.albums),
        albumsSubtitle = stringResource(R.string.saved_albums),
        artists = stringResource(R.string.artists),
        artistsSubtitle = stringResource(R.string.saved_artists),
        playlists = stringResource(R.string.playlists),
        playlistsSubtitle = stringResource(R.string.your_playlists),
        likedSongs = stringResource(R.string.liked_songs),
        likedSongsSubtitle = stringResource(R.string.liked_songs_subtitle),
        downloads = stringResource(R.string.downloads),
        smartDownloads = stringResource(R.string.smart_downloads),
        smartDownloadsSubtitle = stringResource(R.string.smart_downloads_subtitle),
        recentlyPlayed = stringResource(R.string.recently_played),
        recentlyPlayedSubtitle = stringResource(R.string.listening_history),
        cachedSongs = stringResource(R.string.cached_songs),
        cachedSongsSubtitle = stringResource(R.string.cached_songs_subtitle),
        webdav = stringResource(R.string.webdav),
        webdavSubtitle = stringResource(R.string.webdav_subtitle),
        smb = stringResource(R.string.smb),
        smbSubtitle = stringResource(R.string.smb_subtitle),
        spotify = stringResource(R.string.spotify),
        spotifySubtitle = stringResource(R.string.spotify_library_subtitle),
    )
    // The one count this screen states exactly: one entry per downloaded track.
    val downloadsSubtitle = pluralStringResource(
        R.plurals.song_count_plural,
        downloadsSaved.size,
        downloadsSaved.size,
    )
    fun LibraryRowModel.toLink(): LibraryLink {
        val glyph = when (icon) {
            LibraryRowIcon.Songs -> Icons.Rounded.MusicNote
            LibraryRowIcon.Albums -> Icons.Rounded.Album
            LibraryRowIcon.Artists -> Icons.Rounded.Person
            LibraryRowIcon.Playlists -> Icons.Rounded.QueueMusic
            LibraryRowIcon.Liked -> Icons.Rounded.FavoriteBorder
            LibraryRowIcon.Downloads -> Icons.Rounded.Download
            LibraryRowIcon.SmartDownloads -> Icons.Rounded.CloudDownload
            LibraryRowIcon.History -> Icons.Rounded.History
            LibraryRowIcon.Cached -> Icons.Rounded.Storage
            LibraryRowIcon.WebDav -> Icons.Rounded.Cloud
            LibraryRowIcon.Smb -> Icons.Rounded.Lan
            LibraryRowIcon.Spotify -> Icons.Rounded.Cloud
        }
        return LibraryLink(
            item = ShelfItem(
                title = title,
                subtitle = when (val s = subtitle) {
                    is LibraryRowSubtitle.Text -> s.value
                    is LibraryRowSubtitle.SongCount -> downloadsSubtitle
                },
                thumbnailUrl = null,
                videoId = null,
                browseId = browseId,
            ),
            icon = glyph,
            logo = if (logo) Res.drawable.spotify_logo else null,
        )
    }
    // The Smart Downloads row is there when the feature is on, or when a
    // previous run left smart tracks on the device worth finding again.
    val showSmartDownloads = AppSettings.smartDownloadsEnabled.value ||
        com.music.sonariq.download.SmartDownloads.smartIds.value.isNotEmpty()
    return libraryRowModels(
        signedIn = signedIn,
        shelfTitles = shelfTitles,
        downloadsCount = downloadsSaved.size,
        showSmartDownloads = showSmartDownloads,
        showCacheFolder = showCacheFolder,
        webdavConfigured = webdavConfigured.isNotBlank(),
        smbConfigured = smbHost.isNotBlank() && smbShare.isNotBlank(),
        spotifyConnected = spotifyConnected.isNotBlank(),
        strings = strings,
    ).map { it.toLink() }
}

/**
 * A Library row that opens one of the page's own feed shelves as its "Show
 * all" grid rather than a browse page: [LIBRARY_SHELF_ROW_PREFIX] plus the
 * shelf's title. Opening the feed's browse id as a detail page would parse a
 * shelf of albums as a track list; the grid is the page the shelf already
 * opens. Handled in MainActivity's `onLibraryItemClick`.
 */
const val LIBRARY_SHELF_ROW_PREFIX = "shelf:"

/** The browse id behind a feed-shelf row — see [LIBRARY_SHELF_ROW_PREFIX]. */
fun shelfRowBrowseId(shelfTitle: String): String = LIBRARY_SHELF_ROW_PREFIX + shelfTitle

/** The Recently Played row: the listening-history page, handled in MainActivity. */
const val HISTORY_BROWSE_ID = "app:history"

/** The library feed shelf titles behind the Albums and Artists rows. */
private const val ALBUMS_SHELF_TITLE = "Albums"
private const val ARTISTS_SHELF_TITLE = "Artists"

/**
 * The phone's "On device" shelf: the playlists and albums downloaded whole —
 * the same promise the folders above it make, here, now, without a network.
 * Nothing is truncated: the shelf is a row that scrolls, so "all of them"
 * costs nothing.
 */
@Composable
fun libraryDeviceItems(downloadedReleases: List<SavedCollection>): List<ShelfItem> {
    val downloadedPlaylist = stringResource(R.string.downloaded_playlist)
    return downloadedReleases.map { release ->
        ShelfItem(
            title = release.title,
            // The credit the release was downloaded with, because this is also
            // what the page it opens bills itself by — see `headerLines`, which
            // reads the kind and the owner back out of it. Saying "Downloaded
            // playlist" here instead would make that header read "Downloaded
            // playlist" over "PLAYLIST • 12 SONGS", and the shelf this card is on
            // already says where it lives.
            subtitle = release.subtitle.ifBlank { if (release.playlist) downloadedPlaylist else "" },
            thumbnailUrl = release.thumbnailUrl,
            videoId = null,
            browseId = Downloads.pageIdFor(release.id),
        )
    }
}

/** The Library row that opens the connected Spotify account; handled by the app, not a browse page. */
const val SPOTIFY_BROWSE_ID = "app:spotify"

/** The Cached songs folder's page id — one of the `local:` device folders. */
const val CACHE_FOLDER_BROWSE_ID = "local:cache"

/**
 * The phone's way in to Replay: its row of headline cards, placeholders for
 * them while the history is read, and nothing at all while there is not yet
 * enough listening to deal them — Settings still opens Replay either way.
 */
@Composable
fun LibraryReplayEntry(
    cards: List<ReplayHeroCard>,
    loading: Boolean,
    holder: String,
    memberSince: String?,
    onOpenReplay: (ReplayStoryPage) -> Unit,
) {
    if (loading) {
        ReplayCardRowSkeleton()
    } else if (cards.isNotEmpty()) {
        ReplayCardRow(
            cards = cards,
            holder = holder,
            memberSince = memberSince,
            onCardClick = onOpenReplay,
            modifier = Modifier.padding(vertical = 6.dp),
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        )
    }
}

/**
 * The smart-playlist cards for the Library's shelf: one per generated list
 * that actually has songs, each labelled auto-generated.
 *
 * Read when the Library is open — the lists are rebuilt from the current
 * history every time, so a card never outlives the listening behind it. Empty
 * lists get no card; the page behind each card still says plainly when there
 * is no data yet.
 */
@Composable
fun rememberSmartPlaylistItems(active: Boolean): List<ShelfItem> {
    val context = LocalContext.current
    var items by remember { mutableStateOf(emptyList<ShelfItem>()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        items = SmartPlaylists.shelfItems(context.applicationContext)
    }
    return items
}
