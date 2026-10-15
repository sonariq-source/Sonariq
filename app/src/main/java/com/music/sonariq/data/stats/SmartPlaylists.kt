package com.music.sonariq.data.stats

import android.content.Context
import com.music.sonariq.R
import com.music.sonariq.data.LocalMediaRepository
import com.music.sonariq.data.model.ShelfItem
import com.music.sonariq.data.model.Song
import com.music.sonariq.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Playlists nobody made: built on the spot from this device's own records —
 * listening history for three of them, the on-device library for the fourth —
 * every time one is opened.
 *
 * Nothing here is stored and nothing is uploaded. The selections are pure
 * functions over [TrackStats] and song lists, so they are unit-testable without
 * a device; [loadSongs] and [shelfItems] do the IO.
 *
 * Every list is labelled auto-generated wherever it is drawn, and an empty one
 * says plainly that there is no data yet rather than inventing rows.
 */
object SmartPlaylists {

    /** Browse-id scheme: `smart:most-played` and friends. */
    const val PREFIX = "smart:"

    enum class Kind(val id: String, val titleRes: Int) {
        MOST_PLAYED("most-played", R.string.smart_most_played),
        RECENTLY_PLAYED("recently-played", R.string.smart_recently_played),
        FORGOTTEN_FAVORITES("forgotten-favorites", R.string.smart_forgotten_favorites),
        RECENTLY_ADDED("recently-added", R.string.smart_recently_added),
    }

    fun kindOf(browseId: String): Kind? {
        if (!browseId.startsWith(PREFIX)) return null
        val id = browseId.removePrefix(PREFIX)
        return Kind.entries.firstOrNull { it.id == id }
    }

    fun browseId(kind: Kind): String = PREFIX + kind.id

    /**
     * How long a favourite has to go unplayed to count as forgotten: 30 days.
     *
     * A month is the honest reading of "haven't listened in a while" — shorter
     * and the list fills with last month's obsessions, still warm; longer and
     * it takes a season to earn a row.
     */
    const val FORGOTTEN_DAYS = 30L

    /**
     * The plays that make a track a favourite rather than a passing listen.
     *
     * Ten plays is a record someone chose, repeatedly. Below that the list is
     * one-hit wonders with a calendar, not favourites.
     */
    const val FORGOTTEN_MIN_PLAYS = 10

    /** How many rows a generated playlist carries. */
    const val LIMIT = 50

    // ── Pure selections ─────────────────────────────────────────────────────

    /** By play count, all time — the Replay's song chart as a playlist. */
    fun mostPlayed(tracks: List<TrackStats>, limit: Int = LIMIT): List<Song> =
        tracks.filter { it.plays > 0 }
            .sortedWith(compareByDescending<TrackStats> { it.plays }.thenByDescending { it.ms })
            .take(limit)
            .map { it.toSong() }

    /** By last played, most recent first. */
    fun recentlyPlayed(tracks: List<TrackStats>, limit: Int = LIMIT): List<Song> =
        tracks.filter { it.lastPlayedMs > 0 }
            .sortedByDescending { it.lastPlayedMs }
            .take(limit)
            .map { it.toSong() }

    /**
     * Favourites gone quiet: at least [FORGOTTEN_MIN_PLAYS] plays all time and
     * nothing in the last [FORGOTTEN_DAYS] days. Most-played first, so the top
     * of the list is the records that mattered most.
     */
    fun forgottenFavorites(
        tracks: List<TrackStats>,
        nowMs: Long,
        limit: Int = LIMIT,
    ): List<Song> {
        val cutoff = nowMs - FORGOTTEN_DAYS * 24 * 60 * 60 * 1_000L
        return tracks
            .filter { it.plays >= FORGOTTEN_MIN_PLAYS && it.lastPlayedMs > 0 && it.lastPlayedMs < cutoff }
            .sortedByDescending { it.plays }
            .take(limit)
            .map { it.toSong() }
    }

    /**
     * Newest on the device first: downloads and local audio files by the date
     * each was added. Those dates come from the download record and the media
     * store — real "added on" dates, not guesses. Duplicates, where the same
     * file is reached both ways, appear once.
     */
    fun recentlyAdded(songs: List<Song>, limit: Int = LIMIT): List<Song> {
        val seen = HashSet<String>()
        return songs
            .filter { (it.localDateAddedSeconds ?: 0L) > 0L }
            .sortedByDescending { it.localDateAddedSeconds }
            .filter { seen.add(it.localUri ?: it.videoId) }
            .take(limit)
    }

    // ── Loading ─────────────────────────────────────────────────────────────

    /**
     * The playlist's songs, read fresh every time — these are never cached, so
     * a list opened after an afternoon of listening includes the afternoon.
     */
    suspend fun loadSongs(kind: Kind, context: Context): List<Song> = withContext(Dispatchers.IO) {
        when (kind) {
            Kind.MOST_PLAYED -> mostPlayed(ListeningStats.allTracks())
            Kind.RECENTLY_PLAYED -> recentlyPlayed(ListeningStats.allTracks())
            Kind.FORGOTTEN_FAVORITES ->
                forgottenFavorites(ListeningStats.allTracks(), System.currentTimeMillis())
            Kind.RECENTLY_ADDED -> {
                val downloaded = Downloads.getDownloadedSongs(context)
                val local = if (LocalMediaRepository.hasStoragePermission(context)) {
                    LocalMediaRepository.getLocalMusic(context)
                } else {
                    emptyList()
                }
                recentlyAdded(downloaded + local)
            }
        }
    }

    /**
     * The Library shelf's cards: one per generated list that actually has
     * songs, each labelled auto-generated. Empty lists get no card — the
     * detail page behind each one still says plainly when there is no data.
     */
    suspend fun shelfItems(context: Context): List<ShelfItem> = withContext(Dispatchers.IO) {
        val tracks = ListeningStats.allTracks()
        val now = System.currentTimeMillis()
        val autoGenerated = context.getString(R.string.auto_generated)
        fun card(kind: Kind, songs: List<Song>): ShelfItem? {
            if (songs.isEmpty()) return null
            return ShelfItem(
                title = context.getString(kind.titleRes),
                subtitle = context.getString(
                    R.string.smart_playlist_card_subtitle,
                    songs.size,
                    autoGenerated,
                ),
                thumbnailUrl = songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
                videoId = null,
                browseId = browseId(kind),
            )
        }
        val cards = mutableListOf<ShelfItem>()
        card(Kind.MOST_PLAYED, mostPlayed(tracks))?.let(cards::add)
        card(Kind.RECENTLY_PLAYED, recentlyPlayed(tracks))?.let(cards::add)
        card(Kind.FORGOTTEN_FAVORITES, forgottenFavorites(tracks, now))?.let(cards::add)
        card(Kind.RECENTLY_ADDED, loadSongs(Kind.RECENTLY_ADDED, context))?.let(cards::add)
        cards
    }
}
