package com.music.sonariq.playback

import com.music.sonariq.data.model.Song
import com.music.sonariq.playback.vibe.VibeVector
import kotlin.math.abs

/**
 * AutoplayPolicy: the pure, Android-free arithmetic behind Sonariq's 2.21
 * autoplay update — mix normalisation, exploration tuning, ban checks,
 * pool interleaving, discovery anchoring and energy scoring. Everything here
 * takes its inputs as arguments and touches no settings or storage, so it
 * stays unit-testable without a device.
 */
object AutoplayPolicy {

    /**
     * Turns the three mix percentages (library / downloads / discovery) into
     * weights summing to 1. Negative inputs are treated as 0; all-zero (or
     * all-negative) input falls back to even thirds rather than blowing up.
     */
    fun normaliseMix(library: Int, downloads: Int, discovery: Int): FloatArray {
        val l = library.coerceAtLeast(0).toFloat()
        val d = downloads.coerceAtLeast(0).toFloat()
        val e = discovery.coerceAtLeast(0).toFloat()
        val total = l + d + e
        if (total <= 0f) return floatArrayOf(1f / 3f, 1f / 3f, 1f / 3f)
        return floatArrayOf(l / total, d / total, e / total)
    }

    /**
     * The vibe similarity threshold adjusted for how adventurous the user is:
     * Safe demands a closer match (+0.15), Adventurous lets stranger picks
     * through (-0.15), Balanced leaves the base alone. Clamped to 0.3..0.9,
     * the same window [VibeAutoplay][com.music.sonariq.playback.vibe.VibeAutoplay]
     * itself enforces.
     */
    fun explorationThreshold(level: Int, base: Float): Float = when (level) {
        0 -> base + 0.15f
        2 -> base - 0.15f
        else -> base
    }.coerceIn(0.3f, 0.9f)

    /**
     * How many tracks one artist may contribute to a vibe autoplay batch.
     * Safe keeps the sound familiar (2), Balanced opens it up (4),
     * Adventurous imposes no per-artist limit.
     */
    fun explorationArtistCap(level: Int): Int = when (level) {
        0 -> 2
        1 -> 4
        else -> Int.MAX_VALUE
    }

    /**
     * True when a track is banned from autoplay, either by its video id or
     * by its artist. Artist comparison is case-insensitive and trimmed on
     * both sides, so "  Foo  " matches a banned "foo".
     */
    fun isBanned(
        videoId: String,
        artist: String,
        bannedIds: Set<String>,
        bannedArtists: Set<String>,
    ): Boolean {
        if (videoId in bannedIds) return true
        if (artist.isBlank() || bannedArtists.isEmpty()) return false
        val normalised = artist.trim()
        return bannedArtists.any { it.trim().equals(normalised, ignoreCase = true) }
    }

    /**
     * Fills up to [limit] tracks from [pools] in proportion to [weights]
     * (library / downloads / discovery order), keeping each pool's internal
     * order and de-duplicating by videoId across pools.
     *
     * The scheduler is smooth weighted round robin: every live pool gains
     * its weight each turn, the heaviest is picked and then sheds the total.
     * That keeps the mix proportional even for short batches, instead of
     * dumping one pool's whole quota before the next pool starts.
     */
    fun interleaveByWeight(
        pools: List<List<Song>>,
        weights: FloatArray,
        limit: Int,
    ): List<Song> {
        if (limit <= 0 || pools.isEmpty()) return emptyList()
        val active = pools.indices.filter { pools[it].isNotEmpty() }
        if (active.isEmpty()) return emptyList()

        // A pool with no weight contributes nothing; if no pool has any
        // weight, an even split beats returning nothing at all.
        var effective = active.map { weights.getOrElse(it) { 0f }.coerceAtLeast(0f) }
        if (effective.all { it <= 0f }) effective = active.map { 1f }
        val total = effective.sum()

        val currents = FloatArray(active.size)
        val positions = IntArray(active.size)
        val seen = HashSet<String>()
        val out = ArrayList<Song>(limit)

        while (out.size < limit) {
            var best = -1
            var bestWeight = Float.NEGATIVE_INFINITY
            for (i in active.indices) {
                if (positions[i] >= pools[active[i]].size) continue
                currents[i] += effective[i]
                if (currents[i] > bestWeight) {
                    bestWeight = currents[i]
                    best = i
                }
            }
            if (best < 0) break
            currents[best] -= total

            val pool = pools[active[best]]
            var song = pool[positions[best]]
            while (song.videoId in seen && positions[best] + 1 < pool.size) {
                positions[best]++
                song = pool[positions[best]]
            }
            if (song.videoId in seen) {
                positions[best]++
                continue
            }
            seen.add(song.videoId)
            positions[best]++
            out.add(song)
        }
        return out
    }

    /**
     * Discovery-mode ordering: two never-played picks, then one familiar
     * anchor, repeating. New music leads but the queue never drifts into
     * back-to-back strangers. When either side runs dry, its leftovers fill
     * the remaining slots.
     */
    fun discoveryAnchored(
        discovery: List<Song>,
        anchors: List<Song>,
        limit: Int,
    ): List<Song> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<Song>(minOf(limit, discovery.size + anchors.size))
        val seen = HashSet<String>()
        var d = 0
        var a = 0
        fun add(song: Song) {
            if (song.videoId !in seen) {
                seen.add(song.videoId)
                out.add(song)
            }
        }
        while (out.size < limit && d < discovery.size && a < anchors.size) {
            repeat(2) { if (out.size < limit && d < discovery.size) add(discovery[d++]) }
            if (out.size < limit && a < anchors.size) add(anchors[a++])
        }
        while (out.size < limit && d < discovery.size) add(discovery[d++])
        while (out.size < limit && a < anchors.size) add(anchors[a++])
        return out
    }

    /**
     * One track's energy in 0..1 from its vibe vector.
     *
     * raw is the mean of the three high-energy moods (energetic, party,
     * workout). Calm damps it: a track that is fully chill reads as half
     * the energy its raw score claims, because mellow tracks sit lower on
     * the perceived-energy curve even when they tick the active boxes.
     */
    fun energyOf(v: VibeVector): Float {
        val raw = (v.energetic + v.party + v.workout) / 3f
        return (raw * (1f - v.chill * 0.5f)).coerceIn(0f, 1f)
    }

    /**
     * Energy shaping bonus for one scored track: 1 when the track's energy
     * matches [target] exactly, fading linearly to 0 a full point away,
     * scaled by [weight]. With a null target the caller skips this entirely.
     */
    fun energyBonus(vector: VibeVector, target: Float, weight: Float): Float {
        return (1f - abs(energyOf(vector) - target)) * weight
    }
}
