package com.music.sonariq.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchSectionsTest {
    private fun song(id: String, title: String = "title $id") =
        Song(videoId = id, title = title, artist = "artist", thumbnailUrl = null)

    private fun browse(id: String, type: BrowseType) =
        SearchResult.Browse(BrowseItem(id, "item $id", "subtitle", null, type))

    private fun mixedResults() = listOf(
        browse("ar1", BrowseType.ARTIST),
        SearchResult.TopTrack(song("top1")),
        SearchResult.Track(song("s1")),
        browse("al1", BrowseType.ALBUM),
        SearchResult.Track(song("s2")),
        browse("pl1", BrowseType.PLAYLIST),
        browse("ar2", BrowseType.ARTIST),
        browse("ot1", BrowseType.OTHER),
    )

    @Test
    fun mixedResultsGroupIntoTypedSectionsInFixedOrder() {
        val sections = groupSearchResults(mixedResults())
        assertEquals(
            listOf(
                SearchSectionKind.SONGS,
                SearchSectionKind.ARTISTS,
                SearchSectionKind.ALBUMS,
                SearchSectionKind.PLAYLISTS,
                SearchSectionKind.OTHER,
            ),
            sections.map { it.kind },
        )
        assertEquals(listOf("s1", "s2"), sections[0].rows.map { (it as SearchResult.Track).song.videoId })
        assertEquals(2, sections[1].rows.size)
        assertEquals(1, sections[2].rows.size)
        assertEquals(1, sections[3].rows.size)
        assertEquals(1, sections[4].rows.size)
    }

    @Test
    fun topTrackIsNotGrouped() {
        val sections = groupSearchResults(mixedResults())
        val allRows = sections.flatMap { it.rows }
        assertTrue(allRows.none { it is SearchResult.TopTrack })
        // The section rows still hold every non-top result exactly once.
        assertEquals(mixedResults().size - 1, allRows.size)
    }

    @Test
    fun emptySectionsAreDropped() {
        val sections = groupSearchResults(
            listOf(SearchResult.Track(song("s1")), browse("ar1", BrowseType.ARTIST)),
        )
        assertEquals(
            listOf(SearchSectionKind.SONGS, SearchSectionKind.ARTISTS),
            sections.map { it.kind },
        )
    }

    @Test
    fun emptyInputGivesNoSections() {
        assertTrue(groupSearchResults(emptyList()).isEmpty())
    }

    @Test
    fun playableTracksKeepTopTrackFirstThenTrackOrder() {
        val tracks = searchPlayableTracks(mixedResults())
        assertEquals(listOf("top1", "s1", "s2"), tracks.map { it.videoId })
    }

    @Test
    fun browseRowsAreNotPlayable() {
        val tracks = searchPlayableTracks(
            listOf(browse("ar1", BrowseType.ARTIST), browse("pl1", BrowseType.PLAYLIST)),
        )
        assertTrue(tracks.isEmpty())
    }
}
