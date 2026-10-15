package com.music.sonariq.download

import android.content.Context
import android.os.StatFs
import com.music.sonariq.data.DebugLog as Log
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.data.settings.DownloadQuality
import com.music.sonariq.data.stats.ListeningStats
import com.music.sonariq.data.stats.TrackStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Smart Downloads: the device keeps the listener's most-played tracks on disk
 * without being asked, refreshed once a day.
 *
 * YouTube Music's version of this is the reference, and the shape is the same:
 * a periodic background job looks at what was actually played, picks the top
 * tracks that aren't already on the device, and feeds them through the normal
 * download queue. Nothing here invents a second downloader — every track goes
 * through [Downloads.enqueue], which is the one door into the queue, so
 * Wi-Fi-only, quality, dedup and failure handling all behave exactly as if the
 * listener had tapped each row themselves.
 *
 * Which tracks are "smart" is remembered separately from the download record
 * itself, as a set of video ids in plain preferences. A smart track the user
 * deletes is forgotten as smart (it was their call); a smart track they
 * re-download by hand stays smart, because it still came from the feature.
 */
object SmartDownloads {

    private const val TAG = "Sonariq"
    private const val PREFS = "sonariq_smart_downloads"
    private const val KEY_SMART_IDS = "smart_downloaded_ids"
    private const val KEY_LAST_RUN = "last_run_ms"

    /** Rough bytes one track costs at each download rung, for the space check. */
    private fun bytesPerTrack(quality: DownloadQuality): Long = when (quality) {
        DownloadQuality.LOSSLESS -> 35L * 1024 * 1024
        DownloadQuality.HIGH -> 8L * 1024 * 1024
        DownloadQuality.STANDARD -> 4L * 1024 * 1024
    }

    private lateinit var appContext: Context

    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val _smartIds = MutableStateFlow<Set<String>>(emptySet())
    /** Video ids currently on disk because Smart Downloads put them there. */
    val smartIds: StateFlow<Set<String>> = _smartIds.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        _smartIds.value = prefs.getStringSet(KEY_SMART_IDS, emptySet()).orEmpty().toSet()
    }

    fun isSmartDownloaded(videoId: String): Boolean = videoId in _smartIds.value

    /** Epoch millis of the last refresh attempt, 0 when it never ran. */
    val lastRunMs: Long get() = prefs.getLong(KEY_LAST_RUN, 0L)

    private fun recordRun() {
        prefs.edit().putLong(KEY_LAST_RUN, System.currentTimeMillis()).apply()
    }

    private fun persist(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_SMART_IDS, ids).apply()
    }

    fun markSmartDownloaded(videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        _smartIds.update { current ->
            (current + videoIds).also(::persist)
        }
    }

    fun unmarkSmartDownloaded(videoId: String) {
        _smartIds.update { current ->
            (current - videoId).also(::persist)
        }
    }

    /**
     * Which tracks to fetch, from a listening history and the set of ids
     * already on the device.
     *
     * Pure: no disk, no network, no settings read — everything it needs is a
     * parameter, which is what makes it unit-testable. Ranked by play count,
     * then by time listened, then by recency, so a track with more plays
     * always outranks one with fewer and ties break towards what was
     * listened to longer and more recently. Tracks with no plays are never
     * picked, and neither is anything already downloaded.
     */
    fun selectCandidates(
        history: List<TrackStats>,
        alreadyDownloaded: Set<String>,
        limit: Int,
    ): List<TrackStats> {
        if (limit <= 0) return emptyList()
        return history
            .asSequence()
            .filter { it.plays > 0 }
            .filter { it.id !in alreadyDownloaded }
            .sortedWith(
                compareByDescending<TrackStats> { it.plays }
                    .thenByDescending { it.ms }
                    .thenByDescending { it.lastPlayedMs },
            )
            .distinctBy { it.id }
            .take(limit)
            .toList()
    }

    /**
     * How many of [candidates] fit in the space left on the download volume.
     *
     * A download that cannot finish is worse than one never started — it
     * leaves a failed row and a partial file — so the batch is cut to what
     * fits rather than started blind. Zero when not even one track fits.
     */
    fun fitCount(candidates: Int, usableBytes: Long, quality: DownloadQuality): Int {
        if (candidates <= 0 || usableBytes <= 0) return 0
        val perTrack = bytesPerTrack(quality)
        return (usableBytes / perTrack).toInt().coerceAtMost(candidates).coerceAtLeast(0)
    }

    /** Free bytes on the volume holding the download folder. */
    fun usableBytes(context: Context): Long = runCatching {
        val dir = java.io.File(context.filesDir, "downloads").apply { mkdirs() }
        StatFs(dir.absolutePath).let { it.availableBlocksLong * it.blockSizeLong }
    }.getOrDefault(0L)

    /**
     * One refresh: pick the current top tracks and queue the ones that fit.
     *
     * Safe to call from anywhere — the worker, the Settings "refresh now"
     * row, the Smart Downloads page. Respects the feature toggle and the
     * Wi-Fi-only download setting through [Downloads.enqueue]'s own check,
     * so a refresh on mobile data with Wi-Fi-only on queues nothing and
     * fails nothing.
     *
     * @return how many tracks were queued.
     */
    suspend fun refresh(context: Context): Int = withContext(Dispatchers.IO) {
        if (!AppSettings.smartDownloadsEnabled.value) return@withContext 0
        if (!AppSettings.downloadsAllowedNow) {
            // The manual "refresh now" on mobile data with Wi-Fi-only on, or
            // the worker before its constraints are met. enqueue() would
            // refuse each track; refusing the batch is the honest version.
            Log.d(TAG, "smart downloads: downloads not allowed right now, skipping")
            return@withContext 0
        }
        recordRun()
        val limit = AppSettings.smartDownloadsCount.value.coerceIn(1, 100)
        val history = runCatching { ListeningStats.allTracks() }.getOrDefault(emptyList())
        val alreadyDownloaded = Downloads.saved.value.keys
        val candidates = selectCandidates(history, alreadyDownloaded, limit)
        if (candidates.isEmpty()) {
            Log.d(TAG, "smart downloads: nothing new in the top $limit")
            return@withContext 0
        }
        val quality = AppSettings.downloadQuality.value
        val fits = fitCount(candidates.size, usableBytes(context), quality)
        if (fits <= 0) {
            Log.w(TAG, "smart downloads: not enough free space for one track, skipping")
            return@withContext 0
        }
        val batch = candidates.take(fits)
        var queued = 0
        for (track in batch) {
            // enqueue() is the one door: dedup, Wi-Fi-only and the service
            // start all live inside it, so a track that can't go now simply
            // doesn't, without failing the rest of the batch.
            runCatching {
                Downloads.enqueue(context, track.toSong(), from = SMART_DOWNLOADS_FROM)
                queued++
            }.onFailure {
                Log.w(TAG, "smart downloads: could not queue ${track.id}: ${it.message}")
            }
        }
        if (queued > 0) markSmartDownloaded(batch.take(queued).map { it.id })
        Log.d(TAG, "smart downloads: queued $queued of ${candidates.size} candidates")
        queued
    }

    /**
     * The downloaded tracks that Smart Downloads put on the device, for the
     * Smart Downloads page. A smart track the user deleted is gone from the
     * download record and drops out here too — the remembered id alone is
     * not a song.
     */
    suspend fun getSmartDownloadedSongs(context: Context): List<Song> {
        val smart = smartIds.value
        if (smart.isEmpty()) return emptyList()
        return Downloads.getDownloadedSongs(context).filter { it.videoId in smart }
    }

    /**
     * (Re)schedules the daily refresh job, or cancels it.
     *
     * Called from the settings toggle and once at startup, so the job follows
     * the switch even across restarts. No-op before [init].
     */
    fun applySchedule() {
        if (!this::appContext.isInitialized) return
        if (AppSettings.smartDownloadsEnabled.value) {
            SmartDownloadWorker.schedule(appContext)
        } else {
            SmartDownloadWorker.cancel(appContext)
        }
    }

    /** Estimated bytes the current settings would keep on disk, for the UI. */
    fun estimatedBytes(): Long {
        val count = AppSettings.smartDownloadsCount.value.coerceIn(1, 100)
        return count * bytesPerTrack(AppSettings.downloadQuality.value)
    }

    internal const val SMART_DOWNLOADS_FROM = "smart-downloads"

    /**
     * Human "≈ N MB/GB" for the settings row. Pure, so the formatting is
     * pinned by a test rather than eyeballed.
     */
    fun formatBytes(bytes: Long): String {
        val abs = kotlin.math.abs(bytes)
        return when {
            abs < 1024 -> "$bytes B"
            abs < 1024 * 1024 -> "${"%.0f".format(Locale.ROOT, bytes / 1024.0)} KB"
            abs < 1024 * 1024 * 1024 -> "${"%.0f".format(Locale.ROOT, bytes / (1024.0 * 1024))} MB"
            else -> "${"%.1f".format(Locale.ROOT, bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }
}
