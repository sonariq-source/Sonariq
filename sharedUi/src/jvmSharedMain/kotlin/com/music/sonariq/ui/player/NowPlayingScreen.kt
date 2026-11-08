package com.music.sonariq.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.sonariq.data.canvas.CanvasSource
import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.lyrics.LyricsSource
import com.music.sonariq.data.model.LikeStatus
import com.music.sonariq.data.model.PLAYER_ART_PX
import com.music.sonariq.data.model.PlaybackSourceType
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.settings.LastPlayerScreen
import com.music.sonariq.playback.BACK_RESTARTS_AFTER_MS
import com.music.sonariq.playback.PlaybackPosition
import com.music.sonariq.sharedui.resources.*
import com.music.sonariq.ui.haptics.Haptic
import com.music.sonariq.ui.haptics.rememberHaptics
import com.music.sonariq.ui.theme.StatusBarIcons
import com.music.sonariq.ui.theme.rememberArtworkTopBandLuminance
import com.music.sonariq.ui.theme.topBandScrimAlpha
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * Comfortably over the sleeve's drawn size on a phone, without wasting bytes.
 *
 * A rung on the app-wide ladder rather than a number of the player's own, so a
 * large home-screen widget asks for the same copy — see [PLAYER_ART_PX].
 */
internal const val ART_PX = PLAYER_ART_PX

/**
 * How many further goes a cover that failed to load gets.
 *
 * Small on purpose. This is here for the connection that drops for a moment or
 * the request that loses a race with the app coming back to the foreground, not
 * for a track whose artwork has genuinely gone: past a few tries the answer is
 * not going to change, and the placeholder tile is the honest thing to draw.
 */
internal const val ART_RETRIES = 3

/** How long to leave it before trying a failed cover again. */
internal const val ART_RETRY_DELAY_MS = 1_500L

/**
 * How long a canvas lookup waits for the track's album name before giving up
 * on it. Long enough to cover the album lookup on a normal connection, short
 * enough not to be noticed on a track that has no album to find.
 */
internal const val ALBUM_SETTLE_MS = 700L

/** Long enough for the post-upgrade rollback cue to be noticed without lingering. */
private const val REVERT_CUE_MS = 2_600L

/**
 * How close the player's reported position has to get to a released scrub
 * handle before the handle stops being drawn where it was dropped. Wide enough
 * to swallow a coarse progress tick, tight enough that the handle doesn't hand
 * over while it is still visibly wrong.
 */
internal const val SEEK_SETTLE_TOLERANCE_MS = 1_500L

/**
 * How long that handle is held at the drop point regardless. A backstop, not a
 * schedule: a seek normally settles in a tick or two, and this only decides how
 * long a seek that never settles can freeze the bar for. Generous enough that a
 * slow buffer still hands over smoothly rather than snapping back.
 */
internal const val SEEK_SETTLE_TIMEOUT_MS = 4_000L

/**
 * How often the backdrop re-reads the colours of a playing Canvas clip.
 *
 * Every three seconds, with the backdrop fade easing each read into the last.
 * The read is the expensive half — a texture readback off the GPU — and this
 * is the number that decides how many of them there are.
 */
private const val MESH_REFRESH_MS = 3_000L

/** The player's side margin. Scrollable panels reach back across it. */
internal val PLAYER_GUTTER = 30.dp

/**
 * How wide the player's content is ever allowed to get. Controls stretched
 * right across a tablet aren't a bigger player, just a coarser one; past this
 * the column stops growing and centres itself instead. Phones are narrower
 * than this, so for them it does nothing.
 */
internal val PLAYER_MAX_WIDTH = 560.dp

/**
 * The width from which the player counts as tablet-sized: its blur backdrop
 * is the full-cover blur in every state rather than the phone's seam-aware
 * mesh.
 */
private val TABLET_PLAYER_MIN_WIDTH = 700.dp

/**
 * The least a landscape window has to offer before the player splits into its
 * two columns: enough that each half still holds what it is given.
 */
private val LANDSCAPE_PLAYER_MIN_WIDTH = 560.dp

/** A modest floor for the status scrim while a subview replaces the hero. */
private const val SUBVIEW_STATUS_SCRIM_MIN_ALPHA = 0.40f

/**
 * Whether a window this wide is one the player fills outright or a
 * tablet-sized one — the two shapes in which edge-to-edge artwork reads as
 * the screen itself rather than as a picture hung on it.
 */
fun fullBleedArtworkAvailable(windowWidth: Dp): Boolean =
    playerFillsWindow(windowWidth) || tabletSizedPlayer(windowWidth)

/**
 * Whether a player given the whole of a window this wide is still narrow enough
 * to run its artwork edge to edge.
 */
private fun playerFillsWindow(windowWidth: Dp): Boolean =
    windowWidth <= PLAYER_MAX_WIDTH + PLAYER_GUTTER * 2

/**
 * Whether the player is tablet-sized — see [TABLET_PLAYER_MIN_WIDTH].
 *
 * [windowWidth] is the width of the *window*, measured rather than read off
 * `Configuration.screenWidthDp`: in a freeform or desktop window that can
 * report the display instead of the window, and it lands a beat late when
 * the window is dragged.
 */
private fun tabletSizedPlayer(windowWidth: Dp): Boolean =
    windowWidth >= TABLET_PLAYER_MIN_WIDTH

/**
 * Whether the player takes its landscape shape: the artwork card on the
 * left and the header / lyrics / controls column on the right.
 *
 * Width alone isn't enough: a tablet held upright can be as wide as a phone
 * held sideways, and the two-column layout is a landscape shape, not a "wide
 * enough" one. Upright, every device gets the portrait player.
 */
fun landscapePlayerAvailable(windowWidth: Dp, windowHeight: Dp): Boolean =
    windowWidth > windowHeight && windowWidth >= LANDSCAPE_PLAYER_MIN_WIDTH

/** How long the player stands under the lyrics untouched before standing down. */
private const val LYRICS_CONTROLS_IDLE_MS = 5_000L

/**
 * The vibe autoplay badge: a small matte pill naming the session's vibe,
 * e.g. "Sad • staying in the vibe". Long-press is "not this vibe": negative
 * feedback plus an immediate re-pick. A plain tap does nothing, so the pill
 * never reads as a button. In the rebuilt player it sits under the artist
 * in the header (see [ReferencePlayerHeader]'s badge slot).
 */
@Composable
private fun VibeBadge(
    text: String,
    onLongPress: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val pill = RoundedCornerShape(percent = 50)
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(pill)
            .background(GlassTokens.playbackSurface)
            .glassEdge(pill)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Sonariq Now Playing, rebuilt to the reference player's presentation:
 * a compact header (small artwork thumbnail, bold title and dim artist,
 * star like toggle, ••• menu) over an ambient artwork-tinted blurred
 * backdrop; in portrait player mode, page dots under the header, faint
 * blurred lyric lines as ambience, a floating glass vocal button, and a
 * bottom block of scrubber, large transport, volume and a utility row;
 * in lyrics mode the panel fills the screen under the same header; in
 * landscape a big artwork card on the left and the header, lyrics and
 * controls grouped in the right column.
 *
 * Every callback and every piece of playback, lyrics, queue, output and
 * party wiring is the wiring this screen has always had — only the
 * composition changed. See [ReferencePlayerChrome] for the new pieces.
 */
@Composable
@OptIn(ExperimentalHazeApi::class, ExperimentalHazeMaterialsApi::class)
fun NowPlayingScreen(
    song: Song,
    /** Who selected this track in the active Listen Together session. */
    playedBy: String? = null,
    isPlaying: Boolean,
    isLoading: Boolean,
    /**
     * The playhead, read only where it is drawn — never here. Passed as the
     * object rather than its value because a value read at this level is a
     * read in this function's own scope, and it ticks twice a second: the whole
     * player recomposed with it. See [PlaybackPosition].
     */
    position: PlaybackPosition,
    durationMs: Long,
    /** A version switch (video vs audio-only), triggered from the player's
     * menu, is fetching and measuring the target cut. */
    audioVersionSwitching: Boolean,
    /** The player has just swapped this item to a higher-quality source. */
    qualityUpgraded: Boolean,
    queue: List<Song>,
    queueIndex: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    autoplayEnabled: Boolean,
    /**
     * Vibe autoplay badge text, e.g. "Sad • staying in the vibe" — empty when
     * vibe autoplay is off or the session has no vibe yet. Passed down from
     * the host (which reads
     * [com.music.sonariq.playback.vibe.VibeUiState]) rather than read here,
     * because the vibe engine lives in the app module and this screen is
     * shared.
     */
    vibeBadgeText: String = "",
    /** "Not this vibe" long-press on the badge — records negative feedback. */
    onVibeBadgeLongPress: () -> Unit = {},
    signedIn: Boolean,
    accountName: String?,
    likeStatus: LikeStatus,
    onToggleLike: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    /**
     * A control the host has taken away was reached for anyway.
     *
     * The buttons are gone while a party is locked, but a swipe has no button
     * to remove — so the gesture answers instead of silently doing nothing.
     */
    onBlockedControl: () -> Unit,
    onSeek: (Long) -> Unit,
    /**
     * Seek to a fraction of the track, for the scrubber.
     *
     * Separate from [onSeek] because the scrubber is the one caller that knows
     * *where along the bar* it wants to go rather than a time. Converting that
     * here would use this screen's cached duration, which lags a track change by
     * however long the session takes to report the new one — long enough to drop
     * the handle on a bar still scaled to the previous song and seek to the
     * wrong fraction of the current one. The conversion belongs wherever the
     * freshest duration is.
     */
    onSeekFraction: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    /**
     * A queue row started or stopped being dragged.
     *
     * Lets the caller tell a jam's party sync that a reorder is in progress, so
     * it can hold its publish until the row is dropped instead of sending one
     * for every neighbour the drag crosses. See [PartySync.beginQueueDrag].
     */
    onQueueDragActiveChange: (Boolean) -> Unit = {},
    onClearQueue: () -> Unit,
    onOpenMenu: () -> Unit,
    /**
     * Opens the song share sheet. The artwork URL is handed over because the
     * card is rendered from the same sleeve the player is showing — the host
     * must not re-resolve it and risk a different picture.
     *
     * No-op by default; [shareSongEnabled] gates the button so platforms
     * without a share target draw nothing.
     */
    onShareSong: (artworkUrl: String?) -> Unit = {},
    /** Whether the share button is drawn — true where a host implements [onShareSong]. */
    shareSongEnabled: Boolean = false,
    onOpenAlbum: (String) -> Unit,
    /**
     * Opens an artist's page. [browseId] is null for anyone but the lead credit:
     * a track carries a channel id for its main artist and nothing for the rest,
     * so the host resolves those by [name].
     */
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    /** Return to the queue-level page named by the caption above the player. */
    onOpenPlaybackSource: () -> Unit,
    /**
     * Open Listen Together, from the party half of the output capsule.
     *
     * The player does not decide whether it has to get out of the way first:
     * the settings page it opens is drawn under the player sheet, and closing
     * the sheet is the caller's business.
     */
    onListenTogether: () -> Unit,
    lyrics: List<LyricLine>?,
    lyricsSource: LyricsSource?,
    lyricsUnavailable: Boolean,
    lyricsOffsetOpen: Boolean,
    onDismissLyricsOffset: () -> Unit,
    /** The width of the window the player is in — see [fullBleedArtworkAvailable]. */
    windowWidth: Dp,
    /**
     * The window's height, alongside [windowWidth] — needed for exactly one
     * thing: telling a portrait window apart from a landscape one, in
     * [landscapePlayerAvailable]. Width alone can't; a big tablet's portrait
     * width comfortably clears a phone's landscape width.
     */
    windowHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = rememberHaptics()

    // Remote tracks whose art lives inside the file resolve it here, once —
    // every surface below reads the same value rather than each triggering
    // its own extraction.
    val remoteArt = rememberRemoteArtworkUrl(song)
    // This is produced by the palette's existing 128 px decode and cache. It
    // samples the upper band rather than the whole sleeve because that is what
    // lies beneath the status bar when the player expands to full bleed.
    val artTopLuminance = rememberArtworkTopBandLuminance(remoteArt, ART_PX)
    val artworkStatusScrimAlpha = topBandScrimAlpha(artTopLuminance)

    // Media-player convention: the player always owns light status icons. The
    // top treatment below, rather than a window-flag flip per cover, provides
    // their contrast and stays visually stable through artwork transitions.
    StatusBarIcons(dark = false)

    // Kept local to the player: a modal player is not in the page's Haze
    // source tree, so it needs its own source for the same frosted material as
    // the bottom navigation pill.
    val playerHaze = remember { HazeState() }
    var showAudioPipeline by remember { mutableStateOf(false) }
    var showCast by remember { mutableStateOf(false) }
    var showAudioOutput by remember { mutableStateOf(false) }
    // Gated on the Bluetooth permission the first time — see [rememberOutputPicker].
    val openAudioOutput = rememberOutputPicker { showAudioOutput = true }
    var showVocalRemover by remember { mutableStateOf(false) }
    // Listening in a party whose host has taken the controls: the transport
    // keeps only play/pause, which from here moves this device alone.
    val controlsLocked = rememberControlsLocked()
    var showListenTogetherMembers by remember { mutableStateOf(false) }
    // Who's actually in the party is worth a look before the settings page —
    // see [ListenTogetherMembersSheet]. Only meaningful once there is a party
    // to show, so the pill and the caption fall back to [onListenTogether]
    // itself (create/join) when there isn't one.
    val openListenTogetherMembers: () -> Unit = { showListenTogetherMembers = true }
    val party by PlayerPlatform.host.party.collectAsStateWithLifecycle()
    val openParty: () -> Unit = {
        if (party.inParty) openListenTogetherMembers() else onListenTogether()
    }

    val syncedLyricsEnabled by PlayerSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsOffsetMs by PlayerSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    val lyricsOffsetOverrides by PlayerSettings.lyricsOffsetOverrides.collectAsStateWithLifecycle()
    // A lambda, not a value: read by the lyric strip and panel in scopes of
    // their own, so a tick recomposes them and not the player around them.
    val lyricsPlayhead = rememberLyricPlayhead(position, song.videoId)
    val seekToLyric: (Long) -> Unit = { lineTimeMs ->
        onSeek(
            adjustedLyricsSeekTarget(
                lineTimeMs,
                resolveLyricsOffsetMs(lyricsOffsetOverrides, lyricsOffsetMs, song.videoId),
            ),
        )
    }
    val hideVolumeBar by PlayerSettings.hideVolumeBar.collectAsStateWithLifecycle()
    val hideSongStatus by PlayerSettings.hideSongStatus.collectAsStateWithLifecycle()
    val reduceDynamicBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val lyricsBlurSetting by PlayerSettings.lyricsBlur.collectAsStateWithLifecycle()
    // The ambient lines blur only while the same settings that gate the
    // panel's depth blur allow any blur at all.
    val ambientBlurEnabled = lyricsBlurSetting && !reduceDynamicBlur && renderEffectBlurSupported

    // Animated cover art: the looping video some labels publish alongside a
    // release. A miss is the normal answer — see CanvasRepository, which is
    // also where the "is this actually the right track" check lives. In the
    // rebuilt player the clip is the portrait backdrop itself (and the
    // landscape artwork card's picture), rather than a sleeve treatment.
    val mixing by PlayerSettings.smartMixInProgress.collectAsStateWithLifecycle()
    val canvas = rememberCanvasArtwork(song)
    var canvasAspect by remember(canvas) { mutableFloatStateOf(0f) }
    val canvasRenderedState = remember(canvas?.url) { mutableStateOf(false) }
    var canvasRendered by canvasRenderedState
    var canvasFrame by remember(canvas?.url) { mutableStateOf<ImageBitmap?>(null) }
    // How much of the still artwork the clip is covering, reported by the clip
    // itself — used for the scrim over a portrait clip.
    val canvasCover = remember(canvas?.url) { mutableFloatStateOf(0f) }
    // v1.5's backdrop, kept behind a switch — see [PlayerSettings.legacyMeshGradient].
    val legacyMesh by PlayerSettings.legacyMeshGradient.collectAsStateWithLifecycle()
    // The animated canvas backdrop — see [CanvasBackdrop].
    val canvasBackdropEnabled by PlayerSettings.canvasBackdropEnabled.collectAsStateWithLifecycle()
    // Double-tap the artwork area's left/right half to seek ∓10 seconds —
    // see [PlayerArtworkGestures].
    val artworkGesturesEnabled by PlayerSettings.artworkGesturesEnabled.collectAsStateWithLifecycle()
    val meshRefreshMs = MESH_REFRESH_MS

    val scrub = rememberPlayerScrub(song.videoId, position, durationMs)
    // Read by the scrubber itself — see [PlayerScrubber.shown].
    val shown: () -> Float = { scrub.shown(position.positionMs, durationMs) }
    // Back restarts the track rather than stepping back once it is a few
    // seconds in. Derived, so this scope hears about the playhead once, when
    // it crosses that point, rather than on every tick.
    val pastRestartPoint by remember(position) {
        derivedStateOf { position.positionMs > BACK_RESTARTS_AFTER_MS }
    }
    val volume = rememberPlayerVolume()
    // The live visualizer: the artwork area's alternate face, reached by
    // tapping the ambient area or from the header's overflow.
    var visualizerShown by remember { mutableStateOf(false) }
    val visualizerCanRun = rememberVisualizerCanRun()

    // The lyrics and queue live inside the player rather than in sheets.
    // Read once when this expanded-player instance is created; every change
    // is written below, so reopening the player returns to the same surface.
    val restoredPlayerScreen = remember { PlayerSettings.lastPlayerScreen.value }
    var queueOpen by remember {
        mutableStateOf(restoredPlayerScreen == LastPlayerScreen.QUEUE)
    }
    var lyricsOpen by remember {
        mutableStateOf(restoredPlayerScreen == LastPlayerScreen.LYRICS)
    }
    // Whether the lyrics or queue list is actively mid-scroll. The player's
    // own swipe gestures are suppressed for as long as either is true.
    var lyricsScrolling by remember { mutableStateOf(false) }
    var queueScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(lyricsOpen) { if (!lyricsOpen) lyricsScrolling = false }
    LaunchedEffect(queueOpen) { if (!queueOpen) queueScrolling = false }
    val panelScrolling = lyricsScrolling || queueScrolling
    var lyricsControlsOpen by remember {
        mutableStateOf(restoredPlayerScreen == LastPlayerScreen.LYRICS)
    }
    var queueControlsOpen by remember { mutableStateOf(true) }
    LaunchedEffect(lyricsOpen, queueOpen) {
        PlayerSettings.setLastPlayerScreen(
            when {
                lyricsOpen -> LastPlayerScreen.LYRICS
                queueOpen -> LastPlayerScreen.QUEUE
                else -> LastPlayerScreen.MAIN
            },
        )
        if (queueOpen) queueControlsOpen = true
    }
    val openLyrics: () -> Unit = {
        lyricsControlsOpen = true
        lyricsOpen = true
        queueOpen = false
    }
    val closeLyrics: () -> Unit = {
        lyricsControlsOpen = false
        lyricsOpen = false
    }
    val toggleLyrics: () -> Unit = {
        if (lyricsOpen) closeLyrics() else openLyrics()
    }
    val openQueue: () -> Unit = {
        queueControlsOpen = true
        queueOpen = true
        lyricsOpen = false
        lyricsControlsOpen = false
    }
    val toggleQueue: () -> Unit = {
        if (queueOpen) queueOpen = false else openQueue()
    }
    // Present when the reader arrives, out of the way once they are actually
    // reading: left alone for a few seconds the bottom block stands down and
    // gives the words the whole screen; scrolling up brings it back.
    LaunchedEffect(lyricsOpen, lyricsControlsOpen, scrub.scrubbing, volume.dragging) {
        if (lyricsOpen && lyricsControlsOpen && !scrub.scrubbing && !volume.dragging) {
            delay(LYRICS_CONTROLS_IDLE_MS)
            lyricsControlsOpen = false
        }
    }
    val lyricsLoadingLines = stringArrayResource(Res.array.lyrics_loading_lines)
    val lyricsLoadingText = remember(song.videoId) { lyricsLoadingLines.random() }
    val lyricsTranslation = rememberLyricsTranslation(
        trackId = song.videoId,
        lyrics = lyrics,
        lyricsSource = lyricsSource,
        lyricsUnavailable = lyricsUnavailable,
        loadingText = lyricsLoadingText,
        haptics = haptics,
    )
    // The drawer holding the finished card, set the moment Share is confirmed.
    // Nothing is drawn here: the picture is a bitmap and a canvas, which is the
    // phone's to make, so the request is handed down and the sheet comes back
    // through [PlayerHost.LyricsShareSheet].
    var lyricsShare by remember { mutableStateOf<LyricsShareRequest?>(null) }
    val lyricsShareEnabled = lyricsShareAvailable
    val lyricPicker = rememberLyricsPicker(
        song = song,
        lines = lyrics,
        subLines = lyricsTranslation.subLines,
        artworkUrl = remoteArt,
        haptics = haptics,
        onCard = { lyricsShare = it },
    )
    // Copy for the pick bar: the picked lines' text, in song order, onto the
    // clipboard with a toast.
    val clipboard = LocalClipboardManager.current
    val copiedMessage = stringResource(Res.string.copied)
    val copyPickedLyrics: () -> Unit = {
        val text = lyricPicker.picks.sorted()
            .mapNotNull { lyricsTranslation.displayedLyrics.getOrNull(it)?.text }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        if (text.isNotBlank()) {
            clipboard.setText(AnnotatedString(text))
            PlayerPlatform.host.showMessage(copiedMessage)
        }
    }
    // A brief, non-modal confirmation that the three-dot menu now contains a
    // way back to the original rendition. The control keeps its usual
    // action — opening the menu — so the cue teaches rather than surprises.
    var showRevertCue by remember(song.videoId) { mutableStateOf(false) }
    LaunchedEffect(song.videoId, qualityUpgraded) {
        if (!qualityUpgraded) {
            showRevertCue = false
            return@LaunchedEffect
        }
        showRevertCue = true
        delay(REVERT_CUE_MS)
        showRevertCue = false
    }

    // Lyrics are meant to be read continuously, so hold off the device's
    // normal screen timeout — but only while the panel is actually up.
    KeepScreenOn(enabled = lyricsOpen)

    // Back out of the lyrics panel to the player, and only from the player
    // itself out to the mini player. Controls being visible must not insert
    // an extra navigation level.
    PlayerBackHandler(enabled = lyricsOpen, onBack = closeLyrics)
    PlayerBackHandler(enabled = queueOpen) { queueOpen = false }
    PlayerBackHandler(enabled = showAudioOutput) { showAudioOutput = false }
    PlayerBackHandler(enabled = showVocalRemover) { showVocalRemover = false }
    PlayerBackHandler(enabled = showListenTogetherMembers) { showListenTogetherMembers = false }
    PlayerBackHandler(enabled = showAudioPipeline) { showAudioPipeline = false }
    PlayerBackHandler(enabled = showCast) { showCast = false }
    PlayerBackHandler(enabled = lyricsOffsetOpen, onBack = onDismissLyricsOffset)
    PlayerBackHandler(enabled = lyricsShare != null) { lyricsShare = null }
    // Backing out of a pick drops the pick, not the panel.
    PlayerBackHandler(enabled = lyricPicker.picking) { lyricPicker.cancel() }

    // Horizontal fling anywhere on the player skips tracks — the portrait
    // player hangs it on the whole screen, the landscape one on the artwork
    // card alone (the right column there is full of horizontal sliders and
    // a lyric list that should not be one stray sideways drag away from
    // changing the song). The sleeve used to slide with the finger; the
    // ambient area now carries the hint glyph instead.
    val swipeThreshold = with(density) { 72.dp.toPx() }
    val swipeSettle = remember { Animatable(0f) }
    val swipeTargets = remember { Channel<Float>(Channel.CONFLATED) }
    LaunchedEffect(swipeTargets) {
        for (target in swipeTargets) {
            val newest = swipeTargets.tryReceive().getOrNull() ?: target
            launch {
                if (newest != swipeSettle.targetValue) {
                    swipeSettle.animateTo(newest, spring(stiffness = Spring.StiffnessMediumLow))
                }
            }
        }
    }
    val setSwipeOffset: (Float) -> Unit = { swipeTargets.trySend(it) }
    val swipeHintShown by remember(swipeThreshold) {
        derivedStateOf { abs(swipeSettle.value) / swipeThreshold > 0.01f }
    }
    val swipeHintNext by remember { derivedStateOf { swipeSettle.value > 0f } }
    val skipSwipeGesture = Modifier.pointerInput(showAudioPipeline, showCast, panelScrolling, controlsLocked) {
        if (showAudioPipeline || showCast || panelScrolling) return@pointerInput
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragCancel = { setSwipeOffset(0f) },
            onDragEnd = {
                val crossed = total <= -swipeThreshold || total >= swipeThreshold
                when {
                    controlsLocked -> if (crossed) onBlockedControl()
                    total <= -swipeThreshold -> {
                        haptics.play(Haptic.SkipNext)
                        onNext()
                    }
                    total >= swipeThreshold -> {
                        haptics.play(Haptic.SkipPrevious)
                        onPrevious()
                    }
                }
                setSwipeOffset(0f)
            },
            onHorizontalDrag = { _, delta ->
                total += delta
                // Damped: it's a hint, not a drag-to-position.
                setSwipeOffset(total * 0.35f)
            },
        )
    }

    val onScrub: (Float) -> Unit = scrub::drag
    val onScrubFinished: () -> Unit = {
        haptics.play(Haptic.Select)
        scrub.release(onSeekFraction)
    }
    val onVolumeChange: (Float) -> Unit = volume::drag
    val onVolumeChangeFinished: () -> Unit = volume::release

    // Where the caption above the header leads: the party for a track
    // someone else queued, the queue itself for a queue-built session, and
    // otherwise back to the album or playlist the session was started from.
    val openPlaybackOrigin: () -> Unit = {
        if (playedBy != null) {
            onListenTogether()
        } else if (song.playbackSourceType == PlaybackSourceType.QUEUE) {
            openQueue()
        } else {
            onOpenPlaybackSource()
        }
    }

    val fullBleedArt by PlayerSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    val art = rememberPlayerArtwork(remoteArt)
    val fullCoverShowing = fullBleedArt && art.loaded
    val spotifyCanvasOnPhone = canvas?.source == CanvasSource.SPOTIFY &&
        playerFillsWindow(windowWidth)
    // The cached blurred-artwork bitmap: the backdrop in every mode of the
    // rebuilt player, prepared once per cover and crossfaded as bitmaps —
    // never a per-frame blur of live content.
    val fullArtworkBlurImage = rememberFullArtworkBlurImage(
        imageUrl = remoteArt,
        artPx = ART_PX,
        prepare = true,
    )
    val currentFullArtworkBlurImage = rememberUpdatedState(fullArtworkBlurImage)
    val fullArtworkBlurContent = remember {
        movableContentOf<Modifier> { backdropModifier ->
            FullArtworkBlurBackdrop(
                image = currentFullArtworkBlurImage.value,
                modifier = backdropModifier,
            )
        }
    }
    val legacyMeshBackdrop = legacyMesh && !spotifyCanvasOnPhone
    val canvasBackdropOn = canvasBackdropEnabled && canvas == null && !fullCoverShowing
    var playerBounds by remember { mutableStateOf(IntSize.Zero) }
    val versionAligning by PlayerSettings.versionAlignmentInProgress.collectAsStateWithLifecycle()
    val versionSwitching = versionAligning || audioVersionSwitching
    val transitionWindow by PlayerSettings.smartTransitionWindow.collectAsStateWithLifecycle()
    val vocalLevel by PlayerSettings.vocalLevel.collectAsStateWithLifecycle()

    // The backdrop, shared by both shapes: the cached blurred artwork fills
    // the whole screen behind header and controls (the sheets' frost source
    // included), the legacy mesh and the animated canvas backdrop keep
    // their switches, and a provider clip — where there is one — plays as
    // the portrait backdrop itself. [showVideo] is portrait only: in
    // landscape the clip lives in the artwork card instead, so it is never
    // decoded twice.
    val backdrop: @Composable (Boolean, Modifier) -> Unit = { showVideo, backdropModifier ->
        Box(Modifier.fillMaxSize()) {
            fullArtworkBlurContent(backdropModifier.hazeSource(playerHaze))
            if (legacyMeshBackdrop) {
                MeshGradientBackground(
                    palette = rememberArtworkColors(remoteArt, canvasFrame),
                    trackKey = song.videoId,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (canvasBackdropOn) {
                CanvasBackdrop(
                    mesh = rememberArtworkMesh(imageUrl = remoteArt, canvasFrame = canvasFrame),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // A legibility gradient over the blurred artwork: darker at the
            // foot, where the controls sit, in every mode.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.18f),
                            0.5f to Color.Black.copy(alpha = 0.28f),
                            1f to Color.Black.copy(alpha = 0.52f),
                        ),
                    ),
            )
            if (showVideo && canvas != null && !lyricsOpen && !queueOpen) {
                val clip = canvas
                CanvasArtworkPlayer(
                    canvas = clip,
                    isPlaying = isPlaying,
                    pausedForTransition = false,
                    contentMode = if (spotifyCanvasOnPhone) {
                        CanvasContentMode.CROP
                    } else {
                        CanvasContentMode.FIT_PORTRAIT
                    },
                    alignPortraitTop = !spotifyCanvasOnPhone,
                    onAspectRatioChanged = { canvasAspect = it },
                    portraitRevealBounds = playerBounds,
                    presentationAlpha = { 1f },
                    onRenderedChanged = { canvasRendered = it },
                    onFrameCaptured = {
                        if (legacyMesh) canvasFrame = it
                    },
                    refreshFrameEveryMs = if (legacyMesh) meshRefreshMs else null,
                    onCoverChanged = { canvasCover.floatValue = it },
                    bottomFade = if (spotifyCanvasOnPhone) 0f else 0.42f,
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(playerHaze),
                )
                // A plain scrim over a portrait clip so the header and the
                // bottom block read against any frame.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = canvasCover.floatValue }
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.25f),
                                0.40f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.55f),
                            ),
                        ),
                )
            }
        }
    }

    // The header both shapes open with — see [ReferencePlayerHeader]. The
    // ••• tap is the song menu as always; its long-press overflow carries
    // the player-local utilities (share, vocal remover, audio pipeline,
    // visualizer, Listen Together).
    val header: @Composable () -> Unit = {
        ReferencePlayerHeader(
            song = song,
            artRequest = art.request,
            signedIn = signedIn,
            likeStatus = likeStatus,
            showRevertCue = showRevertCue,
            marqueeEnabled = !lyricsOpen && !queueOpen,
            onToggleLike = onToggleLike,
            onOpenMenu = onOpenMenu,
            onOpenAlbum = onOpenAlbum,
            onOpenArtist = onOpenArtist,
            onShareSong = if (shareSongEnabled) {
                { onShareSong(remoteArt ?: song.thumbnailUrl) }
            } else {
                null
            },
            onOpenVocalRemover = if (PlayerSettings.vocalRemoverAvailable) {
                { showVocalRemover = true }
            } else {
                null
            },
            onOpenAudioPipeline = { showAudioPipeline = true },
            onToggleVisualizer = if (visualizerCanRun) {
                { visualizerShown = !visualizerShown }
            } else {
                null
            },
            visualizerShown = visualizerShown,
            onListenTogether = openParty,
            badge = {
                if (vibeBadgeText.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    VibeBadge(
                        text = vibeBadgeText,
                        onLongPress = onVibeBadgeLongPress,
                    )
                }
            },
        )
    }

    // The bottom block both shapes end on: (in lyrics mode) the lyrics
    // status line, the waveform scrubber with its elapsed/remaining times
    // and the quality label between them, the five-up transport (shuffle
    // and repeat flanking previous/play/next, as the deck had them), the
    // volume row where Settings shows it, the utility row (lyrics, output,
    // party, queue), and the output caption under it.
    val bottomBlock: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            if (lyricsOpen) {
                LyricsStatusWithChange(
                    status = lyricsTranslation.status,
                    onStatusClick = {},
                    lines = lyricsTranslation.displayedLyrics,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
            }
            PlayerScrubber(
                shown = shown,
                durationMs = durationMs,
                seedKey = song.videoId,
                loading = versionSwitching,
                mixing = mixing && !scrub.scrubbing,
                transitionWindow = transitionWindow
                    ?.takeIf { !scrub.scrubbing && it.end > it.start }
                    ?.let { it.start..it.end },
                onScrub = onScrub,
                onScrubFinished = onScrubFinished,
            ) {
                PlaybackQualityLabel(
                    song = song,
                    isLoading = isLoading,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 8.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            AppleTransportRow(
                isPlaying = isPlaying,
                isLoading = isLoading || audioVersionSwitching,
                previousEnabled = !controlsLocked &&
                    (hasPrevious || pastRestartPoint),
                nextEnabled = !controlsLocked && hasNext,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
            )
            Spacer(Modifier.height(10.dp))
            if (!hideVolumeBar) {
                VolumeRow(
                    value = { volume.level.value },
                    onValueChange = onVolumeChange,
                    onValueChangeFinished = onVolumeChangeFinished,
                )
            } else {
                Spacer(Modifier.height(VOLUME_ROW_HEIGHT))
            }
            Spacer(Modifier.height(8.dp))
            ReferenceUtilityRow(
                lyricsOpen = lyricsOpen,
                queueOpen = queueOpen,
                partyActive = party.inParty,
                onToggleLyrics = toggleLyrics,
                onOpenOutput = openAudioOutput,
                onOpenParty = openParty,
                onToggleQueue = toggleQueue,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                OutputCaption(
                    accountName = accountName,
                    onOpenOutput = openAudioOutput,
                    onOpenMembers = openListenTogetherMembers,
                )
            }
        }
    }

    // The drawers and dialogs the player raises over itself. Mounted by
    // whichever layout is on screen — they are overlays over the player, not
    // part of either shape of it.
    val playerOverlays: @Composable () -> Unit = {
        if (showAudioOutput) {
            AudioOutputSheet(
                hazeState = playerHaze,
                accountName = accountName,
                onDismiss = { showAudioOutput = false },
                onOpenPipeline = { showAudioPipeline = true },
                onOpenCast = { showCast = true },
            )
        }
        if (showAudioPipeline) {
            AudioPipelineDialog(
                hazeState = playerHaze,
                isPlaying = isPlaying,
                onDismiss = { showAudioPipeline = false },
            )
        }
        if (showCast) {
            PlayerPlatform.host.CastDialog(
                hazeState = playerHaze,
                onDismiss = { showCast = false },
            )
        }
        if (showListenTogetherMembers) {
            ListenTogetherMembersSheet(
                hazeState = playerHaze,
                onDismiss = { showListenTogetherMembers = false },
                onManage = {
                    showListenTogetherMembers = false
                    onListenTogether()
                },
            )
        }
        if (lyricsOffsetOpen) {
            LyricsOffsetSheet(
                hazeState = playerHaze,
                onDismiss = onDismissLyricsOffset,
                videoId = song.videoId,
            )
        }
        if (showVocalRemover && PlayerSettings.vocalRemoverAvailable) {
            VocalRemoverSheet(
                hazeState = playerHaze,
                onDismiss = { showVocalRemover = false },
            )
        }
        lyricsShare?.let { request ->
            PlayerPlatform.host.LyricsShareSheet(
                hazeState = playerHaze,
                request = request,
                onDismiss = { lyricsShare = null },
            )
        }
    }

    // The lyrics panel, shared by both shapes: the panel itself (with its
    // own search and appearance pills), the romanize/translate/share discs
    // floated over its foot while the controls are up, and the pick bar.
    val lyricsContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            LyricsTranslationMotion(
                trigger = lyricsTranslation.transition,
                reduceMotion = lyricsTranslation.reduceMotion,
                modifier = Modifier.fillMaxSize(),
            ) { particleProgress ->
                LyricsPanel(
                    lines = lyrics.orEmpty(),
                    subLines = lyricsTranslation.subLines,
                    trackKey = song.videoId,
                    playhead = lyricsPlayhead,
                    looking = !lyricsUnavailable,
                    isPlaying = isPlaying && position.advancing,
                    active = true,
                    onSeekToLine = seekToLyric,
                    controlsOpen = lyricsControlsOpen,
                    onRevealControls = { lyricsControlsOpen = true },
                    onHideControls = { lyricsControlsOpen = false },
                    translationProgress = particleProgress,
                    onScrollingChange = { lyricsScrolling = it },
                    canPick = lyricsShareEnabled,
                    picking = lyricPicker.picking,
                    picked = lyricPicker.picks,
                    onPickLine = lyricPicker.pick,
                    onTogglePick = lyricPicker.toggle,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (lyricsControlsOpen && !lyricPicker.picking) {
                Box(
                    modifier = Modifier.align(Alignment.BottomStart),
                ) {
                    RomanizationToggleButton(
                        state = lyricsTranslation.romanizationState,
                        showingRomanization = lyricsTranslation.showingRomanization,
                        enabled = !lyrics.isNullOrEmpty(),
                        onClick = lyricsTranslation.toggleRomanization,
                    )
                }
                Box(
                    modifier = Modifier.align(Alignment.BottomEnd),
                ) {
                    TranslationToggleButton(
                        state = lyricsTranslation.translationState,
                        showingTranslation = lyricsTranslation.showingTranslation,
                        enabled = !lyrics.isNullOrEmpty(),
                        onClick = lyricsTranslation.toggleTranslation,
                    )
                }
                if (lyricsShareEnabled) {
                    Box(
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        LyricsShareButton(
                            enabled = !lyrics.isNullOrEmpty(),
                            onClick = lyricPicker.beginPicking,
                        )
                    }
                }
            }
            if (lyricPicker.picking) {
                LyricsPickBar(
                    shareEnabled = lyricPicker.picks.isNotEmpty() &&
                        !lyricPicker.overBudget,
                    onCancel = lyricPicker.cancel,
                    onShare = lyricPicker.share,
                    onCopy = copyPickedLyrics,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    // The queue panel, shared by both shapes.
    val queueContent: @Composable (Boolean) -> Unit = { collapseOnScroll ->
        InlineQueue(
            queue = queue,
            currentIndex = queueIndex,
            autoplayEnabled = autoplayEnabled,
            controlsLocked = controlsLocked,
            onJumpTo = onJumpTo,
            onRemove = onRemoveFromQueue,
            onMove = onMoveInQueue,
            onClear = onClearQueue,
            onToggleAutoplay = onToggleAutoplay,
            onScrollingChange = { queueScrolling = it },
            onDragActiveChange = onQueueDragActiveChange,
            collapsePlayerOnScroll = collapseOnScroll,
            onRevealPlayer = { queueControlsOpen = true },
            onHidePlayer = { queueControlsOpen = false },
            modifier = Modifier.fillMaxSize(),
        )
    }

    // ---- Landscape: artwork card left, header/lyrics/controls right ----
    if (landscapePlayerAvailable(windowWidth, windowHeight)) {
        val panelOpen = lyricsOpen || queueOpen
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            LandscapePlayerLayout(
                background = { backgroundModifier -> backdrop(false, backgroundModifier) },
                artwork = { artworkModifier ->
                    LandscapeArtwork(
                        artRequest = art.request,
                        artLoaded = art.loaded,
                        onArtState = art::onState,
                        canvas = canvas,
                        canvasRendered = canvasRendered,
                        isPlaying = isPlaying,
                        onCanvasRenderedChange = { canvasRendered = it },
                        pausedForTransition = panelOpen,
                        modifier = artworkModifier
                            .then(skipSwipeGesture)
                            .artworkDoubleTapSeek(
                                enabled = artworkGesturesEnabled,
                                onSeekBy = { deltaMs ->
                                    onSeek((position.positionMs + deltaMs).coerceAtLeast(0L))
                                },
                            )
                            .clickable(
                                enabled = panelOpen,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                queueOpen = false
                                closeLyrics()
                            },
                    ) {
                        if (visualizerShown && visualizerCanRun) {
                            PlayerVisualizerFull(
                                isPlaying = isPlaying,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (!panelOpen) {
                            SleeveNerdStats(
                                song = song,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                },
                header = header,
                middle = {
                    when {
                        queueOpen -> queueContent(false)
                        lyricsOpen -> {
                            LandscapeLyricsPane(
                                hasLyrics = lyricsTranslation.displayedLyrics.isNotEmpty(),
                                placeholder = if (lyricsUnavailable) {
                                    stringResource(Res.string.lyrics_not_available)
                                } else {
                                    lyricsLoadingText
                                },
                                status = lyricsTranslation.status,
                                onStatusClick = {},
                                lines = lyricsTranslation.displayedLyrics,
                                picking = lyricPicker.picking,
                                pickBar = {
                                    LyricsPickBar(
                                        shareEnabled = lyricPicker.picks.isNotEmpty() &&
                                            !lyricPicker.overBudget,
                                        onCancel = lyricPicker.cancel,
                                        onShare = lyricPicker.share,
                                        onCopy = copyPickedLyrics,
                                    )
                                },
                                romanizationToggle = {
                                    RomanizationToggleButton(
                                        state = lyricsTranslation.romanizationState,
                                        showingRomanization = lyricsTranslation.showingRomanization,
                                        enabled = !lyrics.isNullOrEmpty(),
                                        onClick = lyricsTranslation.toggleRomanization,
                                    )
                                },
                                translationToggle = {
                                    TranslationToggleButton(
                                        state = lyricsTranslation.translationState,
                                        showingTranslation = lyricsTranslation.showingTranslation,
                                        enabled = !lyrics.isNullOrEmpty(),
                                        onClick = lyricsTranslation.toggleTranslation,
                                    )
                                },
                                panel = { panelModifier ->
                                    LyricsTranslationMotion(
                                        trigger = lyricsTranslation.transition,
                                        reduceMotion = lyricsTranslation.reduceMotion,
                                        modifier = panelModifier,
                                    ) { particleProgress ->
                                        LyricsPanel(
                                            lines = lyrics.orEmpty(),
                                            subLines = lyricsTranslation.subLines,
                                            trackKey = song.videoId,
                                            playhead = lyricsPlayhead,
                                            looking = !lyricsUnavailable,
                                            isPlaying = isPlaying && position.advancing,
                                            onSeekToLine = seekToLyric,
                                            controlsOpen = true,
                                            onRevealControls = {},
                                            onHideControls = {},
                                            translationProgress = particleProgress,
                                            canPick = lyricsShareEnabled,
                                            picking = lyricPicker.picking,
                                            picked = lyricPicker.picks,
                                            onPickLine = lyricPicker.pick,
                                            onTogglePick = lyricPicker.toggle,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                },
                            )
                        }
                        else -> {
                            Box(Modifier.fillMaxSize()) {
                                if (visualizerShown && visualizerCanRun) {
                                    PlayerVisualizerFull(
                                        isPlaying = isPlaying,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    AmbientLyricLines(
                                        lines = lyricsTranslation.displayedLyrics,
                                        playhead = lyricsPlayhead,
                                        loadingText = lyricsLoadingText,
                                        lyricsUnavailable = lyricsUnavailable,
                                        blurEnabled = ambientBlurEnabled,
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .fillMaxWidth(),
                                    )
                                }
                                if (swipeHintShown) {
                                    Icon(
                                        imageVector = if (swipeHintNext) {
                                            Icons.Rounded.FastForward
                                        } else {
                                            Icons.Rounded.FastRewind
                                        },
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(20.dp)
                                            .graphicsLayer {
                                                alpha = (abs(swipeSettle.value) / swipeThreshold)
                                                    .coerceIn(0f, 1f) * 0.85f
                                            },
                                    )
                                }
                                if (PlayerSettings.vocalRemoverAvailable) {
                                    FloatingVocalButton(
                                        highlighted = vocalLevel < DEFAULT_VOCAL_LEVEL,
                                        onClick = { showVocalRemover = true },
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(bottom = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                },
                bottom = { bottomBlock() },
                // Lyrics open in landscape is the reference's composition:
                // artwork card, header and the words, nothing else — the
                // whole control block steps aside and the lyrics take the
                // column. Back and a tap on the artwork card (above) are
                // the way back to the player.
                showBottom = !lyricsOpen,
            )
            playerOverlays()
        }
        return
    }

    // ---- Portrait: header, page dots, ambience / lyrics / queue, bottom ----
    val playerSubviewOpen = lyricsOpen || queueOpen || lyricsOffsetOpen ||
        showAudioPipeline || showCast || showAudioOutput || showVocalRemover ||
        lyricsShare != null
    val topGradientAlpha = if (playerSubviewOpen) {
        maxOf(artworkStatusScrimAlpha, SUBVIEW_STATUS_SCRIM_MIN_ALPHA)
    } else {
        artworkStatusScrimAlpha
    }
    val topScrimBrush = remember(topGradientAlpha) {
        val steps = 8
        val gradientColors = List(steps) { index ->
            val progress = index / (steps - 1).toFloat()
            val factor = (1f - progress).toDouble().pow(1.5).toFloat()
            Color.Black.copy(alpha = topGradientAlpha * factor)
        }
        Brush.verticalGradient(gradientColors)
    }
    val bottomVisible = when {
        lyricPicker.picking -> false
        lyricsOpen -> lyricsControlsOpen
        queueOpen -> queueControlsOpen
        else -> true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { playerBounds = it }
            .background(Color.Black),
    ) {
        backdrop(true, Modifier.fillMaxSize())
        // The status-bar scrim: a transparent top gradient whose opacity
        // follows the artwork's own top band.
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(topScrimBrush),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .then(skipSwipeGesture)
                .artworkDoubleTapSeek(
                    enabled = artworkGesturesEnabled,
                    onSeekBy = { deltaMs ->
                        onSeek((position.positionMs + deltaMs).coerceAtLeast(0L))
                    },
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                ReferenceDragHandle()
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = PLAYER_GUTTER),
            ) {
                if (!hideSongStatus && !lyricsOpen && !queueOpen) {
                    PlaybackOriginCaption(
                        text = playbackOriginText(song, playedBy),
                        onClick = openPlaybackOrigin,
                        textAlign = TextAlign.Center,
                        contentPadding = PaddingValues(vertical = 2.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                header()
                when {
                    queueOpen -> {
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            queueContent(true)
                        }
                    }
                    lyricsOpen -> {
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            lyricsContent()
                        }
                    }
                    else -> {
                        Spacer(Modifier.height(14.dp))
                        ReferencePageDots(
                            pageCount = 3,
                            activePage = 0,
                            onSelectPage = { page ->
                                when (page) {
                                    1 -> openLyrics()
                                    2 -> openQueue()
                                }
                            },
                        )
                        // The middle area: ambient lyric lines over the
                        // backdrop (or the visualizer in their place). A
                        // tap toggles the visualizer where it can run; the
                        // lines themselves take no touches.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clickable(
                                    enabled = visualizerCanRun,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    haptics.play(Haptic.Tap)
                                    visualizerShown = !visualizerShown
                                },
                        ) {
                            if (visualizerShown && visualizerCanRun) {
                                PlayerVisualizerFull(
                                    isPlaying = isPlaying,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                AmbientLyricLines(
                                    lines = lyricsTranslation.displayedLyrics,
                                    playhead = lyricsPlayhead,
                                    loadingText = lyricsLoadingText,
                                    lyricsUnavailable = lyricsUnavailable,
                                    blurEnabled = ambientBlurEnabled,
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .fillMaxWidth(),
                                )
                            }
                            if (swipeHintShown) {
                                Icon(
                                    imageVector = if (swipeHintNext) {
                                        Icons.Rounded.FastForward
                                    } else {
                                        Icons.Rounded.FastRewind
                                    },
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(20.dp)
                                        .graphicsLayer {
                                            alpha = (abs(swipeSettle.value) / swipeThreshold)
                                                .coerceIn(0f, 1f) * 0.85f
                                        },
                                )
                            }
                            // The nerd-stats line's new home: the foot of
                            // the ambient area, where the sleeve's bottom
                            // used to carry it.
                            SleeveNerdStats(
                                song = song,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(bottom = 10.dp),
                            )
                            if (PlayerSettings.vocalRemoverAvailable) {
                                FloatingVocalButton(
                                    highlighted = vocalLevel < DEFAULT_VOCAL_LEVEL,
                                    onClick = { showVocalRemover = true },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(bottom = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = bottomVisible,
                enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 3 },
                exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { it / 3 },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PLAYER_GUTTER)
                        .padding(bottom = 14.dp),
                ) {
                    bottomBlock()
                }
            }
        }
        playerOverlays()
    }
}
