package com.music.sonariq.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.music.sonariq.data.settings.AppSettings

/**
 * Applies the user's playback speed to a player.
 *
 * Speed and pitch ride the same [PlaybackParameters] — pitch stays at 1x now
 * that the karaoke key changer is gone, so applying speed must never clobber
 * the parameters object; every speed application in the app goes through here.
 */
fun Player.applyPlaybackTuning() {
    val speed = AppSettings.playbackSpeed.value
    setPlaybackParameters(PlaybackParameters(speed, 1f))
}
