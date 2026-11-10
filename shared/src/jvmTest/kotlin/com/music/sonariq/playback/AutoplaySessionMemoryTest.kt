package com.music.sonariq.playback

import com.music.sonariq.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Session-strict Autoplay: within one session a track that has played,
 * been skipped, sits in the queue or was already served is not offered
 * again while alternatives exist; every completed track triggers a
 * fresh batch; and when the catalogue genuinely runs out, exclusion
 * relaxes in a fixed order — unplayed suggestions, then skips, then a
 * rotation of the oldest plays — instead of the queue running dry.
 */
class AutoplaySessionMemoryTest {

    private fun song(id: String) =
        Song(videoId = id, title = "Song $id", artist = "Artist $id", thumbnailUrl = null)

    private fun songs(vararg ids: String) = ids.map(::song)

    private fun ids(batch: List<Song>) = batch.map { it.videoId }

    @Test
    fun strictTierExcludesPlayedSkippedQueuedAndRecommended() {
        val memory = AutoplaySessionMemory()
        memory.noteBecameCurrent("a", previousEnded = false)
        memory.noteBecameCurrent("b", previousEnded = true) // a played out
        memory.noteBecameCurrent("c", previousEnded = false) // b skipped
        memory.noteDelivered(listOf("d", "e")) // served, still waiting

        val existing = songs("a", "b", "c", "d", "e")
        val pool = songs("a", "b", "c", "d", "e", "f", "g", "h")
        val picks = memory.selectForQueue(pool, existing, queueIds = setOf("c", "d", "e"), limit = 10)

        assertEquals(listOf("f", "g", "h"), ids(picks))
    }

    @Test
    fun thirtyContinuousTransitionsNeverRepeatWhileAlternativesExist() {
        // A deterministic catalogue and a radio that always offers the
        // twenty tracks after the seed — the shape that used to recycle
        // the same handful of songs for a whole session.
        val catalog = (0 until 100).map { "s$it" }
        fun radio(seed: String): List<Song> {
            val index = catalog.indexOf(seed)
            return (1..20).map { song(catalog[(index + it) % catalog.size]) }
        }

        val memory = AutoplaySessionMemory()
        val queue = mutableListOf(song("s0"))
        memory.noteBecameCurrent("s0", previousEnded = false)
        val heard = mutableListOf("s0")

        repeat(30) {
            val current = queue.first { it.videoId == heard.last() }
            val batch = memory.selectForQueue(
                pool = radio(current.videoId),
                existing = queue.toList(),
                queueIds = queue.map { it.videoId }.toSet(),
                limit = 3,
            )
            assertTrue(batch.isNotEmpty(), "batch dried up at transition ${heard.size}")
            assertTrue(
                batch.none { it.videoId in heard },
                "repeated an already-heard track: ${ids(batch)} after $heard",
            )
            assertTrue(
                batch.none { candidate -> queue.any { it.videoId == candidate.videoId } },
                "duplicate queue insertion: ${ids(batch)}",
            )
            memory.noteDelivered(ids(batch))
            queue += batch
            // The first waiting suggestion plays next, to its end.
            val next = queue.first { it.videoId !in heard }
            memory.noteBecameCurrent(next.videoId, previousEnded = true)
            heard += next.videoId
        }
        assertEquals(31, heard.distinct().size)
    }

    @Test
    fun refreshAfterTheInitialBatchServesNoneOfItAgain() {
        val memory = AutoplaySessionMemory()
        memory.noteBecameCurrent("seed", previousEnded = false)
        val pool = songs("n1", "n2", "n3", "n4", "n5", "n6")

        val first = memory.selectForQueue(pool, songs("seed"), queueIds = setOf("seed"), limit = 3)
        assertEquals(listOf("n1", "n2", "n3"), ids(first))
        memory.noteDelivered(ids(first))

        // n1 plays to its end; the rest of the batch is still queued.
        memory.noteBecameCurrent("n1", previousEnded = true)
        val queue = songs("seed", "n1", "n2", "n3")
        val second = memory.selectForQueue(pool, queue, queueIds = ids(queue).toSet(), limit = 3)

        assertEquals(listOf("n4", "n5", "n6"), ids(second))
    }

    @Test
    fun aSkippedTrackIsFiledAsSkippedAndStaysOutWhileAlternativesExist() {
        val memory = AutoplaySessionMemory()
        memory.noteBecameCurrent("a", previousEnded = false)
        memory.noteBecameCurrent("b", previousEnded = false) // a left early

        assertEquals(setOf("a"), memory.skippedIds())
        assertTrue(memory.playedIds().isEmpty())

        val picks = memory.selectForQueue(
            pool = songs("a", "fresh"),
            existing = songs("a", "b"),
            queueIds = setOf("b"),
            limit = 5,
        )
        assertEquals(listOf("fresh"), ids(picks))
    }

    @Test
    fun relaxationReturnsUnplayedSuggestionsThenSkipsThenOldestPlays() {
        val memory = AutoplaySessionMemory()
        memory.noteBecameCurrent("a", previousEnded = false)
        memory.noteBecameCurrent("b", previousEnded = true) // a played out
        memory.noteBecameCurrent("c", previousEnded = false) // b skipped
        memory.noteDelivered(listOf("d")) // served, then fell out of the queue

        // Nothing fresh exists: the pool is exactly what the session has
        // already touched, ranked d, b, a by the source.
        val picks = memory.selectForQueue(
            pool = songs("d", "b", "a"),
            existing = songs("a", "b", "c", "d"),
            queueIds = setOf("c"),
            limit = 3,
        )
        assertEquals(listOf("d", "b", "a"), ids(picks))
    }

    @Test
    fun rotationBringsBackTheOldestHeardFirst() {
        val memory = AutoplaySessionMemory()
        memory.noteBecameCurrent("a", previousEnded = false)
        memory.noteBecameCurrent("b", previousEnded = true)
        memory.noteBecameCurrent("c", previousEnded = true)

        // The source ranks the most recent play first; the rotation
        // reverses that, so the session cycles instead of looping its
        // last two songs.
        val picks = memory.selectForQueue(
            pool = songs("b", "a"),
            existing = songs("a", "b", "c"),
            queueIds = setOf("c"),
            limit = 2,
        )
        assertEquals(listOf("a", "b"), ids(picks))
    }

    @Test
    fun aQueuedTrackIsNeverSelectedAgainAtAnyTier() {
        val memory = AutoplaySessionMemory()
        memory.noteBecameCurrent("a", previousEnded = false)
        memory.noteBecameCurrent("b", previousEnded = true)

        val picks = memory.selectForQueue(
            pool = songs("waiting"),
            existing = songs("a", "b", "waiting"),
            queueIds = setOf("b", "waiting"),
            limit = 5,
        )
        assertTrue(picks.isEmpty())
    }

    @Test
    fun anEmptyOrFullyExcludedPoolSelectsNothingAndNamesAnAlternateSeed() {
        val memory = AutoplaySessionMemory()
        memory.noteBecameCurrent("a", previousEnded = false)
        memory.noteBecameCurrent("b", previousEnded = true)

        assertTrue(memory.selectForQueue(emptyList(), songs("a", "b"), setOf("b"), 5).isEmpty())
        // The failed request has somewhere to go: re-seed from the track
        // heard before the current one.
        assertEquals("a", memory.alternateSeedId(exceptId = "b"))
        assertEquals(null, AutoplaySessionMemory().alternateSeedId(exceptId = "b"))
    }

    @Test
    fun togglingOffResetsTheSession() {
        val memory = AutoplaySessionMemory()
        memory.noteBecameCurrent("a", previousEnded = false)
        memory.noteBecameCurrent("b", previousEnded = true)
        memory.noteDelivered(listOf("c"))

        memory.reset()

        assertEquals(null, memory.currentId)
        assertTrue(memory.heardIds().isEmpty())
        assertTrue(memory.recommendedIds().isEmpty())
        // Yesterday's plays are this session's fresh candidates again.
        val picks = memory.selectForQueue(
            pool = songs("a", "c"),
            existing = songs("now"),
            queueIds = setOf("now"),
            limit = 5,
        )
        assertEquals(listOf("a", "c"), ids(picks))
    }

    @Test
    fun aDeliveredTrackThatPlaysLeavesTheRecommendedSet() {
        val memory = AutoplaySessionMemory()
        memory.noteBecameCurrent("a", previousEnded = false)
        memory.noteDelivered(listOf("x", "y"))
        assertEquals(setOf("x", "y"), memory.recommendedIds())

        memory.noteBecameCurrent("x", previousEnded = true)
        assertEquals(setOf("y"), memory.recommendedIds())
        assertTrue("x" in memory.heardIds())
        assertFalse("x" in memory.recommendedIds())
    }

    @Test
    fun exclusionTiersRunFromStrictToQueueOnly() {
        val memory = AutoplaySessionMemory()
        memory.noteBecameCurrent("a", previousEnded = false)
        memory.noteBecameCurrent("b", previousEnded = true)
        memory.noteDelivered(listOf("d"))

        val tiers = memory.exclusionTiers(queueIds = setOf("b", "d"))
        assertEquals(setOf("b", "d", "a"), tiers.first())
        assertEquals(setOf("b", "d"), tiers.last())
        assertTrue(tiers.zipWithNext().all { (outer, inner) -> outer.containsAll(inner) })
    }
}
