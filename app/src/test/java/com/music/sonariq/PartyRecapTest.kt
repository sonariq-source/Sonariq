package com.music.sonariq

import com.music.sonariq.data.listentogether.RawPlay
import com.music.sonariq.data.listentogether.parseQueueAddDetail
import com.music.sonariq.data.listentogether.rankPlayedTracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The party recap's pure halves: parsing the server's queue-add broadcasts
 * and ranking observed plays into top tracks.
 */
class PartyRecapTest {

    // ---- Queue-add detail parsing --------------------------------------

    @Test
    fun `parses a single-track add`() {
        val info = parseQueueAddDetail("Added \"Midnight Drive\" to the queue")

        assertEquals("Midnight Drive", info?.title)
        assertEquals(0, info?.moreCount)
    }

    @Test
    fun `parses a multi-track add`() {
        val info = parseQueueAddDetail("Added \"Midnight Drive\" and 3 more to the queue")

        assertEquals("Midnight Drive", info?.title)
        assertEquals(3, info?.moreCount)
    }

    @Test
    fun `a title containing quotes still parses`() {
        val info = parseQueueAddDetail("Added \"Say \"Hello\" Loud\" to the queue")

        assertEquals("Say \"Hello\" Loud", info?.title)
    }

    @Test
    fun `the bare action name is not a parseable add`() {
        assertNull(parseQueueAddDetail("queueAdd"))
    }

    @Test
    fun `other actions are not adds`() {
        assertNull(parseQueueAddDetail("Changed the song to \"Midnight Drive\""))
        assertNull(parseQueueAddDetail(""))
    }

    // ---- Play ranking ----------------------------------------------------

    private fun play(id: String, atMs: Long, by: String? = null) =
        RawPlay(videoId = id, title = "Title $id", artist = "Artist $id", startedByName = by, atMs = atMs)

    @Test
    fun `ranks by plays descending`() {
        val plays = listOf(
            play("a", 1000),
            play("b", 2000),
            play("a", 3000),
            play("c", 4000),
            play("b", 5000),
            play("a", 6000),
        )

        val ranked = rankPlayedTracks(plays)

        assertEquals(listOf("a", "b", "c"), ranked.map { it.videoId })
        assertEquals(3, ranked[0].plays)
        assertEquals(2, ranked[1].plays)
        assertEquals(1, ranked[2].plays)
    }

    @Test
    fun `ties break by earliest first play`() {
        val plays = listOf(
            play("b", 2000),
            play("a", 1000),
        )

        val ranked = rankPlayedTracks(plays)

        assertEquals(listOf("a", "b"), ranked.map { it.videoId })
    }

    @Test
    fun `attribution is the first named picker`() {
        val plays = listOf(
            play("a", 1000, by = null),
            play("a", 2000, by = "Mara"),
            play("a", 3000, by = "Theo"),
        )

        val ranked = rankPlayedTracks(plays)

        assertEquals("Mara", ranked.single().addedBy)
    }

    @Test
    fun `no named picker means no attribution rather than a guess`() {
        val ranked = rankPlayedTracks(listOf(play("a", 1000)))

        assertNull(ranked.single().addedBy)
    }

    @Test
    fun `a blank title falls back to the video id`() {
        val ranked = rankPlayedTracks(
            listOf(RawPlay(videoId = "xyz", title = "", artist = "", startedByName = null, atMs = 1)),
        )

        assertEquals("xyz", ranked.single().title)
    }

    @Test
    fun `no plays means no top tracks`() {
        assertTrue(rankPlayedTracks(emptyList()).isEmpty())
    }
}
