package com.music.sonariq

import com.music.sonariq.data.stats.ReplayPeriod
import com.music.sonariq.data.stats.TrackEntry
import com.music.sonariq.data.stats.weekOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The weekly Replay period: weeks start on Monday, and a week that straddles
 * two months reads both files but only counts its own seven days.
 */
class ListeningStatsWeekTest {

    @Test
    fun `week starts on Monday`() {
        // 2026-10-14 is a Wednesday.
        val week = weekOf(LocalDate.of(2026, 10, 14))
        assertEquals(DayOfWeek.MONDAY, week.start.dayOfWeek)
        assertEquals(LocalDate.of(2026, 10, 12), week.start)
        assertEquals(LocalDate.of(2026, 10, 18), week.endInclusive)
        assertEquals(7, week.endInclusive.toEpochDay() - week.start.toEpochDay() + 1)
    }

    @Test
    fun `week of a Monday is itself`() {
        val week = weekOf(LocalDate.of(2026, 10, 12))
        assertEquals(LocalDate.of(2026, 10, 12), week.start)
        assertEquals(LocalDate.of(2026, 10, 18), week.endInclusive)
    }

    @Test
    fun `week of a Sunday stays in the same week`() {
        val week = weekOf(LocalDate.of(2026, 10, 18))
        assertEquals(LocalDate.of(2026, 10, 12), week.start)
        assertEquals(LocalDate.of(2026, 10, 18), week.endInclusive)
    }

    @Test
    fun `this week covers both months when the week straddles them`() {
        // The week of Mon 2026-09-28 .. Sun 2026-10-04.
        val today = LocalDate.of(2026, 10, 1)
        assertTrue(ReplayPeriod.THIS_WEEK.covers(YearMonth.of(2026, 9), today))
        assertTrue(ReplayPeriod.THIS_WEEK.covers(YearMonth.of(2026, 10), today))
        assertFalse(ReplayPeriod.THIS_WEEK.covers(YearMonth.of(2026, 8), today))
        assertFalse(ReplayPeriod.THIS_WEEK.covers(YearMonth.of(2026, 11), today))
    }

    @Test
    fun `this week covers a single month when the week stays inside it`() {
        val today = LocalDate.of(2026, 10, 14)
        assertTrue(ReplayPeriod.THIS_WEEK.covers(YearMonth.of(2026, 10), today))
        assertFalse(ReplayPeriod.THIS_WEEK.covers(YearMonth.of(2026, 9), today))
        assertFalse(ReplayPeriod.THIS_WEEK.covers(YearMonth.of(2026, 11), today))
    }

    @Test
    fun `window is the seven days for the week and null otherwise`() {
        val today = LocalDate.of(2026, 10, 14)
        val window = ReplayPeriod.THIS_WEEK.window(today)!!
        assertEquals(7, window.size)
        assertTrue(window.contains(LocalDate.of(2026, 10, 12)))
        assertTrue(window.contains(LocalDate.of(2026, 10, 18)))
        assertFalse(window.contains(LocalDate.of(2026, 10, 19)))
        assertNull(ReplayPeriod.THIS_MONTH.window(today))
        assertNull(ReplayPeriod.THIS_YEAR.window(today))
        assertNull(ReplayPeriod.ALL_TIME.window(today))
    }

    @Test
    fun `label names the week's Monday`() {
        val label = ReplayPeriod.THIS_WEEK.label(LocalDate.of(2026, 10, 14))
        assertTrue("unexpected label: $label", label.startsWith("Week of "))
        assertTrue("unexpected label: $label", label.contains("12"))
    }

    @Test
    fun `windowed keeps only the in-window days`() {
        val entry = TrackEntry(id = "x", title = "t", artist = "a").apply {
            dayMs[12] = 60_000L // Monday 2026-10-12
            dayMs[18] = 30_000L // Sunday 2026-10-18
            dayMs[19] = 90_000L // Monday of next week — outside
            dayPlays[12] = 2
            dayPlays[19] = 3
        }
        val window = ReplayPeriod.THIS_WEEK.window(LocalDate.of(2026, 10, 14))!!
        val narrowed = entry.windowed(YearMonth.of(2026, 10), window)!!
        assertEquals(90_000L, narrowed.ms)
        assertEquals(2, narrowed.plays)
        // A week with nothing in it narrows to nothing, not to zeros.
        assertNull(entry.windowed(YearMonth.of(2026, 10), ReplayPeriod.THIS_WEEK.window(LocalDate.of(2026, 10, 26))!!))
    }
}
