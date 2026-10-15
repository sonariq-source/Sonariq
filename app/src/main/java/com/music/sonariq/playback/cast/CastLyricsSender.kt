package com.music.sonariq.playback.cast

import android.util.Log
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.framework.CastSession

/**
 * The phone's half of the TV lyrics channel.
 *
 * The receiver renders karaoke from its own media clock, so the phone only
 * speaks when the *lyrics* change: a payload on track change, a clear when a
 * track has none. Nothing is sent unless a custom Sonariq receiver is in use —
 * with the stock Default Media Receiver there is nobody listening, and the
 * messages would just fail.
 *
 * All failures are swallowed: lyrics on the TV are a bonus, never worth
 * disturbing playback over.
 */
class CastLyricsSender {
    private var session: CastSession? = null
    private var listening = false

    private val callbacks = Cast.MessageReceivedCallback { _, _, _ -> }

    /** Starts listening on [CastLyrics.NAMESPACE]; safe to call repeatedly. */
    fun attach(newSession: CastSession) {
        if (session === newSession && listening) return
        detach()
        session = newSession
        try {
            newSession.setMessageReceivedCallbacks(CastLyrics.NAMESPACE, callbacks)
            listening = true
        } catch (e: Exception) {
            Log.w(TAG, "could not open lyrics channel", e)
        }
    }

    fun detach() {
        val session = session
        session ?: return
        if (listening) {
            try {
                session.removeMessageReceivedCallbacks(CastLyrics.NAMESPACE)
            } catch (e: Exception) {
                Log.w(TAG, "could not close lyrics channel", e)
            }
        }
        listening = false
        this.session = null
    }

    /** Sends a lyrics (or clear) JSON message; silently drops failures. */
    fun send(messageJson: String) {
        val session = session ?: return
        if (!listening) return
        try {
            session.sendMessage(CastLyrics.NAMESPACE, messageJson)
        } catch (e: Exception) {
            Log.w(TAG, "could not send lyrics", e)
        }
    }

    companion object {
        private const val TAG = "SonariqCast"
    }
}
