package com.music.sonariq.ui.stats

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.music.sonariq.R
import com.music.sonariq.data.stats.ListeningStats
import com.music.sonariq.data.stats.ReplayPeriod
import com.music.sonariq.ui.replay.formatMinutes
import com.music.sonariq.ui.replay.grouped
import com.music.sonariq.ui.replay.localizedLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The Replay as a shareable 1080×1350 (4:5) picture, rendered from real
 * listening stats — the top 5 songs, total minutes and the top artist for
 * [period].
 *
 * ## Why Compose, offscreen
 *
 * The card is a fixed 4:5 frame whose layout never reflows, and drawing it as a
 * composable keeps the type, spacing and Prism styling in the same language as
 * the screen it is shared from. The composition is rooted in its own
 * [Recomposer] rather than a window: [ComposeView.createComposition] throws
 * when the view is not attached unless a parent composition context is set, so
 * one is supplied explicitly via [ComposeView.setParentCompositionContext] and
 * the measure/layout/draw pass runs on the main thread.
 *
 * ## Why the card carries no artwork
 *
 * Sleeves load asynchronously through Coil, and a shared picture must not
 * depend on a network fetch finishing before the share sheet opens. Rank
 * numerals and the Prism glows carry the visual weight instead, so the bitmap
 * is complete the moment it is drawn.
 */
suspend fun renderStatsCard(context: Context, period: ReplayPeriod): Bitmap {
    val summary = ListeningStats.summary(period)
    val data = StatsCardData(
        title = context.getString(R.string.stats_card_title),
        periodLabel = summary.localizedLabel(context),
        minutes = grouped(summary.minutes),
        minutesCaption = context.getString(R.string.minutes_listened),
        topSongsCaption = context.getString(R.string.top_songs),
        songs = summary.songs.take(TOP_SONGS).mapIndexed { index, ranked ->
            SongRow(
                rank = index + 1,
                title = ranked.song.title,
                artist = ranked.song.artist,
                minutes = context.getString(
                    R.string.minutes_grouped_short,
                    formatMinutes(ranked.ms),
                ),
            )
        },
        topArtistCaption = context.getString(R.string.top_artist),
        topArtist = summary.artists.firstOrNull()?.title,
        footer = context.getString(R.string.counted_on_device_with_sonariq),
        empty = summary.isEmpty,
        emptyTitle = context.getString(R.string.stats_card_empty_title),
        emptyHint = context.getString(R.string.stats_card_empty_hint),
    )
    // Measure, layout and draw must happen on the main thread; the summary
    // above already did its disk work on Dispatchers.IO internally.
    return withContext(Dispatchers.Main.immediate) { renderOffscreen(context, data) }
}

/**
 * Sends [bitmap] to the app of the user's choice.
 *
 * Written into the cache's `shared/` folder — the only cache path the
 * FileProvider exposes (see `@xml/file_paths`) — and handed out as a content
 * URI with a read grant that travels with the intent and expires with it.
 */
fun shareStatsCard(context: Context, bitmap: Bitmap) {
    val folder = File(context.cacheDir, SHARE_FOLDER).apply { mkdirs() }
    val file = File(folder, FILE_NAME)
    runCatching {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }.getOrNull() ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(MIME_TYPE)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(send, context.getString(R.string.share_your_replay))
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

// ── Offscreen composition ─────────────────────────────────────────────────────

/**
 * Composes [StatsCard] into a view that is never attached to a window and
 * draws it into a bitmap.
 *
 * Without a window, [ComposeView.setContent] only stores the content and waits
 * for attachment — so the composition is created explicitly against a
 * [Recomposer] owned by this call. A few frames are awaited so the initial
 * composition is applied before measuring; the cap keeps a wedged frame clock
 * from hanging the share button forever.
 */
private suspend fun renderOffscreen(context: Context, data: StatsCardData): Bitmap {
    val recomposer = Recomposer(coroutineContext)
    val view = ComposeView(context)
    val recompose = launch { recomposer.runRecomposeAndApplyChanges() }
    try {
        view.setParentCompositionContext(recomposer)
        view.setContent { StatsCard(data) }
        view.createComposition()
        var frames = 0
        while (view.childCount == 0 && frames < MAX_FRAMES) {
            withFrameNanos { }
            frames++
        }
        val widthSpec = View.MeasureSpec.makeMeasureSpec(CARD_W, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(CARD_H, View.MeasureSpec.EXACTLY)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, CARD_W, CARD_H)
        val bitmap = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    } finally {
        runCatching { view.disposeComposition() }
        recompose.cancel()
        recomposer.close()
    }
}

// ── The card ────────────────────────────────────────────────────────────────

/** Everything the card draws, resolved to plain strings up front. */
private data class StatsCardData(
    val title: String,
    val periodLabel: String,
    val minutes: String,
    val minutesCaption: String,
    val topSongsCaption: String,
    val songs: List<SongRow>,
    val topArtistCaption: String,
    val topArtist: String?,
    val footer: String,
    val empty: Boolean,
    val emptyTitle: String,
    val emptyHint: String,
)

private data class SongRow(
    val rank: Int,
    val title: String,
    val artist: String,
    val minutes: String,
)

/** Prism: matte midnight/plum, coral primary, orchid secondary, cyan highlights. */
private val PlumBase = Color(0xFF120D18)
private val Coral = Color(0xFFFF6B5E)
private val Orchid = Color(0xFFDA70D6)
private val Cyan = Color(0xFF5EEAD4)
private val Ink = Color.White
private val InkDim = Color(0xFFB9AECB)
private val InkFaint = Color(0xFF8A7F9E)

@Composable
private fun StatsCard(data: StatsCardData) {
    Box(
        Modifier
            .fillMaxSize()
            .background(PlumBase),
    ) {
        // The Prism wash: coral light from the top, orchid pooling at the
        // foot, a breath of cyan across the middle. Linear gradients need no
        // measurement, so the backdrop is identical at any density.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Coral.copy(alpha = 0.20f),
                            Color.Transparent,
                            Orchid.copy(alpha = 0.16f),
                        ),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Cyan.copy(alpha = 0.05f),
                            Color.Transparent,
                            Coral.copy(alpha = 0.07f),
                        ),
                    ),
                ),
        )
        // The brand bar: coral into orchid into cyan, the Prism signature.
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(Brush.horizontalGradient(listOf(Coral, Orchid, Cyan))),
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 64.dp, vertical = 56.dp),
        ) {
            Text(
                text = data.title.uppercase(),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp,
                color = Cyan.copy(alpha = 0.9f),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = data.periodLabel,
                fontSize = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (data.empty) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = data.emptyTitle,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = data.emptyHint,
                    fontSize = 18.sp,
                    color = InkDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.weight(1f))
            } else {
                Spacer(Modifier.height(44.dp))
                Text(
                    text = data.minutes,
                    fontSize = 84.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Coral,
                    maxLines = 1,
                )
                Text(
                    text = data.minutesCaption.uppercase(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 4.sp,
                    color = InkDim,
                )

                Spacer(Modifier.height(44.dp))
                Caption(data.topSongsCaption)
                Spacer(Modifier.height(18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    data.songs.forEach { song ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = song.rank.toString(),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (song.rank == 1) Coral else InkFaint,
                                modifier = Modifier.width(40.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = song.artist,
                                    fontSize = 16.sp,
                                    color = InkDim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = song.minutes,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Cyan.copy(alpha = 0.85f),
                                maxLines = 1,
                            )
                        }
                    }
                }

                data.topArtist?.let { artist ->
                    Spacer(Modifier.height(44.dp))
                    Caption(data.topArtistCaption)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = artist,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .width(72.dp)
                            .height(5.dp)
                            .background(
                                Brush.horizontalGradient(listOf(Orchid, Coral)),
                            ),
                    )
                }

                Spacer(Modifier.weight(1f))
            }

            Text(
                text = data.footer,
                fontSize = 13.sp,
                color = InkFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 4.sp,
        color = InkDim,
    )
}

/** 1080×1350, the 4:5 frame every share surface expects. */
private const val CARD_W = 1080
private const val CARD_H = 1350

/** How far down the chart the card goes — the same five the Replay shows. */
private const val TOP_SONGS = 5

/** Frames awaited for the initial composition before measuring. */
private const val MAX_FRAMES = 30

private const val MIME_TYPE = "image/png"
private const val SHARE_FOLDER = "shared"
private const val FILE_NAME = "stats-card.png"
