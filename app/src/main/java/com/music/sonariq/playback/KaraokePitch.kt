package com.music.sonariq.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.music.sonariq.data.settings.AppSettings
import kotlin.math.pow

/**
 * Karaoke key changer: shifts the song's pitch without changing its speed.
 *
 * [AppSettings.karaokePitch] is in semitones (-6..+6); ExoPlayer wants a pitch
 * factor, so semitones become 2^(n/12). Speed and pitch ride the same
 * [PlaybackParameters] — setting one must never clobber the other, which is
 * why every speed application in the app goes through [applyPlaybackTuning].
 */
fun semitonesToPitchFactor(semitones: Float): Float =
    2f.pow(semitones / 12f)

/** Applies the user's speed + karaoke pitch to a player, together. */
fun Player.applyPlaybackTuning() {
    val speed = AppSettings.playbackSpeed.value
    val pitch = semitonesToPitchFactor(AppSettings.karaokePitch.value)
    setPlaybackParameters(PlaybackParameters(speed, pitch))
}
