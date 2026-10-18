package com.music.sonariq.data.library

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import coil3.SingletonImageLoader
import com.music.sonariq.data.DebugLog as Log
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.download.DownloadStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What Sonariq is keeping on disk, measured — not estimated.
 *
 * Every figure here is walked or queried, never guessed:
 *  - **Downloads** — the private `downloads` folder walked, or, when downloads
 *    are exported to the shared Music folder, the media store's `SIZE` column
 *    summed over this app's folder. The two disagree on nothing because they
 *    are the same files asked two different ways.
 *  - **Audio cache** — the streaming cache in `cache/audio`, walked.
 *  - **Image cache** — the cover-art disk cache in `cache/image_cache`, walked.
 *  - **Artwork** — collection covers persisted in `files/download-artwork`, walked.
 *
 * One-tap clear touches only what re-fetches itself: the audio and image
 * caches. Downloads and saved artwork are the user's files and are never in
 * the blast radius.
 */
object StorageManager {

    private const val TAG = "Sonariq"

    /** Every number on the storage screen, in bytes, plus the file counts. */
    data class Usage(
        val downloadsBytes: Long,
        val downloadFiles: Int,
        val audioCacheBytes: Long,
        val imageCacheBytes: Long,
        val artworkBytes: Long,
        /** audio + image cache: everything [clearCaches] removes. */
        val clearableBytes: Long get() = audioCacheBytes + imageCacheBytes,
        val totalBytes: Long get() = downloadsBytes + audioCacheBytes + imageCacheBytes + artworkBytes,
    )

    /** What [clearCaches] removed, in bytes, for the confirmation line. */
    data class Cleared(val audioCacheBytes: Long, val imageCacheBytes: Long) {
        val totalBytes: Long get() = audioCacheBytes + imageCacheBytes
    }

    /**
     * Measure everything. Media-store queries and directory walks are IO;
     * callers stay off the main thread.
     */
    suspend fun measure(context: Context): Usage = withContext(Dispatchers.IO) {
        val downloadsBytes: Long
        val downloadFiles: Int
        if (AppSettings.exportDownloads.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (bytes, count) = mediaStoreDownloads(context)
            downloadsBytes = bytes
            downloadFiles = count
        } else {
            val dir = File(context.filesDir, "downloads")
            val (bytes, count) = dirSizeAndCount(dir)
            downloadsBytes = bytes
            downloadFiles = count
        }
        val audioCacheBytes = dirSize(File(context.cacheDir, "audio"))
        val imageCacheBytes = dirSize(File(context.cacheDir, "image_cache"))
        val artworkBytes = dirSize(File(context.filesDir, "download-artwork"))
        Usage(
            downloadsBytes = downloadsBytes,
            downloadFiles = downloadFiles,
            audioCacheBytes = audioCacheBytes,
            imageCacheBytes = imageCacheBytes,
            artworkBytes = artworkBytes,
        )
    }

    /**
     * Clear the audio and image caches. Downloads and saved artwork are left
     * alone — see the class doc for why.
     *
     * @return what was removed, measured before deletion so the number is real.
     */
    suspend fun clearCaches(context: Context): Cleared = withContext(Dispatchers.IO) {
        val audioDir = File(context.cacheDir, "audio")
        val imageDir = File(context.cacheDir, "image_cache")
        val audioBytes = dirSize(audioDir)
        val imageBytes = dirSize(imageDir)
        deleteContents(audioDir)
        deleteContents(imageDir)
        // Belt and suspenders: Coil's disk cache lives in image_cache, but its
        // journal may hold entries for files already gone.
        runCatching { SingletonImageLoader.get(context).diskCache?.clear() }
            .onFailure { Log.w(TAG, "could not clear image disk cache: ${it.message}") }
        Cleared(audioCacheBytes = audioBytes, imageCacheBytes = imageBytes)
    }

    /** Sum of `SIZE` over this app's folder in the shared Music collection. */
    private fun mediaStoreDownloads(context: Context): Pair<Long, Int> = runCatching {
        var bytes = 0L
        var count = 0
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("%${DownloadStore.FOLDER}%"),
            null,
        )?.use { cursor ->
            val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                if (sizeIdx >= 0) bytes += cursor.getLong(sizeIdx)
                count++
            }
        }
        bytes to count
    }.onFailure { Log.w(TAG, "could not measure shared downloads: ${it.message}") }
        .getOrDefault(0L to 0)

    /**
     * The bytes under [dir], walked for real. Symlinks are not followed, and
     * an unreadable entry contributes nothing rather than failing the whole
     * measurement.
     */
    fun dirSize(dir: File): Long = dirSizeAndCount(dir).first

    fun dirSizeAndCount(dir: File): Pair<Long, Int> {
        var bytes = 0L
        var count = 0
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = runCatching { current.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (runCatching { java.nio.file.Files.isSymbolicLink(child.toPath()) }.getOrDefault(false)) continue
                if (child.isDirectory) {
                    stack.add(child)
                } else if (child.isFile) {
                    bytes += runCatching { child.length() }.getOrDefault(0L)
                    count++
                }
            }
        }
        return bytes to count
    }

    /** Empties [dir] but keeps the directory itself — caches recreate it. */
    fun deleteContents(dir: File) {
        runCatching { dir.listFiles() }.getOrNull()?.forEach { child ->
            runCatching {
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }.onFailure { Log.w(TAG, "could not delete ${child.name}: ${it.message}") }
        }
    }

    /**
     * `1536` -> `1.5 KB`, `2_400_000_000` -> `2.2 GB`. Binary units, one
     * decimal, because these are disk numbers.
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "${bytes} B"
        else "%.1f %s".format(value, units[unit])
    }
}
