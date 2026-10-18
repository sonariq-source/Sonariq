package com.music.sonariq

import com.music.sonariq.data.library.PlaylistFolders
import com.music.sonariq.data.library.SmartFolder
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.stats.TrackStats
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smart-folder rules over real metadata: text matches, recency, play
 * counts, explicit flag, and served quality. A rule over a field the song
 * doesn't have fails rather than guessing.
 */
class PlaylistFoldersTest {

    private val now = 1_700_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1_000L

    private fun song(
        id: String,
        artist: String = "Artist",
        album: String? = null,
        addedDaysAgo: Long? = null,
        explicit: Boolean? = null,
        quality: String? = null,
    ) = Song(
        videoId = id,
        title = "Title $id",
        artist = artist,
        thumbnailUrl = null,
        albumName = album,
        localDateAddedSeconds = addedDaysAgo?.let { (now - it * dayMs) / 1_000L },
        isExplicit = explicit,
        sourceQuality = quality,
    )

    private fun stats(plays: Int, lastPlayedDaysAgo: Long?) = TrackStats(
        id = "x",
        title = "T",
        artist = "A",
        album = null,
        albumId = null,
        artistId = null,
        art = null,
        ms = 1_000,
        plays = plays,
        lastPlayedMs = lastPlayedDaysAgo?.let { now - it * dayMs } ?: 0L,
    )

    @Test
    fun `artist rule matches case-insensitively`() {
        val s = song("a", artist = "The Midnight Waves")
        assertTrue(PlaylistFolders.matches(PlaylistFolders.SmartRule.ArtistContains("midnight"), s, null, now))
        assertFalse(PlaylistFolders.matches(PlaylistFolders.SmartRule.ArtistContains("dawn"), s, null, now))
        assertFalse(PlaylistFolders.matches(PlaylistFolders.SmartRule.ArtistContains(""), s, null, now))
    }

    @Test
    fun `album rule needs an album name`() {
        val withAlbum = song("a", album = "After Hours")
        val withoutAlbum = song("b")
        assertTrue(PlaylistFolders.matches(PlaylistFolders.SmartRule.AlbumContains("after"), withAlbum, null, now))
        assertFalse(PlaylistFolders.matches(PlaylistFolders.SmartRule.AlbumContains("after"), withoutAlbum, null, now))
    }

    @Test
    fun `added-in-last-days uses the real added date`() {
        val recent = song("a", addedDaysAgo = 5)
        val old = song("b", addedDaysAgo = 40)
        val unknown = song("c")
        val rule = PlaylistFolders.SmartRule.AddedInLastDays(30)
        assertTrue(PlaylistFolders.matches(rule, recent, null, now))
        assertFalse(PlaylistFolders.matches(rule, old, null, now))
        // No added date: the rule fails rather than treating it as new.
        assertFalse(PlaylistFolders.matches(rule, unknown, null, now))
    }

    @Test
    fun `play-count rules need listening history`() {
        val played = stats(plays = 25, lastPlayedDaysAgo = 2)
        val unplayed: TrackStats? = null
        assertTrue(PlaylistFolders.matches(PlaylistFolders.SmartRule.MinPlays(10), song("a"), played, now))
        assertFalse(PlaylistFolders.matches(PlaylistFolders.SmartRule.MinPlays(30), song("a"), played, now))
        assertFalse(PlaylistFolders.matches(PlaylistFolders.SmartRule.MinPlays(1), song("a"), unplayed, now))
        assertTrue(PlaylistFolders.matches(PlaylistFolders.SmartRule.MaxPlays(30), song("a"), played, now))
    }

    @Test
    fun `quiet-days rule needs a last-played date`() {
        val quiet = stats(plays = 50, lastPlayedDaysAgo = 40)
        val warm = stats(plays = 50, lastPlayedDaysAgo = 5)
        val never: TrackStats? = null
        val rule = PlaylistFolders.SmartRule.NotPlayedInDays(30)
        assertTrue(PlaylistFolders.matches(rule, song("a"), quiet, now))
        assertFalse(PlaylistFolders.matches(rule, song("a"), warm, now))
        assertFalse(PlaylistFolders.matches(rule, song("a"), never, now))
    }

    @Test
    fun `explicit and quality rules match stated metadata only`() {
        val explicit = song("a", explicit = true)
        val unstated = song("b")
        assertTrue(PlaylistFolders.matches(PlaylistFolders.SmartRule.IsExplicit(true), explicit, null, now))
        assertFalse(PlaylistFolders.matches(PlaylistFolders.SmartRule.IsExplicit(true), unstated, null, now))
        val lossless = song("c", quality = "LOSSLESS")
        assertTrue(
            PlaylistFolders.matches(PlaylistFolders.SmartRule.SourceQuality("lossless"), lossless, null, now),
        )
        assertFalse(
            PlaylistFolders.matches(PlaylistFolders.SmartRule.SourceQuality("LOSSLESS"), unstated, null, now),
        )
    }

    @Test
    fun `evaluate honors match-all versus match-any`() {
        val songs = listOf(
            song("a", artist = "Midnight", addedDaysAgo = 5),
            song("b", artist = "Midnight", addedDaysAgo = 60),
            song("c", artist = "Noon", addedDaysAgo = 5),
        )
        val rules = listOf(
            PlaylistFolders.SmartRule.ArtistContains("midnight"),
            PlaylistFolders.SmartRule.AddedInLastDays(30),
        )
        val matchAll = SmartFolder("s1", "All", rules, matchAll = true)
        val matchAny = SmartFolder("s2", "Any", rules, matchAll = false)
        assertEquals(
            listOf("a"),
            PlaylistFolders.evaluate(matchAll, songs, emptyMap(), now).map { it.videoId },
        )
        assertEquals(
            listOf("a", "b", "c"),
            PlaylistFolders.evaluate(matchAny, songs, emptyMap(), now).map { it.videoId }.sorted(),
        )
    }

    @Test
    fun `evaluate with no rules is empty`() {
        val folder = SmartFolder("s", "Empty", emptyList(), true)
        assertTrue(PlaylistFolders.evaluate(folder, listOf(song("a")), emptyMap(), now).isEmpty())
    }

    @Test
    fun `play-count folders sort most-played first`() {
        val songs = listOf(song("a"), song("b"), song("c"))
        val statsById = mapOf(
            "a" to stats(plays = 5, lastPlayedDaysAgo = 1),
            "b" to stats(plays = 50, lastPlayedDaysAgo = 1),
            "c" to stats(plays = 20, lastPlayedDaysAgo = 1),
        )
        val folder = SmartFolder(
            "s", "Top", listOf(PlaylistFolders.SmartRule.MinPlays(1)), matchAll = true,
        )
        assertEquals(
            listOf("b", "c", "a"),
            PlaylistFolders.evaluate(folder, songs, statsById, now).map { it.videoId },
        )
    }

    @Test
    fun `rules survive a json round trip`() {
        val rules = listOf(
            PlaylistFolders.SmartRule.ArtistContains("midnight"),
            PlaylistFolders.SmartRule.AddedInLastDays(30),
            PlaylistFolders.SmartRule.MinPlays(10),
            PlaylistFolders.SmartRule.IsExplicit(false),
            PlaylistFolders.SmartRule.SourceQuality("LOSSLESS"),
        )
        val text = Json.encodeToString(ListSerializer(PlaylistFolders.SmartRule.serializer()), rules)
        val back = Json.decodeFromString(ListSerializer(PlaylistFolders.SmartRule.serializer()), text)
        assertEquals(rules, back)
    }
}
