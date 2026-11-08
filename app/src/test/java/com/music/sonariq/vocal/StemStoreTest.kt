package com.music.sonariq.vocal

import com.music.sonariq.playback.smart.StereoStems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [StemStore]: key hashing is stable (and is plain SHA-256), the
 * MDX tier is preferred when both exist, and eviction removes the oldest
 * stems first, down to the cap, while sparing files open for playback.
 *
 * The store is a process-wide object, so every test re-points it at its own
 * temp directory in [setUp] — nothing here depends on another test's state.
 */
class StemStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(folder.root, "stems")
        StemStore.initForTest(dir, cap = StemStore.DEFAULT_CAP_BYTES)
    }

    private fun stems(frames: Int): StereoStems =
        StereoStems(FloatArray(frames) { 0.25f }, FloatArray(frames) { -0.25f }, 44_100.0)

    @Test
    fun `key hashing is stable sha-256`() {
        // The well-known SHA-256 of "abc": the store's names are this hash.
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(expected, stemKeyHash("abc"))
        assertEquals(expected, stemKeyHash("abc"))
        assertTrue(stemKeyHash("abd") != expected)
    }

    @Test
    fun `written stems are named by the key hash`() {
        assertTrue(StemStore.writeStem("abc", StemTier.UMX, stems(16)))
        val file = File(dir, "${stemKeyHash("abc")}.umx.wav")
        assertTrue(file.isFile)
        assertEquals(StemTier.UMX, StemStore.tierOf("abc"))
        assertEquals(file, StemStore.stemFile("abc"))
        assertEquals(StemState.Ready(StemTier.UMX), StemStore.states.value["abc"])
    }

    @Test
    fun `mdx is preferred over umx when both tiers exist`() {
        assertTrue(StemStore.writeStem("song", StemTier.UMX, stems(16)))
        assertTrue(StemStore.writeStem("song", StemTier.MDX, stems(16)))
        assertEquals(StemTier.MDX, StemStore.tierOf("song"))
        assertTrue(StemStore.stemFile("song")!!.name.endsWith(".mdx.wav"))
        assertEquals(StemState.Ready(StemTier.MDX), StemStore.states.value["song"])
    }

    @Test
    fun `eviction removes the oldest first and respects the cap`() {
        // One stem of 1_000 frames is 4_044 bytes on disk; a cap of 5_000
        // holds exactly one of them.
        StemStore.initForTest(dir, cap = 5_000L)
        assertTrue(StemStore.writeStem("oldest", StemTier.UMX, stems(1_000)))
        val oldest = StemStore.stemFile("oldest")!!
        assertTrue(oldest.setLastModified(1_000L))
        // Writing the second stem triggers eviction inside writeStem.
        assertTrue(StemStore.writeStem("newest", StemTier.UMX, stems(1_000)))
        assertFalse(oldest.exists())
        assertTrue(StemStore.stemFile("newest")!!.isFile)
        assertTrue(StemStore.totalSizeBytes() <= 5_000L)
        assertEquals(null, StemStore.tierOf("oldest"))
    }

    @Test
    fun `eviction never removes a stem open for playback`() {
        // Both stems written under the generous default cap first (a write
        // under the tight cap would evict as it lands), then the store is
        // re-pointed at the same directory with a one-stem cap.
        assertTrue(StemStore.writeStem("playing", StemTier.UMX, stems(1_000)))
        val playing = StemStore.stemFile("playing")!!
        assertTrue(playing.setLastModified(1_000L))
        assertTrue(StemStore.writeStem("other", StemTier.UMX, stems(1_000)))
        val other = StemStore.stemFile("other")!!
        assertTrue(other.setLastModified(2_000L))

        StemStore.initForTest(dir, cap = 5_000L)
        StemStore.registerOpen(playing)
        try {
            StemStore.evictIfNeeded()
            // Over the cap with both files present, the open one survives
            // and the closed one — though newer — is the one evicted.
            assertTrue(playing.isFile)
            assertFalse(other.exists())
        } finally {
            StemStore.unregisterOpen(playing)
        }
    }

    @Test
    fun `clearAll removes stems and their ready states`() {
        assertTrue(StemStore.writeStem("one", StemTier.UMX, stems(8)))
        assertTrue(StemStore.writeStem("two", StemTier.MDX, stems(8)))
        StemStore.clearAll()
        assertEquals(0L, StemStore.totalSizeBytes())
        assertEquals(null, StemStore.tierOf("one"))
        assertTrue(StemStore.states.value["one"] !is StemState.Ready)
    }
}
