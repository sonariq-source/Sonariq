package com.music.sonariq.playback

import com.music.sonariq.data.model.Song

/**
 * Session-fresh Autoplay: the arithmetic that stops a replayed song
 * from being handed back the same recommendation sequence it got last
 * time.
 *
 * The problem this answers is structural. The discovery sources behind
 * Autoplay — the seed's radio, the learned transitions — are close to
 * deterministic for a given seed, so playing Song A on Monday and again
 * on Friday produced Song B, then Song C, in the same order, forever.
 * The queue felt memorised rather than curated.
 *
 * The fix is a bounded memory of what was actually recommended, kept
 * per seed: the last few batches, newest first. A new batch is then
 * re-ranked against that memory before it is queued:
 *
 *  - Candidates never recommended for this seed before lead, in their
 *    ranked order. Freshness wins, relevance breaks no ties it did not
 *    already own.
 *  - Returning candidates follow, the ones whose last recommendation
 *    is oldest first — a song from three sessions ago comes back
 *    before one from the last session. Within one batch they keep
 *    their ranked order.
 *
 * Nothing is ever banned. If the catalogue offers nothing fresh, the
 * returning candidates fill the batch in that aged order: a genuine
 * fresh attempt was made, and a limited catalogue degrades to a
 * reordered repeat rather than an empty queue. Penalties are ordering
 * only, so a favourite is delayed, never exiled.
 *
 * Everything here is pure and deterministic: the history update is a
 * function of the history given, so the store that persists it (and
 * the tests) can reason about it without a device. The history itself
 * is bounded on every axis — [MAX_SEEDS] seeds, [MAX_BATCHES_PER_SEED]
 * batches each, [MAX_IDS_PER_BATCH] ids a batch — so it cannot grow
 * with the catalogue.
 */
object AutoplayFreshness {

    /** How many seeds keep a recommendation history at once. */
    const val MAX_SEEDS = 20

    /** How many past batches one seed remembers. */
    const val MAX_BATCHES_PER_SEED = 3

    /** How many recommended ids one remembered batch keeps. */
    const val MAX_IDS_PER_BATCH = 10

    /**
     * [candidates], ranked as their source ranked them, re-ordered
     * against [previousBatches] — this seed's remembered batches,
     * newest first — and cut to [limit]. See the class doc for the
     * ordering. With no history the candidates pass through untouched,
     * bar de-duplication.
     */
    fun freshOrder(
        candidates: List<Song>,
        previousBatches: List<List<String>>,
        limit: Int,
    ): List<Song> {
        if (limit <= 0) return emptyList()
        val distinct = candidates.distinctBy { it.videoId }
        if (previousBatches.isEmpty()) return distinct.take(limit)
        // How long ago each remembered id was last recommended: the
        // index of the newest batch holding it. Absent means fresh.
        val lastBatchIndex = HashMap<String, Int>()
        previousBatches.forEachIndexed { batchIndex, batch ->
            for (id in batch) {
                if (id !in lastBatchIndex) lastBatchIndex[id] = batchIndex
            }
        }
        val fresh = ArrayList<Song>()
        val returning = ArrayList<Song>()
        for (song in distinct) {
            if (song.videoId in lastBatchIndex) returning += song else fresh += song
        }
        // Oldest last-recommendation first; the sort is stable, so a
        // shared batch keeps the source's own ranking inside it.
        val aged = returning.sortedByDescending { lastBatchIndex.getValue(it.videoId) }
        return (fresh + aged).take(limit)
    }

    /**
     * [history] with [batchIds] recorded as the newest batch for
     * [seedId]. Blank ids are dropped, a batch is de-duplicated and
     * capped, the seed's batches are capped newest-first, and the
     * least recently updated seeds fall off past [MAX_SEEDS]. An empty
     * batch records nothing: a failed discovery is not a session.
     */
    fun recordBatch(
        history: Map<String, List<List<String>>>,
        seedId: String,
        batchIds: List<String>,
    ): Map<String, List<List<String>>> {
        val batch = batchIds.filter { it.isNotBlank() }
            .distinct()
            .take(MAX_IDS_PER_BATCH)
        if (batch.isEmpty() || seedId.isBlank()) return history
        val batches = (listOf(batch) + (history[seedId] ?: emptyList()))
            .take(MAX_BATCHES_PER_SEED)
        val out = LinkedHashMap<String, List<List<String>>>()
        out[seedId] = batches
        for ((seed, seedBatches) in history) {
            if (seed == seedId) continue
            if (out.size >= MAX_SEEDS) break
            out[seed] = seedBatches
        }
        return out
    }
}
