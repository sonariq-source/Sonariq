package com.music.sonariq

import com.music.sonariq.data.listentogether.TasteMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The taste match score: the share of a friend's observed picks that also
 * sit in your top tracks. The score is computed, never fabricated — and
 * below the evidence threshold there is no score at all.
 */
class TasteMatchTest {

    private fun picks(vararg keys: String): Map<String, String> =
        keys.associateWith { "Title $it" }

    @Test
    fun `score is the share of the friend's picks in your top tracks`() {
        val result = TasteMatch.computeScore(
            friendName = "Mara",
            friendPicks = picks("v:a", "v:b", "v:c", "v:d"),
            userTopKeys = setOf("v:a", "v:c", "v:z"),
        )

        assertEquals(50, result?.score)
        assertEquals("Mara", result?.friendName)
        assertEquals(4, result?.friendPickCount)
        assertEquals(setOf("Title v:a", "Title v:c"), result?.sharedTrackTitles?.toSet())
    }

    @Test
    fun `a full overlap scores one hundred`() {
        val result = TasteMatch.computeScore(
            friendName = "Mara",
            friendPicks = picks("v:a", "v:b", "v:c"),
            userTopKeys = setOf("v:a", "v:b", "v:c", "v:d"),
        )

        assertEquals(100, result?.score)
    }

    @Test
    fun `no overlap scores zero, not null`() {
        // Zero is a real answer — the evidence exists, it just does not overlap.
        val result = TasteMatch.computeScore(
            friendName = "Mara",
            friendPicks = picks("v:a", "v:b", "v:c"),
            userTopKeys = setOf("v:z"),
        )

        assertEquals(0, result?.score)
        assertTrue(result?.sharedTrackTitles?.isEmpty() == true)
    }

    @Test
    fun `below the pick threshold there is no score`() {
        assertNull(
            TasteMatch.computeScore(
                friendName = "Mara",
                friendPicks = picks("v:a", "v:b"),
                userTopKeys = setOf("v:a", "v:b"),
            ),
        )
    }

    @Test
    fun `no picks means no score`() {
        assertNull(
            TasteMatch.computeScore(
                friendName = "Mara",
                friendPicks = emptyMap(),
                userTopKeys = setOf("v:a"),
            ),
        )
    }

    @Test
    fun `exactly at the threshold scores`() {
        val result = TasteMatch.computeScore(
            friendName = "Mara",
            friendPicks = picks("v:a", "v:b", "v:c"),
            userTopKeys = setOf("v:a"),
        )

        // 1 of 3 -> 33.33… -> 33
        assertEquals(33, result?.score)
    }

    @Test
    fun `friend picks aggregate one key per pick`() {
        val recaps = listOf(
            com.music.sonariq.data.listentogether.PartyRecap(
                code = "ABC123",
                startedAtMs = 1000,
                endedAtMs = 2000,
                memberNames = listOf("Mara", "Theo"),
                playedTracks = listOf(
                    com.music.sonariq.data.listentogether.RecapPlayedTrack(
                        videoId = "vid-1",
                        title = "Midnight Drive",
                        artist = "Nova",
                        plays = 2,
                        addedBy = "Mara",
                        firstPlayedAtMs = 1100,
                    ),
                ),
                addedTracks = listOf(
                    com.music.sonariq.data.listentogether.RecapAddedTrack(
                        title = "Solar Bloom",
                        by = "Mara",
                    ),
                    com.music.sonariq.data.listentogether.RecapAddedTrack(
                        title = "Solar Bloom",
                        by = "Theo",
                    ),
                ),
            ),
        )

        val picks = TasteMatch.friendPicks("Mara", recaps)

        // One key for the played track (videoId preferred), one for the queued
        // title — and Theo's identical title is not Mara's pick.
        assertEquals(2, picks.size)
        assertTrue(picks.keys.any { it == "v:vid-1" })
        assertTrue(picks.keys.any { it.startsWith("t:") })
    }
}
