package com.music.sonariq.playback

import com.music.sonariq.data.model.Song

/**
 * Session-strict Autoplay: the memory that stops one listening session
 * from being handed the same songs twice.
 *
 * [AutoplayFreshness] already handles the cross-session problem — a seed
 * replayed on another day should not serve last session's batch in last
 * session's order — but it only ever demotes. Inside one session that is
 * not enough: the discovery sources are near-deterministic per seed, so
 * a batch built an hour into a session kept re-offering tracks the
 * session had already played, skipped or queued, and once a pool ran
 * dry the queue simply stopped growing.
 *
 * This is the stricter in-session layer. It remembers, for the life of
 * one service session:
 *
 *  - every track that became current, in the order it did ([heard]);
 *  - which of those the listener left by letting it end ([played]) and
 *    which they left early ([skipped]);
 *  - every track Autoplay delivered that has not been heard yet
 *    ([recommended]).
 *
 * Selection ([selectForQueue]) then works in bounded tiers of
 * progressively relaxed exclusion, strictest first:
 *
 *  1. Exclude the queue, everything heard and everything already
 *     recommended: only genuinely new tracks.
 *  2. Allow tracks recommended earlier but never heard — they fell out
 *     of the queue unplayed, which is not a rejection.
 *  3. Allow skipped tracks — the listener moved on once, but a small
 *     catalogue should not exile a song for one skip.
 *  4. Allow heard tracks, oldest-heard first: a rotation, so a session
 *     that has genuinely exhausted its sources cycles back to the
 *     tracks it played longest ago instead of stopping.
 *
 * Every tier is a single pass over the candidate pool, so selection
 * always terminates. Nothing here persists: a new app session starts
 * with an empty memory and [AutoplayFreshness] is what carries the
 * taste across sessions. Pure and deterministic, so the playback
 * service and the tests reason about it identically.
 */
class AutoplaySessionMemory {

    private val heard = LinkedHashSet<String>()
    private val played = LinkedHashSet<String>()
    private val skipped = LinkedHashSet<String>()
    private val recommended = LinkedHashSet<String>()

    /** The track currently playing, as last reported by [noteBecameCurrent]. */
    var currentId: String? = null
        private set

    /**
     * Records that [id] just became the playing track. The track it
     * replaces is filed as [played] when it ended on its own
     * ([previousEnded]) and as [skipped] when the listener left it
     * early; a repeat of the same track files nothing.
     */
    fun noteBecameCurrent(id: String, previousEnded: Boolean) {
        if (id.isBlank()) return
        val previous = currentId
        if (previous != null && previous != id) {
            if (previousEnded) {
                played += previous
                skipped -= previous
            } else if (previous !in played) {
                skipped += previous
            }
        }
        currentId = id
        heard += id
        recommended -= id
        trim(heard)
        trim(played)
        trim(skipped)
    }

    /** Records one delivered Autoplay batch: suggested, not yet heard. */
    fun noteDelivered(ids: Collection<String>) {
        for (id in ids) {
            if (id.isNotBlank() && id !in heard) recommended += id
        }
        trim(recommended)
    }

    /** Forgets the whole session; AutoPlay turned off is a session over. */
    fun reset() {
        heard.clear()
        played.clear()
        skipped.clear()
        recommended.clear()
        currentId = null
    }

    /** Every track heard this session, oldest first. */
    fun heardIds(): List<String> = heard.toList()

    /** Tracks the listener left early this session. */
    fun skippedIds(): Set<String> = skipped.toSet()

    /** Tracks that played out to their end this session. */
    fun playedIds(): Set<String> = played.toSet()

    /** Delivered this session but not yet heard. */
    fun recommendedIds(): Set<String> = recommended.toSet()

    /**
     * The exclusion sets selection relaxes through, strictest first,
     * against a queue currently holding [queueIds]. Consecutive tiers
     * that exclude the same ids collapse into one.
     */
    fun exclusionTiers(queueIds: Set<String>): List<Set<String>> = listOf(
        queueIds + heard + recommended,
        queueIds + heard,
        queueIds + played,
        queueIds.toSet(),
    ).distinct()

    /**
     * Another track this session has heard, to re-seed discovery with
     * when [exceptId]'s own sources come back empty or fully excluded:
     * the most recently heard one that is not [exceptId] itself.
     */
    fun alternateSeedId(exceptId: String): String? =
        heard.lastOrNull { it != exceptId }

    /**
     * Up to [limit] picks from [pool] for a queue whose full contents —
     * waiting, playing and already played — are [existing], of which
     * [queueIds] are the ids physically in the queue.
     *
     * Each tier runs [QueueBuilder.extend] against only the [existing]
     * songs that tier still excludes, so the strict tiers keep the
     * builder's same-recording and per-artist rules at full strength
     * and the relaxed tiers inherit them against what remains excluded.
     * Picks accumulate across tiers until [limit] is met; the final
     * tier's picks are re-ordered oldest-heard first so an exhausted
     * catalogue rotates rather than repeats its most recent plays.
     */
    fun selectForQueue(
        pool: List<Song>,
        existing: List<Song>,
        queueIds: Set<String>,
        limit: Int,
    ): List<Song> {
        if (limit <= 0 || pool.isEmpty()) return emptyList()
        val chosen = ArrayList<Song>()
        val tiers = exclusionTiers(queueIds)
        tiers.forEachIndexed { index, tier ->
            if (chosen.size >= limit) return@forEachIndexed
            val tierExisting = existing.filter { it.videoId in tier } + chosen
            var picks = QueueBuilder.extend(tierExisting, pool, limit - chosen.size)
            if (index == tiers.lastIndex && picks.isNotEmpty()) {
                picks = picks.sortedBy { heardIndex(it.videoId) }
            }
            chosen += picks
        }
        return chosen
    }

    /** Position in the heard order, or last place for a track never heard. */
    private fun heardIndex(id: String): Int {
        var index = 0
        for (entry in heard) {
            if (entry == id) return index
            index++
        }
        return Int.MAX_VALUE
    }

    /**
     * Keeps one memory set bounded, eldest first out. A session longer
     * than the cap forgets its oldest plays, which the rotation tier
     * then treats as fresh — the right failure mode for a memory whose
     * whole job is recency.
     */
    private fun trim(set: LinkedHashSet<String>) {
        while (set.size > MAX_REMEMBERED) {
            set.remove(set.first())
        }
    }

    private companion object {
        /** Per-set memory cap; see [trim]. */
        const val MAX_REMEMBERED = 500
    }
}
