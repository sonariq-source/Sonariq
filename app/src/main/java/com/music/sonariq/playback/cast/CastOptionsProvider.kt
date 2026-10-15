package com.music.sonariq.playback.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/**
 * How the Cast framework is told this app wants to cast. Named in the manifest
 * and read by the framework itself, which is why it is a class of its own.
 *
 * The stock receiver plays whatever URL it is handed, which is all a player
 * needs. The framework's own media notification and its own media session are
 * both switched off: the service already owns a session and a notification,
 * and a second pair — each showing the same track, each with its own idea of
 * whether it is playing — is what casting looks like when it is bolted on
 * rather than built in. The existing ones follow the receiver instead; see
 * [CastPlayback].
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(null)
            .setMediaSessionEnabled(false)
            .build()
        // The framework reads this before AppSettings is up, so the raw prefs
        // are read directly. Blank means the stock receiver (audio-only, as
        // before); a registered custom App ID gets the Sonariq TV receiver
        // with synced lyrics. Changing it needs an app restart — the framework
        // only asks once.
        val appId = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_RECEIVER_APP_ID, "")
                .orEmpty()
        }.getOrDefault("")
        return CastOptions.Builder()
            .setReceiverApplicationId(CastLyrics.resolveReceiverAppId(appId))
            .setCastMediaOptions(media)
            // Leaving the receiver running after the phone has let go of it
            // keeps a TV on a "casting" screen with nothing driving it.
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        /** Must match AppSettings' prefs file and key. */
        const val PREFS_NAME = "sonariq_settings"
        const val KEY_RECEIVER_APP_ID = "cast_receiver_app_id"
    }
}
