package com.music.sonariq.download

import com.music.sonariq.data.stats.TrackStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Smart Downloads 2.0 scoring is pure — stats in, a number out — so the
 * pattern prediction is pinned here without an Android runtime.
 *
 * All "now" timestamps are built from LocalDateTime in the device's own
 * zone and the expectations are derived back through the same zone, so the
 * tests hold wherever they run.
 */
class SmartDownloadScoringTest {

    private fun track(
        id: String,
        plays: Int,
        ms: Long = 60_000L,
        lastPlayedMs: Long = 0L,
        hourPlays: List<Int> = emptyList(),
        weekdayPlays: List<Int> = emptyList(),
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
        hourPlays = hourPlays,
        weekdayPlays = weekdayPlays,
    )

    /** 2026-10-12 is a Monday; the hour and weekday are derived, not asserted. */
    private fun mondayAt(hour: Int): Long =
        LocalDateTime.of(2026, 10, 12, hour, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun hourOf(nowMs: Long): Int =
        Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).hour

    private fun dowOf(nowMs: Long): Int =
        Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).dayOfWeek.value - 1

    private fun hoursWith(vararg plays: Pair<Int, Int>): List<Int> {
        val bins = MutableList(24) { 0 }
        plays.forEach { (hour, count) -> bins[hour] = count }
        return bins
    }

    private fun daysWith(vararg plays: Pair<Int, Int>): List<Int> {
        val bins = MutableList(7) { 0 }
        plays.forEach { (day, count) -> bins[day] = count }
        return bins
    }

    @Test
    fun `time of day boost lifts morning favorites when the worker runs at dawn`() {
        val now = mondayAt(6)
        val h = hourOf(now)
        val morning = track(
            "morning", plays = 10,
            hourPlays = hoursWith(h to 8, (h + 1) % 24 to 2),
        )
        val night = track(
            "night", plays = 10,
            hourPlays = hoursWith((h + 14) % 24 to 10),
        )
        assertTrue(SmartDownloads.scoreTrack(morning, now) > SmartDownloads.scoreTrack(night, now))
        val picked = SmartDownloads.selectPatternCandidates(listOf(night, morning), emptySet(), 2, now)
        assertEquals(listOf("morning", "night"), picked.map { it.id })
    }

    @Test
    fun `uniform listening gets no time of day boost`() {
        val now = mondayAt(6)
        val uniform = track("uniform", plays = 24, hourPlays = List(24) { 1 })
        val morning = track("morning", plays = 24, hourPlays = hoursWith(6 to 12, 7 to 12))
        assertTrue(SmartDownloads.scoreTrack(morning, now) > SmartDownloads.scoreTrack(uniform, now))
    }

    @Test
    fun `day of week boost lifts same-weekday favorites`() {
        val now = mondayAt(6)
        val dow = dowOf(now)
        val weekday = track("weekday", plays = 10, weekdayPlays = daysWith(dow to 10))
        val weekend = track("weekend", plays = 10, weekdayPlays = daysWith((dow + 5) % 7 to 10))
        assertTrue(SmartDownloads.scoreTrack(weekday, now) > SmartDownloads.scoreTrack(weekend, now))
        val picked = SmartDownloads.selectPatternCandidates(listOf(weekend, weekday), emptySet(), 2, now)
        assertEquals(listOf("weekday", "weekend"), picked.map { it.id })
    }

    @Test
    fun `recent rotation outranks stale at equal plays`() {
        val now = mondayAt(6)
        val dayMs = 86_400_000L
        val fresh = track("fresh", plays = 5, lastPlayedMs = now - dayMs)
        val stale = track("stale", plays = 5, lastPlayedMs = now - 60 * dayMs)
        assertTrue(SmartDownloads.scoreTrack(fresh, now) > SmartDownloads.scoreTrack(stale, now))
    }

    @Test
    fun `no pattern data keeps the base popularity order`() {
        val now = mondayAt(6)
        val history = listOf(
            track("c", plays = 1),
            track("a", plays = 10),
            track("b", plays = 5),
        )
        val picked = SmartDownloads.selectPatternCandidates(history, emptySet(), 10, now)
        assertEquals(listOf("a", "b", "c"), picked.map { it.id })
    }

    @Test
    fun `empty pattern buckets score the same as zeroed ones`() {
        val now = mondayAt(6)
        val empty = track("a", plays = 7)
        val zeroed = track(
            "b", plays = 7,
            hourPlays = List(24) { 0 }, weekdayPlays = List(7) { 0 },
        )
        assertEquals(
            SmartDownloads.scoreTrack(empty, now),
            SmartDownloads.scoreTrack(zeroed, now),
            0.0,
        )
    }

    @Test
    fun `pattern selection respects limit and excludes downloaded`() {
        val now = mondayAt(6)
        val history = (1..5).map { track("t$it", plays = it) }
        val picked = SmartDownloads.selectPatternCandidates(history, setOf("t5"), 2, now)
        assertEquals(listOf("t4", "t3"), picked.map { it.id })
        assertTrue(SmartDownloads.selectPatternCandidates(history, emptySet(), 0, now).isEmpty())
        assertTrue(SmartDownloads.selectPatternCandidates(emptyList(), emptySet(), 10, now).isEmpty())
    }
}
