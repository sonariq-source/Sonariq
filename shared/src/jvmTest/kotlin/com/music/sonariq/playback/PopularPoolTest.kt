package com.music.sonariq.playback

import com.music.sonariq.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The popular pool's composition: the country chart leads with its own
 * ranks, the global chart fills in behind it, and the fallback order —
 * country, then global, then nothing — holds at every emptiness.
 */
class PopularPoolTest {

    private fun song(id: String) =
        Song(videoId = id, title = "Song $id", artist = "Artist $id", thumbnailUrl = null)

    @Test
    fun countryChartLeadsWithItsOwnRanks() {
        val pool = mergePopularPools(
            countryPool = listOf(song("a"), song("b"), song("c")),
            globalPool = listOf(song("x"), song("y")),
        )
        assertEquals(listOf("a", "b", "c", "x", "y"), pool.songs.map { it.videoId })
        assertEquals(0, pool.ranks["a"])
        assertEquals(2, pool.ranks["c"])
        // Global-only songs rank behind every country entry.
        assertEquals(3, pool.ranks["x"])
        assertEquals(4, pool.ranks["y"])
    }

    @Test
    fun aSongOnBothChartsKeepsItsCountryRank() {
        val pool = mergePopularPools(
            countryPool = listOf(song("a"), song("hit")),
            globalPool = listOf(song("hit"), song("a"), song("z")),
        )
        assertEquals(listOf("a", "hit", "z"), pool.songs.map { it.videoId })
        assertEquals(1, pool.ranks["hit"])
        assertEquals(0, pool.ranks["a"])
        assertEquals(2, pool.ranks["z"])
    }

    @Test
    fun emptyCountryChartFallsBackToTheGlobalChart() {
        val pool = mergePopularPools(
            countryPool = emptyList(),
            globalPool = listOf(song("g1"), song("g2")),
        )
        assertEquals(listOf("g1", "g2"), pool.songs.map { it.videoId })
        assertEquals(0, pool.ranks["g1"])
        assertEquals(1, pool.ranks["g2"])
    }

    @Test
    fun noChartsAtAllIsAnEmptyPool() {
        val pool = mergePopularPools(emptyList(), emptyList())
        assertTrue(pool.songs.isEmpty())
        assertTrue(pool.ranks.isEmpty())
        assertEquals(PopularPool.EMPTY, pool)
    }

    @Test
    fun duplicatesAndBlankIdsNeverEnter() {
        val pool = mergePopularPools(
            countryPool = listOf(song("a"), song("a"), song("")),
            globalPool = listOf(song("a"), song("b")),
        )
        assertEquals(listOf("a", "b"), pool.songs.map { it.videoId })
        assertEquals(0, pool.ranks["a"])
        assertEquals(1, pool.ranks["b"])
    }
}
