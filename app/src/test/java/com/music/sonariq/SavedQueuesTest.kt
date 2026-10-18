package com.music.sonariq

import com.music.sonariq.data.model.Song
import com.music.sonariq.playback.SavedQueues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedQueuesTest {

    private fun song(id: String, title: String = "Title $id"): Song = Song(
        videoId = id,
        title = title,
        artist = "Artist",
        thumbnailUrl = null,
    )

    @Test
    fun `blank names and empty track lists are refused`() {
        assertNull(SavedQueues.create("   ", listOf(song("a"))))
        assertNull(SavedQueues.create("Road trip", emptyList()))
    }

    @Test
    fun `create snapshots the tracks`() {
        val queue = SavedQueues.create("Road trip", listOf(song("a"), song("b")))!!
        assertEquals("Road trip", queue.name)
        assertEquals(listOf("a", "b"), queue.tracks.map { it.videoId })
        assertTrue(queue.id.isNotBlank())
    }

    @Test
    fun `add keeps newest first and caps the count`() {
        var queues = emptyList<SavedQueues.SavedQueue>()
        repeat(SavedQueues.MAX_SAVED_QUEUES + 5) { i ->
            val q = SavedQueues.create("Q$i", listOf(song("v$i")))!!
            // Force distinct, ordered timestamps.
            queues = SavedQueues.add(queues, q.copy(updatedAt = i.toLong()))
        }
        assertEquals(SavedQueues.MAX_SAVED_QUEUES, queues.size)
        assertEquals("Q${SavedQueues.MAX_SAVED_QUEUES + 4}", queues.first().name)
    }

    @Test
    fun `rename trims and refuses blanks`() {
        val queue = SavedQueues.create("Old", listOf(song("a")))!!
        val queues = listOf(queue)
        val renamed = SavedQueues.rename(queues, queue.id, "  New name  ")
        assertEquals("New name", renamed.first().name)
        assertEquals(renamed, SavedQueues.rename(renamed, queue.id, "   "))
    }

    @Test
    fun `move track reorders within bounds`() {
        val queue = SavedQueues.create("Q", listOf(song("a"), song("b"), song("c")))!!
        val moved = SavedQueues.moveTrack(queue, 2, 0)
        assertEquals(listOf("c", "a", "b"), moved.tracks.map { it.videoId })
        assertEquals(queue.tracks.map { it.videoId }, SavedQueues.moveTrack(queue, 9, 0).tracks.map { it.videoId })
    }

    @Test
    fun `remove track drops the right one`() {
        val queue = SavedQueues.create("Q", listOf(song("a"), song("b")))!!
        val updated = SavedQueues.removeTrack(queue, 0)
        assertEquals(listOf("b"), updated.tracks.map { it.videoId })
    }

    @Test
    fun `queues survive an encode decode round trip`() {
        val queues = listOf(
            SavedQueues.create("Road trip", listOf(song("a"), song("b")))!!,
            SavedQueues.create("Gym", listOf(song("c")))!!,
        )
        val decoded = SavedQueues.decode(SavedQueues.encode(queues))
        assertEquals(queues.map { it.name }, decoded.map { it.name })
        assertEquals(
            queues.map { it.tracks.map { t -> t.videoId } },
            decoded.map { it.tracks.map { t -> t.videoId } },
        )
    }

    @Test
    fun `decode tolerates garbage`() {
        assertTrue(SavedQueues.decode("").isEmpty())
        assertTrue(SavedQueues.decode("not json at all").isEmpty())
    }

    @Test
    fun `a snapshot round trips back to a playable song`() {
        val original = song("abc", "Hello")
        val restored = with(SavedQueues) { original.toSnapshot().toSong() }
        assertEquals(original.videoId, restored.videoId)
        assertEquals(original.title, restored.title)
        assertEquals(original.artist, restored.artist)
    }
}
