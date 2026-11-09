package com.music.sonariq.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.model.LikeStatus
import com.music.sonariq.data.model.Song
import com.music.sonariq.sharedui.resources.*
import com.music.sonariq.ui.haptics.Haptic
import com.music.sonariq.ui.haptics.rememberHaptics
import com.music.sonariq.ui.icons.SonariqIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The reference player's chrome: the compact header, the page dots, the
 * ambient lyric lines and the bottom utility
 * row — the pieces the rebuilt Now Playing is composed from, in the
 * Emerald Living Sound material (see [GlassTokens]) rather than in the
 * reference's own colours, which belonged to its artwork.
 *
 * Everything here is presentation only: each control is wired by
 * [NowPlayingScreen] to the same callbacks the player always had.
 */

/** The header artwork thumbnail's size — a small rounded square. */
private val HEADER_THUMB_SIZE = 52.dp

/**
 * The header every mode of the rebuilt player opens with: a small
 * rounded artwork thumbnail, the bold title and dim artist beside it
 * (marquee, album/artist navigation as the old credits row had), the
 * star like toggle, and the ••• button.
 *
 * The landscape player's name block is this same header with
 * [showThumbnail] off: the big artwork card is already on screen beside
 * it, so the name stands on its own — title, artist, badge, star and
 * menu, nothing else.
 *
 * A tap on ••• opens the song menu, exactly as it always has
 * ([onOpenMenu] is the host's menu, which is where the sleep timer, the
 * quality/version switch and the lyrics offset live). A long-press on
 * ••• opens the player-local overflow instead: the utilities that
 * belong to this screen rather than to the song — share, the audio
 * pipeline readout, the visualizer and Listen
 * Together — so nothing the old composition carried is lost with it.
 */
@Composable
internal fun ReferencePlayerHeader(
    song: Song,
    artRequest: ImageRequest,
    signedIn: Boolean,
    likeStatus: LikeStatus,
    /** The menu glyph is briefly the undo arrow after a quality upgrade. */
    showRevertCue: Boolean,
    marqueeEnabled: Boolean,
    onToggleLike: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    /** Player-local overflow entries; null hides that entry. */
    onShareSong: (() -> Unit)? = null,
    onOpenAudioPipeline: (() -> Unit)? = null,
    onToggleVisualizer: (() -> Unit)? = null,
    visualizerShown: Boolean = false,
    onListenTogether: (() -> Unit)? = null,
    /** Drawn under the artist — the vibe badge's home. */
    badge: @Composable () -> Unit = {},
    /** The leading artwork thumbnail; off where a big card already shows it. */
    showThumbnail: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    var overflowOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showThumbnail) {
            // The thumbnail travels with the track change like the sleeve
            // does — the same slide, at thumbnail scale — so the header
            // never cuts to the new cover while everything around it is
            // still moving.
            SlidingArtworkImage(
                request = artRequest,
                modifier = Modifier
                    .size(HEADER_THUMB_SIZE)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            var titleOverflowing by remember { mutableStateOf(false) }
            MarqueeText(
                text = song.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                enabled = marqueeEnabled,
                onOverflowChange = { titleOverflowing = it },
                leading = if (song.isExplicit == true) {
                    { ExplicitBadge(Color.White) }
                } else {
                    null
                },
                modifier = Modifier.opensPage(song.albumId, onOpenAlbum),
            )
            ArtistCreditsMarquee(
                text = song.artist,
                credits = remember(song.artist, song.artists, song.artistId) {
                    artistCredits(song.artist, song.artists, song.artistId)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.55f),
                enabled = marqueeEnabled,
                startDelayMillis = if (titleOverflowing) MARQUEE_ARTIST_STAGGER_MS else 0L,
                onOpenArtist = onOpenArtist,
            )
            badge()
        }
        // Same gate as the old credits row: no account to like against for
        // a guest, and no identity to rate a local file against either.
        if (signedIn && song.localUri == null) {
            val liked = likeStatus == LikeStatus.LIKE
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        haptics.play(if (liked) Haptic.ToggleOff else Haptic.ToggleOn)
                        onToggleLike()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (liked) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = stringResource(
                        if (liked) Res.string.remove_from_liked else Res.string.like,
                    ),
                    tint = if (liked) GlassTokens.mint else Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Box {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            haptics.play(Haptic.Tap)
                            onOpenMenu()
                        },
                        onLongClick = {
                            haptics.play(Haptic.Select)
                            overflowOpen = true
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (showRevertCue) {
                        Icons.AutoMirrored.Rounded.Undo
                    } else {
                        Icons.Rounded.MoreHoriz
                    },
                    contentDescription = stringResource(Res.string.more),
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
            DropdownMenu(
                expanded = overflowOpen,
                onDismissRequest = { overflowOpen = false },
            ) {
                if (onShareSong != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.share)) },
                        onClick = {
                            overflowOpen = false
                            onShareSong()
                        },
                    )
                }
                if (onOpenAudioPipeline != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.audio_pipeline)) },
                        onClick = {
                            overflowOpen = false
                            onOpenAudioPipeline()
                        },
                    )
                }
                if (onToggleVisualizer != null) {
                    DropdownMenuItem(
                        text = {
                            Text(if (visualizerShown) "Hide visualizer" else "Visualizer")
                        },
                        onClick = {
                            overflowOpen = false
                            onToggleVisualizer()
                        },
                    )
                }
                if (onListenTogether != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.listen_together_open)) },
                        onClick = {
                            overflowOpen = false
                            onListenTogether()
                        },
                    )
                }
            }
        }
    }
}

/**
 * The page dots under the header in player mode: one per page the
 * player's own state machine models — player, lyrics, queue — with the
 * current page lit. Tapping a dot goes to that page.
 */
@Composable
internal fun ReferencePageDots(
    pageCount: Int,
    activePage: Int,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        repeat(pageCount) { page ->
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelectPage(page) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (page == activePage) {
                                Color.White
                            } else {
                                Color.White.copy(alpha = 0.30f)
                            },
                        ),
                )
            }
            if (page < pageCount - 1) Spacer(Modifier.width(2.dp))
        }
    }
}

/**
 * The player mode's middle area: the lyric lines around the playing one,
 * rendered large, blurred and faint as ambience over the artwork
 * backdrop — the reference's blurred-line field, at the depth model's
 * own values with a floor of blur so even the current line reads as
 * atmosphere rather than as the lyrics panel.
 *
 * Purely decorative: nothing here takes a touch (no clickable, no
 * pointerInput), so the gestures on the area around it — swipe to skip,
 * double-tap to seek, tap for the visualizer — pass straight through to
 * the container the caller puts this in.
 */
@Composable
internal fun AmbientLyricLines(
    lines: List<LyricLine>,
    playhead: LyricPlayhead,
    loadingText: String,
    lyricsUnavailable: Boolean,
    blurEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val isSynced = remember(lines) { lines.any { it.timeMs > 0L } }
    val activeIndex by remember(lines, isSynced) {
        derivedStateOf {
            if (!isSynced) -1 else lines.indexOfLast { it.timeMs <= playhead.positionMs }
        }
    }
    Column(modifier = modifier) {
        if (lines.isEmpty()) {
            Text(
                text = if (lyricsUnavailable) {
                    stringResource(Res.string.lyrics_not_available)
                } else {
                    loadingText
                },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.40f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            return@Column
        }
        val centre = if (activeIndex >= 0) activeIndex else 0
        val from = (centre - 1).coerceAtLeast(0)
        val to = (centre + 3).coerceAtMost(lines.size)
        for (index in from until to) {
            val line = lines[index]
            if (line.isGap) continue
            val depth = LyricDepth.forDistance(index - centre)
            // Ambience, not reading: even the playing line stays faint,
            // and every line carries at least a breath of blur.
            val blurDp = if (blurEnabled) {
                maxOf(depth.blurDp, if (index == centre) 3f else 5f)
            } else {
                0f
            }
            Text(
                text = line.text,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .alpha((depth.alpha * 0.5f).coerceAtMost(0.5f))
                    .then(
                        if (blurDp > 0f) {
                            Modifier.blur(blurDp.dp, BlurredEdgeTreatment.Unbounded)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

/**
 * The bottom utility row of the rebuilt player: lyrics toggle (a filled
 * circular highlight while the lyrics are up, as in the reference), the
 * audio-output button, Listen Together, and the queue button.
 */
@Composable
internal fun ReferenceUtilityRow(
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    partyActive: Boolean,
    onToggleLyrics: () -> Unit,
    onOpenOutput: () -> Unit,
    onOpenParty: () -> Unit,
    onToggleQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UtilityCircle(
            highlighted = lyricsOpen,
            onClick = onToggleLyrics,
        ) {
            Icon(
                imageVector = SonariqIcons.LyricsQuote,
                contentDescription = stringResource(
                    if (lyricsOpen) Res.string.close_lyrics else Res.string.open_lyrics,
                ),
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
        UtilityCircle(
            highlighted = false,
            onClick = onOpenOutput,
        ) {
            Icon(
                imageVector = Icons.Rounded.Headphones,
                contentDescription = stringResource(Res.string.audio_output),
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(24.dp),
            )
        }
        UtilityCircle(
            highlighted = partyActive,
            onClick = onOpenParty,
        ) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = stringResource(Res.string.listen_together_open),
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(24.dp),
            )
        }
        UtilityCircle(
            highlighted = queueOpen,
            onClick = onToggleQueue,
            haptic = if (queueOpen) Haptic.Tap else Haptic.Expand,
        ) {
            Icon(
                imageVector = SonariqIcons.Queue,
                contentDescription = stringResource(Res.string.up_next),
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** One circular utility button: a filled glass highlight while active. */
@Composable
private fun UtilityCircle(
    highlighted: Boolean,
    onClick: () -> Unit,
    haptic: Haptic = Haptic.Tap,
    content: @Composable () -> Unit,
) {
    val haptics = rememberHaptics()
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (highlighted) {
                    Color.White.copy(alpha = 0.28f)
                } else {
                    Color.Transparent
                },
            )
            .then(if (highlighted) Modifier.glassEdge(CircleShape) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.play(haptic)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** The sheet's grab handle, drawn at the player's top centre. */
@Composable
internal fun ReferenceDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(38.dp)
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.70f)),
    )
}
