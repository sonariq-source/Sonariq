package com.music.sonariq

import com.music.sonariq.data.model.HomeShelf
import com.music.sonariq.data.model.ShelfItem
import com.music.sonariq.data.model.Song
import com.music.sonariq.ui.screens.ArtistDetailTab
import com.music.sonariq.ui.screens.ReleaseDetailTab
import com.music.sonariq.ui.screens.artistDetailTabs
import com.music.sonariq.ui.screens.formatDetailMeta
import com.music.sonariq.ui.screens.isArtistShelf
import com.music.sonariq.ui.screens.matching
import com.music.sonariq.ui.screens.releaseDetailTabs
import com.music.sonariq.ui.screens.splitHeaderParts
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure decision logic behind the detail pages' tabs and header lines:
 * which tabs a page earns from its data, and how the header's credit/meta
 * lines are split and joined.
 *
 * The tab gating is the "real data only" rule made testable — a tab exists
 * only when the response carries what it shows, never from anything
 * estimated or invented.
 */
class DetailScreenLogicTest {

    // --- artistDetailTabs ---

    @Test
    fun artistTabs_allData_showsAllThreeInOrder() {
        assertEquals(
            listOf(
                ArtistDetailTab.ABOUT,
                ArtistDetailTab.ALBUMS,
                ArtistDetailTab.SIMILAR_ARTISTS,
            ),
            artistDetailTabs(hasAbout = true, hasReleases = true, hasSimilarArtists = true),
        )
    }

    @Test
    fun artistTabs_noSimilarData_omitsSimilarTab() {
        assertEquals(
            listOf(ArtistDetailTab.ABOUT, ArtistDetailTab.ALBUMS),
            artistDetailTabs(hasAbout = true, hasReleases = true, hasSimilarArtists = false),
        )
    }

    @Test
    fun artistTabs_noAbout_omitsAboutTab() {
        assertEquals(
            listOf(ArtistDetailTab.ALBUMS, ArtistDetailTab.SIMILAR_ARTISTS),
            artistDetailTabs(hasAbout = false, hasReleases = true, hasSimilarArtists = true),
        )
    }

    @Test
    fun artistTabs_noReleases_omitsAlbumsTab() {
        assertEquals(
            listOf(ArtistDetailTab.ABOUT, ArtistDetailTab.SIMILAR_ARTISTS),
            artistDetailTabs(hasAbout = true, hasReleases = false, hasSimilarArtists = true),
        )
    }

    @Test
    fun artistTabs_noData_noTabs() {
        assertEquals(
            emptyList<ArtistDetailTab>(),
            artistDetailTabs(hasAbout = false, hasReleases = false, hasSimilarArtists = false),
        )
    }

    // --- releaseDetailTabs ---

    @Test
    fun releaseTabs_withAbout_showsTracksAndAbout() {
        assertEquals(
            listOf(ReleaseDetailTab.TRACKS, ReleaseDetailTab.ABOUT),
            releaseDetailTabs(hasAbout = true),
        )
    }

    @Test
    fun releaseTabs_withoutAbout_tracksOnly() {
        // Playlists never carry an editorial blurb, so they get a single tab
        // and no tab bar.
        assertEquals(
            listOf(ReleaseDetailTab.TRACKS),
            releaseDetailTabs(hasAbout = false),
        )
    }

    // --- splitHeaderParts ---

    @Test
    fun splitHeaderParts_fullSubtitle() {
        val parts = "Album • Travis Scott • 2023".splitHeaderParts()
        assertEquals("Travis Scott", parts.credit)
        assertEquals("album", parts.kind)
        assertEquals("2023", parts.year)
    }

    @Test
    fun splitHeaderParts_creditOnly() {
        val parts = "Travis Scott".splitHeaderParts()
        assertEquals("Travis Scott", parts.credit)
        assertEquals(null, parts.kind)
        assertEquals(null, parts.year)
    }

    @Test
    fun splitHeaderParts_middleDotSeparator() {
        val parts = "Single · Billie Eilish · 2021".splitHeaderParts()
        assertEquals("Billie Eilish", parts.credit)
        assertEquals("single", parts.kind)
        assertEquals("2021", parts.year)
    }

    @Test
    fun splitHeaderParts_kindMatchingIsCaseInsensitive() {
        val parts = "EP • Artist One • 2020".splitHeaderParts()
        assertEquals("ep", parts.kind)
        assertEquals("2020", parts.year)
    }

    @Test
    fun splitHeaderParts_nonYearNumberIsNotAYear() {
        val parts = "Album • Artist One • Vol 2".splitHeaderParts()
        assertEquals("Artist One, Vol 2", parts.credit)
        assertEquals("album", parts.kind)
        assertEquals(null, parts.year)
    }

    @Test
    fun splitHeaderParts_kindAlone_leavesCreditEmpty() {
        val parts = "Playlist".splitHeaderParts()
        assertEquals("", parts.credit)
        assertEquals("playlist", parts.kind)
        assertEquals(null, parts.year)
    }

    // --- formatDetailMeta ---

    @Test
    fun formatDetailMeta_joinsPresentPartsAndUppercases() {
        // No letter "i" in the fixtures: uppercasing stays stable under every
        // device locale (Turkish dotless-i included).
        assertEquals(
            "ALBUM • 2023 • 12 SONGS • 1H 25M",
            formatDetailMeta("Album", "2023", "12 songs", "1h 25m"),
        )
    }

    @Test
    fun formatDetailMeta_skipsMissingParts() {
        assertEquals(
            "PLAYLIST • 8 SONGS",
            formatDetailMeta("Playlist", null, "8 songs", null),
        )
    }

    @Test
    fun formatDetailMeta_allMissing_rendersEmpty() {
        assertEquals("", formatDetailMeta(null, null, null, null))
    }

    // --- isArtistShelf ---

    private fun artistItem(name: String, browseId: String) =
        ShelfItem(title = name, subtitle = "", thumbnailUrl = null, videoId = null, browseId = browseId)

    @Test
    fun isArtistShelf_allChannelIds_true() {
        val shelf = HomeShelf(
            title = "Fans might also like",
            items = listOf(artistItem("Artist One", "UC111"), artistItem("Artist Two", "UC222")),
        )
        assertEquals(true, shelf.isArtistShelf())
    }

    @Test
    fun isArtistShelf_empty_false() {
        assertEquals(false, HomeShelf(title = "Empty", items = emptyList()).isArtistShelf())
    }

    @Test
    fun isArtistShelf_mixedBrowseIds_false() {
        val shelf = HomeShelf(
            title = "Mixed",
            items = listOf(
                artistItem("Artist One", "UC111"),
                ShelfItem("An Album", "Album • 2023", null, null, "MPREabc"),
            ),
        )
        assertEquals(false, shelf.isArtistShelf())
    }

    // --- matching ---

    private fun song(id: String, title: String, artist: String, album: String? = null) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, albumName = album)

    @Test
    fun matching_blankQuery_returnsAllWithOriginalIndices() {
        val songs = listOf(song("a", "One", "Artist One"), song("b", "Two", "Artist Two"))
        val result = songs.matching("   ")
        assertEquals(listOf(0, 1), result.map { it.index })
        assertEquals(listOf("a", "b"), result.map { it.value.videoId })
    }

    @Test
    fun matching_matchesTitleArtistAndAlbumCaseInsensitively() {
        val songs = listOf(
            song("a", "Midnight Drive", "Neon Coast", "Afterglow"),
            song("b", "Solar Winds", "Dusk Pilots", "Midnight Tales"),
            song("c", "Rivers", "MIDNIGHT Oil", null),
            song("d", "No Match Here", "Quiet Band", "Still Waters"),
        )
        val result = songs.matching("midnight")
        assertEquals(listOf("a", "b", "c"), result.map { it.value.videoId })
        // Indices still point at the full list, so album track numbers survive filtering.
        assertEquals(listOf(0, 1, 2), result.map { it.index })
    }

    @Test
    fun matching_noMatch_returnsEmpty() {
        val songs = listOf(song("a", "One", "Artist One"))
        assertEquals(emptyList<IndexedValue<Song>>(), songs.matching("zzz"))
    }
}
