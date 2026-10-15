package com.music.sonariq.download

import com.music.sonariq.data.settings.DownloadQuality
import com.music.sonariq.data.stats.TrackStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smart Downloads candidate selection is pure — history in, ranked tracks
 * out — so it is pinned here without an Android runtime.
 */
class SmartDownloadSelectionTest {

    private fun track(
        id: String,
        plays: Int,
        ms: Long = 1_000L,
        lastPlayedMs: Long = 0L,
    ) = TrackStats(
        id = id,
        title = "Title $id",
        artist = "Artist",
        album = null,
        albumId = null,
        artistId = null,
        art = null,
        ms = ms,
        plays = plays,
        lastPlayedMs = lastPlayedMs,
    )

    @Test
    fun `ranks by play count descending`() {
        val history = listOf(
            track("c", plays = 1),
            track("a", plays = 10),
            track("b", plays = 5),
        )
        val picked = SmartDownloads.selectCandidates(history, emptySet(), limit = 10)
        assertEquals(listOf("a", "b", "c"), picked.map { it.id })
    }

    @Test
    fun `ties break towards more time listened, then recency`() {
        val history = listOf(
            track("short", plays = 5, ms = 1_000L, lastPlayedMs = 1L),
            track("long", plays = 5, ms = 50_000L, lastPlayedMs = 1L),
            track("recent", plays = 5, ms = 1_000L, lastPlayedMs = 999L),
        )
        val picked = SmartDownloads.selectCandidates(history, emptySet(), limit = 10)
        assertEquals(listOf("long", "recent", "short"), picked.map { it.id })
    }

    @Test
    fun `excludes already downloaded tracks`() {
        val history = listOf(
            track("top", plays = 100),
            track("next", plays = 50),
        )
        val picked = SmartDownloads.selectCandidates(history, setOf("top"), limit = 10)
        assertEquals(listOf("next"), picked.map { it.id })
    }

    @Test
    fun `respects the limit`() {
        val history = (1..10).map { track("t$it", plays = it) }
        val picked = SmartDownloads.selectCandidates(history, emptySet(), limit = 3)
        assertEquals(3, picked.size)
        assertEquals(listOf("t10", "t9", "t8"), picked.map { it.id })
    }

    @Test
    fun `empty history picks nothing`() {
        assertTrue(SmartDownloads.selectCandidates(emptyList(), emptySet(), 50).isEmpty())
    }

    @Test
    fun `tracks with no plays are never picked`() {
        val history = listOf(track("unplayed", plays = 0), track("played", plays = 1))
        val picked = SmartDownloads.selectCandidates(history, emptySet(), 10)
        assertEquals(listOf("played"), picked.map { it.id })
    }

    @Test
    fun `non-positive limit picks nothing`() {
        val history = listOf(track("a", plays = 5))
        assertTrue(SmartDownloads.selectCandidates(history, emptySet(), 0).isEmpty())
        assertTrue(SmartDownloads.selectCandidates(history, emptySet(), -1).isEmpty())
    }

    @Test
    fun `duplicate history rows keep one copy`() {
        val history = listOf(track("a", plays = 5), track("a", plays = 5))
        val picked = SmartDownloads.selectCandidates(history, emptySet(), 10)
        assertEquals(listOf("a"), picked.map { it.id })
    }

    @Test
    fun `fitCount cuts the batch to what fits`() {
        // At HIGH (~8 MB a track), 20 MB of free space fits two tracks.
        val fits = SmartDownloads.fitCount(
            candidates = 10,
            usableBytes = 20L * 1024 * 1024,
            quality = DownloadQuality.HIGH,
        )
        assertEquals(2, fits)
    }

    @Test
    fun `fitCount is zero when nothing fits`() {
        assertEquals(
            0,
            SmartDownloads.fitCount(10, usableBytes = 1L * 1024 * 1024, quality = DownloadQuality.LOSSLESS),
        )
        assertEquals(0, SmartDownloads.fitCount(10, usableBytes = 0L, quality = DownloadQuality.HIGH))
        assertEquals(0, SmartDownloads.fitCount(0, usableBytes = Long.MAX_VALUE, quality = DownloadQuality.HIGH))
    }

    @Test
    fun `formatBytes renders the estimate readably`() {
        assertEquals("350 MB", SmartDownloads.formatBytes(350L * 1024 * 1024))
        assertEquals("1.8 GB", SmartDownloads.formatBytes((1.75 * 1024 * 1024 * 1024).toLong()))
        assertEquals("500 KB", SmartDownloads.formatBytes(500L * 1024))
    }
}
