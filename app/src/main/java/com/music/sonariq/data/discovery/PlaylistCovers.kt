package com.music.sonariq.data.discovery

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Custom covers for playlists, made by the cover maker and kept on this device.
 *
 * ## Why local rather than uploaded
 *
 * The catalogue offers no cover-upload call this app can make — a playlist's
 * artwork upstream is derived from its entries. So a custom cover is a local
 * override: a PNG filed under the playlist's browse id, which the UI prefers
 * over the derived artwork wherever it draws that playlist. The override is
 * per-playlist and survives restarts; removing it restores the derived art.
 *
 * The browse id is the key because it is the one identity every playlist has
 * here: `VL…` for catalogue playlists, `local:playlist:…` for on-device ones.
 */
object PlaylistCovers {

    private const val TAG = "SonariqCovers"
    private const val FOLDER = "playlist_covers"

    private lateinit var folder: File

    /**
     * Browse ids with a custom cover, mapped to the file's last-modified time.
     *
     * The timestamp is the revision: the file's bytes are read on demand, and
     * the UI collects this to know when to re-read.
     *
     * Backed by a small JSON index beside the PNGs, because the filename is
     * a sanitised browse id and sanitising is lossy — the index is what maps
     * a playlist back to its file across restarts.
     */
    private val _covers = MutableStateFlow<Map<String, Long>>(emptyMap())
    val covers: StateFlow<Map<String, Long>> = _covers.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val ready: Boolean get() = this::folder.isInitialized

    fun init(context: Context) {
        folder = File(context.filesDir, FOLDER).apply { mkdirs() }
        scope.launch { _covers.value = readIndex() }
    }

    /** The custom cover for [playlistBrowseId], or null when there is none. */
    fun coverFile(playlistBrowseId: String): File? {
        if (!ready) return null
        if (playlistBrowseId !in _covers.value) return null
        return File(folder, coverFileName(playlistBrowseId)).takeIf { it.exists() }
    }

    /**
     * Saves [bitmap] as the cover for [playlistBrowseId].
     *
     * The bitmap is re-encoded as PNG at [COVER_SIZE_PX] so what is stored is
     * always the finished drawing at full resolution, never a thumbnail of
     * the preview. Returns the written file, or null when the write failed.
     */
    suspend fun save(playlistBrowseId: String, bitmap: Bitmap): File? =
        withContext(Dispatchers.IO) {
            if (!ready) return@withContext null
            runCatching {
                folder.mkdirs()
                val file = File(folder, coverFileName(playlistBrowseId))
                val square = if (bitmap.width == COVER_SIZE_PX && bitmap.height == COVER_SIZE_PX) {
                    bitmap
                } else {
                    Bitmap.createScaledBitmap(bitmap, COVER_SIZE_PX, COVER_SIZE_PX, true)
                }
                FileOutputStream(file).use { out ->
                    if (!square.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        error("PNG encode failed")
                    }
                }
                if (square !== bitmap) square.recycle()
                val updated = _covers.value + (playlistBrowseId to file.lastModified())
                _covers.value = updated
                writeIndex(updated)
                file
            }.onFailure { Log.w(TAG, "Could not save cover for $playlistBrowseId", it) }
                .getOrNull()
        }

    /** Removes the custom cover; the playlist falls back to its derived art. */
    fun remove(playlistBrowseId: String) {
        if (!ready) return
        scope.launch {
            runCatching { File(folder, coverFileName(playlistBrowseId)).delete() }
            val updated = _covers.value - playlistBrowseId
            _covers.value = updated
            writeIndex(updated)
        }
    }

    private fun indexFile(): File = File(folder, "covers_index.json")

    private fun readIndex(): Map<String, Long> {
        val file = indexFile()
        if (!file.exists()) return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(file.readText())
            buildMap {
                obj.keys().forEach { key ->
                    val png = File(folder, coverFileName(key))
                    if (png.exists()) put(key, png.lastModified())
                }
            }
        }.onFailure { Log.w(TAG, "Discarding unreadable cover index", it) }
            .getOrDefault(emptyMap())
    }

    private fun writeIndex(covers: Map<String, Long>) {
        runCatching {
            val obj = org.json.JSONObject()
            covers.keys.forEach { key -> obj.put(key, coverFileName(key)) }
            indexFile().writeText(obj.toString())
        }.onFailure { Log.w(TAG, "Could not write cover index", it) }
    }
}
