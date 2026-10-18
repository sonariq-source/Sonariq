package com.music.sonariq

import com.music.sonariq.data.model.Song
import com.music.sonariq.playback.radio.RadioSession
import com.music.sonariq.playback.radio.RadioSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The radio session's refill contract: when the queue is running low, and
 * what seeds the next batch.
 *
 * The network half ([RadioSession.loadBatch]) is not what's under test —
 * loading tracks needs the provider radio — so this covers the pure decision
 * logic the service asks on every track change: [RadioSession.shouldTopUp],
 * [RadioSession.refillSeed], and the start/stop lifecycle.
 */
class RadioSessionTest {

    private fun song(id: String, title: String = "Track $id") =
        Song(videoId = id, title = title, artist = "Artist", thumbnailUrl = null)

    @Before
    fun setUp() {
        RadioSession.stop()
    }

    @After
    fun tearDown() {
        RadioSession.stop()
    }

    // ---- Lifecycle -------------------------------------------------------

    @Test
    fun `starting from a song names the station for the song`() {
        val track = song("abc", "Midnight Drive")

        assertTrue(RadioSession.start(RadioSource.Song(track)))

        val station = RadioSession.active.value
        assertEquals("Midnight Drive", station?.title)
        assertEquals("abc", station?.seedSong?.videoId)
    }

    @Test
    fun `starting from an artist names the station for the artist`() {
        val top = song("top1", "Biggest Hit")

        assertTrue(RadioSession.start(RadioSource.Artist("Nova Twins", top)))

        assertEquals("Nova Twins", RadioSession.active.value?.title)
        assertEquals("top1", RadioSession.active.value?.seedSong?.videoId)
    }

    @Test
    fun `starting from a playlist seeds from its first track`() {
        val tracks = listOf(song("p1"), song("p2"), song("p3"))

        assertTrue(RadioSession.start(RadioSource.Playlist("Gym", tracks)))

        assertEquals("Gym", RadioSession.active.value?.title)
        assertEquals("p1", RadioSession.active.value?.seedSong?.videoId)
    }

    @Test
    fun `an empty playlist refuses to start and leaves no station`() {
        assertFalse(RadioSession.start(RadioSource.Playlist("Empty", emptyList())))

        assertNull(RadioSession.active.value)
    }

    @Test
    fun `stop clears the station`() {
        RadioSession.start(RadioSource.Song(song("abc")))
        RadioSession.stop()

        assertNull(RadioSession.active.value)
    }

    // ---- Refill trigger --------------------------------------------------

    @Test
    fun `no refill when the queue is comfortably ahead`() {
        // 20 tracks queued, playhead at 0: 19 ahead, well past the threshold.
        assertFalse(RadioSession.shouldTopUp(currentIndex = 0, queueSize = 20))
    }

    @Test
    fun `refill fires when the playhead reaches the threshold`() {
        // 4 tracks left after the current one: exactly at the threshold.
        assertTrue(RadioSession.shouldTopUp(currentIndex = 15, queueSize = 20))
    }

    @Test
    fun `refill fires on the last track`() {
        assertTrue(RadioSession.shouldTopUp(currentIndex = 9, queueSize = 10))
    }

    @Test
    fun `no refill for an empty queue or a nonsense index`() {
        assertFalse(RadioSession.shouldTopUp(currentIndex = 0, queueSize = 0))
        assertFalse(RadioSession.shouldTopUp(currentIndex = -1, queueSize = 10))
        assertFalse(RadioSession.shouldTopUp(currentIndex = 10, queueSize = 10))
    }

    @Test
    fun `the threshold is honoured when overridden`() {
        assertTrue(RadioSession.shouldTopUp(currentIndex = 5, queueSize = 10, threshold = 4))
        assertFalse(RadioSession.shouldTopUp(currentIndex = 5, queueSize = 10, threshold = 3))
    }

    // ---- Refill seed -----------------------------------------------------

    @Test
    fun `the next batch is seeded from the most recently played track`() {
        RadioSession.start(RadioSource.Song(song("seed")))
        val station = RadioSession.active.value!!
        val played = listOf(song("a"), song("b"), song("c"))

        assertEquals("c", RadioSession.refillSeed(played, station).videoId)
    }

    @Test
    fun `with no history the batch is seeded from the station seed`() {
        RadioSession.start(RadioSource.Song(song("seed")))
        val station = RadioSession.active.value!!

        assertEquals("seed", RadioSession.refillSeed(emptyList(), station).videoId)
    }

    @Test
    fun `blank ids in the history are skipped for the seed`() {
        RadioSession.start(RadioSource.Song(song("seed")))
        val station = RadioSession.active.value!!
        val played = listOf(song("a"), song("", "Unresolved"))

        assertEquals("a", RadioSession.refillSeed(played, station).videoId)
    }
}
