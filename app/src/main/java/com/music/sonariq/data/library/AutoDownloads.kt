package com.music.sonariq.data.library

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.sonariq.data.DebugLog as Log
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.data.spotify.LocalPlaylistStore
import com.music.sonariq.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Downloads that happen on their own: liked songs, and the playlists the user
 * picked, kept on the device without being asked every time.
 *
 * The liked list is a snapshot, and that needs saying plainly. Liked songs
 * live in the account's library; there is no local liked-songs store this
 * worker can read at 3am with no UI state. So [snapshotLiked] persists the
 * liked list whenever it changes (wired at the like/unlike path — see the
 * integration manifest), and the worker downloads from that snapshot. A liked
 * song is therefore downloaded at most one refresh late, which is the honest
 * cost of doing this in the background.
 *
 * Everything actually downloaded goes through [Downloads.enqueue], which is
 * where the Wi-Fi-only refusal lives — this file does not reimplement
 * connection policy, it just asks.
 */
object AutoDownloads {

    private const val TAG = "Sonariq"
    private const val PREF_NAME = "sonariq_auto_downloads"
    private const val KEY_LIKED_SNAPSHOT = "liked_snapshot_json"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Persist the current liked songs for the background worker. Called from
     * the like/unlike path; the worker never reads UI state.
     */
    fun snapshotLiked(context: Context, songs: List<Song>) {
        runCatching {
            val stored = songs.map { LibraryBackup.StoredSong(
                videoId = it.videoId,
                title = it.title,
                artist = it.artist,
                thumbnailUrl = it.thumbnailUrl,
                durationText = it.durationText,
                artistId = it.artistId,
                albumId = it.albumId,
                albumName = it.albumName,
                isVideo = it.isVideo,
            ) }
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LIKED_SNAPSHOT, json.encodeToString(
                    ListSerializer(LibraryBackup.StoredSong.serializer()), stored))
                .apply()
        }.onFailure { Log.w(TAG, "could not snapshot liked songs: ${it.message}") }
    }

    /** The liked songs as last snapshotted, or empty when never snapshotted. */
    fun likedSnapshot(context: Context): List<Song> = runCatching {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LIKED_SNAPSHOT, null) ?: return emptyList()
        json.decodeFromString(ListSerializer(LibraryBackup.StoredSong.serializer()), raw)
            .map { it.toSong() }
    }.onFailure { Log.w(TAG, "could not read liked snapshot: ${it.message}") }
        .getOrDefault(emptyList())

    /**
     * Everything that should be on the device and isn't yet: the liked
     * snapshot plus every song of every chosen playlist, minus tracks already
     * downloaded. Pure over its inputs so the selection is unit-testable; the
     * IO (reading the snapshot, the playlists, the download record) stays in
     * [collectTargets].
     */
    fun selectTargets(
        liked: List<Song>,
        playlistSongs: List<Song>,
        downloadedVideoIds: Set<String>,
    ): List<Song> = (liked + playlistSongs)
        .distinctBy { it.videoId }
        .filter { it.videoId.isNotBlank() && it.videoId !in downloadedVideoIds }

    /**
     * The songs to queue right now, honouring the feature switches.
     *
     * Chosen playlists are read live from the playlist store rather than
     * snapshotted: a playlist edited at noon is downloaded as edited at night.
     * Playlist ids that no longer exist are skipped, not failed on.
     */
    suspend fun collectTargets(context: Context): List<Song> = withContext(Dispatchers.IO) {
        if (!AppSettings.autoDownloadsEnabled.value) return@withContext emptyList()
        val liked = if (AppSettings.autoDownloadLikedSongs.value) likedSnapshot(context) else emptyList()
        val playlistSongs = AppSettings.autoDownloadPlaylistIds.value.flatMap { id ->
            LocalPlaylistStore.getPlaylist(id)?.songs ?: emptyList()
        }
        val downloaded = Downloads.saved.value.keys
        selectTargets(liked, playlistSongs, downloaded)
    }

    /**
     * Queue [songs] for download. Each goes through [Downloads.enqueue], so a
     * connection that doesn't allow downloads refuses each one individually
     * rather than this call pretending the batch was accepted.
     */
    fun enqueueAll(context: Context, songs: List<Song>, from: String = "auto-downloads") {
        songs.forEach { Downloads.enqueue(context, it, from) }
    }

    fun schedule(context: Context) = AutoDownloadWorker.schedule(context)

    fun cancel(context: Context) = AutoDownloadWorker.cancel(context)
}

/**
 * The daily auto-downloads refresh, run by WorkManager.
 *
 * Same shape as the Smart Downloads worker on purpose: periodic rather than
 * exact (the point is "the songs are on the device", not a time), unmetered
 * network when the Wi-Fi-only switch is on, charging and a non-low battery
 * because this is a bulk job. Failures are ordinary and answered with
 * success — the next daily run is the retry.
 */
class AutoDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!AppSettings.autoDownloadsEnabled.value) {
            Log.d(TAG, "auto-downloads worker: feature off, nothing to do")
            return Result.success()
        }
        return try {
            val targets = AutoDownloads.collectTargets(applicationContext)
            AutoDownloads.enqueueAll(applicationContext, targets)
            Log.d(TAG, "auto-downloads worker: queued ${targets.size} tracks")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "auto-downloads worker failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "Sonariq"
        private const val WORK_NAME = "auto_downloads_refresh"

        fun constraints(): Constraints {
            val network = if (AppSettings.autoDownloadWifiOnly.value) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }
            return Constraints.Builder()
                .setRequiredNetworkType(network)
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoDownloadWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints())
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            Log.d(TAG, "auto-downloads: daily refresh scheduled")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "auto-downloads: daily refresh cancelled")
        }
    }
}
