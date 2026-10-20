package com.music.sonariq.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.music.sonariq.playback.vibe.VibePlayStats
import com.music.sonariq.playback.vibe.VibeStore
import com.music.sonariq.playback.vibe.VibeVector
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
 * Finds the streaming id that should seed AutoPlay for a song. Module tracks do
 * not carry streaming ids, so they are matched against the catalogue first.
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
 * When [context] is provided, the batch is built as a mix: the user's library
 * and downloads are scored by vibe (see [loadVibeAutoplay]) and interleaved
 * with radio discovery in the proportions of the autoplay mix settings, with
 * SmartAutoplay predictions leading the discovery share. When vibe scoring
 * can't verify anything — no session vibe, nothing passing the threshold —
 * radio alone fills the batch, as before. Banned tracks and artists are
 * filtered out of every source.
 */
suspend fun loadAutoplayTracks(
    existing: List<Song>,
    seedSong: Song,
    limit: Int = MAX_QUEUED_AUTOPLAY,
    context: Context? = null,
): Result<List<Song>> {
    val bannedIds = AppSettings.autoplayBannedSongIds.value
    val bannedArtists = AppSettings.autoplayBannedArtists.value

    if (context != null) {
        // Downloads-only playback never touches the network; so does a
        // device with no validated internet. Both build from downloads only.
        val downloadsOnly = AppSettings.autoplayDownloadsOnly.value ||
            !hasValidatedInternet(context)
        val mixed = runCatching {
            loadVibeAutoplay(
                context = context,
                existing = existing,
                seedSong = seedSong,
                limit = limit,
                bannedIds = bannedIds,
                bannedArtists = bannedArtists,
                downloadsOnly = downloadsOnly,
            ) {
                loadDiscoveryBatch(context, existing, seedSong, limit, bannedIds, bannedArtists)
                    .getOrDefault(emptyList())
            }
        }.getOrDefault(emptyList())
        if (mixed.isNotEmpty()) return Result.success(mixed)
        // There is no radio fallback for a downloads-only batch: nothing else
        // is allowed to play, so an empty batch is the honest answer.
        if (downloadsOnly) return Result.success(emptyList())
    }

    val discovery = loadDiscoveryBatch(context, existing, seedSong, limit, bannedIds, bannedArtists)
        .getOrElse { return Result.failure(it) }
    if (discovery.isEmpty()) return Result.success(emptyList())
    return Result.success(toQueueEntries(discovery, seedSong))
}

/**
 * The discovery share of a batch: SmartAutoplay predictions first (only once
 * the predictor has learned enough), then radio for the seed, all run
 * through the bans and the queue's own de-duplication and artist caps.
 */
private suspend fun loadDiscoveryBatch(
    context: Context?,
    existing: List<Song>,
    seedSong: Song,
    limit: Int,
    bannedIds: Set<String>,
    bannedArtists: Set<String>,
): Result<List<Song>> = runCatching {
    val seed = youtubeSeedFor(seedSong) ?: return@runCatching emptyList()
    val related = YtMusicRepository.radio(seed).getOrThrow()

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

    val combined = (predicted + related)
        .filterNot { AutoplayPolicy.isBanned(it.videoId, it.artist, bannedIds, bannedArtists) }
        .distinctBy { it.videoId }
    QueueBuilder.extend(existing, combined, limit)
}

/** Tags one batch as autoplay queue entries inheriting the seed's source. */
private fun toQueueEntries(songs: List<Song>, seedSong: Song): List<Song> = songs.map {
    it.copy(
        radioName = seedSong.radioName,
        playbackSource = seedSong.playbackSource,
        playbackSourceType = seedSong.playbackSourceType,
        playbackSourceId = seedSong.playbackSourceId,
    ).asQueueEntry(QueueTier.AUTOPLAY)
}

/**
 * True when the device has an internet connection the system has actually
 * validated. Without that, radio and search are dead weight and autoplay
 * stays on the downloads, which are guaranteed playable.
 */
private fun hasValidatedInternet(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * The session vibe: the last few tracks' stored vibes blended into one, or
 * null when nothing usable is stored. Shared by the mixed batch and the
 * downloads-only batch.
 */
private fun sessionVibeOf(
    context: Context,
    existing: List<Song>,
    seedSong: Song,
): VibeVector? {
    val recentVibes = (existing.takeLast(5) + seedSong)
        .mapNotNull { VibeStore.getVibe(context, it.videoId)?.vector }
        .filter { !it.isEmpty() }
    if (recentVibes.isEmpty()) return null
    return VibeAnalyzer.sessionVibe(recentVibes).takeUnless { it.isEmpty() }
}

/**
 * Vibe-first autoplay batch, mixed per the autoplay mix settings, or empty
 * when there is nothing to build from and the caller should fall back to
 * radio. The session vibe is read from the last few tracks' stored vibes;
 * the vibe candidates are the user's offline library (downloads + local
 * files), which needs no sign-in and is guaranteed playable. Everything
 * vibe-scored goes through [VibeAutoplay.selectNext]'s purity rule: no
 * stored vibe, no pick.
 *
 * [loadDiscovery] supplies the discovery share and is only invoked when its
 * mix weight is non-zero.
 */
private suspend fun loadVibeAutoplay(
    context: Context,
    existing: List<Song>,
    seedSong: Song,
    limit: Int,
    bannedIds: Set<String>,
    bannedArtists: Set<String>,
    downloadsOnly: Boolean,
    loadDiscovery: suspend () -> List<Song>,
): List<Song> {
    val banned: (Song) -> Boolean = {
        AutoplayPolicy.isBanned(it.videoId, it.artist, bannedIds, bannedArtists)
    }
    val excludeIds = (existing.map { it.videoId } + seedSong.videoId).toSet()

    // Offline or downloads-only: downloads are the whole world. Vibe-scored
    // when a session vibe exists, taste-ordered when it doesn't.
    if (downloadsOnly) {
        return loadDownloadsOnlyBatch(
            context = context,
            existing = existing,
            seedSong = seedSong,
            limit = limit,
            bannedIds = bannedIds,
            bannedArtists = bannedArtists,
        )
    }

    if (!AppSettings.vibeAutoplay.value) return emptyList()

    val sessionVibe = sessionVibeOf(context, existing, seedSong) ?: return emptyList()

    // The mix's two offline pools: local files are the library share,
    // downloads are their own share, capped for performance.
    val downloaded = runCatching { Downloads.getDownloadedSongs(context) }
        .getOrDefault(emptyList())
        .filter { it.videoId.isNotBlank() && !banned(it) }
        .distinctBy { it.videoId }
        .take(MAX_VIBE_CANDIDATES)
    val local = if (LocalMediaRepository.hasStoragePermission(context)) {
        runCatching { LocalMediaRepository.getLocalMusic(context) }
            .getOrDefault(emptyList())
            .filter { it.videoId.isNotBlank() && !banned(it) }
            .distinctBy { it.videoId }
            .take(MAX_VIBE_CANDIDATES)
    } else {
        emptyList()
    }
    if (downloaded.isEmpty() && local.isEmpty()) return emptyList()

    // Exploration bends the vibe threshold and the per-artist cap: Safe
    // stays close to the seed, Adventurous wanders further.
    val exploration = AppSettings.autoplayExploration.value
    val threshold = AutoplayPolicy.explorationThreshold(
        exploration,
        AppSettings.vibeStrictness.value,
    )
    val artistCap = AutoplayPolicy.explorationArtistCap(exploration)

    val stats = VibeAutoplay.loadPlayStats(context)
    val tasteScore = VibeAutoplay.tasteScore(stats)

    val libraryPicks = pickVibePool(
        context, sessionVibe, local, excludeIds, limit,
        threshold, artistCap, bannedIds, bannedArtists, stats, tasteScore,
    )
    val downloadsPicks = pickVibePool(
        context, sessionVibe, downloaded, excludeIds, limit,
        threshold, artistCap, bannedIds, bannedArtists, stats, tasteScore,
    )

    val weights = AutoplayPolicy.normaliseMix(
        AppSettings.autoplayMixLibrary.value,
        AppSettings.autoplayMixDownloads.value,
        AppSettings.autoplayMixDiscovery.value,
    )
    // The discovery share is fetched only when it carries weight; the
    // interleave then applies the proportions to whatever was picked.
    val discovery = if (weights[2] > 0f) {
        runCatching { loadDiscovery() }.getOrDefault(emptyList())
    } else {
        emptyList()
    }

    val mixed = AutoplayPolicy.interleaveByWeight(
        listOf(libraryPicks, downloadsPicks, discovery),
        weights,
        limit,
    )
    if (mixed.isEmpty()) return emptyList()
    return toQueueEntries(mixed, seedSong)
}

/**
 * One offline pool's vibe picks: scored by [VibeAutoplay.selectNext] with the
 * session's exploration and energy shaping applied, then — in discovery
 * mode — re-anchored so never-played tracks lead with familiar ones between
 * them.
 */
private fun pickVibePool(
    context: Context,
    sessionVibe: VibeVector,
    candidates: List<Song>,
    excludeIds: Set<String>,
    limit: Int,
    threshold: Float,
    artistCap: Int,
    bannedIds: Set<String>,
    bannedArtists: Set<String>,
    stats: VibePlayStats,
    tasteScore: (String) -> Float,
): List<Song> {
    if (candidates.isEmpty()) return emptyList()
    // Familiar-only (the session engine's driving mode): the pool is cut to
    // tracks with a real play count before any scoring — an honest filter on
    // recorded history, applied here where the stats already live.
    val pool = if (AutoplaySession.familiarOnly) {
        candidates.filter { (stats.plays[it.videoId] ?: 0) > 0 }
    } else {
        candidates
    }
    if (pool.isEmpty()) return emptyList()
    val picked = VibeAutoplay.selectNext(
        context = context,
        sessionVibe = sessionVibe,
        candidates = pool,
        excludeIds = excludeIds,
        limit = limit,
        strictness = threshold,
        includeForgottenFavorites = AppSettings.vibeForgottenFavorites.value,
        tasteScore = tasteScore,
        lastPlayedAt = { stats.lastPlayedMs[it] ?: 0L },
        targetEnergy = AutoplaySession.targetEnergy,
        energyWeight = AutoplaySession.energyWeight,
        artistCap = artistCap,
        bannedIds = bannedIds,
        bannedArtists = bannedArtists,
    )
    if (picked.isEmpty() || !AppSettings.autoplayDiscoveryMode.value) return picked

    val (neverPlayed, anchors) = picked.partition { (stats.plays[it.videoId] ?: 0) <= 0 }
    return if (neverPlayed.isEmpty()) {
        picked
    } else {
        AutoplayPolicy.discoveryAnchored(neverPlayed, anchors, limit)
    }
}

/**
 * The batch for offline / downloads-only playback: downloads only, always.
 * Vibe-scored when a session vibe exists; when vibe returns nothing (or is
 * off), the fallback is the user's real taste over their downloads — best
 * taste score first, most recently played winning ties — so the order is
 * deterministic and never a guess. Ban-filtered either way.
 */
private suspend fun loadDownloadsOnlyBatch(
    context: Context,
    existing: List<Song>,
    seedSong: Song,
    limit: Int,
    bannedIds: Set<String>,
    bannedArtists: Set<String>,
): List<Song> {
    val banned: (Song) -> Boolean = {
        AutoplayPolicy.isBanned(it.videoId, it.artist, bannedIds, bannedArtists)
    }
    val excludeIds = (existing.map { it.videoId } + seedSong.videoId).toSet()
    val downloads = runCatching { Downloads.getDownloadedSongs(context) }
        .getOrDefault(emptyList())
        .filter { it.videoId.isNotBlank() && it.videoId !in excludeIds && !banned(it) }
        .distinctBy { it.videoId }
        .take(MAX_VIBE_CANDIDATES)
    if (downloads.isEmpty()) return emptyList()

    val stats = VibeAutoplay.loadPlayStats(context)
    val tasteScore = VibeAutoplay.tasteScore(stats)

    // Familiar-only (driving): downloads are cut to tracks already played,
    // on the same recorded-history rule as the mixed batch's pools.
    val pool = if (AutoplaySession.familiarOnly) {
        downloads.filter { (stats.plays[it.videoId] ?: 0) > 0 }
    } else {
        downloads
    }
    if (pool.isEmpty()) return emptyList()

    val vibeOrdered: List<Song> = if (AppSettings.vibeAutoplay.value) {
        val sessionVibe = sessionVibeOf(context, existing, seedSong)
        if (sessionVibe != null) {
            val exploration = AppSettings.autoplayExploration.value
            VibeAutoplay.selectNext(
                context = context,
                sessionVibe = sessionVibe,
                candidates = pool,
                excludeIds = emptySet(), // already filtered above
                limit = limit,
                strictness = AutoplayPolicy.explorationThreshold(
                    exploration,
                    AppSettings.vibeStrictness.value,
                ),
                includeForgottenFavorites = AppSettings.vibeForgottenFavorites.value,
                tasteScore = tasteScore,
                lastPlayedAt = { stats.lastPlayedMs[it] ?: 0L },
                targetEnergy = AutoplaySession.targetEnergy,
                energyWeight = AutoplaySession.energyWeight,
                artistCap = AutoplayPolicy.explorationArtistCap(exploration),
                bannedIds = bannedIds,
                bannedArtists = bannedArtists,
            )
        } else {
            emptyList()
        }
    } else {
        emptyList()
    }

    val ordered = vibeOrdered.ifEmpty {
        pool.sortedWith(
            compareByDescending<Song> { tasteScore(it.videoId) }
                .thenByDescending { stats.lastPlayedMs[it.videoId] ?: 0L },
        ).take(limit)
    }
    if (ordered.isEmpty()) return emptyList()
    return toQueueEntries(ordered, seedSong)
}
