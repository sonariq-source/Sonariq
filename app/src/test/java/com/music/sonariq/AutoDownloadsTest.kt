package com.music.sonariq

import com.music.sonariq.data.library.AutoDownloads
import com.music.sonariq.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-download selection: liked songs plus chosen playlists, deduplicated
 * and minus anything already on the device. The IO (reading the snapshot,
 * the playlists, the download record) stays out of this function so the
 * selection itself is testable.
 */
class AutoDownloadsTest {

    private fun song(id: String) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist",
        thumbnailUrl = null,
    )

    @Test
    fun `selection merges liked and playlists without duplicates`() {
        val liked = listOf(song("a"), song("b"))
        val playlists = listOf(song("b"), song("c"))
        val selected = AutoDownloads.selectTargets(liked, playlists, emptySet())
        assertEquals(listOf("a", "b", "c"), selected.map { it.videoId })
    }

    @Test
    fun `already-downloaded tracks are not selected`() {
        val liked = listOf(song("a"), song("b"))
        val selected = AutoDownloads.selectTargets(liked, emptyList(), setOf("a"))
        assertEquals(listOf("b"), selected.map { it.videoId })
    }

    @Test
    fun `blank video ids are never selected`() {
        val selected = AutoDownloads.selectTargets(listOf(song("")), emptyList(), emptySet())
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `everything downloaded means nothing selected`() {
        val songs = listOf(song("a"), song("b"))
        val selected = AutoDownloads.selectTargets(songs, songs, setOf("a", "b"))
        assertTrue(selected.isEmpty())
    }
}
