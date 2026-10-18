package com.music.sonariq.playback.radio

import android.content.Context
import com.music.sonariq.data.model.Song as ModelSong
import com.music.sonariq.playback.loadAutoplayTracks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sonariq Radio: an endless station seeded from a song, an artist, or a
 * playlist.
 *
 * This is the session half. The existing song-menu "Start radio" plays one
 * batch of related tracks and then stops unless the AutoPlay toggle happens to
 * be on — a station that ends is not a station. A radio session instead owns
 * its own refill contract: while [active] holds a station, the queue is topped
 * back up from [loadBatch] whenever it runs low, independent of the AutoPlay
 * setting.
 *
 * Everything musical is delegated to [loadAutoplayTracks], so a station gets
 * the same machinery as AutoPlay: vibe-first matching against the offline
 * library when it can verify a pick, learned transitions next, and the
 * provider radio as the fallback. Results keep the AUTOPLAY queue tier, which
 * is what lets the queue tell generated tracks from chosen ones when the
 * station ends or repeat-all stashes the tail.
 */
object RadioSession {

    /** How many tracks may sit ahead of the current one before a refill fires. */
    const val TOP_UP_THRESHOLD = 4

    /** Tracks fetched per refill — matches the initial station batch size. */
    const val BATCH_SIZE = 24

    private val _active = MutableStateFlow<RadioStation?>(null)

    /** The station currently playing, or null when no radio is running. */
    val active: StateFlow<RadioStation?> = _active.asStateFlow()

    /**
     * Starts a station from [source]. Returns false when the source has
     * nothing to seed from (an empty playlist), in which case nothing changes.
     */
    fun start(source: RadioSource): Boolean {
        val station = when (source) {
            is RadioSource.Song -> RadioStation(
                title = source.song.title,
                seedSong = source.song,
            )
            is RadioSource.Artist -> RadioStation(
                title = source.name,
                seedSong = source.topSong,
            )
            is RadioSource.Playlist -> {
                val first = source.songs.firstOrNull() ?: return false
                RadioStation(title = source.name, seedSong = first)
            }
        }
        _active.value = station
        return true
    }

    /** Ends the station. The queued tracks stay; nothing new is appended. */
    fun stop() {
        _active.value = null
    }

    /**
     * Whether the queue needs more station tracks: the playhead is within
     * [threshold] of the end. Pure so the service can ask on every track
     * change without doing any I/O.
     */
    fun shouldTopUp(
        currentIndex: Int,
        queueSize: Int,
        threshold: Int = TOP_UP_THRESHOLD,
    ): Boolean {
        if (queueSize <= 0 || currentIndex < 0 || currentIndex >= queueSize) return false
        return queueSize - 1 - currentIndex <= threshold
    }

    /**
     * Picks the seed for the next batch: the most recently played track, so
     * the station drifts forward through related music the way the provider
     * radio expects, rather than re-asking about the original seed forever.
     * Falls back to the station's own seed when there is no history yet.
     */
    fun refillSeed(playedTail: List<ModelSong>, station: RadioStation): ModelSong =
        playedTail.lastOrNull { it.videoId.isNotBlank() } ?: station.seedSong

    /**
     * Loads one more station batch, named after the station rather than the
     * seed track. Fails when no station is active — the caller treats that as
     * "radio ended while the request was in flight" and drops the result.
     */
    suspend fun loadBatch(
        existing: List<ModelSong>,
        playedTail: List<ModelSong>,
        context: Context,
    ): Result<List<ModelSong>> {
        val station = _active.value ?: return Result.failure(
            IllegalStateException("No radio station active"),
        )
        val seed = refillSeed(playedTail, station).copy(radioName = station.title)
        return loadAutoplayTracks(
            existing = existing,
            seedSong = seed,
            limit = BATCH_SIZE,
            context = context,
        )
    }
}

/** What a station is seeded from. */
sealed interface RadioSource {
    /** A single track: the station plays its neighbourhood. */
    data class Song(val song: ModelSong) : RadioSource

    /** An artist: seeded from their top track, named for the artist. */
    data class Artist(val name: String, val topSong: ModelSong) : RadioSource

    /** A playlist: seeded from its first track, named for the playlist. */
    data class Playlist(val name: String, val songs: List<ModelSong>) : RadioSource
}

/** A running station: its display name and the track it was seeded from. */
data class RadioStation(
    val title: String,
    val seedSong: ModelSong,
    val startedAtMs: Long = System.currentTimeMillis(),
)
