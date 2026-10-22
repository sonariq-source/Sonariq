package com.music.sonariq.download

import com.music.sonariq.data.DebugLog as Log
import com.music.sonariq.data.lyrics.LyricsRepository
import com.music.sonariq.data.lyrics.PrefetchCandidate
import com.music.sonariq.data.lyrics.planListPrefetch
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.model.durationMillis
import com.music.sonariq.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Warms the lyrics cache for tracks that are merely on screen — a Home
 * shelf, a built moment station, a playlist or album page — so playing one
 * of them later opens the lyrics panel with the words already there.
 *
 * Everything goes through [LyricsRepository.lyrics], so the disk cache is
 * the only state this fills, tracks already cached or remembered as
 * genuine misses are never re-asked, and a race the player (or another
 * prefetch) already started for a track is joined rather than duplicated.
 * Runs as a child of the caller's scope: leaving the screen cancels
 * whatever has not started, while anything already racing finishes inside
 * the repository and still lands in the cache.
 *
 * Silent and best-effort, on the same terms as the queue prefetch: the
 * lyrics prefetch setting gates it, two lookups run at a time, and a
 * failure simply leaves that track to fetch at play time as before.
 */
object LyricsListPrefetch {

    private const val TAG = "Sonariq"
    private const val ITEM_TIMEOUT_MS = 60_000L

    suspend fun prefetch(songs: List<Song>) {
        if (!AppSettings.lyricsPrefetch.value || !AppSettings.syncedLyrics.value) return
        val sources = AppSettings.lyricsSources.value
        if (sources.isEmpty()) return
        val candidates = songs
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
        if (candidates.isEmpty()) return
        // The cache and miss checks are suspend and the planner's probe is
        // not, so they happen up front, as in the queue prefetch.
        val uncached = candidates.filter { candidate ->
            LyricsRepository.readCache(candidate.videoId, null) == null &&
                !LyricsRepository.isKnownMiss(candidate.videoId)
        }
        val plan = planListPrefetch(uncached) { false }
        if (plan.isEmpty()) return
        val order = AppSettings.lyricsSourceOrder.value
        val prioritize = AppSettings.prioritizeSyllableSync.value
        val semaphore = Semaphore(2)
        coroutineScope {
            plan.forEach { candidate ->
                launch {
                    semaphore.withPermit {
                        try {
                            withTimeoutOrNull(ITEM_TIMEOUT_MS) {
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
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.d(TAG, "list lyrics prefetch skipped for ${candidate.videoId}: ${e.message}")
                        }
                    }
                }
            }
        }
    }
}
