package com.music.sonariq.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.music.sonariq.MainActivity
import com.music.sonariq.R
import com.music.sonariq.playback.PlayerDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The lyric-peek widget (Widgets 2.0): the full-bleed cover, the track, and the
 * lyric line the song is on right now, with play/pause and next.
 *
 * Its own provider rather than a fourth size of [MediaWidget]: it draws the same
 * kind of blurred backdrop through [MediaWidgetArt], but the band carries three
 * text lines and a two-button transport. It reads the same
 * [MediaWidgetSnapshot] — the service publishes the current line there on line
 * change only — and shares the same [MediaWidgetActions] command path as every
 * other widget, so its buttons are the real transport, not a copy of it.
 */
class MediaWidgetLyric : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        renderAsync(context, ids)
    }

    /** Fires on every resize, which is a re-render: the bitmap is size-specific. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: Bundle?,
    ) {
        renderAsync(context, intArrayOf(id))
    }

    /** A widget that has been dragged off the home screen has no last cover. */
    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) lastArt.remove(id)
    }

    private fun renderAsync(context: Context, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                render(app, ids)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    companion object {

        /** Redraws every placed lyric widget. Called from [MediaWidget.refresh]. */
        internal fun refresh(context: Context) {
            val app = context.applicationContext
            scope.launch {
                val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@launch
                val ids = runCatching {
                    manager.getAppWidgetIds(ComponentName(app, MediaWidgetLyric::class.java))
                }.getOrNull()
                if (ids == null || ids.isEmpty()) return@launch
                render(app, ids)
            }
        }

        /**
         * Draws each instance, pushing the controls first with the last cover
         * when the new artwork isn't ready — the same reasoning as
         * [MediaWidget]'s render: a tap is answered in a frame, and the cover
         * catches up below.
         */
        private suspend fun render(context: Context, ids: IntArray) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val snapshot = MediaWidgetSnapshot.load(context)
            // Keyed on the artwork rather than the track: two tracks off one
            // album are one picture, so moving through an album redraws nothing.
            val key = snapshot.artworkUrl ?: KEY_NO_ARTWORK
            val resources = context.resources
            val density = resources.displayMetrics.density
            val heightPx = (HEIGHT_DP * density).roundToInt().coerceAtLeast(1)
            val bandPx = resources.getDimensionPixelSize(R.dimen.widget_band_lyric)
            val cornerRadiusPx = resources.getDimension(R.dimen.widget_corner_radius)
            for (id in ids) {
                val widthPx = widthPx(context, manager, id)
                val cached = MediaWidgetArt.peek(key, widthPx, heightPx, bandPx)
                if (cached == null) {
                    manager.push(id, views(context, snapshot, lastArt[id]))
                }
                val art = cached ?: withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                    MediaWidgetArt.render(
                        context = context,
                        artworkUrl = snapshot.artworkUrl,
                        widthPx = widthPx,
                        heightPx = heightPx,
                        bandPx = bandPx,
                        key = key,
                        cornerRadiusPx = cornerRadiusPx,
                    )
                } ?: continue
                lastArt[id] = art
                manager.push(id, views(context, snapshot, art))
            }
        }

        /**
         * The widget's width in pixels: the width for the orientation in force,
         * as [MediaWidget]'s measuring reads it. Height is fixed — every size
         * this widget is meant to take is two rows tall, same as the wide one.
         */
        private fun widthPx(context: Context, manager: AppWidgetManager, id: Int): Int {
            val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
            val landscape =
                context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val widthDp = options?.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
            )?.takeIf { it > 0 } ?: FALLBACK_WIDTH_DP
            return (widthDp * context.resources.displayMetrics.density).roundToInt().coerceIn(1, MAX_WIDTH_PX)
        }

        private fun views(
            context: Context,
            snapshot: MediaWidgetSnapshot,
            art: Bitmap?,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_lyric)
            art?.let { views.setImageViewBitmap(R.id.widget_art, it) }

            views.setTextViewText(
                R.id.widget_title,
                if (snapshot.hasTrack) {
                    snapshot.title
                } else {
                    context.getString(R.string.widget_nothing_played)
                },
            )
            views.setTextViewText(R.id.widget_artist, snapshot.artist)
            views.setViewVisibility(
                R.id.widget_artist,
                if (snapshot.hasTrack && snapshot.artist.isNotBlank()) View.VISIBLE else View.GONE,
            )

            // The lyric peek. The service republishes the snapshot on line
            // change only, so what is here is always the line being sung.
            // Hidden rather than blanked when the song has no synced lyrics, so
            // the band falls back to title and artist.
            val lyric = snapshot.lyricLine.takeIf { snapshot.hasTrack }?.takeIf { it.isNotBlank() }
            views.setTextViewText(R.id.widget_lyric_line, lyric.orEmpty())
            views.setViewVisibility(
                R.id.widget_lyric_line,
                if (lyric != null) View.VISIBLE else View.GONE,
            )

            views.setImageViewResource(
                R.id.widget_toggle,
                if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )
            views.setContentDescription(
                R.id.widget_toggle,
                context.getString(
                    if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play,
                ),
            )

            // On the root, not on widget_art: the root is the last view offered
            // a touch, so this catches every pixel that isn't a button — the
            // same reasoning as MediaWidget's views.
            val open = openPlayer(context)
            views.setOnClickPendingIntent(R.id.widget_root, open)

            if (snapshot.hasTrack) {
                views.setImageAlpha(R.id.widget_toggle, ALPHA_ENABLED)
                views.setImageAlpha(
                    R.id.widget_next,
                    if (snapshot.hasNext) ALPHA_ENABLED else ALPHA_DISABLED,
                )
                views.setOnClickPendingIntent(
                    R.id.widget_toggle,
                    MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_TOGGLE),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_next,
                    MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_NEXT),
                )
            } else {
                // Nothing has ever played, so there is nothing to control. The
                // buttons open the app instead — the same fallback MediaWidget
                // and the pill use.
                for (button in TRANSPORT) {
                    views.setImageAlpha(button, ALPHA_DISABLED)
                    views.setOnClickPendingIntent(button, open)
                }
            }
            return views
        }

        private fun RemoteViews.setImageAlpha(viewId: Int, alpha: Int) =
            setInt(viewId, "setImageAlpha", alpha)

        /** Same intent as the other widgets', and why it has no action is explained in MediaWidget. */
        private fun openPlayer(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(PlayerDeepLink.EXTRA_OPEN_PLAYER, true)
            return PendingIntent.getActivity(
                context,
                REQUEST_OPEN_PLAYER,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * A host that has gone away, or one that rejects the bitmap, is not
         * worth taking the process down for — and this runs from a broadcast,
         * where an escaping exception is a crash.
         */
        private fun AppWidgetManager.push(id: Int, views: RemoteViews) {
            runCatching { updateAppWidget(id, views) }
        }

        private val TRANSPORT = intArrayOf(R.id.widget_toggle, R.id.widget_next)

        /**
         * Renders run on a process-wide scope rather than one tied to a receiver:
         * a provider instance lives only for the duration of one `onReceive`, and
         * [refresh] is not called from a receiver at all.
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** The cover each widget is currently showing. See MediaWidget's lastArt. */
        private val lastArt = ConcurrentHashMap<Int, Bitmap>()

        /** How long any one widget's artwork may take before it is given up on. */
        private const val RENDER_TIMEOUT_MS = 8_000L

        /** Four cells by the launcher's 70n − 30 arithmetic, for a host that hasn't said. */
        private const val FALLBACK_WIDTH_DP = 250

        /** Two rows tall, like the wide widget. */
        private const val HEIGHT_DP = 110

        /** Bounds the bitmap parcelled to the host on a tablet-wide instance. */
        private const val MAX_WIDTH_PX = 1_600

        private const val ALPHA_ENABLED = 255
        private const val ALPHA_DISABLED = 90

        /** What the placeholder composite is remembered under. */
        private const val KEY_NO_ARTWORK = "no-artwork"

        private const val REQUEST_OPEN_PLAYER = 0
    }
}
