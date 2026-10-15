package com.music.sonariq

import com.music.sonariq.data.model.Song
import com.music.sonariq.data.stats.ListeningStats
import com.music.sonariq.data.stats.MilestoneKind
import com.music.sonariq.data.stats.ReplayPeriod
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Clear-history behavior, against the real engine pointed at a throwaway
 * folder: recording, reading back, wiping, and reading back empty.
 */
class ListeningStatsClearTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun setUp() {
        val field = ListeningStats::class.java.getDeclaredField("directory")
        field.isAccessible = true
        field.set(ListeningStats, temp.newFolder("listening"))
        ListeningStats.clear()
    }

    private fun song(id: String) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist",
        thumbnailUrl = null,
    )

    @Test
    fun `recorded history reads back and clear wipes it`() = runBlocking {
        ListeningStats.record(song("a"), 60_000L, countsAsPlay = true)
        ListeningStats.record(song("a"), 60_000L, countsAsPlay = true)
        ListeningStats.record(song("b"), 30_000L, countsAsPlay = false)

        val before = ListeningStats.summary(ReplayPeriod.ALL_TIME)
        assertEquals(2, before.totalPlays)
        assertEquals(150_000L, before.totalMs)
        assertFalse(before.isEmpty)
        assertTrue(ListeningStats.months().isNotEmpty())

        ListeningStats.clear()

        val after = ListeningStats.summary(ReplayPeriod.ALL_TIME)
        assertTrue(after.isEmpty)
        assertEquals(0L, after.totalMs)
        assertEquals(0, after.totalPlays)
        assertTrue(ListeningStats.months().isEmpty())
    }

    @Test
    fun `this week sees what was just recorded`() = runBlocking {
        ListeningStats.record(song("w"), 45_000L, countsAsPlay = true)

        val week = ListeningStats.summary(ReplayPeriod.THIS_WEEK)
        assertEquals(1, week.totalPlays)
        assertEquals(45_000L, week.totalMs)
        assertEquals(1, week.distinctSongs)
        assertFalse(week.isEmpty)
    }

    @Test
    fun `milestones reflect the recorded totals`() = runBlocking {
        repeat(3) { index -> ListeningStats.record(song("m$index"), 60_000L, countsAsPlay = true) }

        val plays100 = ListeningStats.milestones()
            .first { it.kind == MilestoneKind.PLAYS && it.target == 100L }
        assertEquals(3L, plays100.current)
        assertFalse(plays100.reached)
    }

    @Test
    fun `clear on an empty history is a no-op`() = runBlocking {
        ListeningStats.clear()
        assertTrue(ListeningStats.summary(ReplayPeriod.ALL_TIME).isEmpty)
    }
}
