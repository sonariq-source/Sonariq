package com.music.sonariq

import com.music.sonariq.data.library.StorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The storage screen's numbers: real directory walks and human-readable
 * byte formatting. The media-store branch needs a device and is not covered
 * here; the walk and the formatting are where the logic lives.
 */
class StorageManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(dir: File, name: String, bytes: Int) {
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes))
        }
    }

    @Test
    fun `dirSize walks nested directories`() {
        val root = temp.newFolder("cache")
        file(root, "a.bin", 100)
        val sub = File(root, "sub").apply { mkdir() }
        file(sub, "b.bin", 200)
        file(sub, "c.bin", 300)
        assertEquals(600L, StorageManager.dirSize(root))
    }

    @Test
    fun `dirSizeAndCount counts files not directories`() {
        val root = temp.newFolder("cache2")
        file(root, "a.bin", 10)
        File(root, "sub").apply { mkdir() }
        val (bytes, count) = StorageManager.dirSizeAndCount(root)
        assertEquals(10L, bytes)
        assertEquals(1, count)
    }

    @Test
    fun `dirSize of a missing directory is zero`() {
        assertEquals(0L, StorageManager.dirSize(File(temp.root, "nope")))
    }

    @Test
    fun `deleteContents empties but keeps the directory`() {
        val root = temp.newFolder("cache3")
        file(root, "a.bin", 10)
        val sub = File(root, "sub").apply { mkdir() }
        file(sub, "b.bin", 10)
        StorageManager.deleteContents(root)
        assertTrue(root.isDirectory)
        assertEquals(0L, StorageManager.dirSize(root))
    }

    @Test
    fun `formatBytes uses binary units with one decimal`() {
        assertEquals("0 B", StorageManager.formatBytes(0))
        assertEquals("512 B", StorageManager.formatBytes(512))
        assertEquals("1.0 KB", StorageManager.formatBytes(1024))
        assertEquals("1.5 KB", StorageManager.formatBytes(1536))
        assertEquals("2.0 MB", StorageManager.formatBytes(2L * 1024 * 1024))
        assertEquals("2.2 GB", StorageManager.formatBytes(2_400_000_000L))
    }

    @Test
    fun `clearable bytes are the two caches`() {
        val usage = StorageManager.Usage(
            downloadsBytes = 1_000_000,
            downloadFiles = 10,
            audioCacheBytes = 500_000,
            imageCacheBytes = 250_000,
            artworkBytes = 100_000,
        )
        assertEquals(750_000L, usage.clearableBytes)
        assertEquals(1_850_000L, usage.totalBytes)
    }
}
