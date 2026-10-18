package com.music.sonariq.data.discovery

import android.util.Log
import com.music.sonariq.data.YtMusicRepository
import com.music.sonariq.data.model.HomeShelf
import com.music.sonariq.data.model.ShelfItem
import com.music.sonariq.data.stats.ListeningStats
import com.music.sonariq.data.stats.ReplayPeriod
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * New releases from the artists the listener cares about.
 *
 * ## Where the artists come from
 *
 * The radar watches two lists, both real. [FollowedArtists] is the explicit
 * one — artists the listener chose to follow. The other is derived: the top
 * artists of the last month of actual listening, whose artist pages carry
 * browse ids ([com.music.sonariq.data.stats.ListeningStats] keeps them), so an
 * artist someone plays every day shows up here without ever being followed.
 * The two are merged with [FollowedArtists.mergedWith] so nobody is fetched
 * twice.
 *
 * ## Where the releases come from
 *
 * Each watched artist's page is fetched and its "latest" shelves are read —
 * the carousel the catalogue itself heads "Latest", "New releases" and the
 * like. Shelf matching is deliberately textual ([isNewReleaseShelf]) because
 * the catalogue offers no typed "this is the new-releases shelf" marker.
 * Everything behind the rows is browsable: each [ShelfItem] carries the
 * browse id of the release it names.
 *
 * Failures are per-artist, never per-feed: one page failing to load skips one
 * artist rather than emptying the radar.
 */
data class RadarArtistRelease(
    val artistName: String,
    val artistBrowseId: String,
    val artistThumbnailUrl: String?,
    /** The shelf's own heading, e.g. "Latest". */
    val shelfTitle: String,
    val items: List<ShelfItem>,
    /** Release year read off the first row's subtitle, when it states one. */
    val year: Int?,
)

/**
 * Whether an artist page's shelf heading is its new-releases shelf.
 *
 * Pure, because the catalogue's headings are strings and the wrong shelf
 * here is a quiet lie — matching "Fans might also like" would fill the radar
 * with other people's music.
 */
fun isNewReleaseShelf(title: String): Boolean =
    NEW_RELEASE_TITLES.containsMatchIn(title)

private val NEW_RELEASE_TITLES = Regex(
    """\blatest\b|\bnew (releases?|albums?|singles?|eps?|music)\b|\bjust (released|dropped)\b|\bnewest\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * A release year off a catalogue subtitle like "2026 • Album" or "Single • 2025".
 *
 * Pure: the subtitle is free text, and the first plausible year in it is the
 * release year in every shape the catalogue uses.
 */
fun releaseYear(subtitle: String): Int? =
    YEAR_IN_TEXT.find(subtitle)?.value?.toIntOrNull()
        ?.takeIf { it in 1900..2100 }

private val YEAR_IN_TEXT = Regex("""\b(19\d{2}|20\d{2}|2100)\b""")

object ReleaseRadar {

    private const val TAG = "SonariqRadar"

    /** How many watched artists the radar fetches per refresh. */
    private const val MAX_ARTISTS = 12

    /** Browse id of the catalogue's own new-releases page. */
    private const val NEW_RELEASES_BROWSE_ID = "FEmusic_new_releases"

    /**
     * The radar: latest releases from followed artists and top-listened artists.
     *
     * Watched artists are fetched concurrently; the whole thing is one
     * suspend call the screen launches once per opening. Empty when nothing
     * could be read — the screen says so honestly rather than inventing rows.
     */
    suspend fun radar(): List<RadarArtistRelease> = coroutineScope {
        val followed = FollowedArtists.follows.value
            .filter { it.browseId.isNotBlank() }
        val topListened = try {
            ListeningStats.summary(ReplayPeriod.THIS_MONTH).artists
                .mapNotNull { ranked ->
                    val browseId = ranked.browseId ?: return@mapNotNull null
                    FollowedArtist(
                        name = ranked.title,
                        browseId = browseId,
                        thumbnailUrl = ranked.artworkUrl,
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
        val watched = FollowedArtists.mergedWith(followed, topListened)
            .take(MAX_ARTISTS)
        watched.map { artist ->
            async { releasesFor(artist) }
        }.awaitAll().filterNotNull()
    }

    /**
     * The catalogue's own new-releases shelves, for the "everyone" section.
     *
     * The same one-shot page the home feed's supplements read — real catalogue
     * data, not a guess at what's new.
     */
    suspend fun generalNewReleases(): List<HomeShelf> =
        try {
            YtMusicRepository.homeSupplement(NEW_RELEASES_BROWSE_ID)
                .getOrElse { emptyList() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load new releases", e)
            emptyList()
        }

    private suspend fun releasesFor(artist: FollowedArtist): RadarArtistRelease? {
        val page = try {
            YtMusicRepository.artistPage(artist.browseId).getOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Could not load artist page for ${artist.name}", e)
            null
        } ?: return null
        // The newest shelf wins when a page carries more than one.
        val shelf = page.sections.firstOrNull { isNewReleaseShelf(it.title) }
            ?: return null
        if (shelf.items.isEmpty()) return null
        return RadarArtistRelease(
            artistName = page.name ?: artist.name,
            artistBrowseId = artist.browseId,
            artistThumbnailUrl = page.thumbnailUrl ?: artist.thumbnailUrl,
            shelfTitle = shelf.title,
            items = shelf.items,
            year = shelf.items.firstNotNullOfOrNull { releaseYear(it.subtitle) },
        )
    }
}
