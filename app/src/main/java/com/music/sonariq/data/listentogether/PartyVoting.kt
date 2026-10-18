package com.music.sonariq.data.listentogether

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Party voting: members vote on what plays next, and every vote genuinely
 * reorders the shared queue.
 *
 * ## How a vote works
 *
 * There is no vote field in the party protocol, and the deployed server only
 * understands the control actions it already has — so a vote is sent as the
 * one control that means exactly what a vote means: [ListenTogether.queueMove].
 * Tapping vote on an upcoming track moves it one slot closer to "next" on the
 * server, and the server's queue frame then carries the new running order to
 * every member. Nothing about the tally is simulated: the reorder every other
 * device sees is the vote having happened.
 *
 * ## How votes are counted
 *
 * The server broadcasts no per-vote record, so each device tallies what it
 * can actually observe: the queue frames. A vote always lands as one exact
 * shape in the queue — a single track moving up exactly one slot, swapping
 * with its neighbour ([countVoteNudges]) — and that shape is tallied on every
 * device that was watching when the queue frame arrived. A device that joins
 * mid-party never saw the earlier frames, so its counts start at zero; the
 * counts are "votes tallied by this device", not a server total, and the UI
 * should present them as votes, not history.
 *
 * Anything that is not that exact shape is not counted: a multi-slot drag, a
 * track added or removed in the same breath, a queue replace. The reorder
 * still happened — it is real on the server — it just is not tallied as a
 * vote, because the tally may never guess.
 *
 * ## Who may vote
 *
 * A vote is a queue control, so the server's control policy applies: in a
 * host-only party a listener's vote is refused by the server, and [vote]
 * refuses it locally first rather than sending something already known to
 * fail. The host may still vote — nudging the order of their own party.
 */
object PartyVoting {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    private val _votes = MutableStateFlow<Map<String, Int>>(emptyMap())

    /**
     * Votes tallied by this device for the current party, by track `videoId`.
     * Reset when the party changes.
     */
    val votes: StateFlow<Map<String, Int>> = _votes.asStateFlow()

    private var lastCode: String? = null
    private var lastItems: List<PartyTrack> = emptyList()

    /**
     * Starts tallying. Safe to call more than once; the collector runs a
     * single time per process. Call from `Application.onCreate` (see the
     * integration notes) so votes are tallied even when the voting sheet was
     * never opened.
     */
    fun init() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            ListenTogether.state.collect(::onState)
        }
    }

    /**
     * Casts one vote for the track: a real [ListenTogether.queueMove] nudging
     * it one slot closer to "next".
     *
     * @return false when there is nothing to vote on — no party, a locked
     * party for this device, or the track is not upcoming (already playing or
     * already next).
     */
    fun vote(videoId: String): Boolean {
        val state = ListenTogether.state.value
        if (!state.inParty || state.controlsLocked) return false
        val queue = state.queue
        val index = queue.items.indexOfFirst { it.videoId == videoId }
        if (index <= queue.index + 1) return false
        ListenTogether.queueMove(fromIndex = index, toIndex = index - 1, videoId = videoId)
        return true
    }

    private fun onState(state: ListenTogether.State) {
        val code = state.code
        if (code != lastCode) {
            lastCode = code
            lastItems = state.queue.items
            _votes.value = emptyMap()
            return
        }
        if (code == null) return
        val newItems = state.queue.items
        val nudges = countVoteNudges(lastItems, newItems)
        lastItems = newItems
        if (nudges.isNotEmpty()) {
            val merged = _votes.value.toMutableMap()
            for ((videoId, n) in nudges) {
                merged[videoId] = (merged[videoId] ?: 0) + n
            }
            _votes.value = merged
        }
    }
}

/**
 * Tallies vote nudges between two queue snapshots.
 *
 * A vote nudge is exactly one shape: two neighbouring tracks swapping, the
 * upper one moving down and the lower one moving up a single slot. Returns the
 * `videoId` of each track that moved up, mapped to how many such swaps it was
 * part of. Several disjoint swaps in one frame each count; anything else —
 * adds, removes, multi-slot moves, a replace — counts nothing, because the
 * tally may never guess at what it cannot see.
 *
 * Pure so the counting is unit-testable without a party.
 */
fun countVoteNudges(
    oldItems: List<PartyTrack>,
    newItems: List<PartyTrack>,
): Map<String, Int> {
    if (oldItems.isEmpty() || oldItems.size != newItems.size) return emptyMap()
    val oldIds = oldItems.map { it.videoId }
    val newIds = newItems.map { it.videoId }
    if (oldIds.sorted() != newIds.sorted()) return emptyMap()
    val counts = mutableMapOf<String, Int>()
    var i = 0
    while (i < oldIds.size - 1) {
        val a = oldIds[i]
        val b = oldIds[i + 1]
        if (a != b && newIds[i] == b && newIds[i + 1] == a) {
            // b swapped up over a: one vote for b.
            counts[b] = (counts[b] ?: 0) + 1
            i += 2
        } else {
            i += 1
        }
    }
    return counts
}
