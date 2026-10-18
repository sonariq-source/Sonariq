package com.music.sonariq.data.discovery

import android.content.Context
import android.util.Log
import com.music.sonariq.data.YtMusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The artists the release radar watches, kept on this device.
 *
 * ## Why a local list instead of only the channel subscription
 *
 * Subscribing on the catalogue ([YtMusicRepository.setSubscribed]) is the
 * account's own follow, but it needs a signed-in session and it is read back
 * off each artist page one at a time — there is no "my followed artists" feed
 * to page through. The radar needs the list up front, offline, in one read,
 * so it keeps its own: one JSON file, one flow, no account required.
 *
 * Following here also nudges the catalogue subscription when the artist page
 * supplied a channel id, best-effort and signed-in only. That keeps the two
 * from silently disagreeing for people who do sign in, without making the
 * local follow depend on it.
 */
data class FollowedArtist(
    /** Display name, as the catalogue spelled it. */
    val name: String,
    /** Artist page browse id — what the radar fetches. Blank when unknown. */
    val browseId: String,
    /** `UC…` channel id for the catalogue subscription, when the page named one. */
    val channelId: String? = null,
    val thumbnailUrl: String? = null,
    val followedAtMs: Long = System.currentTimeMillis(),
)

object FollowedArtists {

    private const val TAG = "SonariqFollowed"
    private const val FILE_NAME = "followed_artists.json"

    private lateinit var file: File

    private val _follows = MutableStateFlow<List<FollowedArtist>>(emptyList())
    val follows: StateFlow<List<FollowedArtist>> = _follows.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val ready: Boolean get() = this::file.isInitialized

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
        scope.launch { _follows.value = read() }
    }

    fun isFollowed(browseId: String): Boolean =
        _follows.value.any { it.browseId == browseId }

    /**
     * Follows [artist], and nudges the catalogue subscription where possible.
     *
     * The local row is written first and unconditionally: the follow is real
     * the moment it is tapped, and a signed-out or failed catalogue call must
     * not take that back.
     */
    fun follow(artist: FollowedArtist) {
        if (!ready) return
        val updated = (_follows.value.filterNot { it.browseId == artist.browseId } + artist)
            .sortedByDescending { it.followedAtMs }
        _follows.value = updated
        scope.launch { write(updated) }
        artist.channelId?.let { channelId ->
            scope.launch {
                try {
                    YtMusicRepository.setSubscribed(channelId, true)
                } catch (e: Exception) {
                    Log.w(TAG, "Catalogue follow failed for ${artist.name}", e)
                }
            }
        }
    }

    fun unfollow(browseId: String) {
        if (!ready) return
        val removed = _follows.value.firstOrNull { it.browseId == browseId }
        _follows.value = _follows.value.filterNot { it.browseId == browseId }
        scope.launch { write(_follows.value) }
        removed?.channelId?.let { channelId ->
            scope.launch {
                try {
                    YtMusicRepository.setSubscribed(channelId, false)
                } catch (e: Exception) {
                    Log.w(TAG, "Catalogue unfollow failed for $browseId", e)
                }
            }
        }
    }

    /**
     * Two follow lists added together without duplicating an artist.
     *
     * Pure, for the radar's merge of the follow list with the top-listened
     * artists — see [ReleaseRadar] — and for the tests.
     */
    fun mergedWith(
        base: List<FollowedArtist>,
        extra: List<FollowedArtist>,
    ): List<FollowedArtist> {
        val seen = base.mapTo(HashSet()) { it.browseId }
        return base + extra.filter { it.browseId.isNotBlank() && seen.add(it.browseId) }
    }

    private fun read(): List<FollowedArtist> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.optString("name", "").takeIf { it.isNotBlank() } ?: continue
                    val browseId = obj.optString("browseId", "").takeIf { it.isNotBlank() } ?: continue
                    add(
                        FollowedArtist(
                            name = name,
                            browseId = browseId,
                            channelId = obj.optString("channelId", null)?.takeIf { it.isNotBlank() },
                            thumbnailUrl = obj.optString("thumbnailUrl", null)?.takeIf { it.isNotBlank() },
                            followedAtMs = obj.optLong("followedAtMs", 0L),
                        ),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Discarding unreadable follow list", it) }
            .getOrDefault(emptyList())
    }

    private fun write(follows: List<FollowedArtist>) {
        runCatching {
            val array = JSONArray()
            follows.forEach { artist ->
                array.put(
                    JSONObject()
                        .put("name", artist.name)
                        .put("browseId", artist.browseId)
                        .put("channelId", artist.channelId)
                        .put("thumbnailUrl", artist.thumbnailUrl)
                        .put("followedAtMs", artist.followedAtMs),
                )
            }
            file.writeText(array.toString())
        }.onFailure { Log.w(TAG, "Could not write follow list", it) }
    }
}
