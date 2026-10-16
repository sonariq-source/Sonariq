package com.music.sonariq.playback.vibe

import android.content.Context
import com.music.sonariq.R
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * UI bridge for vibe autoplay.
 *
 * The vibe engine lives in this package ([VibeAutoplay], [VibeAnalyzer],
 * [VibeStore], [VibeFeedback]); the player screen lives in the sharedUi
 * module, which cannot see the app module. This object exposes the one thing
 * the player needs — the current session vibe's display string — as a plain
 * [StateFlow] the phone UI collects and hands down to the screen.
 */
object VibeUiState {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Last session vibe handed to [updateBadge]; [notThisVibe] feeds it back. */
    private var lastVibe = VibeVector()

    private val rawBadge = MutableStateFlow("")

    /**
     * Flow of the current session vibe display string, e.g.
     * "Sad • staying in the vibe". Empty string if vibe autoplay is off or
     * no vibe has been reported yet.
     */
    val badgeText: StateFlow<String> =
        combine(AppSettings.vibeAutoplay, rawBadge) { vibeAutoplay, text ->
            if (vibeAutoplay) text else ""
        }.stateIn(scope, SharingStarted.Eagerly, "")

    /**
     * Call when the session vibe changes (the autoplay engine calls this).
     * Builds the localized badge text; clears it when the vibe is empty or
     * vibe autoplay is currently off.
     */
    fun updateBadge(context: Context, vibe: VibeVector) {
        lastVibe = vibe
        val dominant = vibe.dominant()
        rawBadge.value = if (dominant == "unknown" || !AppSettings.vibeAutoplay.value) {
            ""
        } else {
            val name = context.getString(vibeNameRes(dominant))
            "$name • ${context.getString(R.string.vibe_staying)}"
        }
    }

    /**
     * Call for the "not this vibe" long-press: records negative feedback for
     * [song] against the current session vibe and arms a re-pick request the
     * playback service consumes to re-run vibe selection at once — see
     * [VibeFeedback.recordNotThisVibe].
     */
    fun notThisVibe(context: Context, song: Song) {
        VibeFeedback.recordNotThisVibe(context, song, lastVibe)
    }

    private fun vibeNameRes(dominant: String): Int = when (dominant) {
        "sad" -> R.string.vibe_sad
        "happy" -> R.string.vibe_happy
        "chill" -> R.string.vibe_chill
        "energetic" -> R.string.vibe_energetic
        "angry" -> R.string.vibe_angry
        "romantic" -> R.string.vibe_romantic
        "nostalgic" -> R.string.vibe_nostalgic
        "party" -> R.string.vibe_party
        "focus" -> R.string.vibe_focus
        "workout" -> R.string.vibe_workout
        else -> R.string.vibe_unknown
    }
}
