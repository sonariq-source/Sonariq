package com.music.sonariq.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsPrefetchTest {

    private fun candidate(
        videoId: String,
        title: String = "title-$videoId",
    ) = PrefetchCandidate(
        videoId = videoId,
        title = title,
        artist = "artist",
        durationMs = 180_000L,
    )

    private val nothingCached: (String) -> Boolean = { false }

    @Test
    fun planPrefetchSkipsCurrentAndEarlier() {
        val queue = listOf(candidate("a"), candidate("b"), candidate("c"), candidate("d"))
        val plan = planPrefetch(queue, currentIndex = 1, isCached = nothingCached)
        assertEquals(listOf("c", "d"), plan.map { it.videoId })
    }

    @Test
    fun planPrefetchAtEndOfQueueReturnsEmpty() {
        val queue = listOf(candidate("a"), candidate("b"))
        assertTrue(planPrefetch(queue, currentIndex = 1, isCached = nothingCached).isEmpty())
        assertTrue(planPrefetch(queue, currentIndex = 5, isCached = nothingCached).isEmpty())
    }

    @Test
    fun planPrefetchDedupesRepeatedVideoIdsKeepingFirst() {
        val queue = listOf(
            candidate("current"),
            candidate("x", title = "first"),
            candidate("y"),
            candidate("x", title = "second"),
        )
        val plan = planPrefetch(queue, currentIndex = 0, isCached = nothingCached)
        assertEquals(listOf("x", "y"), plan.map { it.videoId })
        assertEquals("first", plan.first().title)
    }

    @Test
    fun planPrefetchRespectsCapAndDefaultCap() {
        val queue = (0..20).map { candidate("v$it") }
        assertEquals(3, planPrefetch(queue, currentIndex = -1, cap = 3, isCached = nothingCached).size)
        assertEquals(21, planPrefetch(queue, currentIndex = -1, isCached = nothingCached).size)
    }

    @Test
    fun planPrefetchDefaultCapCoversWholeQueueUpTo100() {
        val queue = (0..149).map { candidate("v$it") }
        val plan = planPrefetch(queue, currentIndex = -1, isCached = nothingCached)
        assertEquals(QUEUE_PREFETCH_CAP, plan.size)
        assertEquals(100, plan.size)
        assertEquals("v0", plan.first().videoId)
        assertEquals("v99", plan.last().videoId)
    }

    @Test
    fun planPrefetchWholeQueueSkipsOnlyCurrentAndEarlier() {
        val queue = (0..119).map { candidate("v$it") }
        val plan = planPrefetch(queue, currentIndex = 19, isCached = nothingCached)
        assertEquals(100, plan.size)
        assertEquals("v20", plan.first().videoId)
    }

    @Test
    fun planListPrefetchCoversVisibleListCapped() {
        val visible = (0..59).map { candidate("v$it") }
        val plan = planListPrefetch(visible, isCached = nothingCached)
        assertEquals(LyricsLookupPolicy.LIST_PREFETCH_CAP, plan.size)
        assertEquals(40, plan.size)
        assertEquals(5, planListPrefetch(visible, cap = 5, isCached = nothingCached).size)
    }

    @Test
    fun planListPrefetchDedupesAndExcludesCachedAndBlank() {
        val visible = listOf(
            candidate("a"),
            candidate("b"),
            candidate("a", title = "second"),
            candidate(""),
            candidate("c"),
        )
        val plan = planListPrefetch(visible, isCached = { it == "b" })
        assertEquals(listOf("a", "c"), plan.map { it.videoId })
        assertEquals("title-a", plan.first().title)
    }

    @Test
    fun planPrefetchExcludesCached() {
        val cached = setOf("b", "d")
        val queue = listOf(candidate("cur"), candidate("b"), candidate("c"), candidate("d"))
        val plan = planPrefetch(queue, currentIndex = 0, isCached = { it in cached })
        assertEquals(listOf("c"), plan.map { it.videoId })
    }

    @Test
    fun planPrefetchIgnoresBlankVideoIds() {
        val queue = listOf(
            candidate("cur"),
            candidate(""),
            candidate("   "),
            candidate("ok"),
        )
        val plan = planPrefetch(queue, currentIndex = 0, isCached = nothingCached)
        assertEquals(listOf("ok"), plan.map { it.videoId })
    }

    @Test
    fun planWarmupPutsLikedFirst() {
        val liked = listOf(candidate("l1"), candidate("l2"))
        val stats = listOf(candidate("s1"), candidate("s2"))
        val plan = planWarmup(liked, stats, isCached = nothingCached)
        assertEquals(listOf("l1", "l2", "s1", "s2"), plan.map { it.videoId })
    }

    @Test
    fun planWarmupDedupesAcrossBothListsKeepingLikedPosition() {
        val liked = listOf(candidate("a", title = "liked"), candidate("b"))
        val stats = listOf(candidate("c"), candidate("a", title = "stats"))
        val plan = planWarmup(liked, stats, isCached = nothingCached)
        assertEquals(listOf("a", "b", "c"), plan.map { it.videoId })
        assertEquals("liked", plan.first().title)
    }

    @Test
    fun planWarmupRespectsCapAndDefaultCap() {
        val liked = (0..10).map { candidate("l$it") }
        val stats = (0..10).map { candidate("s$it") }
        assertEquals(4, planWarmup(liked, stats, cap = 4, isCached = nothingCached).size)
        // Default cap is generous: a realistic warmup set fits whole.
        assertEquals(22, planWarmup(liked, stats, isCached = nothingCached).size)
    }

    @Test
    fun planWarmupExcludesCachedAndBlankIds() {
        val liked = listOf(candidate("a"), candidate(""), candidate("b"))
        val stats = listOf(candidate("c"), candidate("d"))
        val plan = planWarmup(liked, stats, isCached = { it == "c" })
        assertEquals(listOf("a", "b", "d"), plan.map { it.videoId })
    }
}
