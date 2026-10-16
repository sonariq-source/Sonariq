package com.music.sonariq.playback

import android.content.Context
import com.music.sonariq.data.LocalMediaRepository
import com.music.sonariq.data.YtMusicRepository
import com.music.sonariq.data.listentogether.ListenTogether
import com.music.sonariq.data.model.SearchFilter
import com.music.sonariq.data.model.SearchResult
import com.music.sonariq.data.model.QueueTier
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.data.sources.SourceRegistry
import com.music.sonariq.data.sources.TrackMatcher
import com.music.sonariq.download.Downloads
import com.music.sonariq.playback.QueueCoordinator.asQueueEntry
import com.music.sonariq.playback.vibe.VibeAnalyzer
import com.music.sonariq.playback.vibe.VibeAutoplay
import com.music.sonariq.playback.vibe.VibeStore
import kotlinx.coroutines.CancellationException

/** Most AutoPlay-suggested tracks kept queued ahead of the current one at once. */
const val MAX_QUEUED_AUTOPLAY = 10

/** Cap on the library scan for vibe autoplay, so a huge library stays cheap. */
private const val MAX_VIBE_CANDIDATES = 2000

/**
 * Whether AutoPlay is on for the music this device is playing.
 *
 * In a party it is the party's setting and not this phone's: AutoPlay is shared
 * there, so one member's preference cannot be what decides. [localPreference] is
 * `AppSettings.autoplay` and applies everywhere else.
 *
 * Shared by the playback service and by every surface that draws the toggle,
 * because they went their own ways once and the result was a button that lied:
 * the player read the local preference while the service obeyed the party's, so
 * a listener could sit under "AutoPlay on" getting no suggestions at all, press
 * it, and watch the label stay exactly as it was.
 */
fun autoplayEnabledFor(party: ListenTogether.State, localPreference: Boolean): Boolean =
    if (party.inParty) party.playback.autoplayEnabled else localPreference

/**
 * The one device in a party that tops the queue up, or null if there is none.
 *
 * Everybody runs this over the same membership and gets the same answer, which
 * is what stops five phones appending five different sets of suggestions to one
 * queue. The host supplies while it is connected; otherwise the lowest member id
 * takes over, chosen for being stable rather than for meaning anything.
 *
 * Except in a party locked to its host, where there is no stand-in. The server
 * refuses a `queueAdd` from a listener outright — `host_only`, whatever the
 * listener believes it is doing — so electing one produced a control that was
 * always refused, an error on that listener's phone and suggestions for nobody.
 * Returning null instead lets AutoPlay wait for the host, which is what the
 * party's own policy already says should happen.
 */
fun autoplaySupplierId(party: ListenTogether.State): String? {
    if (!party.inParty) return null
    val host = party.members.firstOrNull { it.isHost && it.connected }?.memberId
    if (party.hostOnlyControl) return host
    return host ?: party.members.asSequence()
        .filter { it.connected }
        .minByOrNull { it.memberId }
        ?.memberId
}

/**
 * Whether AutoPlay should forget its last seed and try the current track again.
 *
 * A completed load normally leaves its seed behind to de-duplicate the several
 * player callbacks caused by one queue edit. If that edit leaves the current
 * track with nothing after it, however, the seed is no longer useful: keeping
 * it would make an enabled AutoPlay queue stay empty forever.
 */
fun autoplayQueueNeedsRefresh(
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

/**
 * Finds the YouTube id that should seed AutoPlay for a song. Module tracks do not
 * carry YouTube ids, so they are matched on YouTube before the radio request.
 */
suspend fun youtubeSeedFor(song: Song): String? {
    if (SourceRegistry.parseTrackKey(song.videoId) == null) return song.videoId
    val target = TrackMatcher.targetOf(song)
    val query = TrackMatcher.queries(target).firstOrNull() ?: return null
    return YtMusicRepository.search(query, SearchFilter.SONGS)
        .getOrNull()
        ?.filterIsInstance<SearchResult.Track>()
        ?.map { it.song }
        ?.let { TrackMatcher.best(it, target) }
        ?.videoId
}

/**
 * Loads, de-duplicates and resolves one AutoPlay batch. The playback service is
 * the only caller for the AutoPlay toggle; the player UI's explicit radio start
 * uses this same helper for its initial station batch.
 *
 * When [context] is provided, SmartAutoplay predictions lead the queue: songs
 * the user actually plays after [seedSong] come first, then YouTube's radio
 * fills the rest. The predictor only leads when it has learned enough —
 * otherwise radio runs the show as before.
 */
suspend fun loadAutoplayTracks(
    existing: List<Song>,
    seedSong: Song,
    limit: Int = MAX_QUEUED_AUTOPLAY,
    context: Context? = null,
): Result<List<Song>> {
    // Vibe autoplay leads when enabled: match the session's vibe against the
    // offline library. The regular radio path below is the fallback — vibe
    // returning empty means "couldn't verify anything", not an error.
    if (context != null) {
        val vibe = runCatching { loadVibeAutoplay(context, existing, seedSong, limit) }
            .getOrDefault(emptyList())
        if (vibe.isNotEmpty()) return Result.success(vibe)
    }

    val seed = youtubeSeedFor(seedSong) ?: return Result.success(emptyList())
    val related = YtMusicRepository.radio(seed).getOrElse { return Result.failure(it) }

    // Smart predictions first: resolve cached predictions to full songs.
    val predicted: List<Song> = if (context != null) {
        runCatching {
            val confidence = SmartAutoplay.confidence(context, seedSong)
            if (confidence >= 3) {
                SmartAutoplay.predictNext(context, seedSong, limit = 3)
                    .mapNotNull { cached ->
                        // Resolve via search; skip if unresolvable.
                        runCatching {
                            YtMusicRepository.search(
                                "${cached.title} ${cached.artist}",
                                SearchFilter.SONGS,
                            ).getOrNull()
                                ?.filterIsInstance<SearchResult.Track>()
                                ?.map { it.song }
                                ?.firstOrNull { it.videoId == cached.videoId }
                        }.getOrNull()
                    }
            } else emptyList()
        }.getOrDefault(emptyList())
    } else emptyList()

    val combined = (predicted + related).distinctBy { it.videoId }
    val extra = QueueBuilder.extend(existing, combined, limit)
    if (extra.isEmpty()) return Result.success(emptyList())

    return Result.success(extra.map {
        it.copy(
            radioName = seedSong.radioName,
            playbackSource = seedSong.playbackSource,
            playbackSourceType = seedSong.playbackSourceType,
            playbackSourceId = seedSong.playbackSourceId,
        ).asQueueEntry(QueueTier.AUTOPLAY)
    })
}

/**
 * Vibe-first autoplay batch, or empty when there is nothing vibe-verified to
 * play. The session vibe is read from the last few tracks' stored vibes; the
 * candidates are the user's offline library (downloads + local files), which
 * needs no sign-in and is guaranteed playable. Everything then goes through
 * [VibeAutoplay.selectNext]'s purity rule: no stored vibe, no pick.
 */
private suspend fun loadVibeAutoplay(
    context: Context,
    existing: List<Song>,
    seedSong: Song,
    limit: Int,
): List<Song> {
    if (!AppSettings.vibeAutoplay.value) return emptyList()

    // The session vibe, blended from the last few tracks' stored vibes.
    val recentVibes = (existing.takeLast(5) + seedSong)
        .mapNotNull { VibeStore.getVibe(context, it.videoId)?.vector }
        .filter { !it.isEmpty() }
    if (recentVibes.isEmpty()) return emptyList()
    val sessionVibe = VibeAnalyzer.sessionVibe(recentVibes)
    if (sessionVibe.isEmpty()) return emptyList()

    // The offline library: downloads and local files, capped for performance.
    val downloaded = runCatching { Downloads.getDownloadedSongs(context) }
        .getOrDefault(emptyList())
    val local = if (LocalMediaRepository.hasStoragePermission(context)) {
        runCatching { LocalMediaRepository.getLocalMusic(context) }
            .getOrDefault(emptyList())
    } else {
        emptyList()
    }
    val candidates = (downloaded + local)
        .filter { it.videoId.isNotBlank() }
        .distinctBy { it.videoId }
        .take(MAX_VIBE_CANDIDATES)
    if (candidates.isEmpty()) return emptyList()

    val stats = VibeAutoplay.loadPlayStats(context)
    val picked = VibeAutoplay.selectNext(
        context = context,
        sessionVibe = sessionVibe,
        candidates = candidates,
        excludeIds = (existing.map { it.videoId } + seedSong.videoId).toSet(),
        limit = limit,
        strictness = AppSettings.vibeStrictness.value,
        includeForgottenFavorites = AppSettings.vibeForgottenFavorites.value,
        tasteScore = VibeAutoplay.tasteScore(stats, candidates),
        lastPlayedAt = { stats.lastPlayedMs[it] ?: 0L },
    )
    return picked.map {
        it.copy(
            radioName = seedSong.radioName,
            playbackSource = seedSong.playbackSource,
            playbackSourceType = seedSong.playbackSourceType,
            playbackSourceId = seedSong.playbackSourceId,
        ).asQueueEntry(QueueTier.AUTOPLAY)
    }
}
