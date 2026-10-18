package com.music.sonariq

import androidx.media3.common.MediaMetadata
import com.music.sonariq.data.model.Song
import com.music.sonariq.playback.AutoBrowseTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBrowseTreeTest {

    private val source = object : AutoBrowseTree.AutoDataSource {
        override fun quickPicks() = listOf(song("q1"), song("q2"))
        override fun recents() = listOf(song("r1"))
        override fun liked() = emptyList<Song>()
        override fun downloads() = emptyList<Song>()
        override fun localTracks() = emptyList<Song>()
        override fun moodShelves() = listOf(
            AutoBrowseTree.MoodShelf("mood:focus", "Focus", null),
        )
        override fun playlists() = listOf(
            AutoBrowseTree.AutoPlaylist("pl:1", "Road trip", null, 12),
        )
        override fun moodTracks(moodId: String) = listOf(song("m1"))
        override fun playlistTracks(playlistId: String) = listOf(song("p1"), song("p2"))
    }

    private fun song(id: String) = Song(videoId = id, title = "T $id", artist = "A", thumbnailUrl = null)

    @Test
    fun `the top level is six sections, now playing first`() {
        val sections = AutoBrowseTree.topLevelSections()
        assertEquals(6, sections.size)
        assertEquals(AutoBrowseTree.AutoSection.NOW_PLAYING, sections.first())
    }

    @Test
    fun `section ids are stable and unique`() {
        val ids = AutoBrowseTree.topLevelSections().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("auto2:") })
    }

    @Test
    fun `top level items are browsable folders, never playable`() {
        val items = AutoBrowseTree.topLevelItems(
            AutoBrowseTree.AutoSection.entries.associateWith { it.id },
        )
        assertEquals(6, items.size)
        items.forEach { item ->
            assertTrue(item.mediaMetadata.isBrowsable == true)
            assertFalse(item.mediaMetadata.isPlayable == true)
        }
    }

    @Test
    fun `quick picks children are playable tracks`() {
        val items = AutoBrowseTree.children(
            AutoBrowseTree.AutoSection.QUICK_PICKS.id, source, page = 0, pageSize = 50,
        )
        assertEquals(2, items.size)
        items.forEach { item ->
            assertTrue(item.mediaMetadata.isPlayable == true)
            assertFalse(item.mediaMetadata.isBrowsable == true)
            assertTrue(item.mediaId.startsWith("auto2:track:"))
        }
        assertEquals("T q1", items.first().mediaMetadata.title.toString())
    }

    @Test
    fun `library opens onto its three sub folders`() {
        val items = AutoBrowseTree.children(
            AutoBrowseTree.AutoSection.LIBRARY.id, source, page = 0, pageSize = 50,
        )
        assertEquals(3, items.size)
        assertEquals(
            listOf(
                AutoBrowseTree.LibrarySection.PLAYLISTS.id,
                AutoBrowseTree.LibrarySection.LIKED.id,
                AutoBrowseTree.LibrarySection.LOCAL_MUSIC.id,
            ),
            items.map { it.mediaId },
        )
    }

    @Test
    fun `playlists list and open onto their tracks`() {
        val listed = AutoBrowseTree.children(
            AutoBrowseTree.LibrarySection.PLAYLISTS.id, source, page = 0, pageSize = 50,
        )
        assertEquals(1, listed.size)
        assertEquals("pl:1", listed.first().mediaId)
        val tracks = AutoBrowseTree.children("pl:1", source, page = 0, pageSize = 50)
        assertEquals(2, tracks.size)
    }

    @Test
    fun `mood shelves list and open onto their tracks`() {
        val listed = AutoBrowseTree.children(
            AutoBrowseTree.AutoSection.MOODS.id, source, page = 0, pageSize = 50,
        )
        assertEquals(1, listed.size)
        assertEquals(MediaMetadata.FOLDER_TYPE_PLAYLISTS, listed.first().mediaMetadata.folderType)
        val tracks = AutoBrowseTree.children("mood:focus", source, page = 0, pageSize = 50)
        assertEquals(1, tracks.size)
    }

    @Test
    fun `unknown ids open empty, and paging beyond the end is empty`() {
        assertTrue(AutoBrowseTree.children("nope", source, 0, 50).isEmpty())
        assertTrue(AutoBrowseTree.children(AutoBrowseTree.AutoSection.RECENTS.id, source, 5, 50).isEmpty())
        val page = AutoBrowseTree.children(
            AutoBrowseTree.AutoSection.QUICK_PICKS.id, source, page = 1, pageSize = 1,
        )
        assertEquals(1, page.size)
        assertEquals("T q2", page.first().mediaMetadata.title.toString())
    }

    @Test
    fun `mood shelves without wired tracks still list the shelf`() {
        val bare = object : AutoBrowseTree.AutoDataSource by source {
            override fun moodTracks(moodId: String) = emptyList<Song>()
        }
        val listed = AutoBrowseTree.children(AutoBrowseTree.AutoSection.MOODS.id, bare, 0, 50)
        assertEquals(1, listed.size)
        assertTrue(AutoBrowseTree.children("mood:focus", bare, 0, 50).isEmpty())
    }
}
