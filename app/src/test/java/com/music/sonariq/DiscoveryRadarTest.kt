package com.music.sonariq

import com.music.sonariq.data.discovery.FollowedArtist
import com.music.sonariq.data.discovery.FollowedArtists
import com.music.sonariq.data.discovery.isNewReleaseShelf
import com.music.sonariq.data.discovery.releaseYear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release radar's pure rules: which shelves count as new releases, how a
 * release year is read off a subtitle, and how the follow list merges with
 * the top-listened artists without duplicating anyone.
 */
class DiscoveryRadarTest {

    // ── Shelf matching ──────────────────────────────────────────────────────

    @Test
    fun `latest shelves match`() {
        assertTrue(isNewReleaseShelf("Latest"))
        assertTrue(isNewReleaseShelf("Latest releases"))
        assertTrue(isNewReleaseShelf("Latest Release"))
    }

    @Test
    fun `new release headings match`() {
        assertTrue(isNewReleaseShelf("New releases"))
        assertTrue(isNewReleaseShelf("New albums & singles"))
        assertTrue(isNewReleaseShelf("New singles"))
        assertTrue(isNewReleaseShelf("New music"))
        assertTrue(isNewReleaseShelf("Newest drops"))
        assertTrue(isNewReleaseShelf("Just released"))
        assertTrue(isNewReleaseShelf("Just dropped"))
    }

    @Test
    fun `unrelated shelves do not match`() {
        assertFalse(isNewReleaseShelf("Top songs"))
        assertFalse(isNewReleaseShelf("Fans might also like"))
        assertFalse(isNewReleaseShelf("Popular albums"))
        assertFalse(isNewReleaseShelf("From your library"))
        assertFalse(isNewReleaseShelf(""))
        // "New" alone is not a release shelf — the catalogue's "New to you"
        // shelf is recommendations, not releases.
        assertFalse(isNewReleaseShelf("New to you"))
    }

    // ── Release year ────────────────────────────────────────────────────────

    @Test
    fun `year is read off catalogue subtitles`() {
        assertEquals(2026, releaseYear("2026 • Album"))
        assertEquals(2025, releaseYear("Single • 2025"))
        assertEquals(1999, releaseYear("Album • 1999"))
    }

    @Test
    fun `no year means no year`() {
        assertNull(releaseYear("Album"))
        assertNull(releaseYear(""))
        assertNull(releaseYear("Playlist • 40 songs"))
    }

    // ── Follow merging ──────────────────────────────────────────────────────

    private fun artist(name: String, browseId: String) =
        FollowedArtist(name = name, browseId = browseId)

    @Test
    fun `merge keeps both lists without duplicating artists`() {
        val followed = listOf(artist("Alpha", "UC1"))
        val top = listOf(artist("Alpha", "UC1"), artist("Beta", "UC2"))
        val merged = FollowedArtists.mergedWith(followed, top)
        assertEquals(listOf("UC1", "UC2"), merged.map { it.browseId })
    }

    @Test
    fun `merge keeps the followed row on a duplicate`() {
        val followed = listOf(
            FollowedArtist(name = "Alpha", browseId = "UC1", thumbnailUrl = "followed-art"),
        )
        val top = listOf(
            FollowedArtist(name = "Alpha", browseId = "UC1", thumbnailUrl = "top-art"),
        )
        val merged = FollowedArtists.mergedWith(followed, top)
        assertEquals(1, merged.size)
        assertEquals("followed-art", merged.first().thumbnailUrl)
    }

    @Test
    fun `merge skips blank browse ids`() {
        val merged = FollowedArtists.mergedWith(
            emptyList(),
            listOf(artist("Ghost", "")),
        )
        assertTrue(merged.isEmpty())
    }
}
