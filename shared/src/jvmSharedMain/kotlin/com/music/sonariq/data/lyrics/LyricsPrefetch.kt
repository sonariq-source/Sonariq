package com.music.sonariq.data.lyrics

/**
 * One track worth warming the lyrics cache for. The fields mirror what the
 * lyrics lookup needs, so a planner result can be handed straight to
 * [LyricsRepository.lyrics] with no translation.
 */
data class PrefetchCandidate(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val album: String? = null,
)

/** The most queue tracks one prefetch pass warms — the whole queue, bounded. */
const val QUEUE_PREFETCH_CAP = 100

/**
 * The tracks coming up after the one playing, worth fetching lyrics for ahead
 * of time so the panel opens instantly when each one starts.
 *
 * Takes the window after [currentIndex] only — no wrapping back to the head
 * of the queue, because wrapping would warm tracks the listener has just
 * heard. Repeated video ids keep their first occurrence, entries already on
 * disk are dropped, and the result is capped at [cap]. Stays in queue order.
 *
 * The default cap covers the whole queue (up to [QUEUE_PREFETCH_CAP]): the
 * queue is the one list the listener has already committed to hearing, so
 * every track in it earns a warm cache rather than only the next few.
 */
fun planPrefetch(
    queue: List<PrefetchCandidate>,
    currentIndex: Int,
    cap: Int = QUEUE_PREFETCH_CAP,
    isCached: (String) -> Boolean,
): List<PrefetchCandidate> {
    if (cap <= 0) return emptyList()
    val window = if (currentIndex < 0) queue else queue.drop(currentIndex + 1)
    return dedupeUncached(window, isCached).take(cap)
}

/**
 * The tracks worth warming at idle time: liked songs first, then the tracks
 * the listening stats point at, which the caller passes already ordered by
 * recency and plays.
 *
 * Merged unique by video id — a liked track that also shows up in the stats
 * keeps its liked position — with entries already on disk dropped and the
 * result capped at [cap].
 */
fun planWarmup(
    liked: List<PrefetchCandidate>,
    stats: List<PrefetchCandidate>,
    cap: Int = 300,
    isCached: (String) -> Boolean,
): List<PrefetchCandidate> {
    if (cap <= 0) return emptyList()
    return dedupeUncached(liked + stats, isCached).take(cap)
}

/**
 * The tracks worth warming because they are on screen: a Home shelf's
 * visible cards, or a playlist / album page's track list.
 *
 * There is no playing position to skip here — the whole list is the window —
 * so this is [dedupeUncached] over the list as given, capped at
 * [LyricsLookupPolicy.LIST_PREFETCH_CAP] so a very long page warms its head
 * rather than its whole scrollback. Callers pass only tracks with no local
 * file: a local track's lyrics come from its own tags, never a service.
 */
fun planListPrefetch(
    visible: List<PrefetchCandidate>,
    cap: Int = LyricsLookupPolicy.LIST_PREFETCH_CAP,
    isCached: (String) -> Boolean,
): List<PrefetchCandidate> {
    if (cap <= 0) return emptyList()
    return dedupeUncached(visible, isCached).take(cap)
}

/** Entries with a usable video id, first occurrence per id, cache hits removed. */
private fun dedupeUncached(
    candidates: List<PrefetchCandidate>,
    isCached: (String) -> Boolean,
): List<PrefetchCandidate> {
    val seen = HashSet<String>()
    return candidates.filter { candidate ->
        candidate.videoId.isNotBlank() &&
            seen.add(candidate.videoId) &&
            !isCached(candidate.videoId)
    }
}
