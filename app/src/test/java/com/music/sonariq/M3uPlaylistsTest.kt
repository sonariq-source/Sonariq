package com.music.sonariq

import com.music.sonariq.data.library.LibraryCleanup
import com.music.sonariq.data.library.M3uPlaylists
import com.music.sonariq.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * m3u in and out: extended entries, plain path lists, comments, and
 * matching entries back against the library by path or by artist/title.
 */
class M3uPlaylistsTest {

    private fun song(
        id: String,
        title: String = "Title $id",
        artist: String = "Artist $id",
        localPath: String? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        localPath = localPath,
    )

    @Test
    fun `parse reads extended entries`() {
        val text = """
            #EXTM3U
            #EXTINF:187, Midnight - Neon Skyline
            /music/Midnight - Neon Skyline.mp3
            #EXTINF:243,Noon - Glass Hour
            /music/Noon - Glass Hour.flac
        """.trimIndent()
        val entries = M3uPlaylists.parse(text)
        assertEquals(2, entries.size)
        assertEquals("/music/Midnight - Neon Skyline.mp3", entries[0].path)
        assertEquals(187, entries[0].durationSeconds)
        assertEquals("Midnight - Neon Skyline", entries[0].title)
        assertEquals(243, entries[1].durationSeconds)
    }

    @Test
    fun `parse reads plain path lists and skips comments`() {
        val text = """
            # A comment
            /music/a.mp3

            /music/b.mp3
        """.trimIndent()
        val entries = M3uPlaylists.parse(text)
        assertEquals(listOf("/music/a.mp3", "/music/b.mp3"), entries.map { it.path })
        assertEquals("a.mp3", entries[0].title)
        assertEquals(null, entries[0].durationSeconds)
    }

    @Test
    fun `parse handles extinf without duration`() {
        val entries = M3uPlaylists.parse("#EXTINF:-1, Artist - Title\n/song.mp3\n")
        assertEquals(1, entries.size)
        assertEquals(-1, entries[0].durationSeconds)
        assertEquals("Artist - Title", entries[0].title)
    }

    @Test
    fun `build writes extinf and real paths`() {
        val songs = listOf(
            song("a", title = "Neon Skyline", artist = "Midnight", localPath = "/music/neon.mp3"),
            song("b", title = "Stream Only", artist = "Noon"),
        )
        val text = M3uPlaylists.build(songs)
        assertTrue(text.startsWith("#EXTM3U"))
        assertTrue(text.contains("#EXTINF:-1,Midnight - Neon Skyline"))
        assertTrue(text.contains("/music/neon.mp3"))
        // No local path: the video id is carried as the identifier line.
        assertTrue(text.contains("\nb\n"))
    }

    @Test
    fun `build and parse round trip`() {
        val songs = listOf(song("a", title = "Neon", artist = "Midnight", localPath = "/m/neon.mp3"))
        val entries = M3uPlaylists.parse(M3uPlaylists.build(songs))
        assertEquals(1, entries.size)
        assertEquals("/m/neon.mp3", entries[0].path)
    }

    @Test
    fun `matchEntry prefers the file path`() {
        val songs = listOf(
            song("a", title = "Neon Skyline", artist = "Midnight", localPath = "/music/neon.mp3"),
            song("b", title = "Neon Skyline", artist = "Midnight"),
        )
        val entry = M3uPlaylists.Entry("/other/folder/neon.mp3", 187, "Midnight - Neon Skyline")
        // Suffix match: the file moved folders but is the same file.
        assertEquals("a", M3uPlaylists.matchEntry(entry, songs)?.videoId)
    }

    @Test
    fun `matchEntry falls back to artist and title`() {
        val songs = listOf(song("a", title = "Neon Skyline", artist = "Midnight"))
        val entry = M3uPlaylists.Entry("https://example.com/stream", null, "Midnight - Neon Skyline")
        assertEquals("a", M3uPlaylists.matchEntry(entry, songs)?.videoId)
    }

    @Test
    fun `matchEntry is punctuation-insensitive`() {
        val songs = listOf(song("a", title = "Don't Stop Me Now", artist = "Queen"))
        val entry = M3uPlaylists.Entry("/q.mp3", null, "Queen - dont  stop me now!")
        assertEquals("a", M3uPlaylists.matchEntry(entry, songs)?.videoId)
    }

    @Test
    fun `matchEntry returns null when nothing matches`() {
        val songs = listOf(song("a", title = "Neon", artist = "Midnight"))
        val entry = M3uPlaylists.Entry("/z.mp3", null, "Nobody - Nothing")
        assertNull(M3uPlaylists.matchEntry(entry, songs))
    }

    @Test
    fun `matchAll reports misses instead of dropping them`() {
        val songs = listOf(song("a", title = "Neon", artist = "Midnight"))
        val entries = listOf(
            M3uPlaylists.Entry("/x.mp3", null, "Midnight - Neon"),
            M3uPlaylists.Entry("/y.mp3", null, "Nobody - Nothing"),
        )
        val matches = M3uPlaylists.matchAll(entries, songs)
        assertEquals(2, matches.size)
        assertTrue(matches[0] is M3uPlaylists.Match.Found)
        assertTrue(matches[1] is M3uPlaylists.Match.Missing)
        assertEquals("Nobody - Nothing", (matches[1] as M3uPlaylists.Match.Missing).entry.title)
    }

    @Test
    fun `splitArtistTitle handles bare titles`() {
        assertEquals(null to "Just a Title", M3uPlaylists.splitArtistTitle("Just a Title"))
        assertEquals("A" to "B", M3uPlaylists.splitArtistTitle("A - B"))
    }

    @Test
    fun `cleanup reuses the m3u normaliser`() {
        // The duplicate finder and the m3u matcher agree on identity.
        assertEquals(
            M3uPlaylists.normalise("Don't Stop!"),
            M3uPlaylists.normalise("dont stop"),
        )
        assertEquals(
            LibraryCleanup.normaliseKey("Don't Stop!", "Queen"),
            LibraryCleanup.normaliseKey("dont stop", "queen"),
        )
    }
}
