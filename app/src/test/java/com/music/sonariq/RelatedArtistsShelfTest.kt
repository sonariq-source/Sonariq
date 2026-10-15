package com.music.sonariq

import com.music.sonariq.data.model.HomeShelf
import com.music.sonariq.data.model.ShelfItem
import com.music.sonariq.ui.screens.isArtistShelf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isArtistShelf] is the gate for the related-artists section: it must open
 * only for shelves the data actually marks as artists, and never for release
 * shelves, mixed shelves, or song cards. A shelf that fails the gate renders
 * through the generic release carousel instead.
 */
class RelatedArtistsShelfTest {

    private fun artistItem(id: String) = ShelfItem(
        title = "Artist $id",
        subtitle = "Artist",
        thumbnailUrl = null,
        videoId = null,
        browseId = id,
    )

    private fun releaseItem(id: String) = ShelfItem(
        title = "Release $id",
        subtitle = "Album • 2024",
        thumbnailUrl = null,
        videoId = null,
        browseId = id,
    )

    private fun songCard(videoId: String) = ShelfItem(
        title = "Song $videoId",
        subtitle = "Song • Artist",
        thumbnailUrl = null,
        videoId = videoId,
        browseId = null,
    )

    private fun shelf(title: String, items: List<ShelfItem>) = HomeShelf(
        title = title,
        items = items,
    )

    @Test
    fun `shelf of channel ids is an artist shelf`() {
        val s = shelf(
            "Fans might also like",
            listOf(artistItem("UCabcdefghij1234567890"), artistItem("UC0987654321abcdefghij")),
        )
        assertTrue(s.isArtistShelf())
    }

    @Test
    fun `single artist item still counts as an artist shelf`() {
        assertTrue(shelf("Similar artists", listOf(artistItem("UCabc"))).isArtistShelf())
    }

    @Test
    fun `release shelf is not an artist shelf`() {
        val s = shelf(
            "Albums",
            listOf(releaseItem("MPREb_abc123"), releaseItem("MPREb_def456")),
        )
        assertFalse(s.isArtistShelf())
    }

    @Test
    fun `mixed shelf is not an artist shelf`() {
        val s = shelf(
            "More",
            listOf(artistItem("UCabc"), releaseItem("MPREb_def456")),
        )
        assertFalse(s.isArtistShelf())
    }

    @Test
    fun `empty shelf is not an artist shelf`() {
        assertFalse(shelf("Empty", emptyList()).isArtistShelf())
    }

    @Test
    fun `song cards without browse ids are not an artist shelf`() {
        val s = shelf("Quick picks", listOf(songCard("vid1"), songCard("vid2")))
        assertFalse(s.isArtistShelf())
    }

    @Test
    fun `playlist ids are not artist ids`() {
        val s = shelf(
            "Playlists",
            listOf(releaseItem("VLOLAK5uy_abc"), releaseItem("PLxyz")),
        )
        assertFalse(s.isArtistShelf())
    }
}
