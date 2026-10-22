package com.music.sonariq.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.sonariq.data.DebugLog as Log
import com.music.sonariq.data.library.AutoDownloads
import com.music.sonariq.data.lyrics.LyricsRepository
import com.music.sonariq.data.lyrics.LyricsSource
import com.music.sonariq.data.lyrics.PrefetchCandidate
import com.music.sonariq.data.lyrics.planWarmup
import com.music.sonariq.data.model.durationMillis
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.data.stats.ListeningStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * The daily lyrics warmup, run by WorkManager.
 *
 * Fetches lyrics for liked songs and heavy-rotation tracks so the panel opens
 * with words already on it, even when the connection is poor later. The
 * fetches go through [LyricsRepository.lyrics], so the shared disk cache is
 * the only thing that fills — no new state, no new storage path.
 *
 * A miss is ordinary here (unreleased demos, live bootlegs, regional tracks
 * with no catalogued lyrics), and a miss costs nothing past one lookup
 * because [planWarmup] drops anything already cached before the first
 * request. Failures answer with success: the next daily run is the retry.
 * Only an unexpected exception asks WorkManager to try again.
 */
class LyricsWarmupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!AppSettings.lyricsPrefetch.value || !AppSettings.syncedLyrics.value) {
            Log.d(TAG, "lyrics warmup: feature off, nothing to do")
            return Result.success()
        }
        val sources = AppSettings.lyricsSources.value
        if (sources.isEmpty()) {
            Log.d(TAG, "lyrics warmup: no sources enabled, nothing to do")
            return Result.success()
        }
        return try {
            val warmed = warmNow(sources)
            Log.d(TAG, "lyrics warmup: warmed $warmed tracks")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "lyrics warmup failed: ${e.message}", e)
            Result.retry()
        }
    }

    /**
     * Builds the warmup list and fetches it, two requests at a time.
     *
     * Candidates come from the liked snapshot first and then from the
     * listening stats — most recent and most played, deduped by video id —
     * with anything already on disk dropped before the first request. Local
     * files are skipped: their lyrics are read from the file's own tags at
     * play time, so asking a service for them is wasted traffic. Never
     * throws for anything but cancellation.
     */
    private suspend fun warmNow(sources: Set<LyricsSource>): Int {
        val liked = AutoDownloads.likedSnapshot(applicationContext)
            .filter { it.localUri == null && it.videoId.isNotBlank() }
            .map { song ->
                PrefetchCandidate(
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.artist,
                    durationMs = song.durationMillis(),
                    album = song.albumName,
                )
            }
        val tracks = ListeningStats.allTracks()
        val byRecency = tracks.filter { it.lastPlayedMs > 0 }
            .sortedByDescending { it.lastPlayedMs }
        val byPlays = tracks.filter { it.plays > 0 }
            .sortedByDescending { it.plays }
        // TrackStats carries no track length — ms is lifetime listened time —
        // so the lookup runs on title and artist alone, as it does for the
        // current track when playback knows no duration yet.
        val stats = (byRecency + byPlays).distinctBy { it.id }
            .map { track ->
                PrefetchCandidate(
                    videoId = track.id,
                    title = track.title,
                    artist = track.artist,
                    durationMs = 0L,
                    album = track.album,
                )
            }
        // [LyricsRepository.hasFinalLyrics] is suspend and the planner's
        // isCached probe is not, so the cache check happens here, up front,
        // and the planner sees only what still needs a lookup.
        val uncachedLiked = liked.filter {
            !LyricsRepository.hasFinalLyrics(it.videoId, null) &&
                !LyricsRepository.isKnownMiss(it.videoId)
        }
        val uncachedStats = stats.filter {
            !LyricsRepository.hasFinalLyrics(it.videoId, null) &&
                !LyricsRepository.isKnownMiss(it.videoId)
        }
        val plan = planWarmup(uncachedLiked, uncachedStats, cap = WARMUP_CAP) { false }
        if (plan.isEmpty()) return 0
        val order = AppSettings.lyricsSourceOrder.value
        val prioritize = AppSettings.prioritizeSyllableSync.value
        val semaphore = Semaphore(2)
        val warmedCount = java.util.concurrent.atomic.AtomicInteger(0)
        coroutineScope {
            plan.forEachIndexed { index, candidate ->
                if (index > 0) delay(LAUNCH_SPACING_MS)
                launch {
                    semaphore.withPermit {
                        try {
                            val result = withTimeoutOrNull(ITEM_TIMEOUT_MS) {
                                LyricsRepository.lyrics(
                                    videoId = candidate.videoId,
                                    title = candidate.title,
                                    artist = candidate.artist,
                                    durationMs = candidate.durationMs,
                                    album = candidate.album,
                                    sources = sources,
                                    order = order,
                                    prioritizeSyllableSync = prioritize,
                                )
                            }
                            if (result != null) {
                                warmedCount.incrementAndGet()
                                // A warmed result that is only line-synced
                                // is offered for AI word sync, as a
                                // background job: fire-and-forget, with the
                                // dedupe and Wi-Fi-only gates inside
                                // AiWordSync deciding whether it runs.
                                if (result.isAiWordSyncCandidate()) {
                                    AiWordSync.maybeAlign(
                                        videoId = candidate.videoId,
                                        title = candidate.title,
                                        artist = candidate.artist,
                                        durationMs = candidate.durationMs,
                                        immediate = false,
                                    )
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.d(TAG, "lyrics warmup skipped for ${candidate.videoId}: ${e.message}")
                        }
                    }
                }
            }
        }
        return warmedCount.get()
    }

    companion object {
        private const val TAG = "Sonariq"
        private const val WORK_NAME = "lyrics_warmup"

        /** The most tracks one daily run asks for — a heavy rotation, not a library. */
        private const val WARMUP_CAP = 300

        /** Stagger launches so the whole list doesn't stampede the network at once. */
        private const val LAUNCH_SPACING_MS = 300L

        /** Bound one slow service the same way per-track prefetch does. */
        private const val ITEM_TIMEOUT_MS = 60_000L

        /**
         * Unmetered network and nothing else: the spec is Wi-Fi preferred,
         * charging not required. The battery checks belong on bulk audio
         * jobs — this one is kilobytes of text.
         */
        fun constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LyricsWarmupWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints())
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            Log.d(TAG, "lyrics warmup: daily job scheduled")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "lyrics warmup: daily job cancelled")
        }
    }
}

/**
 * Owns the warmup job the way [SmartDownloads] owns its own: a context held
 * from [init], and [applySchedule] called once at startup and from the
 * settings toggle, so the job follows the switch even across restarts.
 * No-op before [init].
 */
object LyricsWarmup {

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun applySchedule() {
        val context = appContext ?: return
        if (AppSettings.lyricsPrefetch.value) {
            LyricsWarmupWorker.schedule(context)
        } else {
            LyricsWarmupWorker.cancel(context)
        }
    }
}
