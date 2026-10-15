package com.music.sonariq

import com.music.sonariq.ui.screens.LibraryRowIcon
import com.music.sonariq.ui.screens.LibraryRowSubtitle
import com.music.sonariq.ui.screens.LibraryRowStrings
import com.music.sonariq.ui.screens.libraryRowModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library's grouped rows as pure data: real destinations, honest gating,
 * and exactly one real count. No Compose here — the builder takes resolved
 * strings and primitives, so these tests pin the order, the gates and the
 * browse ids without an Android runtime.
 */
class LibraryRowModelsTest {

    private val strings = LibraryRowStrings(
        allSongs = "All Songs",
        allSongsSubtitle = "Audio files on device",
        albums = "Albums",
        albumsSubtitle = "Saved albums",
        artists = "Artists",
        artistsSubtitle = "Saved artists",
        playlists = "Playlists",
        playlistsSubtitle = "Your playlists",
        likedSongs = "Liked Songs",
        likedSongsSubtitle = "Songs you've liked",
        downloads = "Downloads",
        smartDownloads = "Smart Downloads",
        smartDownloadsSubtitle = "Auto-downloaded most played",
        recentlyPlayed = "Recently Played",
        recentlyPlayedSubtitle = "Listening history",
        cachedSongs = "Cached songs",
        cachedSongsSubtitle = "Cached from streaming",
        webdav = "WebDAV",
        webdavSubtitle = "WebDAV subtitle",
        smb = "SMB",
        smbSubtitle = "SMB subtitle",
        spotify = "Spotify",
        spotifySubtitle = "Spotify subtitle",
    )

    private fun models(
        signedIn: Boolean = true,
        shelfTitles: Set<String> = setOf("Playlists", "Albums", "Artists"),
        downloadsCount: Int = 42,
        showSmartDownloads: Boolean = false,
        showCacheFolder: Boolean = false,
        webdavConfigured: Boolean = false,
        smbConfigured: Boolean = false,
        spotifyConnected: Boolean = false,
    ) = libraryRowModels(
        signedIn = signedIn,
        shelfTitles = shelfTitles,
        downloadsCount = downloadsCount,
        showSmartDownloads = showSmartDownloads,
        showCacheFolder = showCacheFolder,
        webdavConfigured = webdavConfigured,
        smbConfigured = smbConfigured,
        spotifyConnected = spotifyConnected,
        strings = strings,
    )

    @Test
    fun `signed-in rows come first in mockup order`() {
        val titles = models().map { it.title }
        assertEquals(
            listOf(
                "All Songs", "Albums", "Artists", "Playlists",
                "Liked Songs", "Downloads", "Recently Played",
            ),
            titles,
        )
    }

    @Test
    fun `signed out hides the account rows`() {
        val titles = models(signedIn = false).map { it.title }
        assertEquals(listOf("All Songs", "Downloads", "Recently Played"), titles)
    }

    @Test
    fun `albums and artists rows need their feed shelf`() {
        val titles = models(shelfTitles = setOf("Playlists")).map { it.title }
        assertEquals(
            listOf("All Songs", "Playlists", "Liked Songs", "Downloads", "Recently Played"),
            titles,
        )
    }

    @Test
    fun `downloads row carries the real count and nothing else does`() {
        val rows = models(downloadsCount = 42)
        val downloads = rows.single { it.title == "Downloads" }
        assertEquals(LibraryRowSubtitle.SongCount(42), downloads.subtitle)
        assertTrue(
            rows.filter { it.title != "Downloads" }
                .none { it.subtitle is LibraryRowSubtitle.SongCount },
        )
    }

    @Test
    fun `every row opens a real destination`() {
        val byTitle = models().associateBy { it.title }
        assertEquals("local:all", byTitle.getValue("All Songs").browseId)
        assertEquals("shelf:Albums", byTitle.getValue("Albums").browseId)
        assertEquals("shelf:Artists", byTitle.getValue("Artists").browseId)
        assertEquals("shelf:Playlists", byTitle.getValue("Playlists").browseId)
        assertEquals("VLLM", byTitle.getValue("Liked Songs").browseId)
        assertEquals("local:downloads", byTitle.getValue("Downloads").browseId)
        assertEquals("app:history", byTitle.getValue("Recently Played").browseId)
    }

    @Test
    fun `conditional folders come after the collections`() {
        val rows = models(
            showCacheFolder = true,
            webdavConfigured = true,
            smbConfigured = true,
            spotifyConnected = true,
        )
        val titles = rows.map { it.title }
        assertEquals(
            listOf(
                "All Songs", "Albums", "Artists", "Playlists", "Liked Songs",
                "Downloads", "Recently Played",
                "Cached songs", "WebDAV", "SMB", "Spotify",
            ),
            titles,
        )
        assertTrue(rows.single { it.title == "Spotify" }.logo)
    }

    @Test
    fun `smart downloads row appears when enabled with its own browse id`() {
        val rows = models(showSmartDownloads = true)
        val smart = rows.single { it.title == "Smart Downloads" }
        assertEquals("local:smart-downloads", smart.browseId)
        val titles = rows.map { it.title }
        assertTrue(titles.indexOf("Smart Downloads") == titles.indexOf("Downloads") + 1)
    }

    @Test
    fun `smart downloads row hidden when disabled`() {
        val titles = models(showSmartDownloads = false).map { it.title }
        assertTrue("Smart Downloads" !in titles)
    }

    @Test
    fun `each glyph-rendered row has its own icon`() {
        // Spotify draws its brand mark instead of its glyph, so its Cloud
        // icon doubling WebDAV's never reaches the screen.
        val icons = models(
            showCacheFolder = true,
            webdavConfigured = true,
            smbConfigured = true,
            spotifyConnected = true,
        ).filter { !it.logo }.map { it.icon }
        assertEquals(icons.size, icons.toSet().size)
        assertEquals(LibraryRowIcon.History, icons.single { it == LibraryRowIcon.History })
    }
}
