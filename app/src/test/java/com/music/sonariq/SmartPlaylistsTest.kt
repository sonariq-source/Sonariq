package com.music.sonariq

import com.music.sonariq.data.model.Song
import com.music.sonariq.data.stats.SmartPlaylists
import com.music.sonariq.data.stats.TrackStats
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The smart-playlist selections: real data in, honest lists out. Forgotten
 * favorites means at least [SmartPlaylists.FORGOTTEN_MIN_PLAYS] plays and
 * nothing in the last [SmartPlaylists.FORGOTTEN_DAYS] days.
 */
class SmartPlaylistsTest {

    private fun track(id: String, plays: Int, ms: Long, last: Long) = TrackStats(
        id = id,
        title = "Title $id",
        artist = "Artist",
        album = null,
        albumId = null,
        artistId = null,
        art = null,
        ms = ms,
        plays = plays,
        lastPlayedMs = last,
    )

    private fun song(id: String, addedSeconds: Long?, uri: String? = null) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist",
        thumbnailUrl = null,
        localUri = uri,
        localDateAddedSeconds = addedSeconds,
    )

    @Test
    fun `most played orders by plays then time`() {
        val tracks = listOf(
            track("a", plays = 5, ms = 1_000, last = 3),
            track("b", plays = 10, ms = 100, last = 2),
            track("c", plays = 10, ms = 9_000, last = 1),
            // Sampled but never a full play: not "played".
            track("d", plays = 0, ms = 99_999, last = 4),
        )
        assertEquals(listOf("c", "b", "a"), SmartPlaylists.mostPlayed(tracks).map { it.videoId })
    }

    @Test
    fun `most played honors the limit`() {
        val tracks = (1..60).map { track("t$it", plays = it, ms = 1_000, last = it.toLong()) }
        assertEquals(50, SmartPlaylists.mostPlayed(tracks).size)
        assertEquals(10, SmartPlaylists.mostPlayed(tracks, limit = 10).size)
    }

    @Test
    fun `recently played is most recent first`() {
        val now = 1_000_000_000_000L
        val tracks = listOf(
            track("a", plays = 3, ms = 1_000, last = now - 5_000),
            track("b", plays = 3, ms = 1_000, last = now - 1_000),
            track("c", plays = 3, ms = 1_000, last = 0),
        )
        assertEquals(
            listOf("b", "a"),
            SmartPlaylists.recentlyPlayed(tracks).map { it.videoId },
        )
    }

    @Test
    fun `forgotten favorites need ten plays and thirty quiet days`() {
        val now = 1_000_000_000_000L
        val day = 24 * 60 * 60 * 1_000L
        val tracks = listOf(
            track("old-fav", plays = 50, ms = 1_000, last = now - 31 * day),
            // Played five days ago: still warm.
            track("recent-fav", plays = 60, ms = 1_000, last = now - 5 * day),
            // Quiet for two months, but nine plays is not a favourite.
            track("not-a-fav", plays = 9, ms = 1_000, last = now - 60 * day),
            // Exactly thirty days: the cutoff is "in the last N days".
            track("boundary", plays = 20, ms = 1_000, last = now - 30 * day),
        )
        assertEquals(
            listOf("old-fav"),
            SmartPlaylists.forgottenFavorites(tracks, now).map { it.videoId },
        )
    }

    @Test
    fun `forgotten favorites sort most played first`() {
        val now = 1_000_000_000_000L
        val day = 24 * 60 * 60 * 1_000L
        val tracks = listOf(
            track("less", plays = 15, ms = 1_000, last = now - 40 * day),
            track("more", plays = 100, ms = 1_000, last = now - 40 * day),
        )
        assertEquals(
            listOf("more", "less"),
            SmartPlaylists.forgottenFavorites(tracks, now).map { it.videoId },
        )
    }

    @Test
    fun `recently added is newest first and dedupes`() {
        val songs = listOf(
            song("a", addedSeconds = 1_000),
            song("b", addedSeconds = 3_000, uri = "file:///b"),
            song("c", addedSeconds = 2_000),
            song("no-date", addedSeconds = null),
            song("zero", addedSeconds = 0),
            // Same file reached a second way: listed once.
            song("b-again", addedSeconds = 2_500, uri = "file:///b"),
        )
        assertEquals(
            listOf("b", "c", "a"),
            SmartPlaylists.recentlyAdded(songs).map { it.videoId },
        )
    }

    @Test
    fun `smart browse ids round-trip`() {
        SmartPlaylists.Kind.entries.forEach { kind ->
            assertEquals(kind, SmartPlaylists.kindOf(SmartPlaylists.browseId(kind)))
        }
    }
}
