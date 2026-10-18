package com.music.sonariq.data.library

import android.content.Context
import android.net.Uri
import com.music.sonariq.BuildConfig
import com.music.sonariq.data.DebugLog as Log
import com.music.sonariq.data.model.SearchHistoryEntity
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.data.settings.SearchHistory
import com.music.sonariq.data.spotify.LocalPlaylistStore
import com.music.sonariq.data.stats.Backup
import com.music.sonariq.data.stats.ListeningStats
import com.music.sonariq.data.stats.StoredBucket
import com.music.sonariq.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The whole library in one file: everything [Backup] already carries
 * (settings, listening history) plus the things it deliberately does not —
 * local playlists, playlist folders and smart folders, search history, and
 * the record of downloaded files.
 *
 * This does not edit [Backup]; it is the superset of it, built from the same
 * public pieces. Importing one of these files restores the full library state;
 * importing one of [Backup]'s files still works through the original path.
 *
 * What it cannot restore, honestly stated rather than faked:
 *  - **Downloaded files** stay on disk whatever happens, but the record of
 *    which file belongs to which track lives in [Downloads] behind a private
 *    API — the import lists how many downloads the file names, and the files
 *    themselves are untouched. Re-registering them needs a new public API on
 *    [Downloads] (see the integration manifest).
 *  - **Party-shared playlists** are not backed up: they are owned by the party
 *    they were made in and re-sync from it. A backup pretending to own them
 *    would restore ghosts the party no longer recognises.
 */
object LibraryBackup {

    private const val TAG = "Sonariq"
    private const val APP_TAG = "sonariq"
    private const val SCHEMA_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** A suggested filename, dated so successive exports don't collide. */
    fun suggestedName(): String =
        "sonariq-library-backup-${DateTimeFormatter.ofPattern("yyyy-MM-dd").format(
            Instant.now().atZone(ZoneId.systemDefault()),
        )}.json"

    /**
     * Writes the library backup to [target], a document the user picked.
     *
     * Through the content resolver, exactly like [Backup.exportTo]: the picker
     * grants access to that one document and nothing else, which is the
     * correct amount.
     *
     * @return how many playlists were carried.
     */
    suspend fun exportTo(context: Context, target: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val file = LibraryBackupFile(
                versionName = BuildConfig.VERSION_NAME,
                exportedAt = Instant.now().toString(),
                settings = AppSettings.exportPrefs().mapNotNull { (key, value) ->
                    Backup.PrefValue.of(value)?.let { key to it }
                }.toMap(),
                listening = ListeningStats.exportAll(),
                searchHistory = SearchHistory.recent.value,
                playlists = LocalPlaylistStore.playlists.value.map { it.toStored() },
                folders = PlaylistFolders.folders.value.map { it.toStored() },
                smartFolders = PlaylistFolders.smartFolders.value.map { it.toStored() },
                downloads = Downloads.saved.value.map { (videoId, uri) ->
                    StoredDownload(videoId = videoId, uri = uri)
                },
            )
            val text = json.encodeToString(LibraryBackupFile.serializer(), file)
            context.contentResolver.openOutputStream(target, "wt")
                ?.use { it.write(text.toByteArray()) }
                ?: error("Couldn't open that file for writing")
            file.playlists.size
        }
    }

    /**
     * Reads [source] and replaces this device's library with it.
     *
     * Validated before anything is written, for the same reason [Backup]
     * validates: a half-applied import is worse than a refused one.
     */
    suspend fun importFrom(context: Context, source: Uri): Result<Summary> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(source)
                ?.use { it.readBytes().decodeToString() }
                ?: error("Couldn't open that file")
            val file = runCatching { json.decodeFromString(LibraryBackupFile.serializer(), text) }
                .getOrElse { error("That doesn't look like a Sonariq library backup") }
            require(file.app == APP_TAG) { "That backup is from another app" }
            require(file.version <= SCHEMA_VERSION) {
                "That backup was written by a newer version of Sonariq"
            }

            ListeningStats.importAll(file.listening)
            AppSettings.importPrefs(file.settings.mapValues { it.value.decoded() })
            // Shares AppSettings' preference file, so the import above has
            // already replaced what is stored here — it just doesn't know yet.
            SearchHistory.reload()
            // The explicit list then wins over whatever the settings import
            // restored, because it is the record this file actually carries.
            if (file.searchHistory.isNotEmpty()) {
                SearchHistory.clear()
                file.searchHistory.asReversed().forEach { SearchHistory.record(it) }
            }

            val titleToId = importPlaylists(file.playlists)
            importFolders(file.folders, titleToId)
            importSmartFolders(file.smartFolders)

            Summary(
                playlists = file.playlists.size,
                folders = file.folders.size + file.smartFolders.size,
                downloads = file.downloads.size,
                months = file.listening.size,
                settings = file.settings.size,
                from = file.versionName,
                at = file.exportedAt,
            )
        }
    }

    /**
     * Playlists, merged by title rather than replaced.
     *
     * A playlist with the same title already on the device keeps its place and
     * gains the backup's songs (deduplicated by video id); one with no match
     * is created fresh. Merging rather than replacing is what makes importing
     * twice, or importing onto a device that kept listening, idempotent
     * instead of duplicating everything.
     *
     * @return the backup title to the live playlist id, for folder remapping.
     */
    private fun importPlaylists(playlists: List<StoredPlaylist>): Map<String, String> {
        val titleToId = mutableMapOf<String, String>()
        for (stored in playlists) {
            val songs = stored.songs.map { it.toSong() }
            val existing = LocalPlaylistStore.playlists.value
                .firstOrNull { it.title.equals(stored.title, ignoreCase = true) }
            if (existing == null) {
                val created = LocalPlaylistStore.savePlaylist(stored.title, songs)
                titleToId[stored.title] = created.id
            } else {
                val merged = (existing.songs + songs).distinctBy { it.videoId }
                LocalPlaylistStore.deletePlaylist(existing.id)
                val recreated = LocalPlaylistStore.savePlaylist(existing.title, merged)
                titleToId[stored.title] = recreated.id
            }
        }
        return titleToId
    }

    /**
     * Folders, with playlist membership remapped through the import above.
     *
     * Each entry is resolved by id first — an id match means the playlist
     * survived the move untouched — and then by title, which is what the
     * merge above renamed things into. Entries that match neither are dropped
     * rather than kept as dead links.
     */
    private fun importFolders(
        folders: List<StoredFolder>,
        titleToId: Map<String, String>,
    ) {
        val live = LocalPlaylistStore.playlists.value
        for (stored in folders) {
            val folder = PlaylistFolders.createFolder(stored.title)
            val titleToIdLower = titleToId.mapKeys { it.key.lowercase() }
            for (entry in stored.entries) {
                val id = live.firstOrNull { it.id == entry.id || it.browseId == entry.id }?.id
                    ?: titleToIdLower[entry.title.lowercase()]
                    ?: live.firstOrNull { it.title.equals(entry.title, ignoreCase = true) }?.id
                    ?: continue
                PlaylistFolders.addPlaylistToFolder(folder.id, id)
            }
        }
    }

    private fun importSmartFolders(smartFolders: List<StoredSmartFolder>) {
        for (stored in smartFolders) {
            PlaylistFolders.createSmartFolder(stored.title, stored.rules, stored.matchAll)
        }
    }

    /** What an import turned out to contain, for the line shown afterwards. */
    data class Summary(
        val playlists: Int,
        val folders: Int,
        val downloads: Int,
        val months: Int,
        val settings: Int,
        val from: String,
        val at: String,
    )

    @Serializable
    data class LibraryBackupFile(
        val app: String = APP_TAG,
        val version: Int = SCHEMA_VERSION,
        /** Which build wrote it — for the reader, not for any logic here. */
        val versionName: String = "",
        val exportedAt: String = "",
        val settings: Map<String, Backup.PrefValue> = emptyMap(),
        val listening: List<StoredBucket> = emptyList(),
        val searchHistory: List<SearchHistoryEntity> = emptyList(),
        val playlists: List<StoredPlaylist> = emptyList(),
        val folders: List<StoredFolder> = emptyList(),
        val smartFolders: List<StoredSmartFolder> = emptyList(),
        /** Informational on import: see the class doc. */
        val downloads: List<StoredDownload> = emptyList(),
    )

    /** A playlist, with just the song fields a restore can faithfully replay. */
    @Serializable
    data class StoredPlaylist(
        val id: String,
        val title: String,
        val songs: List<StoredSong> = emptyList(),
    )

    /** A folder entry keeps the title alongside the id so imports can remap. */
    @Serializable
    data class StoredFolder(
        val id: String,
        val title: String,
        val entries: List<StoredFolderEntry> = emptyList(),
    )

    @Serializable
    data class StoredFolderEntry(
        val id: String,
        val title: String,
    )

    @Serializable
    data class StoredSmartFolder(
        val id: String,
        val title: String,
        val rules: List<PlaylistFolders.SmartRule> = emptyList(),
        val matchAll: Boolean = true,
    )

    @Serializable
    data class StoredDownload(
        val videoId: String,
        val uri: String,
    )

    /**
     * The song fields a backup can honestly carry. Queue state, playback
     * source and other session fields are deliberately left out — restoring
     * them would be restoring somebody else's afternoon.
     */
    @Serializable
    data class StoredSong(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String? = null,
        val durationText: String? = null,
        val artistId: String? = null,
        val albumId: String? = null,
        val albumName: String? = null,
        val isVideo: Boolean = false,
    ) {
        fun toSong(): Song = Song(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            durationText = durationText,
            artistId = artistId,
            albumId = albumId,
            albumName = albumName,
            isVideo = isVideo,
        )
    }

    private fun com.music.sonariq.data.spotify.LocalPlaylist.toStored() = StoredPlaylist(
        id = id,
        title = title,
        songs = songs.map { song ->
            StoredSong(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                durationText = song.durationText,
                artistId = song.artistId,
                albumId = song.albumId,
                albumName = song.albumName,
                isVideo = song.isVideo,
            )
        },
    )

    private fun PlaylistFolder.toStored() = StoredFolder(
        id = id,
        title = title,
        entries = playlistIds.map { playlistId ->
            val playlist = LocalPlaylistStore.getPlaylist(playlistId)
            StoredFolderEntry(id = playlistId, title = playlist?.title ?: "")
        },
    )

    private fun SmartFolder.toStored() = StoredSmartFolder(
        id = id,
        title = title,
        rules = rules,
        matchAll = matchAll,
    )
}
