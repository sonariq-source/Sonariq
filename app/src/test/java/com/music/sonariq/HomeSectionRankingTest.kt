package com.music.sonariq

import com.music.sonariq.data.model.HomeShelf
import com.music.sonariq.data.model.ShelfItem
import com.music.sonariq.ui.home.HomeSectionRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSectionRankingTest {

    private fun shelf(
        title: String,
        browseId: String? = null,
        artists: List<String> = emptyList(),
    ): HomeShelf = HomeShelf(
        title = title,
        items = artists.mapIndexed { i, artist ->
            ShelfItem(
                title = "Track $i",
                subtitle = artist,
                thumbnailUrl = null,
                videoId = "vid$i",
                browseId = null,
            )
        },
        moreBrowseId = browseId,
    )

    @Test
    fun `section id prefers the browse id`() {
        val s = shelf("Listen again", browseId = "FEmusic_listen_again")
        assertEquals("browse:FEmusic_listen_again", HomeSectionRanking.sectionId(s))
    }

    @Test
    fun `section id falls back to the normalised title`() {
        val s = shelf("  New Albums & Singles ")
        assertEquals("title:new albums & singles", HomeSectionRanking.sectionId(s))
    }

    @Test
    fun `a manual order beats the behavioural ranking`() {
        val recents = shelf("Listen again", browseId = "b1")
        val moods = shelf("Mood & genres", browseId = "b2")
        val ranked = HomeSectionRanking.rankSections(
            shelves = listOf(recents, moods),
            recentArtists = emptySet(),
            manualOrder = listOf("browse:b2"),
            learn = true,
        )
        assertEquals(listOf(moods, recents), ranked)
    }

    @Test
    fun `unknown manual ids are ignored, not crashed on`() {
        val recents = shelf("Listen again", browseId = "b1")
        val ranked = HomeSectionRanking.rankSections(
            shelves = listOf(recents),
            recentArtists = emptySet(),
            manualOrder = listOf("browse:gone"),
            learn = true,
        )
        assertEquals(listOf(recents), ranked)
    }

    @Test
    fun `recents surface first when learning is on and nothing is dragged`() {
        val moods = shelf("Mood & genres", browseId = "b2")
        val recents = shelf("Listen again", browseId = "b1")
        val ranked = HomeSectionRanking.rankSections(
            shelves = listOf(moods, recents),
            recentArtists = emptySet(),
            manualOrder = emptyList(),
            learn = true,
        )
        assertEquals(recents, ranked.first())
    }

    @Test
    fun `artist affinity lifts matching sections`() {
        val generic = shelf("Some mix", browseId = "b1")
        val artistShelf = shelf(
            "More like them",
            browseId = "b2",
            artists = listOf("The Beatles", "The Beatles", "Nobody"),
        )
        val ranked = HomeSectionRanking.rankSections(
            shelves = listOf(generic, artistShelf),
            recentArtists = setOf("the beatles"),
            manualOrder = emptyList(),
            learn = true,
        )
        assertEquals(artistShelf, ranked.first())
    }

    @Test
    fun `learning off keeps feed order after manual ids`() {
        val a = shelf("A", browseId = "b1")
        val b = shelf("B", browseId = "b2")
        val ranked = HomeSectionRanking.rankSections(
            shelves = listOf(a, b),
            recentArtists = setOf("x"),
            manualOrder = emptyList(),
            learn = false,
        )
        assertEquals(listOf(a, b), ranked)
    }

    @Test
    fun `move id reorders within bounds`() {
        val order = listOf("a", "b", "c")
        assertEquals(listOf("c", "a", "b"), HomeSectionRanking.moveId(order, "c", 0))
        assertEquals(listOf("a", "b", "c"), HomeSectionRanking.moveId(order, "c", 5))
        assertEquals(order, HomeSectionRanking.moveId(order, "zzz", 0))
    }

    @Test
    fun `order survives an encode decode round trip`() {
        val order = listOf("browse:b1", "title:listen again")
        assertEquals(order, HomeSectionRanking.decodeOrder(HomeSectionRanking.encodeOrder(order)))
        assertTrue(HomeSectionRanking.decodeOrder("garbage\n\n").size <= 1)
    }
}
