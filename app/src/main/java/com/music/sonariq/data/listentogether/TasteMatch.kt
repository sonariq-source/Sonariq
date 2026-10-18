package com.music.sonariq.data.listentogether

import com.music.sonariq.data.stats.ListeningStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Taste match: how compatible a friend's taste is with yours, from real
 * listening overlap — never a fabricated score.
 *
 * ## What "overlap" honestly means here
 *
 * The party server has no listening-history or presence endpoint, so there is
 * no feed of "what your friends listen to" to read. What exists is what this
 * device actually observed in parties it was in:
 *
 * - the friend's *picks*: tracks they started ([PartyPlayback.startedByName])
 *   or queued (the server's queue-add broadcasts) across recorded party
 *   recaps — the closest thing to a taste signal the protocol offers;
 * - your top tracks: this device's own lifetime listening totals
 *   ([ListeningStats.allTracks]).
 *
 * The score is the share of the friend's picks that also sit in your top
 * tracks. A friend with fewer than [MIN_PICKS] observed picks gets no score
 * at all — the UI says there is not enough shared listening yet, rather than
 * showing a number made of nothing.
 *
 * ## Identity
 *
 * Friends are keyed by display name, the same identity the friend feed shows.
 * A display name somebody changes mid-era splits their record; that is
 * accepted rather than papered over with a guess.
 */
data class TasteMatchResult(
    val friendName: String,
    /** 0–100: share of the friend's observed picks that are in your top tracks. */
    val score: Int,
    /** Titles you both reach for, most representative first. */
    val sharedTrackTitles: List<String>,
    /** How many of the friend's picks the score was computed from. */
    val friendPickCount: Int,
)

object TasteMatch {

    /** Below this many observed picks, no score is shown — only an honest "not yet". */
    const val MIN_PICKS = 3

    /** How far down your lifetime chart the comparison reaches. */
    const val USER_TOP_TRACKS = 150

    /**
     * The compatibility score for one friend, or null when there is not
     * enough real overlap to say anything.
     */
    suspend fun scoreFor(friendName: String): TasteMatchResult? = withContext(Dispatchers.IO) {
        val name = friendName.trim()
        if (name.isBlank()) return@withContext null
        if (name == ListenTogether.nickname().trim()) return@withContext null
        val picks = friendPicks(name, PartyRecapCollector.recaps.value)
        if (picks.size < MIN_PICKS) return@withContext null
        val userKeys = userTopTrackKeys()
        computeScore(name, picks, userKeys)
    }

    /**
     * Scores for every friend with recorded picks, best match first. Never
     * includes this device's own nickname.
     */
    suspend fun allScores(): List<TasteMatchResult> = withContext(Dispatchers.IO) {
        val self = ListenTogether.nickname().trim()
        val recaps = PartyRecapCollector.recaps.value
        val names = mutableSetOf<String>()
        for (recap in recaps) {
            recap.playedTracks.mapNotNullTo(names) { it.addedBy?.trim()?.takeIf(String::isNotBlank) }
            recap.addedTracks.mapNotNullTo(names) { it.by.trim().takeIf(String::isNotBlank) }
        }
        val userKeys = userTopTrackKeys()
        names
            .filter { it != self }
            .mapNotNull { name ->
                val picks = friendPicks(name, recaps)
                if (picks.size < MIN_PICKS) null
                else computeScore(name, picks, userKeys)
            }
            .sortedByDescending { it.score }
    }

    /**
     * Everything a friend demonstrably picked across recorded parties: one
     * track key per pick, to a display title. Keys prefer the protocol's
     * `videoId` and fall back to the normalised title where only the activity
     * broadcast's title is known — one key per pick so a partial key overlap
     * can never understate the score.
     */
    fun friendPicks(
        friendName: String,
        recaps: List<PartyRecap>,
    ): Map<String, String> {
        val picks = LinkedHashMap<String, String>()
        for (recap in recaps) {
            for (track in recap.playedTracks) {
                if (track.addedBy?.trim() == friendName) {
                    val title = listOfNotNull(
                        track.title.takeIf { it.isNotBlank() },
                        track.artist.takeIf { it.isNotBlank() },
                    ).joinToString(" — ").ifBlank { track.videoId }
                    val key = track.videoId.takeIf { it.isNotBlank() }
                        ?.let(::videoKey)
                        ?: titleKey(track.title.ifBlank { track.videoId })
                    picks.putIfAbsent(key, title)
                }
            }
            for (add in recap.addedTracks) {
                if (add.by.trim() == friendName) {
                    val title = add.title?.takeIf { it.isNotBlank() } ?: continue
                    picks.putIfAbsent(titleKey(title), title)
                }
            }
        }
        return picks
    }

    private suspend fun userTopTrackKeys(): Set<String> {
        val tracks = runCatching { ListeningStats.allTracks() }.getOrDefault(emptyList())
        return tracks.sortedByDescending { it.plays }
            .take(USER_TOP_TRACKS)
            .flatMapTo(mutableSetOf()) { track ->
                listOf(videoKey(track.id), titleKey(track.title))
            }
    }

    /**
     * The score itself, pure so it is unit-testable without stats or recaps:
     * the share of the friend's picks present in your top tracks, as a
     * percentage. Null when the picks do not clear [MIN_PICKS].
     */
    fun computeScore(
        friendName: String,
        friendPicks: Map<String, String>,
        userTopKeys: Set<String>,
    ): TasteMatchResult? {
        if (friendPicks.size < MIN_PICKS) return null
        val shared = friendPicks.filterKeys { it in userTopKeys }
        val titles = shared.values.distinct().take(10)
        val score = ((shared.size * 100.0) / friendPicks.size)
            .coerceIn(0.0, 100.0)
            .toInt()
        return TasteMatchResult(
            friendName = friendName,
            score = score,
            sharedTrackTitles = titles,
            friendPickCount = friendPicks.size,
        )
    }

    private fun videoKey(videoId: String): String = "v:${videoId.trim()}"

    private fun titleKey(title: String): String =
        "t:" + title.trim().lowercase().replace(Regex("\\s+"), " ")
}
