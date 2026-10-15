package com.music.sonariq.playback

import android.content.Context
import android.content.SharedPreferences
import com.music.sonariq.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Smart Autoplay: predicts what the user wants to hear next.
 *
 * Learns song-to-song transitions — when you play B right after A, the link
 * A→B gets stronger. Over time the queue starts to feel like it reads your
 * mind: the songs you actually reach for surface first, blended with artist
 * affinity and YouTube's radio as fallback.
 *
 * Stored as compact JSON in SharedPreferences: "videoIdA>videoIdB" → count,
 * plus a small song-info cache so predictions can show titles without a
 * network round-trip. Capped to keep the store small.
 */
private const val PREFS_NAME = "smart_autoplay"
private const val KEY_TRANSITIONS = "transitions"
private const val KEY_SONGS = "songs"
private const val MAX_TRANSITIONS = 2000
private const val MAX_SONG_CACHE = 500

@Serializable
internal data class CachedSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
)

object SmartAutoplay {

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun parseTransitions(raw: String?): MutableMap<String, Int> =
        runCatching {
            json.decodeFromString<Map<String, Int>>(raw ?: "{}").toMutableMap()
        }.getOrDefault(mutableMapOf())

    private fun parseSongs(raw: String?): MutableMap<String, CachedSong> =
        runCatching {
            json.decodeFromString<Map<String, CachedSong>>(raw ?: "{}").toMutableMap()
        }.getOrDefault(mutableMapOf())

    /**
     * Records that [next] was played right after [previous]. Called by the
     * playback service on every natural track advance.
     */
    fun recordTransition(context: Context, previous: Song, next: Song) {
        if (previous.videoId == next.videoId) return
        val store = prefs(context)
        val transitions = parseTransitions(store.getString(KEY_TRANSITIONS, null))
        val key = "${previous.videoId}>${next.videoId}"
        transitions[key] = (transitions[key] ?: 0) + 1
        // Keep the map bounded: drop the weakest links.
        if (transitions.size > MAX_TRANSITIONS) {
            val sorted = transitions.entries.sortedBy { it.value }
            sorted.take(transitions.size - MAX_TRANSITIONS).forEach {
                transitions.remove(it.key)
            }
        }

        val songs = parseSongs(store.getString(KEY_SONGS, null))
        songs[next.videoId] = CachedSong(
            videoId = next.videoId,
            title = next.title,
            artist = next.artist,
            thumbnailUrl = next.thumbnailUrl,
        )
        if (songs.size > MAX_SONG_CACHE) {
            songs.keys.take(songs.size - MAX_SONG_CACHE).forEach { songs.remove(it) }
        }

        store.edit()
            .putString(KEY_TRANSITIONS, json.encodeToString(transitions))
            .putString(KEY_SONGS, json.encodeToString(songs))
            .apply()
    }

    /**
     * Predicts the most likely next songs after [current], strongest first.
     * Returns cached song info; the caller resolves full [Song] objects.
     */
    internal fun predictNext(
        context: Context,
        current: Song,
        limit: Int = 5,
    ): List<CachedSong> {
        val store = prefs(context)
        val transitions = parseTransitions(store.getString(KEY_TRANSITIONS, null))
        val songs = parseSongs(store.getString(KEY_SONGS, null))
        val prefix = "${current.videoId}>"
        return transitions.entries
            .filter { it.key.startsWith(prefix) }
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { (key, _) ->
                val nextId = key.removePrefix(prefix)
                songs[nextId]
            }
    }

    /**
     * How confident the predictor is about [current] — used to decide whether
     * learned transitions should lead the autoplay queue or just season it.
     */
    fun confidence(context: Context, current: Song): Int {
        val store = prefs(context)
        val transitions = parseTransitions(store.getString(KEY_TRANSITIONS, null))
        val prefix = "${current.videoId}>"
        return transitions.entries
            .filter { it.key.startsWith(prefix) }
            .sumOf { it.value }
    }
}
