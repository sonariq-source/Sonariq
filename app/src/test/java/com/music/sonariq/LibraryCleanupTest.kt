package com.music.sonariq

import com.music.sonariq.data.library.LibraryCleanup
import com.music.sonariq.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Duplicate detection by real comparison — normalised title/artist plus
 * duration within tolerance — and metadata-gap reporting with file-name
 * suggestions. The finder reports; it never alters.
 */
class LibraryCleanupTest {

    private fun song(
        id: String,
        title: String = "Neon Skyline",
        artist: String = "Midnight",
        duration: String? = "3:07",
        localPath: String? = null,
        album: String? = "After Dark",
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        localPath = localPath,
        albumName = album,
    )

    @Test
    fun `same title artist and duration is a duplicate`() {
        val groups = LibraryCleanup.findDuplicates(
            listOf(song("a"), song("b"), song("c", title = "Other")),
        )
        assertEquals(1, groups.size)
        assertEquals(setOf("a", "b"), groups[0].songs.map { it.videoId }.toSet())
        assertTrue(groups[0].reason.contains("duration"))
    }

    @Test
    fun `different durations are not the same recording`() {
        val groups = LibraryCleanup.findDuplicates(
            listOf(song("a", duration = "3:07"), song("b", duration = "5:42")),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `durations within tolerance still group`() {
        // 3:07 vs 3:09: the same take with different rounding.
        val groups = LibraryCleanup.findDuplicates(
            listOf(song("a", duration = "3:07"), song("b", duration = "3:09")),
        )
        assertEquals(1, groups.size)
    }

    @Test
    fun `missing durations group on title and artist`() {
        val groups = LibraryCleanup.findDuplicates(
            listOf(song("a", duration = null), song("b", duration = null)),
        )
        assertEquals(1, groups.size)
        assertTrue(groups[0].reason.contains("title and artist"))
    }

    @Test
    fun `matching is punctuation and case insensitive`() {
        val groups = LibraryCleanup.findDuplicates(
            listOf(
                song("a", title = "Don't Stop Me Now", artist = "Queen"),
                song("b", title = "dont  stop me now!", artist = "queen"),
            ),
        )
        assertEquals(1, groups.size)
    }

    @Test
    fun `keep prefers the on-device copy`() {
        val groups = LibraryCleanup.findDuplicates(
            listOf(
                song("stream"),
                song("local", localPath = "/music/neon.mp3"),
            ),
        )
        assertEquals("local", groups[0].keep.videoId)
    }

    @Test
    fun `keep prefers richer metadata among streams`() {
        val groups = LibraryCleanup.findDuplicates(
            listOf(song("bare", album = null), song("rich", album = "After Dark")),
        )
        assertEquals("rich", groups[0].keep.videoId)
    }

    @Test
    fun `blank titles and artists are reported with file-name suggestions`() {
        val gaps = LibraryCleanup.findMetadataGaps(
            listOf(song("a", title = "", artist = "", localPath = "/music/Midnight - Neon Skyline.mp3")),
        )
        val byField = gaps.associateBy { it.field }
        assertEquals("Neon Skyline", byField["title"]?.suggestion)
        assertEquals("Midnight", byField["artist"]?.suggestion)
    }

    @Test
    fun `a file name without artist-title shape suggests the title only`() {
        val gaps = LibraryCleanup.findMetadataGaps(
            listOf(song("a", title = "", artist = "", localPath = "/music/track01.mp3")),
        )
        val titleGap = gaps.first { it.field == "title" }
        assertEquals("track01", titleGap.suggestion)
        assertNull(gaps.first { it.field == "artist" }.suggestion)
    }

    @Test
    fun `album gaps are reported never guessed`() {
        val gaps = LibraryCleanup.findMetadataGaps(
            listOf(song("a", album = null, localPath = "/music/Midnight - Neon.mp3")),
        )
        val albumGap = gaps.first { it.field == "album" }
        assertNull(albumGap.suggestion)
    }

    @Test
    fun `complete songs produce no gaps`() {
        assertTrue(LibraryCleanup.findMetadataGaps(listOf(song("a"))).isEmpty())
    }

    @Test
    fun `parseDurationSeconds handles real formats`() {
        assertEquals(187, LibraryCleanup.parseDurationSeconds("3:07"))
        assertEquals(3_661, LibraryCleanup.parseDurationSeconds("1:01:01"))
        assertEquals(45, LibraryCleanup.parseDurationSeconds("45"))
        assertNull(LibraryCleanup.parseDurationSeconds(null))
        assertNull(LibraryCleanup.parseDurationSeconds(""))
        assertNull(LibraryCleanup.parseDurationSeconds("live"))
        assertNull(LibraryCleanup.parseDurationSeconds("1:2:3:4"))
    }

    @Test
    fun `splitFileName strips the extension`() {
        assertEquals("Midnight" to "Neon Skyline", LibraryCleanup.splitFileName("Midnight - Neon Skyline.mp3"))
        assertEquals(null to "track01", LibraryCleanup.splitFileName("track01.mp3"))
        assertEquals(null to null, LibraryCleanup.splitFileName(".mp3"))
    }
}
