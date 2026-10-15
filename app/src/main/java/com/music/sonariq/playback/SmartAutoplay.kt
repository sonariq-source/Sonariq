package com.music.sonariq.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.music.sonariq.data.model.Song
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
 * Stored as a compact JSON map in DataStore: "videoIdA>videoIdB" → count,
 * plus a small song-info cache so predictions can show titles without a
 * network round-trip. Capped to keep the store small.
 */
private val Context.smartAutoplayStore by preferencesDataStore("smart_autoplay")

private const val MAX_TRANSITIONS = 2000
private const val MAX_SONG_CACHE = 500

@Serializable
private data class CachedSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
)

object SmartAutoplay {

    private val json = Json { ignoreUnknownKeys = true }
    private val transitionsKey = stringPreferencesKey("transitions")
    private val songsKey = stringPreferencesKey("songs")

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
    suspend fun recordTransition(context: Context, previous: Song, next: Song) {
        if (previous.videoId == next.videoId) return
        val store = context.smartAutoplayStore
        store.edit { prefs ->
            val transitions = parseTransitions(prefs[transitionsKey])
            val key = "${previous.videoId}>${next.videoId}"
            transitions[key] = (transitions[key] ?: 0) + 1
            // Keep the map bounded: drop the weakest links.
            if (transitions.size > MAX_TRANSITIONS) {
                val sorted = transitions.entries.sortedBy { it.value }
                sorted.take(transitions.size - MAX_TRANSITIONS).forEach {
                    transitions.remove(it.key)
                }
            }
            prefs[transitionsKey] = json.encodeToString(transitions)

            val songs = parseSongs(prefs[songsKey])
            songs[next.videoId] = CachedSong(
                videoId = next.videoId,
                title = next.title,
                artist = next.artist,
                thumbnailUrl = next.thumbnailUrl,
            )
            if (songs.size > MAX_SONG_CACHE) {
                songs.keys.take(songs.size - MAX_SONG_CACHE).forEach { songs.remove(it) }
            }
            prefs[songsKey] = json.encodeToString(songs)
        }
    }

    /**
     * Predicts the most likely next songs after [current], strongest first.
     * Returns cached song info; the caller resolves full [Song] objects.
     */
    suspend fun predictNext(
        context: Context,
        current: Song,
        limit: Int = 5,
    ): List<CachedSong> {
        val store = context.smartAutoplayStore
        val prefs = store.data.first()
        val transitions = parseTransitions(prefs[transitionsKey])
        val songs = parseSongs(prefs[songsKey])
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
    suspend fun confidence(context: Context, current: Song): Int {
        val store = context.smartAutoplayStore
        val transitions = parseTransitions(store.data.map { it[transitionsKey] }.first())
        val prefix = "${current.videoId}>"
        return transitions.entries
            .filter { it.key.startsWith(prefix) }
            .sumOf { it.value }
    }
}
