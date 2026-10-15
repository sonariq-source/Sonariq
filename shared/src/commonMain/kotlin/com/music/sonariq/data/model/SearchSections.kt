package com.music.sonariq.data.model

/**
 * The typed sections an unfiltered search groups its mixed rows into, in the
 * order they appear on the page.
 */
enum class SearchSectionKind {
    SONGS,
    ARTISTS,
    ALBUMS,
    PLAYLISTS,
    /** Anything the parser didn't classify — episodes, podcasts, profiles. */
    OTHER,
}

/** One typed section of a grouped search result list. */
data class SearchSection(
    val kind: SearchSectionKind,
    val rows: List<SearchResult>,
)

/**
 * Groups a mixed search result list into typed sections: songs, artists,
 * albums, playlists, then anything else — each in the order YouTube returned
 * them, empty sections dropped.
 *
 * The promoted [SearchResult.TopTrack] is deliberately not grouped. The
 * search page lifts it into its own card at the head of the results, so
 * grouping it here too would render it twice.
 *
 * Pure presentation logic over the wire model: no fetching, no parsing, safe
 * to share between the phone and desktop UIs.
 */
fun groupSearchResults(rows: List<SearchResult>): List<SearchSection> {
    val browse = rows.filterIsInstance<SearchResult.Browse>()
    return listOf(
        SearchSection(SearchSectionKind.SONGS, rows.filterIsInstance<SearchResult.Track>()),
        SearchSection(
            SearchSectionKind.ARTISTS,
            browse.filter { it.item.type == BrowseType.ARTIST },
        ),
        SearchSection(
            SearchSectionKind.ALBUMS,
            browse.filter { it.item.type == BrowseType.ALBUM },
        ),
        SearchSection(
            SearchSectionKind.PLAYLISTS,
            browse.filter { it.item.type == BrowseType.PLAYLIST },
        ),
        SearchSection(
            SearchSectionKind.OTHER,
            browse.filter { it.item.type == BrowseType.OTHER },
        ),
    ).filter { it.rows.isNotEmpty() }
}

/**
 * The rows of a result list that play as a continuous track list: the
 * promoted top track first, then every track row in order. Browse rows
 * (artists, albums, playlists) have no playable audio and are skipped.
 */
fun searchPlayableTracks(rows: List<SearchResult>): List<Song> =
    rows.mapNotNull { row ->
        when (row) {
            is SearchResult.TopTrack -> row.song
            is SearchResult.Track -> row.song
            is SearchResult.Browse -> null
        }
    }
