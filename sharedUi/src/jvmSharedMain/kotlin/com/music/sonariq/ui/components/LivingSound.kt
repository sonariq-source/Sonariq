package com.music.sonariq.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.sonariq.ui.player.GlassTokens
import com.music.sonariq.ui.player.glassEdge

/**
 * Living Sound: Sonariq's own design signatures, as shared components.
 *
 * The identity does not live in the palette — emerald was already there —
 * it lives in these recurring shapes:
 *
 *  - the **soundline**, the six-bar waveform of the brand mark, drawn as a
 *    glyph in headers and as the selected-navigation indicator;
 *  - the **eyebrow header**: a small letter-spaced mint label with the
 *    glyph beside it, over a large tight-tracked title;
 *  - **echo artwork**: a hero artwork layered over an offset, faded copy
 *    of itself, so artwork sits in the space instead of on it;
 *  - the **spine**: a thin emerald rule on a feature card's leading edge.
 *
 * Everything here is static geometry drawn once per layout: no backdrop
 * sampling, no real-time blur, nothing animating on a loop.
 */

/** The brand soundline's bar heights, as fractions of the glyph's height. */
private val SOUNDLINE_BARS = floatArrayOf(0.42f, 0.78f, 1f, 0.6f, 0.88f, 0.5f)

/**
 * The six-bar soundline glyph. Drawn on a canvas as rounded bars; the
 * bars are the logo's waveform, so it reads as Sonariq at any size.
 */
@Composable
fun SoundlineGlyph(
    modifier: Modifier = Modifier,
    color: Color = GlassTokens.mint,
    barCount: Int = 6,
) {
    Canvas(modifier.height(14.dp).width(22.dp)) {
        val bars = barCount.coerceIn(1, SOUNDLINE_BARS.size)
        val barWidth = size.width * 0.11f
        val gap = (size.width - barWidth * bars) / (bars - 1).coerceAtLeast(1)
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        for (i in 0 until bars) {
            val h = size.height * SOUNDLINE_BARS[i]
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barWidth + gap), (size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * The eyebrow: the soundline glyph beside a small, letter-spaced,
 * uppercase mint label. The top line of every Living Sound header.
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = GlassTokens.mint,
    glyphColor: Color = color,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SoundlineGlyph(color = glyphColor)
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.W700,
                letterSpacing = 2.2.sp,
            ),
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * The Living Sound screen header: eyebrow over a large tight title, with
 * an optional quiet subtitle under it. Every screen opens with this shape
 * instead of a bare title or a greeting bar.
 */
@Composable
fun LivingScreenHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(modifier = modifier) {
        Eyebrow(eyebrow)
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/**
 * The Living Sound section header: a small eyebrow-style label row over
 * the section title, with an optional trailing action. Sections are
 * labelled by what they are ("Shelf", "Collection") in the eyebrow and
 * named in the title — the two-line rhythm replaces the single bold line
 * every screen used to open a section with.
 */
@Composable
fun LivingSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            if (!eyebrow.isNullOrBlank()) {
                Eyebrow(eyebrow)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
        }
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = GlassTokens.activeNav,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Echo artwork: the signature artwork treatment. The same artwork is
 * drawn twice — once offset behind, scaled a touch smaller, faded and
 * washed emerald — and once in front at full strength with the hero
 * corner. The offset copy is the *echo*: depth without blur, and because
 * both layers draw the one already-loaded bitmap (the image loader
 * serves the second draw from its memory cache), it costs no extra
 * decode.
 */
@Composable
fun EchoArtwork(
    modifier: Modifier = Modifier,
    shape: Shape = GlassTokens.heroShape,
    echoOffset: androidx.compose.ui.unit.Dp = GlassTokens.echoOffset,
    artwork: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        // The echo: behind, offset down the leading diagonal, quieter.
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = echoOffset, y = echoOffset)
                .scale(0.97f)
                .alpha(0.38f)
                .clip(shape)
                .background(GlassTokens.panelGlass),
        ) {
            artwork()
            // The emerald wash that ties the echo to the room's light.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(GlassTokens.accent.copy(alpha = 0.22f)),
            )
        }
        // The artwork itself, in front, with a depth shadow.
        Box(
            modifier = Modifier
                .matchParentSize()
                .shadow(elevation = 14.dp, shape = shape)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            artwork()
        }
    }
}

/**
 * A feature card with the Living Sound spine: panel glass with the glass
 * edge, and a thin emerald rule running down the leading edge. Feature
 * cards (collections, spotlights, the queue's current track) carry the
 * spine; ordinary rows and grid cards do not, which is what makes the
 * spine mean "featured".
 */
@Composable
fun SpineFeatureCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .clip(GlassTokens.panelShape)
            .glassEdge(GlassTokens.panelShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = GlassTokens.panelShape,
        color = GlassTokens.panelSurface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row {
            Box(
                modifier = Modifier
                    .width(GlassTokens.spineWidth)
                    .fillMaxHeight()
                    .background(GlassTokens.spineColor),
            )
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

/**
 * The selected-navigation indicator: a five-bar mini soundline that sits
 * under the selected tab's icon in place of a pill or a dot. Unselected
 * tabs show nothing — the soundline only sounds for the current place.
 */
@Composable
fun NavWaveIndicator(
    modifier: Modifier = Modifier,
    color: Color = GlassTokens.activeNav,
) {
    Canvas(modifier.height(10.dp).width(26.dp)) {
        val pattern = floatArrayOf(0.5f, 0.85f, 1f, 0.7f, 0.45f)
        val barWidth = size.width * 0.12f
        val gap = (size.width - barWidth * pattern.size) / (pattern.size - 1)
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        pattern.forEachIndexed { i, fraction ->
            val h = size.height * fraction
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barWidth + gap), size.height - h),
                size = Size(barWidth, h),
                cornerRadius = radius,
            )
        }
    }
}
