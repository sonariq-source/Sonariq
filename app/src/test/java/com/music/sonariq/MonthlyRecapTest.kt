package com.music.sonariq

import com.music.sonariq.data.discovery.longestStreakIn
import com.music.sonariq.data.discovery.recapFromBucket
import com.music.sonariq.data.stats.NameEntry
import com.music.sonariq.data.stats.StoredBucket
import com.music.sonariq.data.stats.TrackEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/**
 * The monthly recap's pure reduction: one stored bucket becomes the month's
 * totals, charts, streak and busiest day — the same ranking rules the Replay
 * uses, so the two agree about the same month.
 */
class MonthlyRecapTest {

    private fun bucket() = StoredBucket(
        month = "2026-09",
        tracks = listOf(
            TrackEntry(id = "a", title = "Alpha", artist = "Band A", ms = 600_000L, plays = 5),
            TrackEntry(id = "b", title = "Beta", artist = "Band B", ms = 300_000L, plays = 10),
            TrackEntry(id = "c", title = "Gamma", artist = "Band A", ms = 100_000L, plays = 1),
        ),
        artists = listOf(
            NameEntry(name = "Band A", ms = 700_000L, plays = 6),
            NameEntry(name = "Band B", ms = 300_000L, plays = 10),
        ),
        days = mapOf(3 to 600_000L, 4 to 300_000L, 5 to 100_000L, 20 to 50_000L),
    )

    @Test
    fun `totals come from the bucket`() {
        val recap = recapFromBucket(bucket())
        assertEquals(YearMonth.of(2026, 9), recap.month)
        assertEquals(1_000_000L, recap.totalMs)
        assertEquals(16, recap.totalPlays)
        assertEquals(3, recap.distinctTracks)
        assertEquals(16L, recap.minutes)
    }

    @Test
    fun `tracks rank by time then plays`() {
        val recap = recapFromBucket(bucket())
        assertEquals(listOf("Alpha", "Beta", "Gamma"), recap.topTracks.map { it.title })
    }

    @Test
    fun `artists rank by time then plays`() {
        val recap = recapFromBucket(bucket())
        assertEquals(listOf("Band A", "Band B"), recap.topArtists.map { it.name })
    }

    @Test
    fun `busiest day is the day with the most listening`() {
        val recap = recapFromBucket(bucket())
        assertEquals(3, recap.busiestDay)
    }

    @Test
    fun `streak is the month's longest run`() {
        // Days 3, 4, 5 are a run of three; day 20 stands alone.
        val recap = recapFromBucket(bucket())
        assertEquals(3, recap.streakDays)
    }

    @Test
    fun `empty bucket makes an empty recap`() {
        val recap = recapFromBucket(StoredBucket(month = "2026-09"))
        assertTrue(recap.isEmpty)
        assertEquals(0, recap.streakDays)
        assertNull(recap.busiestDay)
        assertNull(recap.topGenre)
    }

    // ── longestStreakIn ─────────────────────────────────────────────────────

    private val september = YearMonth.of(2026, 9)

    @Test
    fun `a single day is a streak of one`() {
        assertEquals(1, longestStreakIn(mapOf("2026-09-10" to 1L), september))
    }

    @Test
    fun `a gap breaks the run`() {
        val days = mapOf(
            "2026-09-01" to 1L,
            "2026-09-02" to 1L,
            // 3rd missing
            "2026-09-04" to 1L,
            "2026-09-05" to 1L,
            "2026-09-06" to 1L,
        )
        assertEquals(3, longestStreakIn(days, september))
    }

    @Test
    fun `no listening is no streak`() {
        assertEquals(0, longestStreakIn(emptyMap(), september))
    }

    @Test
    fun `days outside the month do not count`() {
        val days = mapOf(
            "2026-08-31" to 1L,
            "2026-09-01" to 1L,
            "2026-10-01" to 1L,
        )
        assertEquals(1, longestStreakIn(days, september))
    }
}
