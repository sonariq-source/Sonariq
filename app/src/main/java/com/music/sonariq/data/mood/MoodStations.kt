package com.music.sonariq.data.mood

import android.content.Context
import com.music.sonariq.R
import com.music.sonariq.data.YtMusicRepository
import com.music.sonariq.data.model.SearchFilter
import com.music.sonariq.data.model.SearchResult
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.stats.ListeningStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One of the moods a listener can start a radio station from.
 *
 * @param labelRes the user-facing name, read through resources at render time.
 * @param fallbackQuery the keyword search used when the listener's own history
 *   has too few tracks to seed from — still real results from YouTube Music,
 *   never anything invented.
 */
enum class Mood(
    val labelRes: Int,
    val fallbackQuery: String,
) {
    CHILL(R.string.mood_chill, "chill lofi acoustic"),
    PARTY(R.string.mood_party, "party dance hits"),
    FOCUS(R.string.mood_focus, "focus concentration study"),
    WORKOUT(R.string.mood_workout, "workout gym pump up"),
}

/**
 * Builds a ~25-track radio station for a [Mood].
 *
 * Everything here is real. Seeds come from the listener's own play history in
 * [ListeningStats]: high-energy moods (party, workout) seed from the most
 * played tracks, low-energy ones (chill, focus) from the longest. Each seed is
 * expanded with [YtMusicRepository.radio] — YouTube Music's own "what to play
 * next" queue — and the result is deduplicated by video id.
 *
 * When the history is too thin to seed from, the mood's keyword search feeds
 * the same expansion path, so an empty library still produces a real station.
 */
object MoodStations {
    /** Target station length; the last seed may overshoot the expansion loop. */
    private const val STATION_TARGET = 25
    private const val SEED_COUNT = 3
    private const val MIN_HISTORY_SEEDS = 2

    suspend fun buildStation(context: Context, mood: Mood): List<Song> =
        withContext(Dispatchers.IO) {
            val historySeeds = pickSeeds(mood)
            val seeds =
                if (historySeeds.size >= MIN_HISTORY_SEEDS) historySeeds
                else searchFallback(mood.fallbackQuery)
            buildStationFromSeeds(seeds)
        }

    /**
     * Expands [seeds] into a ~[STATION_TARGET]-track station.
     *
     * Public so the time-of-day stations reuse the exact same expansion rather
     * than duplicating it: each seed is kept, then grown with
     * [YtMusicRepository.radio] — YouTube Music's own "what to play next"
     * queue — deduplicated by video id.
     */
    suspend fun buildStationFromSeeds(seeds: List<Song>): List<Song> =
        withContext(Dispatchers.IO) {
            if (seeds.isEmpty()) return@withContext emptyList()
            val station = LinkedHashMap<String, Song>(STATION_TARGET * 2)
            for (seed in seeds.take(SEED_COUNT)) {
                if (seed.videoId.isBlank()) continue
                station.putIfAbsent(seed.videoId, seed)
                val related =
                    runCatching { YtMusicRepository.radio(seed.videoId).getOrThrow() }
                        .getOrDefault(emptyList())
                for (song in related) {
                    if (station.size >= STATION_TARGET) break
                    if (song.videoId.isNotBlank()) station.putIfAbsent(song.videoId, song)
                }
            }
            station.values.toList()
        }

    /** Seeds from the listener's own history: most-played for upbeat moods, longest for mellow ones. */
    private suspend fun pickSeeds(mood: Mood): List<Song> {
        val history =
            runCatching { ListeningStats.allTracks() }
                .getOrDefault(emptyList())
                .filter { it.id.isNotBlank() }
        if (history.size < MIN_HISTORY_SEEDS) return emptyList()
        return when (mood) {
            // High-energy moods seed from what the listener reaches for most.
            Mood.PARTY, Mood.WORKOUT -> history.sortedByDescending { it.plays }
            // Low-energy moods seed from the longest tracks in the same history.
            Mood.CHILL, Mood.FOCUS -> history.sortedByDescending { it.ms }
        }.take(SEED_COUNT).map { it.toSong() }
    }

    /**
     * Seed songs from a real YouTube Music keyword search when history is thin.
     *
     * Public so the time-of-day stations share the same last-resort path: an
     * empty library still produces a real station, never anything invented.
     */
    suspend fun searchFallback(query: String): List<Song> =
        runCatching {
            YtMusicRepository.searchPage(query, SearchFilter.SONGS)
                .getOrThrow()
                .rows
                .mapNotNull { row ->
                    when (row) {
                        is SearchResult.TopTrack -> row.song
                        is SearchResult.Track -> row.song
                        else -> null
                    }
                }
                .filter { it.videoId.isNotBlank() }
                .distinctBy { it.videoId }
                .take(SEED_COUNT)
        }.getOrDefault(emptyList())
}
