package com.music.sonariq

import com.music.sonariq.data.library.ScheduledDownloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The overnight schedule's clock math: the next occurrence of a wall-clock
 * time, rolling over to tomorrow when today's has passed.
 */
class ScheduledDownloadsTest {

    private fun millis(hour: Int, minute: Int, dayOffset: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        return cal.timeInMillis
    }

    @Test
    fun `a later time today is today`() {
        val now = millis(1, 0)
        assertEquals(millis(2, 30), ScheduledDownloads.nextRunMillis(2, 30, now))
    }

    @Test
    fun `a passed time today is tomorrow`() {
        val now = millis(3, 0)
        assertEquals(millis(2, 30, dayOffset = 1), ScheduledDownloads.nextRunMillis(2, 30, now))
    }

    @Test
    fun `the exact minute counts as passed`() {
        val now = millis(2, 30)
        assertEquals(millis(2, 30, dayOffset = 1), ScheduledDownloads.nextRunMillis(2, 30, now))
    }

    @Test
    fun `midnight rolls to the next day`() {
        val now = millis(23, 59)
        assertEquals(millis(0, 0, dayOffset = 1), ScheduledDownloads.nextRunMillis(0, 0, now))
    }

    @Test
    fun `delay is the gap to the next run and never negative`() {
        val now = millis(1, 0)
        assertEquals(90L * 60 * 1_000L, ScheduledDownloads.delayUntilMillis(2, 30, now))
        val late = millis(23, 0)
        val delay = ScheduledDownloads.delayUntilMillis(2, 30, late)
        assertTrue(delay > 0)
        assertEquals(ScheduledDownloads.nextRunMillis(2, 30, late) - late, delay)
    }

    @Test
    fun `hours and minutes are clamped to the clock`() {
        val now = millis(1, 0)
        // 25:99 is not a time; the schedule treats it as 23:59.
        val run = ScheduledDownloads.nextRunMillis(25, 99, now)
        val cal = Calendar.getInstance().apply { timeInMillis = run }
        assertEquals(23, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, cal.get(Calendar.MINUTE))
    }
}
