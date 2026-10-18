package com.music.sonariq

import com.music.sonariq.data.listentogether.PartyTrack
import com.music.sonariq.data.listentogether.countVoteNudges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vote tally: which queue diffs count as votes and which do not.
 *
 * A vote always lands as one exact shape — a single track swapping up one
 * slot with its neighbour — because [PartyVoting.vote] sends exactly that
 * queue move. The tally may count that shape and nothing else.
 */
class PartyVotingTest {

    private fun track(id: String) = PartyTrack(videoId = id, title = "Track $id")

    private fun ids(vararg ids: String) = ids.map(::track)

    @Test
    fun `a single upward swap counts one vote for the track that moved up`() {
        val old = ids("a", "b", "c", "d")
        val new = ids("a", "c", "b", "d")

        assertEquals(mapOf("c" to 1), countVoteNudges(old, new))
    }

    @Test
    fun `an unchanged queue counts nothing`() {
        val queue = ids("a", "b", "c")

        assertTrue(countVoteNudges(queue, queue).isEmpty())
    }

    @Test
    fun `an added track is not a vote`() {
        val old = ids("a", "b")
        val new = ids("a", "b", "c")

        assertTrue(countVoteNudges(old, new).isEmpty())
    }

    @Test
    fun `a removed track is not a vote`() {
        val old = ids("a", "b", "c")
        val new = ids("a", "c")

        assertTrue(countVoteNudges(old, new).isEmpty())
    }

    @Test
    fun `a multi-slot move is not a vote`() {
        // A drag two slots down is a manual reorder, not a vote nudge.
        val old = ids("a", "b", "c", "d")
        val new = ids("a", "c", "d", "b")

        assertTrue(countVoteNudges(old, new).isEmpty())
    }

    @Test
    fun `two disjoint swaps each count`() {
        val old = ids("a", "b", "c", "d", "e", "f")
        val new = ids("a", "c", "b", "d", "f", "e")

        assertEquals(mapOf("c" to 1, "f" to 1), countVoteNudges(old, new))
    }

    @Test
    fun `a rotation is not votes`() {
        // Could be two rapid votes or one manual move — the tally never guesses.
        val old = ids("a", "b", "c")
        val new = ids("b", "c", "a")

        assertTrue(countVoteNudges(old, new).isEmpty())
    }

    @Test
    fun `duplicate tracks do not crash and do not count`() {
        val old = ids("a", "a", "b")
        val new = ids("a", "a", "b")

        assertTrue(countVoteNudges(old, new).isEmpty())
    }

    @Test
    fun `empty queues count nothing`() {
        assertTrue(countVoteNudges(emptyList(), emptyList()).isEmpty())
        assertTrue(countVoteNudges(ids("a"), emptyList()).isEmpty())
    }
}
