package com.music.sonariq.playback

import com.music.sonariq.data.model.Song
import com.music.sonariq.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Queue 2.0: named saved queues plus a history of what the queue actually
 * played.
 *
 * The existing queue UI ([InlineQueue][com.music.sonariq.ui.player.InlineQueue])
 * is untouched — this is the storage and the logic behind the new queue
 * manager screen. A saved queue is a snapshot of tracks, not live player
 * state: loading one starts playback of those tracks, and the snapshot is
 * deliberately small ([QueueTrackSnapshot]) so dozens of queues stay cheap.
 *
 * New AppSettings key (Phase 2 adds, same pattern as the existing settings):
 * - `savedQueuesJson`: String, default `""`
 */
object SavedQueues {

    /** A named queue snapshot. */
    @Serializable
    data class SavedQueue(
        val id: String,
        val name: String,
        val tracks: List<QueueTrackSnapshot>,
        val createdAt: Long,
        val updatedAt: Long,
    )

    /** Everything needed to rebuild a playable [Song] from a snapshot. */
    @Serializable
    data class QueueTrackSnapshot(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String?,
        val durationText: String?,
    )

    /** Most saved queues kept. Enough for real use, small enough to stay cheap. */
    const val MAX_SAVED_QUEUES = 25

    /** Most tracks kept per queue. */
    const val MAX_TRACKS_PER_QUEUE = 500

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun Song.toSnapshot(): QueueTrackSnapshot = QueueTrackSnapshot(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
    )

    fun QueueTrackSnapshot.toSong(): Song = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
    )

    /** Builds a new queue; blank names are refused rather than stored empty. */
    fun create(name: String, tracks: List<Song>): SavedQueue? {
        val trimmed = name.trim().take(60)
        if (trimmed.isEmpty() || tracks.isEmpty()) return null
        val now = System.currentTimeMillis()
        return SavedQueue(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            tracks = tracks.take(MAX_TRACKS_PER_QUEUE).map { it.toSnapshot() },
            createdAt = now,
            updatedAt = now,
        )
    }

    /** Adds a queue, newest first, dropping the oldest past [MAX_SAVED_QUEUES]. */
    fun add(queues: List<SavedQueue>, queue: SavedQueue): List<SavedQueue> =
        (listOf(queue) + queues.filter { it.id != queue.id })
            .sortedByDescending { it.updatedAt }
            .take(MAX_SAVED_QUEUES)

    fun rename(queues: List<SavedQueue>, id: String, name: String): List<SavedQueue> {
        val trimmed = name.trim().take(60)
        if (trimmed.isEmpty()) return queues
        return queues.map {
            if (it.id == id) it.copy(name = trimmed, updatedAt = System.currentTimeMillis()) else it
        }
    }

    fun remove(queues: List<SavedQueue>, id: String): List<SavedQueue> =
        queues.filter { it.id != id }

    /** Replaces a queue's tracks (from the reorder screen), capping length. */
    fun setTracks(queue: SavedQueue, tracks: List<QueueTrackSnapshot>): SavedQueue =
        queue.copy(
            tracks = tracks.take(MAX_TRACKS_PER_QUEUE),
            updatedAt = System.currentTimeMillis(),
        )

    /** Moves one track inside a saved queue. Pure, shared with the UI and tests. */
    fun moveTrack(queue: SavedQueue, from: Int, to: Int): SavedQueue {
        if (from !in queue.tracks.indices) return queue
        val mutable = queue.tracks.toMutableList()
        val track = mutable.removeAt(from)
        mutable.add(to.coerceIn(0, mutable.size), track)
        return setTracks(queue, mutable)
    }

    fun removeTrack(queue: SavedQueue, index: Int): SavedQueue {
        if (index !in queue.tracks.indices) return queue
        return setTracks(queue, queue.tracks.toMutableList().apply { removeAt(index) })
    }

    fun encode(queues: List<SavedQueue>): String = json.encodeToString(queues)

    fun decode(raw: String): List<SavedQueue> {
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<SavedQueue>>(raw) }
            .getOrDefault(emptyList())
            .take(MAX_SAVED_QUEUES)
            .map { it.copy(tracks = it.tracks.take(MAX_TRACKS_PER_QUEUE)) }
    }
}

/**
 * The persisted saved queues, behind [AppSettings.savedQueuesJson]. The only
 * writer, so the manager screen and the service-side snapshot helper below
 * never disagree about the format.
 */
object SavedQueueStore {

    private val _queues = MutableStateFlow<List<SavedQueues.SavedQueue>>(emptyList())
    val queues: StateFlow<List<SavedQueues.SavedQueue>> = _queues.asStateFlow()

    /** Call once at startup, after AppSettings.init. */
    fun load() {
        _queues.value = SavedQueues.decode(AppSettings.savedQueuesJson.value)
    }

    private fun persist(queues: List<SavedQueues.SavedQueue>) {
        _queues.value = queues
        AppSettings.savedQueuesJson.value = SavedQueues.encode(queues)
    }

    fun saveQueue(name: String, tracks: List<Song>): SavedQueues.SavedQueue? {
        val queue = SavedQueues.create(name, tracks) ?: return null
        persist(SavedQueues.add(_queues.value, queue))
        return queue
    }

    fun rename(id: String, name: String) {
        persist(SavedQueues.rename(_queues.value, id, name))
    }

    fun delete(id: String) {
        persist(SavedQueues.remove(_queues.value, id))
    }

    fun updateTracks(id: String, tracks: List<SavedQueues.QueueTrackSnapshot>) {
        persist(_queues.value.map {
            if (it.id == id) SavedQueues.setTracks(it, tracks) else it
        })
    }
}

/**
 * What the queue actually played, newest first. Fed from the playback
 * service's track transitions (Phase 2 hooks [notePlayed] where the current
 * track changes); the history view in the queue manager reads [entries].
 *
 * In-memory on purpose: it answers "what did I just hear", not "what did I
 * hear last month" — that is the Replay stats' job. Cleared on process death
 * rather than persisted half-true.
 */
object QueueHistoryLog {

    /** Entries kept; the session's tail, not its whole biography. */
    const val MAX_ENTRIES = 50

    private val _entries = MutableStateFlow<List<SavedQueues.QueueTrackSnapshot>>(emptyList())
    val entries: StateFlow<List<SavedQueues.QueueTrackSnapshot>> = _entries.asStateFlow()

    @Synchronized
    fun notePlayed(song: Song) {
        val snapshot = with(SavedQueues) { song.toSnapshot() }
        val withoutDup = _entries.value.filter { it.videoId != snapshot.videoId }
        _entries.value = (listOf(snapshot) + withoutDup).take(MAX_ENTRIES)
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }
}
