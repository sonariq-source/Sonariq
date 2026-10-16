package com.music.sonariq.data.mood

import android.content.Context
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.stats.ListeningStats
import com.music.sonariq.data.stats.TrackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds a ~25-track station for a [MomentSlot] — the current slice of the
 * day — reusing [MoodStations]' expansion and search fallback rather than
 * duplicating them.
 *
 * Everything here is real. Seeds come from the listener's own play history in
 * [ListeningStats], preferring tracks the listener actually plays during the
 * slot's own hours: the engine keeps per-track plays per day and per-day
 * listening per hour, so each day's plays are attributed across the day's
 * slots in proportion to when that day's listening really happened. A track
 * needs at least one full play's worth of weight in the slot to seed from it —
 * background listening spread thinly across the day does not count.
 *
 * When the slot has no history of its own, the seeds fall back to the
 * overall most-played tracks, and only then to a real keyword search — the
 * same last resort the mood stations use. Nothing is ever invented.
 */
object MomentStations {
    private const val SEED_COUNT = 3
    private const val MIN_HISTORY_SEEDS = 2

    /** One slot-weighted play: the bar for "the listener plays this in this slot". */
    private const val MIN_SLOT_WEIGHT = 1.0

    /** A built station, and whether its seeds came from the listener's own history. */
    data class Station(val songs: List<Song>, val personal: Boolean)

    suspend fun buildStation(context: Context, slot: MomentSlot): Station =
        withContext(Dispatchers.IO) {
            val (seeds, personal) = pickSeeds(slot)
            val finalSeeds =
                if (seeds.size >= MIN_HISTORY_SEEDS) seeds
                else MoodStations.searchFallback(slot.fallbackQuery)
            Station(MoodStations.buildStationFromSeeds(finalSeeds), personal && finalSeeds.size >= MIN_HISTORY_SEEDS)
        }

    /**
     * Seeds from the listener's own history.
     *
     * Returns the slot-preferred seeds when at least [MIN_HISTORY_SEEDS] tracks
     * carry real weight in the slot's hours, else the overall most-played —
     * and flags whether either history path produced the seeds, so the card
     * can say so honestly.
     */
    private suspend fun pickSeeds(slot: MomentSlot): Pair<List<Song>, Boolean> {
        val buckets = runCatching { ListeningStats.exportAll() }.getOrDefault(emptyList())
        val songs = HashMap<String, Song>()
        val slotWeight = HashMap<String, Double>()
        val totalPlays = HashMap<String, Int>()
        for (bucket in buckets) {
            for (track in bucket.tracks) {
                if (track.id.isBlank()) continue
                // Later months overwrite: metadata is freshest where it was last touched.
                songs[track.id] = track.toSong()
                totalPlays[track.id] = (totalPlays[track.id] ?: 0) + track.plays
                track.dayPlays.forEach { (day, plays) ->
                    val hours = bucket.dayHours[day] ?: return@forEach
                    val dayMs = hours.sum()
                    if (dayMs <= 0L) return@forEach
                    val slotMs = hours.filterIndexed { hour, _ -> slot.contains(hour) }.sum()
                    if (slotMs <= 0L) return@forEach
                    // The day's plays, shared across the day's slots exactly the
                    // way that day's listening was shared across its hours.
                    // The per-track total stays the recorded total; only the
                    // attribution is proportional.
                    slotWeight[track.id] =
                        (slotWeight[track.id] ?: 0.0) + plays * slotMs / dayMs
                }
            }
        }
        val slotSeeds = slotWeight.entries
            .filter { it.value >= MIN_SLOT_WEIGHT }
            .sortedByDescending { it.value }
            .take(SEED_COUNT)
            .mapNotNull { songs[it.key] }
        if (slotSeeds.size >= MIN_HISTORY_SEEDS) return slotSeeds to true
        val overallSeeds = totalPlays.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(SEED_COUNT)
            .mapNotNull { songs[it.key] }
        return overallSeeds to (overallSeeds.size >= MIN_HISTORY_SEEDS)
    }

    private fun TrackEntry.toSong(): Song = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = art,
        artistId = artistId,
        albumId = albumId,
        albumName = album,
    )
}
