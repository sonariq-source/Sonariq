package com.music.sonariq

import com.music.sonariq.data.library.LibraryBackup
import com.music.sonariq.data.library.PlaylistFolders
import com.music.sonariq.data.model.EntityType
import com.music.sonariq.data.model.SearchHistoryEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library backup file: the whole schema survives a JSON round trip, and
 * stored songs convert back to real songs with their metadata intact.
 */
class LibraryBackupTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private fun song(index: Int) = LibraryBackup.StoredSong(
        videoId = "vid$index",
        title = "Title $index",
        artist = "Artist",
        thumbnailUrl = "https://example.com/a.jpg",
        durationText = "3:07",
        artistId = "artist1",
        albumId = "album1",
        albumName = "Album",
        isVideo = false,
    )

    @Test
    fun `stored songs convert back with metadata intact`() {
        val restored = song(1).toSong()
        assertEquals("vid1", restored.videoId)
        assertEquals("Title 1", restored.title)
        assertEquals("Artist", restored.artist)
        assertEquals("https://example.com/a.jpg", restored.thumbnailUrl)
        assertEquals("3:07", restored.durationText)
        assertEquals("Album", restored.albumName)
    }

    @Test
    fun `the whole file survives a json round trip`() {
        val file = LibraryBackup.LibraryBackupFile(
            versionName = "2.14",
            exportedAt = "2026-10-18T10:00:00Z",
            searchHistory = listOf(
                SearchHistoryEntity(
                    id = "vid1",
                    title = "Title",
                    subtitle = "Song • Artist",
                    artworkUrl = null,
                    entityType = EntityType.TRACK,
                    timestamp = 1_700_000_000_000L,
                ),
            ),
            playlists = listOf(
                LibraryBackup.StoredPlaylist(
                    id = "sp_local_1",
                    title = "Gym",
                    songs = listOf(song(1), song(2)),
                ),
            ),
            folders = listOf(
                LibraryBackup.StoredFolder(
                    id = "folder_1",
                    title = "Workout",
                    entries = listOf(LibraryBackup.StoredFolderEntry("sp_local_1", "Gym")),
                ),
            ),
            smartFolders = listOf(
                LibraryBackup.StoredSmartFolder(
                    id = "smart_1",
                    title = "Recent rock",
                    rules = listOf(
                        PlaylistFolders.SmartRule.AddedInLastDays(30),
                        PlaylistFolders.SmartRule.MinPlays(5),
                    ),
                    matchAll = true,
                ),
            ),
            downloads = listOf(LibraryBackup.StoredDownload("vid1", "content://media/1")),
        )
        val text = json.encodeToString(LibraryBackup.LibraryBackupFile.serializer(), file)
        val back = json.decodeFromString(LibraryBackup.LibraryBackupFile.serializer(), text)

        assertEquals("sonariq", back.app)
        assertEquals(1, back.version)
        assertEquals("2.14", back.versionName)
        assertEquals(1, back.playlists.size)
        assertEquals("Gym", back.playlists[0].title)
        assertEquals(2, back.playlists[0].songs.size)
        assertEquals("Title 1", back.playlists[0].songs[0].title)
        assertEquals("Workout", back.folders[0].title)
        assertEquals("sp_local_1", back.folders[0].entries[0].id)
        assertEquals("Recent rock", back.smartFolders[0].title)
        assertEquals(2, back.smartFolders[0].rules.size)
        assertTrue(back.smartFolders[0].rules[0] is PlaylistFolders.SmartRule.AddedInLastDays)
        assertEquals(1, back.downloads.size)
        assertEquals("vid1", back.downloads[0].videoId)
        assertEquals(1, back.searchHistory.size)
        assertEquals(EntityType.TRACK, back.searchHistory[0].entityType)
    }

    @Test
    fun `an older file with missing sections still reads`() {
        val minimal = """{"app":"sonariq","version":1,"playlists":[]}"""
        val back = json.decodeFromString(LibraryBackup.LibraryBackupFile.serializer(), minimal)
        assertEquals("sonariq", back.app)
        assertTrue(back.playlists.isEmpty())
        assertTrue(back.folders.isEmpty())
        assertTrue(back.settings.isEmpty())
        assertTrue(back.listening.isEmpty())
    }

    @Test
    fun `a file from another app is recognizable`() {
        val foreign = """{"app":"other-app","version":1}"""
        val back = json.decodeFromString(LibraryBackup.LibraryBackupFile.serializer(), foreign)
        assertEquals("other-app", back.app)
    }
}
