package com.music.sonariq.ui.discovery

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
import com.music.sonariq.data.discovery.MonthlyRecap
import com.music.sonariq.data.stats.ListeningStats
import com.music.sonariq.data.stats.MilestoneKind
import com.music.sonariq.data.stats.ReplayPeriod
import com.music.sonariq.ui.replay.formatMinutes
import com.music.sonariq.ui.replay.grouped
import com.music.sonariq.ui.replay.localizedLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Shareable cards for listening insights and the monthly recap.
 *
 * ## Same frame, same language as the Replay cards
 *
 * Every card is the 1080×1350 (4:5) frame the share surfaces expect, drawn
 * with the same offscreen-composition machinery as
 * [com.music.sonariq.ui.stats.renderStatsCard] — a composable rooted in its
 * own [Recomposer], measured, laid out and drawn with no window. The
 * machinery is duplicated rather than reused because that file keeps its
 * renderer private, and reaching into it would couple these cards to the
 * Replay screen's internals.
 *
 * ## No artwork on the cards
 *
 * For the same reason the Replay card carries none: sleeves load
 * asynchronously, and a shared picture must be complete the moment it is
 * drawn. Rank numerals, bars and the gradient washes carry the weight.
 */

/** The current listening streak as a card: days big, plays underneath. */
suspend fun renderStreakCard(context: Context): Bitmap {
    val milestones = ListeningStats.milestones()
    val streak = milestones.firstOrNull { it.kind == MilestoneKind.STREAK }
        ?.current?.toInt() ?: 0
    val plays = milestones.firstOrNull { it.kind == MilestoneKind.PLAYS }
        ?.current ?: 0L
    val data = StreakCardData(
        title = context.getString(R.string.discovery_card_streak_title),
        streak = grouped(streak.toLong()),
        streakCaption = context.getString(R.string.discovery_card_streak_caption),
        playsCaption = context.getString(R.string.discovery_streak_best, plays),
        empty = streak == 0 && plays == 0L,
        emptyTitle = context.getString(R.string.discovery_no_streak_yet),
        footer = context.getString(R.string.discovery_card_footer),
    )
    return withContext(Dispatchers.Main.immediate) {
        renderCardOffscreen(context) { StreakCard(data) }
    }
}

/** The genre breakdown as a card: the top genres as proportional bars. */
suspend fun renderGenreCard(context: Context, period: ReplayPeriod): Bitmap {
    val summary = ListeningStats.summary(period)
    val top = summary.genres.take(GENRES_ON_CARD)
    val total = top.sumOf { it.ms }.coerceAtLeast(1L)
    val data = GenreCardData(
        title = context.getString(R.string.discovery_card_genres_title),
        periodLabel = summary.localizedLabel(context),
        genres = top.map {
            GenreBar(
                name = it.title,
                share = it.ms.toFloat() / total.toFloat(),
                caption = context.getString(
                    R.string.discovery_card_of_minutes,
                    formatMinutes(it.ms),
                ),
            )
        },
        empty = summary.isEmpty || top.isEmpty(),
        emptyTitle = context.getString(R.string.discovery_genres_empty),
        footer = context.getString(R.string.discovery_card_footer),
    )
    return withContext(Dispatchers.Main.immediate) {
        renderCardOffscreen(context) { GenreCard(data) }
    }
}

/** The period's top tracks as a card. */
suspend fun renderTopTracksCard(context: Context, period: ReplayPeriod): Bitmap {
    val summary = ListeningStats.summary(period)
    val data = ChartCardData(
        title = context.getString(R.string.discovery_card_tracks_title),
        periodLabel = summary.localizedLabel(context),
        rows = summary.songs.take(ROWS_ON_CARD).mapIndexed { index, ranked ->
            ChartRow(
                rank = index + 1,
                title = ranked.song.title,
                subtitle = ranked.song.artist,
                caption = context.getString(
                    R.string.discovery_card_of_minutes,
                    formatMinutes(ranked.ms),
                ),
            )
        },
        empty = summary.isEmpty,
        emptyTitle = context.getString(R.string.discovery_no_streak_yet),
        footer = context.getString(R.string.discovery_card_footer),
    )
    return withContext(Dispatchers.Main.immediate) {
        renderCardOffscreen(context) { ChartCard(data) }
    }
}

/** The period's top artists as a card. */
suspend fun renderTopArtistsCard(context: Context, period: ReplayPeriod): Bitmap {
    val summary = ListeningStats.summary(period)
    val data = ChartCardData(
        title = context.getString(R.string.discovery_card_artists_title),
        periodLabel = summary.localizedLabel(context),
        rows = summary.artists.take(ROWS_ON_CARD).mapIndexed { index, ranked ->
            ChartRow(
                rank = index + 1,
                title = ranked.title,
                subtitle = ranked.subtitle ?: "",
                caption = context.getString(
                    R.string.discovery_card_of_minutes,
                    formatMinutes(ranked.ms),
                ),
            )
        },
        empty = summary.isEmpty,
        emptyTitle = context.getString(R.string.discovery_no_streak_yet),
        footer = context.getString(R.string.discovery_card_footer),
    )
    return withContext(Dispatchers.Main.immediate) {
        renderCardOffscreen(context) { ChartCard(data) }
    }
}

/** The month in music as a card: minutes, top track, artist, genre, streak. */
suspend fun renderMonthlyRecapCard(context: Context, recap: MonthlyRecap): Bitmap {
    val data = RecapCardData(
        title = context.getString(R.string.discovery_card_recap_title),
        monthLabel = recap.label,
        minutes = grouped(recap.minutes),
        minutesCaption = context.getString(R.string.discovery_recap_minutes),
        topTrack = recap.topTracks.firstOrNull()?.let {
            "${it.title} — ${it.artist}"
        },
        topArtist = recap.topArtists.firstOrNull()?.name,
        topGenre = recap.topGenre,
        streak = if (recap.streakDays > 0) {
            context.getString(R.string.discovery_streak_days, recap.streakDays)
        } else {
            null
        },
        plays = grouped(recap.totalPlays.toLong()),
        playsCaption = context.getString(R.string.discovery_recap_plays),
        topTrackCaption = context.getString(R.string.discovery_recap_top_track),
        topArtistCaption = context.getString(R.string.discovery_recap_top_artist),
        topGenreCaption = context.getString(R.string.discovery_recap_top_genre),
        empty = recap.isEmpty,
        emptyTitle = context.getString(R.string.discovery_recap_no_data),
        footer = context.getString(R.string.discovery_card_footer),
    )
    return withContext(Dispatchers.Main.immediate) {
        renderCardOffscreen(context) { RecapCard(data) }
    }
}

/**
 * Sends [bitmap] to the app of the user's choice, under [fileName].
 *
 * Same contract as the Replay share: the cache's `shared/` folder, a
 * FileProvider content URI, a read grant that travels with the intent.
 */
fun shareDiscoveryCard(context: Context, bitmap: Bitmap, fileName: String) {
    val folder = File(context.cacheDir, SHARE_FOLDER).apply { mkdirs() }
    val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeIf { it.isNotBlank() }
        ?: "card.png"
    val file = File(folder, safe)
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
    val chooser = Intent.createChooser(send, context.getString(R.string.discovery_share_image))
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

// ── Offscreen composition ─────────────────────────────────────────────────────

/**
 * Composes [content] into a view that is never attached to a window and
 * draws it into a bitmap.
 *
 * Without a window, [ComposeView.setContent] only stores the content and waits
 * for attachment — so the composition is created explicitly against a
 * [Recomposer] owned by this call. A few frames are awaited so the initial
 * composition is applied before measuring; the cap keeps a wedged frame clock
 * from hanging the share button forever.
 */
private suspend fun renderCardOffscreen(
    context: Context,
    content: @Composable () -> Unit,
): Bitmap {
    return kotlinx.coroutines.coroutineScope {
        val recomposer = Recomposer(coroutineContext)
        val view = ComposeView(context)
        val recompose = launch { recomposer.runRecomposeAndApplyChanges() }
        try {
            view.setParentCompositionContext(recomposer)
            view.setContent(content)
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
            return@coroutineScope bitmap
        } finally {
            runCatching { view.disposeComposition() }
            recompose.cancel()
            recomposer.close()
        }
    }
}

// ── Card data ─────────────────────────────────────────────────────────────────

private data class StreakCardData(
    val title: String,
    val streak: String,
    val streakCaption: String,
    val playsCaption: String,
    val empty: Boolean,
    val emptyTitle: String,
    val footer: String,
)

private data class GenreBar(val name: String, val share: Float, val caption: String)

private data class GenreCardData(
    val title: String,
    val periodLabel: String,
    val genres: List<GenreBar>,
    val empty: Boolean,
    val emptyTitle: String,
    val footer: String,
)

private data class ChartRow(
    val rank: Int,
    val title: String,
    val subtitle: String,
    val caption: String,
)

private data class ChartCardData(
    val title: String,
    val periodLabel: String,
    val rows: List<ChartRow>,
    val empty: Boolean,
    val emptyTitle: String,
    val footer: String,
)

private data class RecapCardData(
    val title: String,
    val monthLabel: String,
    val minutes: String,
    val minutesCaption: String,
    val topTrack: String?,
    val topArtist: String?,
    val topGenre: String?,
    val streak: String?,
    val plays: String,
    val playsCaption: String,
    val topTrackCaption: String,
    val topArtistCaption: String,
    val topGenreCaption: String,
    val empty: Boolean,
    val emptyTitle: String,
    val footer: String,
)

// ── Card styling ──────────────────────────────────────────────────────────────

private val PlumBase = Color(0xFF120D18)
private val Coral = Color(0xFFFF6B5E)
private val Orchid = Color(0xFFDA70D6)
private val Cyan = Color(0xFF5EEAD4)
private val Lime = Color(0xFFA3E635)
private val Ink = Color.White
private val InkDim = Color(0xFFB9AECB)
private val InkFaint = Color(0xFF8A7F9E)

@Composable
private fun CardFrame(
    accent: List<Color>,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(PlumBase)) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        accent[0].copy(alpha = 0.22f),
                        Color.Transparent,
                        accent[1].copy(alpha = 0.35f),
                    ),
                ),
            ),
        )
        Box(
            Modifier.fillMaxWidth().height(6.dp)
                .background(Brush.horizontalGradient(accent)),
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 56.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun CardEyebrow(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 6.sp,
        color = Cyan.copy(alpha = 0.9f),
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun CardFooter(text: String) {
    Spacer(Modifier.weight(1f))
    Text(
        text = text,
        fontSize = 13.sp,
        color = InkFaint,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EmptyCardBody(title: String) {
    Spacer(Modifier.weight(1f))
    Text(
        text = title,
        fontSize = 30.sp,
        fontWeight = FontWeight.ExtraBold,
        color = Ink,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.weight(1f))
}

@Composable
private fun StreakCard(data: StreakCardData) {
    CardFrame(accent = listOf(Coral, Orchid)) {
        CardEyebrow(data.title)
        if (data.empty) {
            EmptyCardBody(data.emptyTitle)
        } else {
            Spacer(Modifier.height(120.dp))
            Text(
                text = data.streak,
                fontSize = 150.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Coral,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = data.streakCaption.uppercase(Locale.getDefault()),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
                color = InkDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(60.dp))
            Text(
                text = data.playsCaption,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                color = InkDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        CardFooter(data.footer)
    }
}

@Composable
private fun GenreCard(data: GenreCardData) {
    CardFrame(accent = listOf(Orchid, Cyan)) {
        CardEyebrow(data.title)
        Text(
            text = data.periodLabel,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (data.empty) {
            EmptyCardBody(data.emptyTitle)
        } else {
            Spacer(Modifier.height(44.dp))
            Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
                data.genres.forEachIndexed { index, genre ->
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = genre.name,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = genre.caption,
                                fontSize = 15.sp,
                                color = InkDim,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier.fillMaxWidth().height(10.dp)
                                .background(InkFaint.copy(alpha = 0.25f)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(genre.share.coerceIn(0.02f, 1f))
                                    .height(10.dp)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                if (index % 2 == 0) Coral else Orchid,
                                                Cyan,
                                            ),
                                        ),
                                    ),
                            )
                        }
                    }
                }
            }
        }
        CardFooter(data.footer)
    }
}

@Composable
private fun ChartCard(data: ChartCardData) {
    CardFrame(accent = listOf(Lime, Color(0xFF4D7C0F))) {
        CardEyebrow(data.title)
        Text(
            text = data.periodLabel,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (data.empty) {
            EmptyCardBody(data.emptyTitle)
        } else {
            Spacer(Modifier.height(44.dp))
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                data.rows.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.rank.toString(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (row.rank == 1) Coral else InkFaint,
                            modifier = Modifier.width(44.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = row.title,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (row.subtitle.isNotBlank()) {
                                Text(
                                    text = row.subtitle,
                                    fontSize = 16.sp,
                                    color = InkDim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = row.caption,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Cyan.copy(alpha = 0.85f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        CardFooter(data.footer)
    }
}

@Composable
private fun RecapCard(data: RecapCardData) {
    CardFrame(accent = listOf(Coral, Orchid)) {
        CardEyebrow(data.title)
        Text(
            text = data.monthLabel,
            fontSize = 40.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Ink,
            maxLines = 1,
        )
        if (data.empty) {
            EmptyCardBody(data.emptyTitle)
        } else {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = data.minutes,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Coral,
                    maxLines = 1,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = data.minutesCaption.uppercase(Locale.getDefault()),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 3.sp,
                    color = InkDim,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            RecapLine(data.topTrackCaption, data.topTrack)
            RecapLine(data.topArtistCaption, data.topArtist)
            RecapLine(data.topGenreCaption, data.topGenre)
            data.streak?.let {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = it,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Lime,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "${data.plays} ${data.playsCaption}",
                fontSize = 18.sp,
                color = InkDim,
            )
        }
        CardFooter(data.footer)
    }
}

@Composable
private fun RecapLine(caption: String, value: String?) {
    value ?: return
    Text(
        text = caption.uppercase(Locale.getDefault()),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 4.sp,
        color = InkDim,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = value,
        fontSize = 24.sp,
        fontWeight = FontWeight.ExtraBold,
        color = Ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(16.dp))
}

/** 1080×1350, the 4:5 frame every share surface expects. */
private const val CARD_W = 1080
private const val CARD_H = 1350

/** How far down the chart the track/artist cards go. */
private const val ROWS_ON_CARD = 5

/** How many genres the genre card draws bars for. */
private const val GENRES_ON_CARD = 5

/** Frames awaited for the initial composition before measuring. */
private const val MAX_FRAMES = 30

private const val MIME_TYPE = "image/png"
private const val SHARE_FOLDER = "shared"
