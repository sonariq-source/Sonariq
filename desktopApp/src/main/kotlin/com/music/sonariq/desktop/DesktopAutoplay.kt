package com.music.sonariq.desktop

import com.music.sonariq.data.model.QueueTier
import com.music.sonariq.data.model.SearchFilter
import com.music.sonariq.data.model.SearchResult
import com.music.sonariq.data.model.Song
import com.music.sonariq.playback.AutoplayFreshness
import com.music.sonariq.playback.QueueBuilder
import java.util.prefs.Preferences

/** Most Autoplay-suggested tracks kept queued ahead of the current one at once. */
const val MAX_QUEUED_AUTOPLAY = 10

/** The station that keeps playing when the queue runs out. */
internal object DesktopAutoplay {

    /** The same deterministic party supplier election used by the phone. */
    fun supplierId(party: DesktopListenTogether.State): String? {
        if (!party.inParty) return null
        val host = party.members.firstOrNull { it.isHost && it.connected }?.memberId
        if (party.hostOnlyControl) return host
        return host ?: party.members.asSequence()
            .filter { it.connected }
            .minByOrNull { it.memberId }
            ?.memberId
    }

    fun enabled(party: DesktopListenTogether.State, localPreference: Boolean): Boolean =
        if (party.inParty) party.playback.autoplayEnabled else localPreference

    /** Same re-arm rule as the phone when a queue edit exposes the current track as the tail. */
    fun queueNeedsRefresh(
        enabled: Boolean,
        repeatAll: Boolean,
        currentIndex: Int,
        itemCount: Int,
        loadInProgress: Boolean,
    ): Boolean = enabled &&
        !repeatAll &&
        !loadInProgress &&
        currentIndex >= 0 &&
        currentIndex == itemCount - 1

    /** The YouTube id to seed a station on. */
    suspend fun youtubeSeedFor(song: Song): String? {
        if (DesktopMusicSources.hasYouTubeOriginal(song)) return song.videoId
        val query = DesktopTrackMatcher.queries(song).firstOrNull() ?: return null
        // YouTube specifically, not the configured sources.
        return DesktopSearchClient.search(query, SearchFilter.SONGS)
            .getOrNull()
            ?.mapNotNull { result ->
                when (result) {
                    is SearchResult.Track -> result.song
                    is SearchResult.TopTrack -> result.song
                    is SearchResult.Browse -> null
                }
            }
            ?.let { DesktopTrackMatcher.best(it, song) }
            ?.videoId
    }

    /** One batch of station tracks to append after [existing]. */
    suspend fun tracksFor(
        existing: List<Song>,
        seedSong: Song,
        limit: Int = MAX_QUEUED_AUTOPLAY,
    ): Result<List<Song>> {
        val seed = youtubeSeedFor(seedSong)
        if (seed == null) {
            DesktopTrackLog.log("autoplay: no YouTube match to seed a station on for '${seedSong.title}'")
            return Result.success(emptyList())
        }
        val related = DesktopSearchClient.radio(seed).getOrElse { return Result.failure(it) }
        DesktopTrackLog.log("autoplay: station for '${seedSong.title}' offered ${related.size} tracks")
        // Session freshness, the same rule as the phone: the pool is
        // demoted against this seed's remembered batches before the
        // queue builder cuts it, so replaying a song does not hand back
        // last session's sequence, and the delivered batch is recorded
        // for the next session.
        val previous = history()[seedSong.videoId].orEmpty()
        val freshFirst = AutoplayFreshness.freshOrder(related, previous, related.size)
        val extra = QueueBuilder.extend(existing, freshFirst, limit)
        if (extra.isEmpty()) return Result.success(emptyList())
        recordBatch(seedSong.videoId, extra.map { it.videoId })
        return Result.success(extra.map { it.copy(queueTier = QueueTier.AUTOPLAY, radioName = seedSong.radioName) })
    }

    // -- Recommendation history ----------------------------------------
    //
    // The bounded per-seed history [AutoplayFreshness] reasons about,
    // persisted in the desktop preferences as one line per seed:
    // `seedId|id,id;id,id` — batches newest first, ids comma-joined,
    // batches semicolon-joined. Catalogue ids carry none of those
    // characters, so the flat encoding cannot corrupt an id.

    private val historyPreferences: Preferences =
        Preferences.userRoot().node("com.music.sonariq.desktop.autoplay")

    private var historyCache: Map<String, List<List<String>>>? = null

    @Synchronized
    private fun history(): Map<String, List<List<String>>> {
        historyCache?.let { return it }
        val raw = runCatching { historyPreferences.get(KEY_HISTORY, "") }.getOrDefault("")
        val parsed = LinkedHashMap<String, List<List<String>>>()
        for (line in raw.lineSequence()) {
            val seed = line.substringBefore('|')
            val batchesPart = line.substringAfter('|', "")
            if (seed.isBlank() || batchesPart.isBlank()) continue
            parsed[seed] = batchesPart.split(';')
                .map { batch -> batch.split(',').filter { it.isNotBlank() } }
                .filter { it.isNotEmpty() }
        }
        historyCache = parsed
        return parsed
    }

    @Synchronized
    private fun recordBatch(seedId: String, batchIds: List<String>) {
        val updated = AutoplayFreshness.recordBatch(history(), seedId, batchIds)
        historyCache = updated
        val raw = updated.entries.joinToString("\n") { (seed, batches) ->
            seed + "|" + batches.joinToString(";") { it.joinToString(",") }
        }
        runCatching { historyPreferences.put(KEY_HISTORY, raw) }
    }

    private const val KEY_HISTORY = "freshHistory"
}
