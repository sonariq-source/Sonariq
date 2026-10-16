package com.music.sonariq.data.listentogether

import android.os.SystemClock
import androidx.media3.session.MediaController
import com.music.sonariq.data.sources.TrackMatcher
import com.music.sonariq.playback.toSong
import kotlinx.coroutines.delay

/**
 * Cross-device handoff, carried by the Listen Together party server.
 *
 * There is no direct phone-to-TV channel in the app — no socket pairing, no
 * discovery — so a handoff is a party: the sending device creates (or reuses)
 * one, publishes what it is playing with [startHandoff], and the receiving
 * device joins with the code. Its PartySync then loads the handed-off track
 * at the handed-off position on its own; nothing here touches a player.
 */

/** What this device is playing right now, as a handoff knows it. */
data class HandoffSource(
    val track: PartyTrack,
    val positionMs: Long,
    val isPlaying: Boolean,
)

/**
 * Reads the controller's current item as a handoff source.
 *
 * Null when there is nothing to hand off: no item, or a file that lives only
 * on this device — a party cannot play a content:// URI, and handing one out
 * would have every other member fail to resolve it. See the same refusal in
 * [com.music.sonariq.playback.PartySync.publish].
 */
fun MediaController.handoffSource(): HandoffSource? {
    val song = currentMediaItem?.toSong() ?: return null
    if (song.videoId.startsWith("content://") || song.videoId.startsWith("file://")) return null
    val durationMs = duration.takeIf { it > 0L }
        ?: TrackMatcher.secondsOf(song.durationText)?.let { it * 1000L }
    return HandoffSource(
        track = PartyTrack(
            videoId = song.videoId,
            title = song.title,
            artist = song.artist,
            thumbnailUrl = song.thumbnailUrl,
            durationMs = durationMs,
        ),
        positionMs = currentPosition.coerceAtLeast(0L),
        isPlaying = playWhenReady,
    )
}

/**
 * Starts a handoff: joins a party — creating one when this device is not
 * already in one — publishes the handed-off track at its position, and
 * returns the code for the other device to join with.
 *
 * The track is published with an explicit [ListenTogether.setTrack] rather
 * than relying on the host seeding in PartySync, so the position is the one
 * read at the moment the handoff was asked for. setTrack is a fire-and-forget
 * socket frame that is dropped without a word before the socket is up, so
 * this waits for the connection first.
 */
suspend fun startHandoff(source: HandoffSource): Result<String> {
    if (!ListenTogether.state.value.inParty) {
        ListenTogether.createParty().getOrElse { return Result.failure(it) }
    }
    val code = ListenTogether.state.value.code
        ?: return Result.failure(IllegalStateException("Party has no code"))
    val deadlineMs = SystemClock.elapsedRealtime() + HANDOFF_LIVE_TIMEOUT_MS
    while (ListenTogether.state.value.connection != ListenTogether.Connection.LIVE &&
        SystemClock.elapsedRealtime() < deadlineMs
    ) {
        delay(200)
    }
    if (ListenTogether.state.value.connection != ListenTogether.Connection.LIVE) {
        return Result.failure(IllegalStateException("The party connection is not ready yet"))
    }
    ListenTogether.setTrack(source.track, source.positionMs, source.isPlaying)
    return Result.success(code)
}

/**
 * Takes playback back: leaves the party, then resumes locally at the party's
 * current position — the one captured just before leaving.
 *
 * Leaving restores this device's own queue on the player, which takes a
 * moment; the seek lands after a short wait so it is not overwritten by the
 * restore.
 */
suspend fun takeBack(controller: MediaController, positionMs: Long): Result<Unit> {
    ListenTogether.leaveParty()
    delay(TAKEBACK_RESTORE_WAIT_MS)
    controller.seekTo(positionMs.coerceAtLeast(0L))
    controller.play()
    return Result.success(Unit)
}

private const val HANDOFF_LIVE_TIMEOUT_MS = 15_000L
private const val TAKEBACK_RESTORE_WAIT_MS = 1_500L
