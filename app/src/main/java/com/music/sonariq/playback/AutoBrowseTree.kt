package com.music.sonariq.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.music.sonariq.data.model.Song

/**
 * Android Auto overhaul: a browse tree built for the car.
 *
 * The car screen is ten feet away and the driver is driving, so this tree is
 * deliberately shallow and wide: six big top-level sections, each opening
 * onto a flat list with large touch targets, instead of the old nested
 * Quick-picks/Recents/Playlists/Liked/More maze. The rows themselves are the
 * car's native browse rows — what we control is the structure, the artwork,
 * and the content-style hints (big grid cards for collections, full-width
 * list rows for tracks).
 *
 * This file owns the *structure* and the pure MediaItem mapping. The data
 * comes from [AutoDataSource], which Phase 2 implements inside PlaybackService
 * on top of the snapshot helpers the current tree already uses
 * (`snapshotQuickPicks`, `snapshotRecents`, `snapshotLiked`, the cached home
 * feed's mood shelves, the download store and the local library). The
 * MediaLibraryCallback's `onGetChildren` then answers `MEDIA_ROOT_ID` with
 * [topLevelItems] and every other id with [children], replacing the current
 * folder switch — the existing `MEDIA_*_ID` constants can be retired or kept
 * as aliases in that edit.
 */
object AutoBrowseTree {

    // ── Content-style extras, mirrored from the service's current builder ────

    const val EXTRA_CONTENT_STYLE_SUPPORTED = "android.media.browse.extra.CONTENT_STYLE_SUPPORTED"
    const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_BROWSABLE_HINT"
    const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_PLAYABLE_HINT"
    const val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1
    const val CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 4

    /** The new top-level sections. Stable ids; the car caches them. */
    enum class AutoSection(
        val id: String,
        /** True where big artwork cards beat a text list. */
        val grid: Boolean,
        val folderType: Int,
    ) {
        NOW_PLAYING("auto2:now_playing", grid = false, folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
        MOODS("auto2:moods", grid = true, folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
        QUICK_PICKS("auto2:quick_picks", grid = false, folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
        RECENTS("auto2:recents", grid = false, folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
        LIBRARY("auto2:library", grid = true, folderType = MediaMetadata.FOLDER_TYPE_MIXED),
        DOWNLOADS("auto2:downloads", grid = false, folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
    }

    /** Sub-sections of [AutoSection.LIBRARY]. */
    enum class LibrarySection(val id: String) {
        PLAYLISTS("auto2:library:playlists"),
        LIKED("auto2:library:liked"),
        LOCAL_MUSIC("auto2:library:local"),
    }

    /** The top level, in car order: what you're hearing, then how you pick. */
    fun topLevelSections(): List<AutoSection> = AutoSection.entries.toList()

    // ── Data ────────────────────────────────────────────────────────────────

    /**
     * Everything the tree needs, supplied by the service. Each function
     * returns the same lists the current Auto tree already snapshots —
     * nothing new is fetched for the car.
     */
    interface AutoDataSource {
        fun quickPicks(): List<Song>
        fun recents(): List<Song>
        fun liked(): List<Song>
        fun downloads(): List<Song>
        fun localTracks(): List<Song>
        /** The home feed's mood/genre shelves, if the feed has loaded. */
        fun moodShelves(): List<MoodShelf>
        /** The user's playlists, newest first. */
        fun playlists(): List<AutoPlaylist>

        /**
         * The tracks behind a mood shelf. Empty by default: sources that only
         * list the shelves (no track endpoint wired yet) still get the
         * browsable shelf rows, and the folder opens empty rather than failing.
         */
        fun moodTracks(moodId: String): List<Song> = emptyList()

        /**
         * The tracks of a playlist by id. Empty by default, for the same
         * reason as [moodTracks].
         */
        fun playlistTracks(playlistId: String): List<Song> = emptyList()
    }

    data class MoodShelf(
        val id: String,
        val title: String,
        val artworkUrl: String?,
    )

    data class AutoPlaylist(
        val id: String,
        val title: String,
        val artworkUrl: String?,
        val trackCount: Int,
    )

    /**
     * The children of a section id: one of [AutoSection.id], one of
     * [LibrarySection.id], a mood shelf id, or a playlist id. Unknown ids
     * return an empty list — the car shows an empty folder rather than an
     * error for a stale cache entry.
     */
    fun children(
        parentId: String,
        source: AutoDataSource,
        page: Int,
        pageSize: Int,
    ): List<MediaItem> {
        val items: List<MediaItem> = when (parentId) {
            AutoSection.NOW_PLAYING.id -> emptyList() // answered from the live queue by the service
            AutoSection.MOODS.id -> source.moodShelves().map { mood ->
                folderItem(
                    id = mood.id,
                    title = mood.title,
                    artwork = mood.artworkUrl,
                    folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS,
                    grid = true,
                )
            }
            AutoSection.QUICK_PICKS.id -> source.quickPicks().map { it.toAutoMediaItem() }
            AutoSection.RECENTS.id -> source.recents().map { it.toAutoMediaItem() }
            AutoSection.DOWNLOADS.id -> source.downloads().map { it.toAutoMediaItem() }
            AutoSection.LIBRARY.id -> listOf(
                folderItem(
                    id = LibrarySection.PLAYLISTS.id,
                    title = "Playlists",
                    artwork = null,
                    folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS,
                    grid = true,
                ),
                folderItem(
                    id = LibrarySection.LIKED.id,
                    title = "Liked",
                    artwork = null,
                    folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS,
                    grid = false,
                ),
                folderItem(
                    id = LibrarySection.LOCAL_MUSIC.id,
                    title = "On this device",
                    artwork = null,
                    folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS,
                    grid = false,
                ),
            )
            LibrarySection.PLAYLISTS.id -> source.playlists().map { playlist ->
                folderItem(
                    id = playlist.id,
                    title = playlist.title,
                    artwork = playlist.artworkUrl,
                    folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS,
                    grid = true,
                )
            }
            LibrarySection.LIKED.id -> source.liked().map { it.toAutoMediaItem() }
            LibrarySection.LOCAL_MUSIC.id -> source.localTracks().map { it.toAutoMediaItem() }
            else -> {
                // A mood shelf or a playlist opened from the lists above.
                val mood = source.moodShelves().firstOrNull { it.id == parentId }
                if (mood != null) {
                    source.moodTracks(mood.id).map { it.toAutoMediaItem() }
                } else if (source.playlists().any { it.id == parentId }) {
                    source.playlistTracks(parentId).map { it.toAutoMediaItem() }
                } else {
                    emptyList()
                }
            }
        }
        return paginate(items, page, pageSize)
    }

    /** Top-level rows for `onGetChildren(MEDIA_ROOT_ID)`. */
    fun topLevelItems(titles: Map<AutoSection, String>): List<MediaItem> =
        topLevelSections().map { section ->
            folderItem(
                id = section.id,
                title = titles[section] ?: section.id,
                artwork = null,
                folderType = section.folderType,
                grid = section.grid,
            )
        }

    // ── Item builders ───────────────────────────────────────────────────────

    /**
     * A big browsable folder row: large artwork cards where [grid] is true
     * (collections), full-width list rows otherwise.
     */
    fun folderItem(
        id: String,
        title: String,
        artwork: String?,
        folderType: Int,
        grid: Boolean,
    ): MediaItem {
        val style = if (grid) CONTENT_STYLE_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_LIST_ITEM_HINT_VALUE
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setFolderType(folderType)
                    .setArtworkUri(artwork?.let(Uri::parse))
                    .setExtras(styleExtras(style, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                    .build(),
            )
            .build()
    }

    /**
     * A playable track row: list style with artwork — the car's biggest
     * playable touch target.
     */
    fun Song.toAutoMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId("auto2:track:$videoId")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setArtworkUri(thumbnailUrl?.let(Uri::parse))
                .setExtras(
                    styleExtras(
                        CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
                        CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
                    ),
                )
                .build(),
        )
        .build()

    private fun styleExtras(browsableHint: Int, playableHint: Int): Bundle =
        Bundle().apply {
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, playableHint)
            putInt("android.media.extras.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
            putInt("android.media.extras.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
        }

    private fun <T> paginate(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0 || items.isEmpty()) return emptyList()
        val safePageSize = pageSize.coerceAtMost(200)
        val from = (page.toLong() * safePageSize).coerceAtMost(items.size.toLong()).toInt()
        val to = (from + safePageSize).coerceAtMost(items.size)
        return items.subList(from, to)
    }
}
