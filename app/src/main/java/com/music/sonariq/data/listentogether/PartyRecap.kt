package com.music.sonariq.data.listentogether

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One track as the party played it, for the recap.
 *
 * [addedBy] is the protocol's own attribution — [PartyPlayback.startedByName],
 * the member who picked the track — and is null when the protocol does not
 * know, e.g. an AutoPlay top-up. The protocol carries no per-track adder for
 * queued tracks, so anything queued but never played is attributed from the
 * activity log instead (see [RecapAddedTrack]); a null here is shown as no
 * attribution rather than a guess.
 */
@Serializable
data class RecapPlayedTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val plays: Int,
    val addedBy: String?,
    val firstPlayedAtMs: Long,
)

/**
 * A track somebody queued, from the server's activity broadcast.
 *
 * [title] is null when the broadcast named no title — a multi-add names only
 * its first track — and [moreCount] is how many further tracks the same action
 * stood for. The adder ([by]) is always real: it is the member the server
 * named on the broadcast.
 */
@Serializable
data class RecapAddedTrack(
    val title: String?,
    val by: String,
    val moreCount: Int = 0,
)

/**
 * What a party amounted to, from this device's honest observations.
 *
 * Only what this device actually saw while it was in the party: tracks are
 * recorded from the party-state frames' track transitions, adds from the
 * server's activity broadcasts. A party this device never joined has no
 * recap, and a process death mid-party ends the record where it ends — the
 * recap never invents what it did not see.
 */
@Serializable
data class PartyRecap(
    val code: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val memberNames: List<String>,
    /** Ranked: most plays first, ties broken by who played first. */
    val playedTracks: List<RecapPlayedTrack>,
    val addedTracks: List<RecapAddedTrack>,
) {
    /** Total tracks queued by [member], titled or not. */
    fun addedCount(member: String): Int =
        addedTracks.filter { it.by == member }.sumOf { 1 + it.moreCount }
}

/** One observed play, before ranking. */
data class RawPlay(
    val videoId: String,
    val title: String,
    val artist: String,
    val startedByName: String?,
    val atMs: Long,
)

/** The titled part of a queue-add activity detail, plus how many more it stood for. */
data class QueueAddInfo(val title: String, val moreCount: Int)

/**
 * Parses the server's queue-add activity detail.
 *
 * The server writes `Added "Title" to the queue` for one track and
 * `Added "Title" and N more to the queue` for several. Anything else —
 * including the bare action name the server falls back to when it has no
 * title — is not a parseable add and returns null.
 */
fun parseQueueAddDetail(detail: String): QueueAddInfo? {
    Regex("^Added \"(.+)\" and (\\d+) more to the queue$").matchEntire(detail)?.let {
        return QueueAddInfo(title = it.groupValues[1], moreCount = it.groupValues[2].toInt())
    }
    Regex("^Added \"(.+)\" to the queue$").matchEntire(detail)?.let {
        return QueueAddInfo(title = it.groupValues[1], moreCount = 0)
    }
    return null
}

/**
 * Ranks observed plays into recap tracks: most plays first, ties broken by
 * earliest first play. Attribution is the first named picker across the
 * track's plays, or null when the protocol never named one.
 *
 * Pure so the ranking is unit-testable without a party.
 */
fun rankPlayedTracks(plays: List<RawPlay>): List<RecapPlayedTrack> {
    return plays.groupBy { it.videoId }
        .map { (videoId, list) ->
            val first = list.minByOrNull { it.atMs } ?: return@map null
            RecapPlayedTrack(
                videoId = videoId,
                title = first.title.ifBlank { videoId },
                artist = first.artist,
                plays = list.size,
                addedBy = list.firstNotNullOfOrNull { it.startedByName?.takeIf(String::isNotBlank) },
                firstPlayedAtMs = first.atMs,
            )
        }
        .filterNotNull()
        .sortedWith(
            compareByDescending<RecapPlayedTrack> { it.plays }
                .thenBy { it.firstPlayedAtMs },
        )
}

/**
 * Records a party as it happens and keeps the last few recaps.
 *
 * Watches [ListenTogether.state] for track transitions and member lists and
 * [ListenTogether.activity] for queue-add broadcasts, then snapshots a
 * [PartyRecap] when the party ends for this device and persists it. Must be
 * started from `Application.onCreate` (see the integration notes): a recap
 * can only cover what was observed live, so starting it when a screen opens
 * would silently drop everything before that moment.
 */
object PartyRecapCollector {

    private const val PREFS = "sonariq_party_recaps"
    private const val KEY_RECAPS = "recaps_json"
    private const val MAX_RECAPS = 10

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private lateinit var prefs: SharedPreferences

    private val _recaps = MutableStateFlow<List<PartyRecap>>(emptyList())
    val recaps: StateFlow<List<PartyRecap>> = _recaps.asStateFlow()

    private val _latest = MutableStateFlow<PartyRecap?>(null)
    val latest: StateFlow<PartyRecap?> = _latest.asStateFlow()

    // --- live session ----------------------------------------------------

    private var sessionCode: String? = null
    private var sessionStartMs: Long = 0L
    private var lastTrackId: String? = null
    private var memberNames: List<String> = emptyList()
    private val plays = mutableListOf<RawPlay>()
    private val adds = mutableListOf<RecapAddedTrack>()
    private val seenActivityKeys = mutableSetOf<String>()

    fun init(context: Context) {
        if (!started.compareAndSet(false, true)) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
        scope.launch { ListenTogether.state.collect(::onState) }
        scope.launch { ListenTogether.activity.collect(::onActivity) }
    }

    private fun onState(state: ListenTogether.State) {
        val code = state.code
        if (code == null) {
            if (sessionCode != null) endSession(System.currentTimeMillis())
            return
        }
        if (code != sessionCode) {
            if (sessionCode != null) endSession(System.currentTimeMillis())
            startSession(code, System.currentTimeMillis())
        }
        memberNames = state.members
            .map { it.displayName.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val track = state.playback.track
        val trackId = track?.videoId
        if (trackId != null && trackId != lastTrackId) {
            lastTrackId = trackId
            plays += RawPlay(
                videoId = trackId,
                title = track.title,
                artist = track.artist,
                startedByName = state.playback.startedByName?.takeIf { it.isNotBlank() },
                atMs = System.currentTimeMillis(),
            )
        } else if (trackId == null) {
            lastTrackId = null
        }
    }

    private fun onActivity(entries: List<PartyActivity>) {
        if (sessionCode == null) return
        // Oldest first so the record reads in the order things happened.
        for (entry in entries.asReversed()) {
            val key = "${entry.atMs}|${entry.action}|${entry.by}|${entry.detail}"
            if (!seenActivityKeys.add(key)) continue
            if (entry.action == "queueAdd") {
                val info = parseQueueAddDetail(entry.detail)
                adds += RecapAddedTrack(
                    title = info?.title,
                    by = entry.by,
                    moreCount = info?.moreCount ?: 0,
                )
            }
        }
    }

    private fun startSession(code: String, nowMs: Long) {
        sessionCode = code
        sessionStartMs = nowMs
        lastTrackId = null
        memberNames = emptyList()
        plays.clear()
        adds.clear()
        seenActivityKeys.clear()
        // Whatever is already playing when this device arrives has no activity
        // broadcast on this device to learn it from — record it from the state
        // instead, so joining mid-song does not lose the song.
        val state = ListenTogether.state.value
        val track = state.playback.track
        if (track != null) {
            lastTrackId = track.videoId
            plays += RawPlay(
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                startedByName = state.playback.startedByName?.takeIf { it.isNotBlank() },
                atMs = nowMs,
            )
        }
    }

    private fun endSession(nowMs: Long) {
        val code = sessionCode ?: return
        val recap = PartyRecap(
            code = code,
            startedAtMs = sessionStartMs,
            endedAtMs = nowMs,
            memberNames = memberNames,
            playedTracks = rankPlayedTracks(plays),
            addedTracks = adds.toList(),
        )
        sessionCode = null
        val updated = (listOf(recap) + _recaps.value).take(MAX_RECAPS)
        _recaps.value = updated
        _latest.value = recap
        persist(updated)
    }

    private fun load() {
        val raw = prefs.getString(KEY_RECAPS, null) ?: return
        runCatching { json.decodeFromString<List<PartyRecap>>(raw) }
            .onSuccess {
                _recaps.value = it.take(MAX_RECAPS)
                _latest.value = it.firstOrNull()
            }
    }

    private fun persist(recaps: List<PartyRecap>) {
        if (!this::prefs.isInitialized) return
        runCatching {
            prefs.edit()
                .putString(KEY_RECAPS, json.encodeToString(recaps))
                .apply()
        }
    }
}
