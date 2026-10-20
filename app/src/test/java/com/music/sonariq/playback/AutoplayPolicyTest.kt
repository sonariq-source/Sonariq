package com.music.sonariq.playback

import com.music.sonariq.data.model.Song
import com.music.sonariq.playback.vibe.VibeVector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure arithmetic behind the 2.21 autoplay update: how the library /
 * downloads / discovery mix is weighted, how far exploration may roam, what
 * counts as banned, how the pools interleave, and how a track's energy is
 * scored. None of it touches Android, so all of it is pinned here — the
 * interleaving expectations in particular are the smooth weighted round
 * robin worked out by hand, because a scheduler that is merely "roughly
 * proportional" still feels wrong in a short queue.
 */
class AutoplayPolicyTest {

    private fun song(id: String, artist: String = "Artist $id") =
        Song(videoId = id, title = "Song $id", artist = artist, thumbnailUrl = null)

    private fun songs(prefix: String, count: Int) = (1..count).map { song("$prefix$it") }

    // ---------------------------------------------------------- normaliseMix --

    @Test
    fun `mix percentages normalise to weights summing to one`() {
        val weights = AutoplayPolicy.normaliseMix(50, 30, 20)
        assertArrayEquals(floatArrayOf(0.5f, 0.3f, 0.2f), weights, 0.001f)
        assertEquals(1f, weights.sum(), 0.001f)
    }

    @Test
    fun `an all zero mix falls back to even thirds`() {
        val weights = AutoplayPolicy.normaliseMix(0, 0, 0)
        assertArrayEquals(floatArrayOf(1f / 3f, 1f / 3f, 1f / 3f), weights, 0.001f)
    }

    @Test
    fun `a mix with one zero keeps the other two in proportion`() {
        val weights = AutoplayPolicy.normaliseMix(75, 0, 25)
        assertArrayEquals(floatArrayOf(0.75f, 0f, 0.25f), weights, 0.001f)
    }

    @Test
    fun `a single source mix gives that source all the weight`() {
        val weights = AutoplayPolicy.normaliseMix(0, 100, 0)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), weights, 0.001f)
    }

    @Test
    fun `negative mix entries are treated as zero`() {
        val weights = AutoplayPolicy.normaliseMix(-50, 50, 50)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 0.5f), weights, 0.001f)
    }

    @Test
    fun `an all negative mix falls back to even thirds`() {
        val weights = AutoplayPolicy.normaliseMix(-10, -20, -30)
        assertArrayEquals(floatArrayOf(1f / 3f, 1f / 3f, 1f / 3f), weights, 0.001f)
    }

    // --------------------------------------------------- exploration tuning --

    @Test
    fun `safe exploration raises the threshold and adventurous lowers it`() {
        assertEquals(0.65f, AutoplayPolicy.explorationThreshold(0, 0.5f), 0.001f)
        assertEquals(0.5f, AutoplayPolicy.explorationThreshold(1, 0.5f), 0.001f)
        assertEquals(0.35f, AutoplayPolicy.explorationThreshold(2, 0.5f), 0.001f)
    }

    @Test
    fun `the threshold clamps at the top of the window`() {
        assertEquals(0.9f, AutoplayPolicy.explorationThreshold(0, 0.85f), 0.001f)
        assertEquals(0.9f, AutoplayPolicy.explorationThreshold(1, 0.95f), 0.001f)
        assertEquals(0.9f, AutoplayPolicy.explorationThreshold(2, 1.2f), 0.001f)
    }

    @Test
    fun `the threshold clamps at the bottom of the window`() {
        assertEquals(0.3f, AutoplayPolicy.explorationThreshold(2, 0.4f), 0.001f)
        assertEquals(0.3f, AutoplayPolicy.explorationThreshold(1, 0.1f), 0.001f)
        assertEquals(0.3f, AutoplayPolicy.explorationThreshold(0, 0f), 0.001f)
    }

    @Test
    fun `artist caps follow the exploration level`() {
        assertEquals(2, AutoplayPolicy.explorationArtistCap(0))
        assertEquals(4, AutoplayPolicy.explorationArtistCap(1))
        assertEquals(Int.MAX_VALUE, AutoplayPolicy.explorationArtistCap(2))
    }

    // --------------------------------------------------------------- isBanned --

    @Test
    fun `a banned video id is banned whatever the artist`() {
        assertTrue(AutoplayPolicy.isBanned("bad1", "Anyone", setOf("bad1"), emptySet()))
        assertTrue(AutoplayPolicy.isBanned("bad1", "Anyone", setOf("bad1"), setOf("Someone Else")))
    }

    @Test
    fun `a banned artist matches case insensitively`() {
        val banned = setOf("foo fighters")
        assertTrue(AutoplayPolicy.isBanned("ok1", "Foo Fighters", emptySet(), banned))
        assertTrue(AutoplayPolicy.isBanned("ok1", "FOO FIGHTERS", emptySet(), banned))
        assertTrue(AutoplayPolicy.isBanned("ok1", "foo fighters", emptySet(), banned))
    }

    @Test
    fun `a banned artist matches through surrounding whitespace on both sides`() {
        assertTrue(
            AutoplayPolicy.isBanned("ok1", "  Foo Fighters  ", emptySet(), setOf("foo fighters")),
        )
        assertTrue(
            AutoplayPolicy.isBanned("ok1", "Foo Fighters", emptySet(), setOf("  Foo Fighters ")),
        )
    }

    @Test
    fun `an unbanned track passes`() {
        assertFalse(AutoplayPolicy.isBanned("ok1", "Someone Else", setOf("bad1"), setOf("foo")))
        // A partial name is not the banned artist.
        assertFalse(AutoplayPolicy.isBanned("ok1", "Foo", emptySet(), setOf("Foo Fighters")))
    }

    @Test
    fun `no bans means nothing is banned`() {
        assertFalse(AutoplayPolicy.isBanned("ok1", "Anyone", emptySet(), emptySet()))
    }

    @Test
    fun `a blank artist is never artist banned`() {
        assertFalse(AutoplayPolicy.isBanned("ok1", "", emptySet(), setOf("foo")))
        assertFalse(AutoplayPolicy.isBanned("ok1", "   ", emptySet(), setOf("")))
    }

    // ------------------------------------------------------ interleaveByWeight --

    @Test
    fun `interleave keeps a two to one proportion over a short batch`() {
        val out = AutoplayPolicy.interleaveByWeight(
            pools = listOf(songs("a", 4), songs("b", 3)),
            weights = floatArrayOf(2f, 1f),
            limit = 6,
        )
        assertEquals(listOf("a1", "b1", "a2", "a3", "b2", "a4"), out.map { it.videoId })
    }

    @Test
    fun `interleave with equal weights alternates pools`() {
        val out = AutoplayPolicy.interleaveByWeight(
            pools = listOf(songs("a", 2), songs("b", 2)),
            weights = floatArrayOf(1f, 1f),
            limit = 4,
        )
        assertEquals(listOf("a1", "b1", "a2", "b2"), out.map { it.videoId })
    }

    @Test
    fun `interleave keeps a three to one proportion across a full batch`() {
        val out = AutoplayPolicy.interleaveByWeight(
            pools = listOf(songs("a", 6), songs("b", 2)),
            weights = floatArrayOf(3f, 1f),
            limit = 8,
        )
        assertEquals(
            listOf("a1", "a2", "b1", "a3", "a4", "a5", "b2", "a6"),
            out.map { it.videoId },
        )
    }

    @Test
    fun `interleave preserves each pool's own order`() {
        val out = AutoplayPolicy.interleaveByWeight(
            pools = listOf(songs("a", 2), songs("b", 6)),
            weights = floatArrayOf(1f, 3f),
            limit = 8,
        )
        assertEquals(
            listOf("b1", "a1", "b2", "b3", "b4", "a2", "b5", "b6"),
            out.map { it.videoId },
        )
        assertEquals(listOf("a1", "a2"), out.filter { it.videoId.startsWith("a") }.map { it.videoId })
        assertEquals(
            listOf("b1", "b2", "b3", "b4", "b5", "b6"),
            out.filter { it.videoId.startsWith("b") }.map { it.videoId },
        )
    }

    @Test
    fun `interleave de-duplicates a video id shared by two pools`() {
        val out = AutoplayPolicy.interleaveByWeight(
            pools = listOf(
                listOf(song("x"), song("a2")),
                listOf(song("x"), song("b2")),
            ),
            weights = floatArrayOf(1f, 1f),
            limit = 10,
        )
        assertEquals(listOf("x", "b2", "a2"), out.map { it.videoId })
    }

    @Test
    fun `a zero weight pool only fills once the weighted pools run dry`() {
        val out = AutoplayPolicy.interleaveByWeight(
            pools = listOf(songs("a", 2), songs("b", 1)),
            weights = floatArrayOf(1f, 0f),
            limit = 3,
        )
        assertEquals(listOf("a1", "a2", "b1"), out.map { it.videoId })
    }

    @Test
    fun `all zero weights fall back to an even split`() {
        val out = AutoplayPolicy.interleaveByWeight(
            pools = listOf(songs("a", 2), songs("b", 2)),
            weights = floatArrayOf(0f, 0f),
            limit = 4,
        )
        assertEquals(listOf("a1", "b1", "a2", "b2"), out.map { it.videoId })
    }

    @Test
    fun `an empty pool is skipped without disturbing the rest`() {
        val out = AutoplayPolicy.interleaveByWeight(
            pools = listOf(emptyList(), songs("b", 2)),
            weights = floatArrayOf(1f, 1f),
            limit = 5,
        )
        assertEquals(listOf("b1", "b2"), out.map { it.videoId })
    }

    @Test
    fun `interleave honours the limit`() {
        val out = AutoplayPolicy.interleaveByWeight(
            pools = listOf(songs("a", 10), songs("b", 10)),
            weights = floatArrayOf(1f, 1f),
            limit = 3,
        )
        assertEquals(listOf("a1", "b1", "a2"), out.map { it.videoId })
    }

    @Test
    fun `interleave stops when every pool is exhausted`() {
        val out = AutoplayPolicy.interleaveByWeight(
            pools = listOf(songs("a", 1), songs("b", 1)),
            weights = floatArrayOf(1f, 1f),
            limit = 10,
        )
        assertEquals(listOf("a1", "b1"), out.map { it.videoId })
    }

    @Test
    fun `interleave returns nothing for no pools, empty pools or a useless limit`() {
        assertTrue(AutoplayPolicy.interleaveByWeight(emptyList(), floatArrayOf(1f), 5).isEmpty())
        assertTrue(
            AutoplayPolicy.interleaveByWeight(
                listOf(emptyList(), emptyList()),
                floatArrayOf(1f, 1f),
                5,
            ).isEmpty(),
        )
        assertTrue(
            AutoplayPolicy.interleaveByWeight(listOf(songs("a", 3)), floatArrayOf(1f), 0).isEmpty(),
        )
        assertTrue(
            AutoplayPolicy.interleaveByWeight(listOf(songs("a", 3)), floatArrayOf(1f), -2).isEmpty(),
        )
    }

    // ------------------------------------------------------- discoveryAnchored --

    @Test
    fun `discovery anchored runs two new tracks then one anchor`() {
        val out = AutoplayPolicy.discoveryAnchored(songs("d", 5), songs("a", 2), limit = 10)
        assertEquals(
            listOf("d1", "d2", "a1", "d3", "d4", "a2", "d5"),
            out.map { it.videoId },
        )
    }

    @Test
    fun `anchor leftovers fill once discovery runs dry`() {
        val out = AutoplayPolicy.discoveryAnchored(songs("d", 1), songs("a", 3), limit = 10)
        assertEquals(listOf("d1", "a1", "a2", "a3"), out.map { it.videoId })
    }

    @Test
    fun `discovery anchored honours the limit mid pattern`() {
        val out = AutoplayPolicy.discoveryAnchored(songs("d", 5), songs("a", 3), limit = 4)
        assertEquals(listOf("d1", "d2", "a1", "d3"), out.map { it.videoId })
    }

    @Test
    fun `discovery anchored never repeats a video id across the two sides`() {
        val out = AutoplayPolicy.discoveryAnchored(
            discovery = listOf(song("x"), song("d2")),
            anchors = listOf(song("x"), song("a2")),
            limit = 10,
        )
        assertEquals(listOf("x", "d2", "a2"), out.map { it.videoId })
    }

    @Test
    fun `discovery anchored handles empty sides and a zero limit`() {
        assertEquals(
            listOf("a1", "a2"),
            AutoplayPolicy.discoveryAnchored(emptyList(), songs("a", 2), 10).map { it.videoId },
        )
        assertEquals(
            listOf("d1", "d2"),
            AutoplayPolicy.discoveryAnchored(songs("d", 2), emptyList(), 10).map { it.videoId },
        )
        assertTrue(AutoplayPolicy.discoveryAnchored(emptyList(), emptyList(), 10).isEmpty())
        assertTrue(AutoplayPolicy.discoveryAnchored(songs("d", 2), songs("a", 2), 0).isEmpty())
    }

    // ---------------------------------------------------------------- energyOf --

    @Test
    fun `an empty vibe vector has no energy`() {
        assertEquals(0f, AutoplayPolicy.energyOf(VibeVector()), 0.001f)
    }

    @Test
    fun `a pure chill vector has no energy`() {
        assertEquals(0f, AutoplayPolicy.energyOf(VibeVector(chill = 1f)), 0.001f)
    }

    @Test
    fun `a fully energetic party workout vector maxes out`() {
        val v = VibeVector(energetic = 1f, party = 1f, workout = 1f)
        assertEquals(1f, AutoplayPolicy.energyOf(v), 0.001f)
    }

    @Test
    fun `chill damps a maxed vector to half energy`() {
        val v = VibeVector(energetic = 1f, party = 1f, workout = 1f, chill = 1f)
        assertEquals(0.5f, AutoplayPolicy.energyOf(v), 0.001f)
    }

    @Test
    fun `energy is the mean of the three active moods, damped by chill`() {
        // raw = (0.9 + 0.6 + 0.3) / 3 = 0.6; chill 0.4 damps by a fifth -> 0.48.
        val v = VibeVector(energetic = 0.9f, party = 0.6f, workout = 0.3f, chill = 0.4f)
        assertEquals(0.48f, AutoplayPolicy.energyOf(v), 0.001f)
    }

    @Test
    fun `one active mood alone contributes a third`() {
        assertEquals(1f / 3f, AutoplayPolicy.energyOf(VibeVector(energetic = 1f)), 0.001f)
        assertEquals(1f / 3f, AutoplayPolicy.energyOf(VibeVector(workout = 1f)), 0.001f)
    }

    @Test
    fun `moods outside the energy set do not count`() {
        val v = VibeVector(sad = 1f, happy = 1f, romantic = 1f, nostalgic = 1f, focus = 1f)
        assertEquals(0f, AutoplayPolicy.energyOf(v), 0.001f)
    }

    // -------------------------------------------------------------- energyBonus --

    @Test
    fun `the energy bonus is full on target and fades with distance`() {
        val hot = VibeVector(energetic = 1f, party = 1f, workout = 1f) // energy exactly 1
        assertEquals(0.4f, AutoplayPolicy.energyBonus(hot, target = 1f, weight = 0.4f), 0.001f)
        assertEquals(0.2f, AutoplayPolicy.energyBonus(hot, target = 0.5f, weight = 0.4f), 0.001f)
        assertEquals(0f, AutoplayPolicy.energyBonus(hot, target = 0f, weight = 0.4f), 0.001f)
        assertEquals(0f, AutoplayPolicy.energyBonus(hot, target = 1f, weight = 0f), 0.001f)
    }
}
