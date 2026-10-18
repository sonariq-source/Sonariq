package com.music.sonariq.data.library

import android.content.Context
import android.content.SharedPreferences
import com.music.sonariq.data.DebugLog as Log
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.stats.TrackStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Folders that hold playlists, and smart folders that fill themselves.
 *
 * A plain [PlaylistFolder] is just an ordered set of playlist ids — the
 * playlists themselves still live where they always have, in the local
 * playlist store, so a folder is a view over them rather than a second copy.
 * Deleting a folder never touches the playlists inside it.
 *
 * A [SmartFolder] is a saved set of [SmartRule]s evaluated against real
 * library metadata every time the folder is opened. The rules are limited on
 * purpose to fields the app actually has: artist/album text, the date a track
 * was added to the device, play counts and last-played dates from listening
 * history, the explicit flag, and the served quality. There is deliberately no
 * genre rule and no star-rating rule — neither exists anywhere in this app's
 * metadata, and a rule over invented data would be a lie dressed as a filter.
 */
object PlaylistFolders {

    private const val TAG = "Sonariq"
    private const val PREF_NAME = "sonariq_playlist_folders"
    private const val KEY_FOLDERS = "folders_json"
    private const val KEY_SMART = "smart_folders_json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private lateinit var prefs: SharedPreferences

    private val _folders = MutableStateFlow<List<PlaylistFolder>>(emptyList())
    val folders = _folders.asStateFlow()

    private val _smartFolders = MutableStateFlow<List<SmartFolder>>(emptyList())
    val smartFolders = _smartFolders.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        load()
    }

    // ── Rules ────────────────────────────────────────────────────────────────

    /**
     * One test a song either passes or fails. Every rule names a field that
     * exists on [Song] or on [TrackStats]; adding a rule over a field neither
     * has is a compile-time-shaped mistake this file refuses to make.
     */
    @Serializable
    sealed class SmartRule {
        /** The artist line contains this text, case-insensitive. */
        @Serializable @SerialName("artist")
        data class ArtistContains(val text: String) : SmartRule()

        /** The album name contains this text, case-insensitive. */
        @Serializable @SerialName("album")
        data class AlbumContains(val text: String) : SmartRule()

        /** Added to the device within the last [days] days. */
        @Serializable @SerialName("addedDays")
        data class AddedInLastDays(val days: Int) : SmartRule()

        /** Played at least [plays] times, all time. */
        @Serializable @SerialName("minPlays")
        data class MinPlays(val plays: Int) : SmartRule()

        /** Played at most [plays] times, all time. */
        @Serializable @SerialName("maxPlays")
        data class MaxPlays(val plays: Int) : SmartRule()

        /** Not played in the last [days] days. */
        @Serializable @SerialName("quietDays")
        data class NotPlayedInDays(val days: Int) : SmartRule()

        /** The catalogue's explicit-content flag, when stated. */
        @Serializable @SerialName("explicit")
        data class IsExplicit(val explicit: Boolean) : SmartRule()

        /** The served quality rung: `LOSSLESS`, `HIGH` or `LOW`. */
        @Serializable @SerialName("quality")
        data class SourceQuality(val quality: String) : SmartRule()
    }

    /**
     * Whether [song] satisfies [rule].
     *
     * @param stats this track's listening record, or null when it has never
     *   been played on this device — rules over play history then fail rather
     *   than treating silence as zero, because "never played" is not "played
     *   zero times".
     * @param nowMs the clock rules about recency are measured against.
     */
    fun matches(
        rule: SmartRule,
        song: Song,
        stats: TrackStats?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = when (rule) {
        is SmartRule.ArtistContains ->
            rule.text.isNotBlank() && song.artist.contains(rule.text, ignoreCase = true)
        is SmartRule.AlbumContains ->
            rule.text.isNotBlank() && (song.albumName ?: "").contains(rule.text, ignoreCase = true)
        is SmartRule.AddedInLastDays -> {
            val addedMs = (song.localDateAddedSeconds ?: return false) * 1_000L
            rule.days > 0 && nowMs - addedMs <= rule.days * DAY_MS && addedMs <= nowMs
        }
        is SmartRule.MinPlays -> (stats?.plays ?: return false) >= rule.plays
        is SmartRule.MaxPlays -> (stats?.plays ?: return false) <= rule.plays
        is SmartRule.NotPlayedInDays -> {
            val last = stats?.lastPlayedMs ?: return false
            rule.days > 0 && last > 0 && nowMs - last >= rule.days * DAY_MS
        }
        is SmartRule.IsExplicit -> song.isExplicit == rule.explicit
        is SmartRule.SourceQuality ->
            song.sourceQuality?.equals(rule.quality, ignoreCase = true) == true
    }

    /**
     * The songs of [folder]: every song passing all of its rules when
     * [SmartFolder.matchAll] is true, or any of them otherwise. Most-played
     * first when the folder has any play-count rule (that is the reading a
     * "top" folder expects), otherwise in the order the songs arrived.
     */
    fun evaluate(
        folder: SmartFolder,
        songs: List<Song>,
        statsById: Map<String, TrackStats>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Song> {
        if (folder.rules.isEmpty()) return emptyList()
        val matched = songs.filter { song ->
            val stats = statsById[song.videoId]
            if (folder.matchAll) folder.rules.all { matches(it, song, stats, nowMs) }
            else folder.rules.any { matches(it, song, stats, nowMs) }
        }
        return if (folder.rules.any { it is SmartRule.MinPlays || it is SmartRule.MaxPlays }) {
            matched.sortedByDescending { statsById[it.videoId]?.plays ?: 0 }
        } else {
            matched
        }
    }

    /** A one-line, human reading of a rule, for the folder editor. */
    fun describe(rule: SmartRule): String = when (rule) {
        is SmartRule.ArtistContains -> "Artist contains \"${rule.text}\""
        is SmartRule.AlbumContains -> "Album contains \"${rule.text}\""
        is SmartRule.AddedInLastDays -> "Added in the last ${rule.days} days"
        is SmartRule.MinPlays -> "Played at least ${rule.plays} times"
        is SmartRule.MaxPlays -> "Played at most ${rule.plays} times"
        is SmartRule.NotPlayedInDays -> "Not played in ${rule.days} days"
        is SmartRule.IsExplicit -> if (rule.explicit) "Explicit" else "Not explicit"
        is SmartRule.SourceQuality -> "Quality: ${rule.quality}"
    }

    // ── Plain folders ────────────────────────────────────────────────────────

    fun createFolder(title: String): PlaylistFolder {
        val folder = PlaylistFolder(
            id = "folder_" + System.currentTimeMillis(),
            title = title.trim().ifEmpty { "Folder" },
            playlistIds = emptyList(),
        )
        _folders.value = _folders.value + folder
        persist()
        return folder
    }

    fun renameFolder(id: String, title: String) {
        _folders.value = _folders.value.map {
            if (it.id == id) it.copy(title = title.trim().ifEmpty { it.title }) else it
        }
        persist()
    }

    fun deleteFolder(id: String) {
        _folders.value = _folders.value.filterNot { it.id == id }
        persist()
    }

    fun addPlaylistToFolder(folderId: String, playlistId: String) {
        _folders.value = _folders.value.map {
            if (it.id == folderId && playlistId !in it.playlistIds) {
                it.copy(playlistIds = it.playlistIds + playlistId)
            } else it
        }
        persist()
    }

    fun removePlaylistFromFolder(folderId: String, playlistId: String) {
        _folders.value = _folders.value.map {
            if (it.id == folderId) it.copy(playlistIds = it.playlistIds - playlistId) else it
        }
        persist()
    }

    // ── Smart folders ────────────────────────────────────────────────────────

    fun createSmartFolder(title: String, rules: List<SmartRule>, matchAll: Boolean): SmartFolder {
        val folder = SmartFolder(
            id = "smart_" + System.currentTimeMillis(),
            title = title.trim().ifEmpty { "Smart folder" },
            rules = rules,
            matchAll = matchAll,
        )
        _smartFolders.value = _smartFolders.value + folder
        persist()
        return folder
    }

    fun updateSmartFolder(folder: SmartFolder) {
        _smartFolders.value = _smartFolders.value.map { if (it.id == folder.id) folder else it }
        persist()
    }

    fun deleteSmartFolder(id: String) {
        _smartFolders.value = _smartFolders.value.filterNot { it.id == id }
        persist()
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private fun load() {
        if (!this::prefs.isInitialized) return
        _folders.value = runCatching {
            json.decodeFromString(
                ListSerializer(PlaylistFolder.serializer()),
                prefs.getString(KEY_FOLDERS, "[]") ?: "[]",
            )
        }.onFailure { Log.w(TAG, "could not read playlist folders: ${it.message}") }
            .getOrDefault(emptyList())
        _smartFolders.value = runCatching {
            json.decodeFromString(
                ListSerializer(SmartFolder.serializer()),
                prefs.getString(KEY_SMART, "[]") ?: "[]",
            )
        }.onFailure { Log.w(TAG, "could not read smart folders: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun persist() {
        if (!this::prefs.isInitialized) return
        runCatching {
            prefs.edit()
                .putString(KEY_FOLDERS, json.encodeToString(ListSerializer(PlaylistFolder.serializer()), _folders.value))
                .putString(KEY_SMART, json.encodeToString(ListSerializer(SmartFolder.serializer()), _smartFolders.value))
                .apply()
        }.onFailure { Log.w(TAG, "could not save playlist folders: ${it.message}") }
    }

    private const val DAY_MS = 24L * 60 * 60 * 1_000L
}

/** A named set of playlist ids. The playlists live in the playlist store; this is a view. */
@Serializable
data class PlaylistFolder(
    val id: String,
    val title: String,
    val playlistIds: List<String> = emptyList(),
)

/** A folder whose contents are recomputed from [rules] every time it opens. */
@Serializable
data class SmartFolder(
    val id: String,
    val title: String,
    val rules: List<PlaylistFolders.SmartRule> = emptyList(),
    /** True: every rule must pass. False: any rule passing is enough. */
    val matchAll: Boolean = true,
)
