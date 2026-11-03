package com.music.sonariq.playback

import com.music.sonariq.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Session-fresh Autoplay: a replayed seed must not be handed back the
 * same recommendation sequence it got last time when the catalogue
 * offers anything fresh, returning songs are demoted rather than
 * banned, and the remembered history is bounded on every axis.
 */
class AutoplayFreshnessTest {

    private fun song(id: String) =
        Song(videoId = id, title = "Song $id", artist = "Artist", thumbnailUrl = null)

    private fun songs(vararg ids: String) = ids.map(::song)

    private fun ids(batch: List<Song>) = batch.map { it.videoId }

    @Test
    fun noHistoryPassesTheRankedOrderThrough() {
        val out = AutoplayFreshness.freshOrder(songs("a", "b", "c"), emptyList(), limit = 10)
        assertEquals(listOf("a", "b", "c"), ids(out))
    }

    @Test
    fun freshCandidatesLeadAndLastSessionsPicksAreDemoted() {
        // Last session for this seed queued b then c. The radio offers
        // the same pool again plus one fresh song, ranked last by the
        // source: freshness, not the stale ranking, decides the order.
        val previous = listOf(listOf("b", "c"))
        val out = AutoplayFreshness.freshOrder(songs("b", "c", "a", "d"), previous, limit = 10)
        assertEquals(listOf("a", "d", "b", "c"), ids(out))
    }

    @Test
    fun theSameSeedDoesNotReproduceTheSameSequenceWhenFreshCandidatesExist() {
        val previous = listOf(listOf("b", "c", "d"))
        val out = AutoplayFreshness.freshOrder(songs("b", "c", "d", "e", "f"), previous, limit = 3)
        // The previous batch's exact sequence is gone: fresh songs the
        // source ranked below the cut take the demoted picks' places.
        assertEquals(listOf("e", "f", "b"), ids(out))
    }

    @Test
    fun returningCandidatesComeBackOldestRecommendationFirst() {
        // b was recommended in the newest batch, d two batches ago:
        // d has waited longer, so d returns ahead of b.
        val previous = listOf(listOf("b"), listOf("x"), listOf("d"))
        val out = AutoplayFreshness.freshOrder(songs("b", "d"), previous, limit = 10)
        assertEquals(listOf("d", "b"), ids(out))
    }

    @Test
    fun aCatalogueWithNothingFreshStillFillsTheBatch() {
        // Every candidate was recommended last session. Nothing is
        // banned: the batch is the same songs, in the aged order,
        // rather than an empty queue.
        val previous = listOf(listOf("a", "b", "c"))
        val out = AutoplayFreshness.freshOrder(songs("a", "b", "c"), previous, limit = 10)
        assertEquals(listOf("a", "b", "c"), ids(out))
    }

    @Test
    fun duplicatesAndTheLimitAreHonoured() {
        val previous = listOf(listOf("a"))
        val out = AutoplayFreshness.freshOrder(songs("a", "a", "b", "c", "d"), previous, limit = 2)
        assertEquals(listOf("b", "c"), ids(out))
        assertTrue(AutoplayFreshness.freshOrder(songs("a"), previous, limit = 0).isEmpty())
    }

    @Test
    fun recordBatchPutsTheNewestBatchFirstAndCapsBatches() {
        var history = AutoplayFreshness.recordBatch(emptyMap(), "seed", listOf("a", "b"))
        history = AutoplayFreshness.recordBatch(history, "seed", listOf("c"))
        history = AutoplayFreshness.recordBatch(history, "seed", listOf("d"))
        history = AutoplayFreshness.recordBatch(history, "seed", listOf("e"))
        assertEquals(
            listOf(listOf("e"), listOf("d"), listOf("c")),
            history["seed"],
        )
    }

    @Test
    fun recordBatchCapsIdsAndSeeds() {
        var history = AutoplayFreshness.recordBatch(
            emptyMap(),
            "seed",
            (1..25).map { "id$it" },
        )
        assertEquals(AutoplayFreshness.MAX_IDS_PER_BATCH, history.getValue("seed").single().size)
        for (n in 1..(AutoplayFreshness.MAX_SEEDS + 5)) {
            history = AutoplayFreshness.recordBatch(history, "seed$n", listOf("x"))
        }
        assertEquals(AutoplayFreshness.MAX_SEEDS, history.size)
        // The most recently updated seed survives; the original fell off.
        assertTrue("seed${AutoplayFreshness.MAX_SEEDS + 5}" in history)
        assertTrue("seed" !in history)
    }

    @Test
    fun recordBatchIgnoresEmptyBatchesAndBlankSeeds() {
        val history = mapOf("seed" to listOf(listOf("a")))
        assertEquals(history, AutoplayFreshness.recordBatch(history, "seed", emptyList()))
        assertEquals(history, AutoplayFreshness.recordBatch(history, "seed", listOf("", " ")))
        assertEquals(history, AutoplayFreshness.recordBatch(history, "", listOf("b")))
    }
}
