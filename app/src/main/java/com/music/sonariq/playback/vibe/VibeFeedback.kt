package com.music.sonariq.playback.vibe

import android.content.Context
import android.content.SharedPreferences
import com.music.sonariq.data.model.Song

/**
 * VibeFeedback: the learning loop behind vibe autoplay.
 *
 * A skip nudges the track's stored vibe away from the session's vibe (and
 * counts the mismatch); a replay or like nudges it toward the session's
 * vibe. A "not this vibe" long-press additionally arms a one-shot re-pick
 * request the playback service can consume to re-run vibe selection at once.
 */
object VibeFeedback {

    private const val PREFS_NAME = "vibe_feedback"
    private const val KEY_REPICK_REQUESTED = "vibe_repick_requested"
    private const val MISMATCH_KEY_PREFIX = "vibe_mismatch_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Call when the user skips an autoplayed track: logs the vibe mismatch.
     * (Wire this up where the skip is detected within the first seconds of
     * playback; the early-exit check itself lives there, not here.)
     */
    fun recordSkip(context: Context, song: Song, sessionVibe: VibeVector) {
        if (song.videoId.isBlank()) return
        VibeStore.recordVibeFeedback(context, song.videoId, sessionVibe, positive = false)
        val key = MISMATCH_KEY_PREFIX + song.videoId
        val store = prefs(context)
        store.edit().putInt(key, store.getInt(key, 0) + 1).apply()
    }

    /**
     * Call when the user replays or likes an autoplayed track: boosts the
     * match.
     */
    fun recordPositive(context: Context, song: Song, sessionVibe: VibeVector) {
        if (song.videoId.isBlank()) return
        VibeStore.recordVibeFeedback(context, song.videoId, sessionVibe, positive = true)
    }

    /**
     * Call from the "not this vibe" long-press: forces negative feedback and
     * arms a re-pick request. The playback service should call
     * [consumeRepickRequest] and re-run vibe selection when it returns true.
     */
    fun recordNotThisVibe(context: Context, song: Song, sessionVibe: VibeVector) {
        recordSkip(context, song, sessionVibe)
        prefs(context).edit().putBoolean(KEY_REPICK_REQUESTED, true).apply()
    }

    /** How many vibe mismatches [videoId] has accumulated. */
    fun mismatchCount(context: Context, videoId: String): Int =
        prefs(context).getInt(MISMATCH_KEY_PREFIX + videoId, 0)

    /**
     * Get-and-clear the re-pick flag armed by [recordNotThisVibe]. Returns
     * true when an immediate vibe re-pick was requested.
     */
    fun consumeRepickRequest(context: Context): Boolean {
        val store = prefs(context)
        val requested = store.getBoolean(KEY_REPICK_REQUESTED, false)
        if (requested) store.edit().remove(KEY_REPICK_REQUESTED).apply()
        return requested
    }
}
