package com.music.sonariq.data.admin

import android.content.Context
import android.os.StatFs
import com.music.sonariq.data.lyrics.LyricsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Measured administration numbers for this device: the lyrics disk cache
 * scanned entry by entry, and the device's own storage. Nothing here is
 * estimated or invented — a cache entry is classified by the timing value
 * its stored payload actually carries, and storage comes from the
 * filesystem itself.
 */
object AdminStats {

    /** Mirrors the lyrics engine's on-disk layout; see LyricsRepository. */
    private const val LYRICS_CACHE_DIRECTORY = "lyrics_v1"

    data class LyricsCacheStats(
        val entries: Int,
        val wordSynced: Int,
        /** Word timings measured from the track's own audio by the aligner. */
        val aiMeasured: Int,
        val lineOnly: Int,
        val unclassified: Int,
        /** Remembered lookups that found no lyrics; retried by the engine. */
        val misses: Int,
        val bytes: Long,
    )

    private val timingPattern = Regex("\"timing\"\\s*:\\s*\"([A-Z_]+)\"")

    /**
     * Counts the lyrics cache. Each entry's payload is a gzip JSON document
     * whose timing field sits at its head, so classification reads only the
     * first chunk of each file rather than whole lyrics.
     */
    suspend fun lyricsCacheStats(): LyricsCacheStats = withContext(Dispatchers.IO) {
        val root = LyricsRepository.cacheDir
            ?: return@withContext LyricsCacheStats(0, 0, 0, 0, 0, 0, 0)
        val directory = File(root, LYRICS_CACHE_DIRECTORY)
        val files = directory.listFiles().orEmpty()
        var word = 0
        var ai = 0
        var line = 0
        var other = 0
        var misses = 0
        var bytes = 0L
        var entries = 0
        for (file in files) {
            bytes += file.length()
            when {
                file.name.endsWith(".miss") -> misses++
                file.name.endsWith(".json.gz") -> {
                    entries++
                    when (timingOf(file)) {
                        "WORD_SYNCED" -> word++
                        "WORD_SYNCED_AI_MEASURED" -> ai++
                        // PLAIN carries no timings; ESTIMATED is a legacy
                        // class the engine strips back to line timing on
                        // read, so both count as line-level here.
                        "LINE_SYNCED", "PLAIN", "WORD_SYNCED_ESTIMATED" -> line++
                        else -> other++
                    }
                }
            }
        }
        LyricsCacheStats(entries, word, ai, line, other, misses, bytes)
    }

    /** The timing class one cache entry declares, or null when unreadable. */
    private fun timingOf(file: File): String? = runCatching {
        GZIPInputStream(file.inputStream()).bufferedReader().use { reader ->
            val buffer = CharArray(2048)
            val read = reader.read(buffer)
            if (read <= 0) null
            else timingPattern.find(String(buffer, 0, read))?.groupValues?.get(1)
        }
    }.getOrNull()

    /**
     * Deletes every cached lyrics entry and remembered miss. The engine
     * simply fetches again on the next play, so this is safe; returns how
     * many files were removed.
     */
    suspend fun clearLyricsCache(): Int = withContext(Dispatchers.IO) {
        val root = LyricsRepository.cacheDir ?: return@withContext 0
        val directory = File(root, LYRICS_CACHE_DIRECTORY)
        var removed = 0
        for (file in directory.listFiles().orEmpty()) {
            if (file.isFile && file.delete()) removed++
        }
        removed
    }

    data class DeviceStorage(val freeBytes: Long, val totalBytes: Long)

    fun deviceStorage(context: Context): DeviceStorage {
        val stat = StatFs(context.filesDir.path)
        return DeviceStorage(
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
        )
    }
}
