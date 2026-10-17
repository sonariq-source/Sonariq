package com.music.sonariq.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.ui.replay.Fonts
import com.music.sonariq.ui.replay.drawArtwork
import com.music.sonariq.ui.replay.drawLogo
import com.music.sonariq.ui.replay.ellipsised
import com.music.sonariq.ui.replay.loadBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 2–3 real lyric lines around [positionMs]: the current line and what follows
 * it, skipping gaps. Null when the track has no lyrics (or none yet) — the
 * caller hides the lyrics share option rather than offering an empty card.
 *
 * Reads the same synced lines the player draws; nothing is estimated or
 * invented here.
 */
fun lyricSnippet(lines: List<LyricLine>?, positionMs: Long): List<String>? {
    if (lines.isNullOrEmpty()) return null
    val current = lines.indexOfLast { it.timeMs <= positionMs && it.text.isNotBlank() }
    if (current < 0) return null
    return lines
        .drop(current)
        .mapNotNull { it.text.takeIf(String::isNotBlank) }
        .take(3)
        .takeIf { it.isNotEmpty() }
}

/**
 * A song as a 1080×1920 story card, drawn to a bitmap you can send.
 *
 * Canvas-drawn like the Replay posters rather than composed offscreen: the
 * layout is fixed — nothing reflows — and a canvas is a canvas on every API
 * level the app supports. The card is the shape of the song, not the brand:
 * blurred artwork fills the frame, the sleeve (or the lyric) is the hero, and
 * the Sonariq mark sits small at the foot.
 *
 * @param lyricLines 2–3 real lyric lines around the current position, or null
 * for the plain card. Never synthesized — the caller passes lines from the
 * synced lyrics engine, and a track without lyrics gets the plain card.
 * @param style which card look to draw; defaults to [ShareCardStyle.Classic].
 */
suspend fun renderSongShareCard(
    context: Context,
    title: String,
    artist: String,
    artworkUrl: String?,
    lyricLines: List<String>? = null,
    style: ShareCardStyle = ShareCardStyle.Classic,
): Bitmap = withContext(Dispatchers.Default) {
    val art = artworkUrl?.let { loadBitmap(context, it) }
    val bitmap = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val type = Fonts(context)

    if (style == ShareCardStyle.Minimal) {
        // Minimal skips the sleeve entirely: the words on a clean dark field.
        canvas.drawColor(0xFF0E0A12.toInt())
    } else {
        drawBlurredBackdrop(canvas, art)
    }
    if (lyricLines.isNullOrEmpty()) {
        drawPlainCard(canvas, type, title, artist, art)
    } else {
        when (style) {
            ShareCardStyle.Classic -> drawLyricsCard(canvas, type, title, artist, art, lyricLines.take(5))
            ShareCardStyle.Wide -> drawLyricsCardWide(canvas, type, title, artist, art, lyricLines.take(5))
            ShareCardStyle.Minimal -> drawLyricsCardMinimal(canvas, type, lyricLines.take(5))
        }
    }
    drawBrandFoot(canvas, context, type)
    bitmap
}

/**
 * The sleeve, smeared across the whole frame and weighted dark — the card's
 * colour comes from the song. A missing sleeve falls back to near-black plum
 * rather than a hole.
 */
private fun drawBlurredBackdrop(canvas: Canvas, art: Bitmap?) {
    if (art != null) {
        // A cheap, dependency-free blur: crush to a thumbnail and stretch it
        // back. At 27×48 no detail survives — only the colour fields, which is
        // all a backdrop wants.
        val tiny = Bitmap.createScaledBitmap(art, 27, 48, true)
        val smeared = Bitmap.createScaledBitmap(tiny, CARD_W, CARD_H, true)
        tiny.recycle()
        canvas.drawBitmap(smeared, 0f, 0f, null)
        smeared.recycle()
    } else {
        canvas.drawColor(0xFF120D18.toInt())
    }
    val paint = Paint()
    paint.shader = LinearGradient(
        0f, 0f, 0f, CARD_H.toFloat(),
        intArrayOf(0xB3000000.toInt(), 0x66000000, 0xE6000000.toInt()),
        floatArrayOf(0f, 0.45f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, CARD_W.toFloat(), CARD_H.toFloat(), paint)
    paint.shader = null
}

/** Sleeve hero, title, artist — the card for a song with nothing to quote. */
private fun drawPlainCard(
    canvas: Canvas,
    type: Fonts,
    title: String,
    artist: String,
    art: Bitmap?,
) {
    val artSize = 800f
    val artX = (CARD_W - artSize) / 2f
    val artY = 400f
    drawArtwork(canvas, art, title, artX, artY, artSize, circular = false)

    val cx = CARD_W / 2f
    var y = artY + artSize + 110f
    val titlePaint = type.heading(64f, 0xFFFFFFFF.toInt()).apply {
        textAlign = Paint.Align.CENTER
    }
    y = drawWrapped(canvas, title, titlePaint, cx, y, CONTENT_W, maxLines = 2, lineGap = 18f)

    val artistPaint = type.body(44f, 0xB3FFFFFF.toInt()).apply {
        textAlign = Paint.Align.CENTER
    }
    drawWrapped(
        canvas,
        ellipsised(artist, artistPaint, CONTENT_W),
        artistPaint,
        cx,
        y + 28f,
        CONTENT_W,
        maxLines = 1,
        lineGap = 0f,
    )
}

/**
 * The Spotify-shaped card: the lyric, big and quoted, over the smeared
 * sleeve — then the song identified small underneath, the way a story viewer
 * reads it: words first, source second.
 */
private fun drawLyricsCard(
    canvas: Canvas,
    type: Fonts,
    title: String,
    artist: String,
    art: Bitmap?,
    lines: List<String>,
) {
    val cx = CARD_W / 2f
    val quotePaint = type.heading(200f, 0x80FFFFFF.toInt()).apply {
        textAlign = Paint.Align.CENTER
    }
    val linePaint = type.heading(72f, 0xFFFFFFFF.toInt()).apply {
        textAlign = Paint.Align.CENTER
    }

    // Measure the whole lyric block first so it lands centred, not top-hung.
    val wrapped = lines.flatMap { wrapLine(it, linePaint, CONTENT_W) }.take(6)
    val lineHeight = linePaint.fontSpacing
    val blockH = 200f + wrapped.size * lineHeight
    var y = (CARD_H - blockH) / 2f - 60f

    canvas.drawText("\u201C", cx, y + 150f, quotePaint)
    y += 200f
    wrapped.forEach { line ->
        y += lineHeight
        canvas.drawText(line, cx, y, linePaint)
    }

    // The source, small: sleeve thumb plus title — artist, then the brand.
    val thumb = 170f
    val thumbX = (CARD_W - thumb) / 2f
    val thumbY = y + 110f
    drawArtwork(canvas, art, title, thumbX, thumbY, thumb, circular = false)
    val idY = thumbY + thumb + 64f
    val titlePaint = type.body(40f, 0xFFFFFFFF.toInt(), bold = true).apply {
        textAlign = Paint.Align.CENTER
    }
    val artistPaint = type.body(34f, 0xB3FFFFFF.toInt()).apply {
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(ellipsised(title, titlePaint, CONTENT_W), cx, idY, titlePaint)
    canvas.drawText(
        ellipsised(artist, artistPaint, CONTENT_W),
        cx,
        idY + artistPaint.fontSpacing + 8f,
        artistPaint,
    )
}

/**
 * Wide: the lyric set larger and spread across the frame, fewer words per
 * line, more air between them — built for the lines the user hand-picked.
 */
private fun drawLyricsCardWide(
    canvas: Canvas,
    type: Fonts,
    title: String,
    artist: String,
    art: Bitmap?,
    lines: List<String>,
) {
    val cx = CARD_W / 2f
    val linePaint = type.heading(96f, 0xFFFFFFFF.toInt()).apply {
        textAlign = Paint.Align.CENTER
    }

    val wrapped = lines.flatMap { wrapLine(it, linePaint, CONTENT_W) }.take(8)
    val lineHeight = linePaint.fontSpacing + 26f
    val blockH = wrapped.size * lineHeight
    var y = (CARD_H - blockH) / 2f - 80f

    wrapped.forEach { line ->
        y += lineHeight
        canvas.drawText(line, cx, y, linePaint)
    }

    val idY = y + 150f
    val titlePaint = type.body(44f, 0xFFFFFFFF.toInt(), bold = true).apply {
        textAlign = Paint.Align.CENTER
    }
    val artistPaint = type.body(36f, 0xB3FFFFFF.toInt()).apply {
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(ellipsised(title, titlePaint, CONTENT_W), cx, idY, titlePaint)
    canvas.drawText(
        ellipsised(artist, artistPaint, CONTENT_W),
        cx,
        idY + artistPaint.fontSpacing + 10f,
        artistPaint,
    )
}

/**
 * Minimal: the picked lines on a clean dark field, nothing else competing —
 * the waveform mark at the foot (drawn by [drawBrandFoot]) is the only brand.
 */
private fun drawLyricsCardMinimal(
    canvas: Canvas,
    type: Fonts,
    lines: List<String>,
) {
    val cx = CARD_W / 2f
    val linePaint = type.heading(84f, 0xFFFFFFFF.toInt()).apply {
        textAlign = Paint.Align.CENTER
    }

    val wrapped = lines.flatMap { wrapLine(it, linePaint, CONTENT_W) }.take(8)
    val lineHeight = linePaint.fontSpacing + 30f
    val blockH = wrapped.size * lineHeight
    var y = (CARD_H - blockH) / 2f

    wrapped.forEach { line ->
        y += lineHeight
        canvas.drawText(line, cx, y, linePaint)
    }
}

/** The mark and the name, small at the foot — the card is the song's, not ours. */
private fun drawBrandFoot(canvas: Canvas, context: Context, type: Fonts) {    val baseline = CARD_H - 72f
    val right = CARD_W - 96f
    drawLogo(canvas, context, right, baseline)
    val label = type.label(26f, 0xB3FFFFFF.toInt(), tracking = 0.14f)
    label.textAlign = Paint.Align.RIGHT
    // drawLogo paints LOGO_W wide ending at `right`; the wordmark sits left of it.
    canvas.drawText("SONARIQ", right - 96f, baseline - 8f, label)
}

/**
 * Draws [text] centred on [cx], wrapped to [maxWidth], up to [maxLines];
 * returns the baseline below the last line drawn.
 */
private fun drawWrapped(
    canvas: Canvas,
    text: String,
    paint: Paint,
    cx: Float,
    y: Float,
    maxWidth: Float,
    maxLines: Int,
    lineGap: Float,
): Float {
    var baseline = y
    wrapLine(text, paint, maxWidth).take(maxLines).forEach { line ->
        baseline += paint.fontSpacing + lineGap
        canvas.drawText(line, cx, baseline, paint)
    }
    return baseline
}

/** Greedy word wrap — a canvas has no paragraph layout, so this is it. */
private fun wrapLine(text: String, paint: Paint, maxWidth: Float): List<String> {
    val words = text.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val lines = mutableListOf<String>()
    var current = StringBuilder()
    for (word in words) {
        val trial = if (current.isEmpty()) word else "$current $word"
        if (paint.measureText(trial) <= maxWidth || current.isEmpty()) {
            current = StringBuilder(trial)
        } else {
            lines.add(current.toString())
            current = StringBuilder(word)
        }
    }
    if (current.isNotEmpty()) lines.add(current.toString())
    return lines
}

/** 1080×1920 — the 9:16 every story surface expects. */
private const val CARD_W = 1080
private const val CARD_H = 1920

/** The width type is laid out in. */
private const val CONTENT_W = 840f
