package com.music.sonariq.data.collab

import android.content.Context
import android.content.SharedPreferences
import com.music.sonariq.data.DebugLog as Log
import com.music.sonariq.data.listentogether.ListenTogether
import com.music.sonariq.data.listentogether.PartyMember
import com.music.sonariq.data.listentogether.PartyTrack
import com.music.sonariq.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A playlist that friends build together through a Listen Together party.
 *
 * The playlist itself lives on this device ([CollabPlaylistStore] persists
 * it); the *party* is what makes it collaborative. The party's shared queue
 * is the one transport every member's device already reads and writes, so
 * sync runs through it in both directions while this device is connected:
 *
 * - **Outbound:** a song added here is pushed into the party queue with
 *   [ListenTogether.queueAdd], so the whole party can play it immediately.
 * - **Inbound:** songs friends add through the party's own add-songs UI
 *   land in the queue, and the store folds them into the playlist as
 *   contributions.
 *
 * The one honest gap is attribution across devices: the party protocol
 * carries no per-track adder ([PartyTrack] is `{videoId, title, artist,
 * ...}` and nothing more), so a contribution that arrived through the
 * queue reads as "added via party" rather than naming a friend. What this
 * device added itself is attributed exactly, with the member's name.
 */
@Serializable
data class CollabMember(
    val userId: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
)

@Serializable
data class CollabContribution(
    val videoId: String,
    val title: String,
    val artist: String = "",
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    /**
     * Null when the track arrived through the party queue — the protocol
     * carries no adder, so there is nobody honest to name. The UI reads
     * these as "added via party".
     */
    val addedByUserId: String = "",
    val addedByName: String? = null,
    val addedByAvatarUrl: String? = null,
    val addedAtMs: Long = 0L,
)

@Serializable
data class CollabPlaylist(
    val id: String,
    val title: String,
    val ownerUserId: String = "",
    val ownerName: String = "",
    /** The party this playlist syncs through; null once it outlives the party. */
    val partyCode: String? = null,
    val members: List<CollabMember> = emptyList(),
    val contributions: List<CollabContribution> = emptyList(),
    /** Contribution ids already pushed into the party queue. */
    val syncedVideoIds: Set<String> = emptySet(),
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

object CollabPlaylistStore {

    private const val PREFS = "sonariq_collab_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var prefs: SharedPreferences

    private val _playlists = MutableStateFlow<List<CollabPlaylist>>(emptyList())
    val playlists: StateFlow<List<CollabPlaylist>> = _playlists.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
        scope.launch {
            ListenTogether.state.collect { onPartyState(it) }
        }
    }

    private fun load() {
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return
        runCatching {
            json.decodeFromString<List<CollabPlaylist>>(raw)
        }.onSuccess { list ->
            _playlists.value = list
        }.onFailure {
            Log.w("CollabPlaylists", "could not read playlists: ${it.message}")
        }
    }

    private fun persist() {
        if (!this::prefs.isInitialized) return
        runCatching {
            prefs.edit()
                .putString(KEY_PLAYLISTS, json.encodeToString(_playlists.value))
                .apply()
        }.onFailure {
            Log.w("CollabPlaylists", "could not save playlists: ${it.message}")
        }
    }

    private inline fun updatePlaylist(playlistId: String, transform: (CollabPlaylist) -> CollabPlaylist) {
        var changed = false
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) {
                changed = true
                transform(playlist)
            } else {
                playlist
            }
        }
        if (changed) persist()
    }

    /**
     * Starts a collaborative playlist from the party this device is in now:
     * the owner is this device's party identity, the members are the party's
     * members. Null when there is no party (or no identity in it) to build
     * from — creation is only offered while in a party.
     */
    fun createFromParty(title: String, state: ListenTogether.State): CollabPlaylist? {
        val trimmed = title.trim().take(120)
        if (trimmed.isEmpty()) return null
        val you = state.you ?: return null
        val code = state.code ?: return null
        val now = System.currentTimeMillis()
        val playlist = CollabPlaylist(
            id = UUID.randomUUID().toString(),
            title = trimmed,
            ownerUserId = you.userId,
            ownerName = you.displayName,
            partyCode = code,
            members = state.members.map { it.toCollabMember() },
            createdAtMs = now,
            updatedAtMs = now,
        )
        _playlists.value = _playlists.value + playlist
        persist()
        return playlist
    }

    /**
     * Adds a song as this device's party identity, then pushes it into the
     * party queue when connected so every member sees it. False when the song
     * is already in the playlist or there is no identity to attribute it to.
     */
    fun addSong(playlistId: String, song: Song): Boolean {
        val you = ListenTogether.state.value.you ?: return false
        var added = false
        updatePlaylist(playlistId) { playlist ->
            if (playlist.contributions.any { it.videoId == song.videoId }) {
                playlist
            } else {
                added = true
                playlist.copy(
                    contributions = playlist.contributions + CollabContribution(
                        videoId = song.videoId,
                        title = song.title,
                        artist = song.artist,
                        thumbnailUrl = song.thumbnailUrl,
                        durationText = song.durationText,
                        addedByUserId = you.userId,
                        addedByName = you.displayName,
                        addedByAvatarUrl = you.avatarUrl,
                        addedAtMs = System.currentTimeMillis(),
                    ),
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
        }
        if (added) syncPlaylist(playlistId)
        return added
    }

    fun removeSong(playlistId: String, videoId: String) {
        updatePlaylist(playlistId) { playlist ->
            playlist.copy(
                contributions = playlist.contributions.filterNot { it.videoId == videoId },
                syncedVideoIds = playlist.syncedVideoIds - videoId,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun deletePlaylist(playlistId: String) {
        _playlists.value = _playlists.value.filterNot { it.id == playlistId }
        persist()
    }

    /**
     * Plays the whole playlist through the party: every contribution is
     * queued on the shared queue, so the party hears the playlist as one.
     * Only while in the playlist's own party.
     */
    fun playInParty(playlistId: String): Boolean {
        val state = ListenTogether.state.value
        if (!state.inParty || state.connection != ListenTogether.Connection.LIVE) return false
        val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return false
        if (playlist.partyCode != state.code || playlist.contributions.isEmpty()) return false
        val tracks = playlist.contributions.map { it.toPartyTrack() }
        ListenTogether.queueAdd(tracks)
        ListenTogether.markOwnQueueAdditions(playlist.contributions.map { it.videoId })
        updatePlaylist(playlistId) { it.copy(syncedVideoIds = it.syncedVideoIds + playlist.contributions.map { c -> c.videoId }) }
        return true
    }

    /**
     * Pushes this playlist's not-yet-seen contributions into the party queue.
     * Called after a local add and on every party-state update, and idempotent
     * on both: a contribution already in the queue or already marked synced is
     * never pushed twice, and a push the socket dropped is retried on the next
     * state update because the track is still neither queued nor marked.
     */
    fun syncPlaylist(playlistId: String) {
        val state = ListenTogether.state.value
        if (!state.inParty || state.connection != ListenTogether.Connection.LIVE) return
        val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        if (playlist.partyCode != state.code) return
        val queueIds = state.queue.items.map { it.videoId }.toSet()
        val unsynced = playlist.contributions.filter {
            it.videoId !in playlist.syncedVideoIds && it.videoId !in queueIds
        }
        if (unsynced.isEmpty()) return
        ListenTogether.queueAdd(unsynced.map { it.toPartyTrack() })
        ListenTogether.markOwnQueueAdditions(unsynced.map { it.videoId })
        updatePlaylist(playlistId) { it.copy(syncedVideoIds = it.syncedVideoIds + unsynced.map { c -> c.videoId }) }
    }

    /**
     * Keeps every playlist of the current party in sync with it: the member
     * list follows the party's, friends' queue additions are folded in as
     * contributions, and local additions not yet seen are pushed out.
     */
    private fun onPartyState(state: ListenTogether.State) {
        val code = state.code
        if (!state.inParty || code == null) return
        var changed = false
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.partyCode != code) {
                playlist
            } else {
                var next = playlist
                val members = state.members.map { it.toCollabMember() }
                if (members != next.members) {
                    next = next.copy(members = members)
                    changed = true
                }
                if (state.connection == ListenTogether.Connection.LIVE) {
                    val knownIds = next.contributions.map { it.videoId }.toSet()
                    // AutoPlay's top-ups are the party's, not the playlist's.
                    val inbound = state.queue.items.filter { !it.fromAutoplay && it.videoId !in knownIds }
                    if (inbound.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        next = next.copy(
                            contributions = next.contributions + inbound.map { track ->
                                CollabContribution(
                                    videoId = track.videoId,
                                    title = track.title,
                                    artist = track.artist,
                                    thumbnailUrl = track.thumbnailUrl,
                                    addedAtMs = now,
                                )
                            },
                            updatedAtMs = now,
                        )
                        changed = true
                    }
                    val queueIds = state.queue.items.map { it.videoId }.toSet()
                    val unsynced = next.contributions.filter {
                        it.videoId !in next.syncedVideoIds && it.videoId !in queueIds
                    }
                    if (unsynced.isNotEmpty()) {
                        ListenTogether.queueAdd(unsynced.map { it.toPartyTrack() })
                        ListenTogether.markOwnQueueAdditions(unsynced.map { it.videoId })
                        next = next.copy(syncedVideoIds = next.syncedVideoIds + unsynced.map { it.videoId })
                        changed = true
                    }
                }
                next
            }
        }
        if (changed) persist()
    }

    private fun PartyMember.toCollabMember() = CollabMember(
        userId = userId,
        displayName = displayName,
        avatarUrl = avatarUrl,
    )

    private fun CollabContribution.toPartyTrack(): PartyTrack = PartyTrack(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
    )
}
