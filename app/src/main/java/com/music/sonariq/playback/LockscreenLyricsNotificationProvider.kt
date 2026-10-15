package com.music.sonariq.playback

import android.content.Context
import androidx.annotation.StringRes
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import com.music.sonariq.data.settings.AppSettings

/**
 * The playback notification, with the currently sung lyric line as its
 * content text while [AppSettings.lockscreenLyrics] is on and a synced line
 * is active. The moment there is no line to show — no lyrics, a gap, the
 * feature toggled off — it falls back to [super.getNotificationContentText],
 * i.e. the artist, exactly as the stock provider would.
 *
 * Only the content text is customized. Actions (play/pause/previous/next),
 * artwork, channel, compact view and everything else come straight from
 * [DefaultMediaNotificationProvider], so the notification behaves exactly as
 * before, and Android Auto is untouched — it reads the session's metadata,
 * not this provider.
 *
 * The notification is rebuilt by media3 whenever the player's metadata
 * changes, and [PlaybackService] publishes a fresh metadata snapshot each
 * time the lyric line changes, so the content text here is already
 * throttled to line changes and never re-posted on every ticker tick.
 */
@UnstableApi
class LockscreenLyricsNotificationProvider(
    context: Context,
    channelId: String,
    @StringRes channelNameResourceId: Int,
    private val getLyricLine: () -> String?,
) : DefaultMediaNotificationProvider(context, null, channelId, channelNameResourceId) {

    override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? {
        if (AppSettings.lockscreenLyrics.value) {
            getLyricLine()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return super.getNotificationContentText(metadata)
    }
}
