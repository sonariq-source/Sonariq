package com.music.sonariq.playback

import android.content.Context
import android.widget.Toast
import androidx.media3.session.MediaController
import com.music.sonariq.R
import com.music.sonariq.data.YtMusicRepository
import com.music.sonariq.data.model.SearchFilter
import com.music.sonariq.data.model.SearchResult
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.mood.Mood
import com.music.sonariq.data.mood.MoodStations
import com.music.sonariq.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Start Autoplay" seed entry points: build a real seed queue from an artist,
 * a mood, a genre or an era, start playback of it, and turn the AutoPlay
 * setting on — which is the button's explicit meaning, since PlaybackService
 * keeps the queue going from the playing tracks while the setting holds.
 *
 * Every helper returns what it honestly found, or an empty list when the
 * network or the response had nothing. Callers do nothing on an empty list:
 * there is no invented fallback, only the "could not load" notice from
 * [startSeededAutoplay].
 */
object AutoplaySeeds {

    /** Seed queues are a launchpad, not a library — the station builder's own size. */
    private const val SEED_LIMIT = 25

    /**
     * The artist's own tracks, the same listing an artist page plays:
     * tracks already in hand win, otherwise the artist page is fetched the
     * way [com.music.sonariq.ui.MainViewModel.openDetail] fetches it.
     */
    suspend fun artistSeeds(
        name: String,
        browseId: String? = null,
        knownTracks: List<Song> = emptyList(),
    ): List<Song> {
        if (knownTracks.isNotEmpty()) return knownTracks.deduped()
        val id = browseId
            ?: YtMusicRepository.findArtistPageId(name).getOrNull()
            ?: return emptyList()
        return YtMusicRepository.artistPage(id).getOrNull()?.songs
            .orEmpty()
            .deduped()
    }

    /**
     * A mood's station, built by the same builder the Home mood chips use —
     * the listener's own history first, falling back to real search results.
     */
    suspend fun moodSeeds(context: Context, mood: Mood): List<Song> =
        runCatching { MoodStations.buildStation(context, mood) }.getOrDefault(emptyList())

    /**
     * A genre, browsed the way search browses it: the genre's own music off
     * a songs-filtered search, tracks only.
     */
    suspend fun genreSeeds(genre: String): List<Song> =
        searchTracks("$genre music")

    /**
     * A decade, e.g. "70s": that decade's music off a songs-filtered search,
     * tracks only.
     */
    suspend fun eraSeeds(decade: String): List<Song> =
        searchTracks("$decade hits")

    private suspend fun searchTracks(query: String): List<Song> {
        val results = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
            ?: return emptyList()
        return results.mapNotNull { result ->
            when (result) {
                is SearchResult.Track -> result.song
                is SearchResult.TopTrack -> result.song
                is SearchResult.Browse -> null
            }
        }.deduped()
    }

    private fun List<Song>.deduped(): List<Song> =
        filter { it.videoId.isNotBlank() }
            .distinctBy { it.videoId }
            .take(SEED_LIMIT)

    /**
     * Starts [seeds] through the same queue path every other play action
     * uses, with the AutoPlay setting turned on so PlaybackService keeps the
     * queue going from the playing tracks. Returns false when there was
     * nothing real to start, in which case nothing about playback changes.
     */
    suspend fun startSeededAutoplay(
        controller: MediaController?,
        seeds: List<Song>,
    ): Boolean {
        if (seeds.isEmpty() || controller == null) return false
        AppSettings.setAutoplay(true)
        controller.playSongs(seeds, 0)
        return true
    }
}

/**
 * The full "Start Autoplay" tap from a composable: builds [seeds] off the
 * main thread, starts them with autoplay on via [AutoplaySeeds], and answers
 * an empty or failed build with the same notice other play actions show —
 * never with invented tracks.
 */
suspend fun Context.launchSeededAutoplay(
    controller: MediaController?,
    seeds: suspend () -> List<Song>,
) {
    val tracks = withContext(Dispatchers.IO) { seeds() }
    if (!AutoplaySeeds.startSeededAutoplay(controller, tracks)) {
        Toast.makeText(this, R.string.couldnt_load_tracks, Toast.LENGTH_SHORT).show()
    }
}
