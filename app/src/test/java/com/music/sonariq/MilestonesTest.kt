package com.music.sonariq

import com.music.sonariq.data.stats.MilestoneKind
import com.music.sonariq.data.stats.milestonesFor
import com.music.sonariq.data.stats.streakOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Milestone thresholds: the documented bars, honest progress, and the streak
 * rule — consecutive days with listening, tolerating an unplayed today.
 */
class MilestonesTest {

    @Test
    fun `eight milestones with the documented bars`() {
        val milestones = milestonesFor(totalPlays = 0, totalMs = 0, streakDays = 0)
        assertEquals(8, milestones.size)
        assertEquals(
            listOf(100L, 1_000L, 10_000L),
            milestones.filter { it.kind == MilestoneKind.PLAYS }.map { it.target },
        )
        assertEquals(
            listOf(10 * 3_600_000L, 100 * 3_600_000L),
            milestones.filter { it.kind == MilestoneKind.LISTENING_TIME }.map { it.target },
        )
        assertEquals(
            listOf(7L, 30L, 100L),
            milestones.filter { it.kind == MilestoneKind.STREAK }.map { it.target },
        )
    }

    @Test
    fun `reached and progress come from the real totals`() {
        val milestones = milestonesFor(
            totalPlays = 1_500,
            totalMs = 5 * 3_600_000L,
            streakDays = 10,
        )
        val plays100 = milestones.first { it.kind == MilestoneKind.PLAYS && it.target == 100L }
        assertTrue(plays100.reached)
        assertEquals(1f, plays100.progress, 0.001f)

        val plays10k = milestones.first { it.kind == MilestoneKind.PLAYS && it.target == 10_000L }
        assertFalse(plays10k.reached)
        assertEquals(1_500L, plays10k.current)
        assertEquals(0.15f, plays10k.progress, 0.001f)

        val hours10 = milestones.first { it.kind == MilestoneKind.LISTENING_TIME }
        assertFalse(hours10.reached)
        assertEquals(0.5f, hours10.progress, 0.001f)

        val streak7 = milestones.first { it.kind == MilestoneKind.STREAK && it.target == 7L }
        assertTrue(streak7.reached)
        val streak30 = milestones.first { it.kind == MilestoneKind.STREAK && it.target == 30L }
        assertFalse(streak30.reached)
        assertEquals(10L, streak30.current)
    }

    @Test
    fun `zero history means nothing reached`() {
        assertTrue(milestonesFor(0, 0, 0).none { it.reached })
    }

    @Test
    fun `streak counts consecutive days ending today`() {
        val today = LocalDate.of(2026, 10, 14)
        val days = mapOf(
            "2026-10-14" to 1_000L,
            "2026-10-13" to 2_000L,
            "2026-10-12" to 3_000L,
            // Gap on the 11th breaks the run.
            "2026-10-10" to 4_000L,
        )
        assertEquals(3, streakOf(days, today))
    }

    @Test
    fun `streak tolerates an unplayed today`() {
        val today = LocalDate.of(2026, 10, 14)
        val days = mapOf(
            "2026-10-13" to 2_000L,
            "2026-10-12" to 3_000L,
        )
        assertEquals(2, streakOf(days, today))
    }

    @Test
    fun `streak is zero with no recent listening`() {
        val today = LocalDate.of(2026, 10, 14)
        assertEquals(0, streakOf(emptyMap(), today))
        assertEquals(0, streakOf(mapOf("2026-10-10" to 5_000L), today))
    }
}
