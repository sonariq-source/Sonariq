package com.music.sonariq.data.listentogether

import android.content.Context
import android.content.SharedPreferences
import com.music.sonariq.data.DebugLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One friend and what they are listening to, as the party server reports it.
 *
 * Every field is real server data — a name and face the server handed out for
 * a party member, and a track from a party-state frame:
 *
 * - [trackIsLive] is true when the track comes from the live party socket:
 *   this device is in that party, so the party-state endpoint (the socket's
 *   frames, and `GET /api/parties/{code}` underneath them) is visible to it
 *   and the track is what the party is playing *right now*.
 * - [trackIsLive] is false when the track is the last one this device saw in
 *   that party before leaving it. The public `/preview` endpoint — the
 *   closest real API the server offers for a party this device is not in —
 *   carries members, the host and the headcount, but deliberately no
 *   playback, so there is no live track to show there. The UI says "last
 *   played" for these rather than presenting them as current.
 * - [trackTitle] is null when this device has never been in the party, so it
 *   holds no track for it at all; the entry then reads as "in X's party"
 *   with no track line, rather than an invented one.
 *
 * [joinable] is false for members of the party this device is already in —
 * there is no session to join there — and the UI shows a "listening together"
 * chip instead of a Join button.
 */
data class FriendActivityEntry(
    val partyCode: String,
    val partyHostName: String,
    val friendName: String,
    val friendAvatarUrl: String?,
    val memberCount: Int,
    val isFull: Boolean,
    val trackTitle: String?,
    val trackArtist: String?,
    val trackThumbnailUrl: String?,
    val trackIsLive: Boolean,
    val joinable: Boolean,
)

/**
 * The friend-activity feed, behind an interface so the UI never cares where
 * the entries came from.
 *
 * The server has no presence endpoint — no "who is playing what" anywhere —
 * so the real implementation below is wired to the closest real APIs that
 * exist: the live party-state frames for the party this device is in, and
 * the public `/preview` endpoint for parties it has recently been in.
 */
interface FriendActivityRepository {
    val feed: StateFlow<List<FriendActivityEntry>>

    /** Re-reads every known party's preview; drops codes the server no longer knows. */
    suspend fun refresh()

    /**
     * Joins the friend's party through the real join flow: a plain join when
     * idle, the transactional switch when already in another party, so a
     * refused target leaves the current one untouched.
     */
    suspend fun join(partyCode: String): Result<String>
}

/**
 * The real [FriendActivityRepository], built on party state only.
 *
 * "Friends" here are party members the server has named — people this device
 * has actually listened with. A party this device created, joined, or opened
 * an invite for is remembered locally ([KnownParty]); [refresh] resolves each
 * one against the server's public `/preview` endpoint, which is exactly the
 * API the app already uses to look at a party before joining it.
 *
 * Nothing here is fabricated: a party the server answers 404 for is dropped,
 * a preview that carries no members yields no entries, and a track is only
 * ever shown when it arrived in a party-state frame this device was entitled
 * to see.
 */
object PartyActivityRepository : FriendActivityRepository {

    private const val PREFS = "sonariq_friend_activity"
    private const val KEY_KNOWN = "known_parties_json"
    private const val MAX_KNOWN_PARTIES = 20
    private const val MAX_ENTRIES = 25

    @Serializable
    private data class KnownParty(
        val code: String,
        val hostName: String = "",
        val lastSeenMs: Long = 0L,
        val lastTrackTitle: String? = null,
        val lastTrackArtist: String? = null,
        val lastTrackThumbnailUrl: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var prefs: SharedPreferences
    private val known = LinkedHashMap<String, KnownParty>()
    private val previews = HashMap<String, PartyPreview>()
    private var lastPersistKey: String? = null

    private val _feed = MutableStateFlow<List<FriendActivityEntry>>(emptyList())
    override val feed: StateFlow<List<FriendActivityEntry>> = _feed.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
        scope.launch {
            ListenTogether.state.collect { onPartyState(it) }
        }
    }

    private fun load() {
        val raw = prefs.getString(KEY_KNOWN, null) ?: return
        runCatching {
            json.decodeFromString<List<KnownParty>>(raw)
        }.onSuccess { list ->
            known.clear()
            list.take(MAX_KNOWN_PARTIES).forEach { known[it.code] = it }
        }.onFailure {
            Log.w("FriendActivity", "could not read known parties: ${it.message}")
        }
    }

    private fun persist() {
        if (!this::prefs.isInitialized) return
        runCatching {
            prefs.edit()
                .putString(KEY_KNOWN, json.encodeToString(known.values.toList()))
                .apply()
        }.onFailure {
            Log.w("FriendActivity", "could not save known parties: ${it.message}")
        }
    }

    /**
     * Remembers every party this device is in — its code, its host, and the
     * last track the party-state frames carried — so [refresh] has real codes
     * to resolve and the feed has a last-played track for parties it has left.
     */
    private fun onPartyState(state: ListenTogether.State) {
        val code = state.code
        if (code != null) {
            val hostName = state.members.firstOrNull { it.isHost }?.displayName.orEmpty()
            val track = state.playback.track
            val key = listOf(code, hostName, track?.videoId.orEmpty()).joinToString("|")
            if (key != lastPersistKey) {
                lastPersistKey = key
                known[code] = KnownParty(
                    code = code,
                    hostName = hostName,
                    lastSeenMs = System.currentTimeMillis(),
                    lastTrackTitle = track?.title?.ifBlank { null },
                    lastTrackArtist = track?.artist?.ifBlank { null },
                    lastTrackThumbnailUrl = track?.thumbnailUrl,
                )
                while (known.size > MAX_KNOWN_PARTIES) {
                    known.remove(known.keys.first())
                }
                persist()
            }
        }
        rebuildFeed()
    }

    override suspend fun refresh() {
        val current = ListenTogether.state.value.code
        for (code in known.keys.toList()) {
            if (code == current) continue
            ListenTogether.previewParty(code).fold(
                onSuccess = { preview ->
                    previews[code] = preview
                    known[code]?.let { kp ->
                        known[code] = kp.copy(
                            hostName = preview.hostName.ifBlank { kp.hostName },
                            lastSeenMs = System.currentTimeMillis(),
                        )
                    }
                },
                onFailure = { failure ->
                    if ((failure as? ListenTogether.PartyException)?.code == "no_such_party") {
                        Log.i("FriendActivity", "party $code is gone; forgetting it")
                        known.remove(code)
                        previews.remove(code)
                    } else {
                        Log.w("FriendActivity", "could not refresh party $code: ${failure.message}")
                    }
                },
            )
        }
        persist()
        rebuildFeed()
    }

    private fun rebuildFeed() {
        val st = ListenTogether.state.value
        val selfId = st.you?.memberId
        val selfName = ListenTogether.nickname().trim()
        val out = mutableListOf<FriendActivityEntry>()

        // The live party: every other member, with the track the party-state
        // frames say is playing right now.
        if (st.inParty) {
            val track = st.playback.track
            val hostName = st.members.firstOrNull { it.isHost }?.displayName.orEmpty()
            for (member in st.members) {
                if (member.memberId == selfId) continue
                out += FriendActivityEntry(
                    partyCode = st.code.orEmpty(),
                    partyHostName = hostName,
                    friendName = member.displayName.ifBlank { member.userId.take(8) },
                    friendAvatarUrl = member.avatarUrl,
                    memberCount = st.members.size,
                    isFull = st.isFull,
                    trackTitle = track?.title?.ifBlank { null },
                    trackArtist = track?.artist?.ifBlank { null },
                    trackThumbnailUrl = track?.thumbnailUrl,
                    trackIsLive = true,
                    joinable = false,
                )
            }
        }

        // Recently known parties: members from the real /preview endpoint.
        // Track info is last-known at best — the endpoint carries none — and
        // the entry says so.
        val others = known.values
            .filter { it.code != st.code }
            .sortedByDescending { it.lastSeenMs }
        for (kp in others) {
            val preview = previews[kp.code] ?: continue
            for (member in preview.members) {
                if (selfName.isNotBlank() && member.displayName == selfName) continue
                out += FriendActivityEntry(
                    partyCode = kp.code,
                    partyHostName = preview.hostName.ifBlank { kp.hostName },
                    friendName = member.displayName.ifBlank { "?" },
                    friendAvatarUrl = member.avatarUrl,
                    memberCount = preview.memberCount,
                    isFull = preview.isFull,
                    trackTitle = kp.lastTrackTitle,
                    trackArtist = kp.lastTrackArtist,
                    trackThumbnailUrl = kp.lastTrackThumbnailUrl,
                    trackIsLive = false,
                    joinable = true,
                )
            }
        }
        _feed.value = out.take(MAX_ENTRIES)
    }

    override suspend fun join(partyCode: String): Result<String> {
        val st = ListenTogether.state.value
        val cleaned = partyCode.filter { it.isLetterOrDigit() }.uppercase()
        return when {
            st.inParty && !st.code.equals(cleaned, ignoreCase = true) -> {
                val target = ListenTogether.resolveSwitchTarget(
                    inviteServer = null,
                    customServer = ListenTogether.customServerUrl.value,
                )
                when (val r = ListenTogether.switchPartyWithRecovery(target, cleaned)) {
                    is ListenTogether.SwitchPartyResult.Success ->
                        Result.success(r.partyCode)
                    is ListenTogether.SwitchPartyResult.TargetFailedRecovered ->
                        Result.failure(ListenTogether.PartyException("join_failed", r.targetError))
                    is ListenTogether.SwitchPartyResult.TargetFailedNoParty ->
                        Result.failure(ListenTogether.PartyException("join_failed", r.targetError))
                }
            }
            st.inParty -> Result.success(st.code.orEmpty())
            else -> ListenTogether.joinParty(cleaned)
        }
    }
}
