package com.music.sonariq.playback.vibe

import android.content.Context
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.stats.ListeningStats
import com.music.sonariq.playback.AutoplayPolicy
import kotlin.math.log10

/**
 * One track's personal numbers, reduced once from the listening history so
 * [selectNext] doesn't re-scan the buckets per candidate.
 */
data class VibePlayStats(
    /** Lifetime play count per videoId. */
    val plays: Map<String, Int>,
    /** Last-played epoch millis per videoId, absent when never played. */
    val lastPlayedMs: Map<String, Long>,
)

/**
 * VibeAutoplay: picks the next tracks for vibe-pure autoplay.
 *
 * The session's current vibe is matched against the per-track vibe vectors
 * learned in [VibeStore]. A track only makes the cut when its stored vibe
 * passes the [strictness] similarity threshold — no stored vibe, no pick;
 * the caller then falls back to the regular (radio) autoplay path.
 * Never invents a vibe for a track it has nothing on.
 */
object VibeAutoplay {

    /** Slot reserved for the forgotten favorite when one is injected. */
    private const val FORGOTTEN_SLOT = 7

    /** Minimum queue length before a forgotten-favorite slot is reserved. */
    private const val FORGOTTEN_MIN_LIMIT = 10

    /** 30 days in millis — how long a track must sit unplayed to count as forgotten. */
    private const val FORGOTTEN_AFTER_MS = 30L * 24 * 60 * 60 * 1_000L

    /**
     * Select the next tracks for vibe-pure autoplay, best first.
     *
     * @param context Android context (for [VibeStore] reads)
     * @param sessionVibe the current session vibe (from `VibeAnalyzer.sessionVibe`)
     * @param candidates all available songs (library / downloaded)
     * @param excludeIds videoIds to exclude (recently played, in queue)
     * @param limit how many to return
     * @param strictness vibe similarity threshold (0.3..0.9)
     * @param includeForgottenFavorites whether to inject a forgotten favorite
     * @param tasteScore personal taste score 0..1 per videoId
     * @param lastPlayedAt last-played epoch millis per videoId, 0 if never
     * @param targetEnergy optional session target energy (0..1) from
     * [AutoplaySession][com.music.sonariq.playback.AutoplaySession]; when
     * null, scoring is exactly the pre-energy-shaping behaviour
     * @param energyWeight how strongly [targetEnergy] pulls the score
     * @param artistCap max tracks per artist in one batch
     * @param bannedIds videoIds banned from autoplay
     * @param bannedArtists artists banned from autoplay (case-insensitive)
     */
    fun selectNext(
        context: Context,
        sessionVibe: VibeVector,
        candidates: List<Song>,
        excludeIds: Set<String>,
        limit: Int,
        strictness: Float,
        includeForgottenFavorites: Boolean,
        tasteScore: (String) -> Float,
        lastPlayedAt: (String) -> Long,
        targetEnergy: Float? = null,
        energyWeight: Float = 0.35f,
        artistCap: Int = Int.MAX_VALUE,
        bannedIds: Set<String> = emptySet(),
        bannedArtists: Set<String> = emptySet(),
    ): List<Song> {
        if (limit <= 0 || sessionVibe.isEmpty()) return emptyList()
        val threshold = strictness.coerceIn(0.3f, 0.9f)

        data class Scored(val song: Song, val score: Float)

        val scored = ArrayList<Scored>(candidates.size)
        for (song in candidates) {
            val id = song.videoId
            if (id.isBlank() || id in excludeIds) continue
            // Bans go first: a banned track is out before any vibe work
            // happens on it, whatever its score would have been.
            if (AutoplayPolicy.isBanned(id, song.artist, bannedIds, bannedArtists)) continue
            // Vibe purity: a track with no stored vibe can't be verified,
            // so it's skipped — never scored on a guess.
            val stored = VibeStore.getVibe(context, id) ?: continue
            val vibeSim = stored.vector.cosineSimilarity(sessionVibe)
            if (vibeSim < threshold) continue
            var score = vibeSim * 0.6f + tasteScore(id) * 0.4f
            // Energy shaping, only when a session engine has set a target.
            if (targetEnergy != null) {
                score += AutoplayPolicy.energyBonus(stored.vector, targetEnergy, energyWeight)
            }
            scored.add(Scored(song, score))
        }
        scored.sortByDescending { it.score }

        // The artist cap is enforced while picking, not after: a capped
        // artist's tracks are simply passed over for the next best match.
        val picked = ArrayList<Scored>(minOf(limit, scored.size))
        val perArtist = HashMap<String, Int>()
        for (candidate in scored) {
            if (picked.size >= limit) break
            val artist = candidate.song.artist.trim().lowercase()
            val count = perArtist[artist] ?: 0
            if (count >= artistCap) continue
            perArtist[artist] = count + 1
            picked.add(candidate)
        }

        // Forgotten favorites: reserve one slot for a beloved track gone
        // quiet for 30+ days. Only when one actually exists — never forced.
        // The artist cap still applies to the injected track.
        if (includeForgottenFavorites && limit >= FORGOTTEN_MIN_LIMIT && picked.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val pickedIds = picked.mapTo(HashSet(picked.size)) { it.song.videoId }
            val forgotten = scored
                .filter { it.song.videoId !in pickedIds }
                .filter { (perArtist[it.song.artist.trim().lowercase()] ?: 0) < artistCap }
                .filter {
                    val last = lastPlayedAt(it.song.videoId)
                    last > 0 && now - last >= FORGOTTEN_AFTER_MS
                }
                .maxByOrNull { it.score }
            if (forgotten != null) {
                picked.add(FORGOTTEN_SLOT.coerceAtMost(picked.size - 1), forgotten)
                if (picked.size > limit) picked.removeLast()
            }
        }

        return picked.map { it.song }
    }

    /**
     * One pass over the listening history for the two signals [selectNext]
     * needs from it: play counts and last-played times.
     */
    suspend fun loadPlayStats(context: Context): VibePlayStats {
        val tracks = runCatching { ListeningStats.allTracks() }.getOrDefault(emptyList())
        val plays = HashMap<String, Int>(tracks.size)
        val lastPlayedMs = HashMap<String, Long>(tracks.size)
        for (track in tracks) {
            plays[track.id] = track.plays
            lastPlayedMs[track.id] = track.lastPlayedMs
        }
        return VibePlayStats(plays = plays, lastPlayedMs = lastPlayedMs)
    }

    /**
     * Personal taste score 0..1 from real history: log-scaled play count plus
     * a bonus for liked tracks. No skip-count penalty — skips aren't tracked
     * as per-track counts, so no fake signal is substituted.
     */
    fun tasteScore(stats: VibePlayStats, likedIds: Set<String> = emptySet()): (String) -> Float {
        return { videoId ->
            val plays = stats.plays[videoId] ?: 0
            (log10((plays + 1).toFloat()) / 2f + if (videoId in likedIds) 0.3f else 0f)
                .coerceIn(0f, 1f)
        }
    }
}
