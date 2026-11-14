package com.music.sonariq.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.music.sonariq.data.canvas.CanvasArtwork
import com.music.sonariq.data.listentogether.PartyMember
import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.lyrics.LyricsAutoOffset
import com.music.sonariq.data.lyrics.LyricsSource
import com.music.sonariq.data.lyrics.lyricsLinesFingerprint
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.settings.AudioQuality
import com.music.sonariq.data.settings.LastPlayerScreen
import com.music.sonariq.data.settings.SmartAnalysis
import com.music.sonariq.data.settings.MixBlend
import com.music.sonariq.data.settings.TransitionWindow
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the player takes from the application it is drawn in.
 *
 * The player itself — its layout, its gestures, its animations, the lyric sheet
 * and the queue — is one piece of code for the phone and the desktop alike.
 * What differs is underneath it: where settings are kept, how a looping clip is
 * decoded, what an audio output is, which party this device is in. Each
 * application answers those once, here, and installs its answer with
 * [PlayerPlatform.install] before the player is first drawn.
 */
interface PlayerHost {
    val settings: PlayerSettingsSource

    /**
     * Where this platform reports the audio output's route and measured
     * latency for lyrics timing; see [OutputSyncSource]. Platforms with
     * no output story keep the default: no compensation, and no
     * output-sync section in the offset UI.
     */
    val outputSync: OutputSyncSource get() = NoOutputSync

    /** A Canvas already settled for [song], without a lookup. */
    fun cachedCanvas(song: Song): CanvasArtwork?

    /** Looks a Canvas up for [song]; null is the normal answer. */
    suspend fun canvasFor(song: Song): CanvasArtwork?

    /** The looping clip over the cover, decoded however this platform decodes video. */
    @Composable
    fun CanvasVideo(spec: CanvasVideoSpec, modifier: Modifier)

    /**
     * Artwork for [song], resolving covers that live inside a remote file
     * (WebDAV, SMB) where the platform can. Returns [Song.thumbnailUrl] otherwise.
     */
    @Composable
    fun rememberRemoteArtworkUrl(song: Song?): String?

    /** The system output level the player's volume bar drives, if it drives one. */
    val volume: SystemVolume

    /** The outputs the output drawer lists, the active one marked. */
    @Composable
    fun rememberAudioOutputs(): List<AudioOutputDevice>

    /**
     * Wraps opening the output drawer in whatever it needs first — on Android,
     * the Bluetooth permission that lets it name headphones.
     */
    @Composable
    fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit

    fun selectAudioOutput(id: Int?)

    /** The negotiated output format, as the pipeline row and the hi-res shine read it. */
    val outputFormat: StateFlow<OutputFormatUi>

    /** The Listen Together party this device is in, as the player draws it. */
    val party: StateFlow<PartyUi>

    suspend fun translateLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsTranslationResult

    suspend fun romanizeLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsRomanizationResult

    /** A short, non-blocking notice — a Toast on the phone. */
    fun showMessage(message: String)

    /** The full signal-chain readout, opened from the output drawer. */
    @Composable
    fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit)

    /**
     * Live audio capture for the Now Playing visualizer. [NoVisualizer]
     * where the platform captures nothing — the strip hides itself there.
     */
    val visualizer: VisualizerUi
        get() = NoVisualizer

    /** Casting to a network receiver. Absent, and so never drawn, where the platform has none. */
    val castState: StateFlow<CastUi> get() = NoCast

    /**
     * This device's own model name — e.g. "Nothing Phone (2)", "Pixel 8 Pro".
     * The output caption shows it when the music plays on this device itself,
     * the same identity the TV app shows on its home screen. Blank where the
     * platform does not name itself; the caption falls back as before.
     */
    val deviceModelName: String get() = ""

    /** The device picker the output drawer's Cast row opens, in the pipeline dialog's card. */
    @Composable
    fun CastDialog(hazeState: HazeState, onDismiss: () -> Unit) = Unit

    /**
     * Whether lines can be put on a share card here. False hides the pick mode
     * altogether rather than offering a mode with nowhere to put its result.
     */
    val lyricsShareAvailable: Boolean get() = false

    /**
     * The picked lines, drawn as a picture this platform can write out and send.
     * Absent, and so never called, where [lyricsShareAvailable] is false.
     */
    @Composable
    fun LyricsShareSheet(hazeState: HazeState, request: LyricsShareRequest, onDismiss: () -> Unit) = Unit
}

private val NoCast = MutableStateFlow(CastUi())

private val DefaultCanvasBackdropEnabled = MutableStateFlow(true)
private val DefaultArtworkGesturesEnabled = MutableStateFlow(true)
private val DefaultLyricsHighlightIntensity = MutableStateFlow(DEFAULT_LYRICS_HIGHLIGHT_INTENSITY)
private val DefaultLyricsBackground = MutableStateFlow(LyricsBackground.ARTWORK)
private val DefaultLyricsReducedMotion = MutableStateFlow(false)
private val DefaultLyricsAutoOffsets = MutableStateFlow<Map<String, LyricsAutoOffset>>(emptyMap())
private val DefaultLyricsRouteCalibrations =
    MutableStateFlow<Map<AudioOutputKind, Int>>(emptyMap())
private val DefaultLiquidGlassEnabled = MutableStateFlow(true)

/** What the output drawer needs to know about casting to draw its row. */
data class CastUi(
    /** Whether this device can cast at all. False hides the row. */
    val supported: Boolean = false,
    /** The receiver the music is on now, or null when it is on this device. */
    val connectedName: String? = null,
    val connecting: Boolean = false,
)

/** Where the installed [PlayerHost] lives. */
object PlayerPlatform {
    @Volatile
    private var installed: PlayerHost? = null

    val host: PlayerHost
        get() = installed ?: error("PlayerPlatform.install() was never called")

    fun install(host: PlayerHost) {
        installed = host
    }
}

/** The settings the player reads, named and typed as the phone's AppSettings has them. */
interface PlayerSettingsSource {
    val animatedCanvas: StateFlow<Boolean>
    val audioQualityCellular: StateFlow<AudioQuality>
    val audioQualityWifi: StateFlow<AudioQuality>
    val canvasBackdropEnabled: StateFlow<Boolean> get() = DefaultCanvasBackdropEnabled
    val artworkGesturesEnabled: StateFlow<Boolean> get() = DefaultArtworkGesturesEnabled
    val canvasOverCellular: StateFlow<Boolean>
    val fullBleedArtwork: StateFlow<Boolean>
    val hideSongStatus: StateFlow<Boolean>
    val hideVolumeBar: StateFlow<Boolean>
    val lastPlayerScreen: StateFlow<LastPlayerScreen>
    val legacyMeshGradient: StateFlow<Boolean>
    /** Whether Liquid Glass is on; platforms without the setting keep it on. */
    val liquidGlass: StateFlow<Boolean> get() = DefaultLiquidGlassEnabled
    /** Whether this platform can sample a backdrop blur for glass at all (Android 12+; desktop always). */
    val glassBlurCapable: Boolean get() = true
    val lyricsAutoScroll: StateFlow<Boolean>
    val lyricsBlur: StateFlow<Boolean>
    val lyricsFontSizeSp: StateFlow<Float>
    val lyricsKaraokeEffects: StateFlow<Boolean>
    /** How strong the playing line's bloom is, as a scale around its long-standing strength. */
    val lyricsHighlightIntensity: StateFlow<Float> get() = DefaultLyricsHighlightIntensity
    /** What the lyrics panel draws behind its lines. */
    val lyricsBackground: StateFlow<LyricsBackground> get() = DefaultLyricsBackground
    /** Lyrics-only reduced motion, on top of the app-wide reduce-animation setting. */
    val lyricsReducedMotion: StateFlow<Boolean> get() = DefaultLyricsReducedMotion
    val lyricsOffsetMs: StateFlow<Int>
    /** Per-track lyric offsets, videoId to offsetMs; a track without an entry follows [lyricsOffsetMs]. */
    val lyricsOffsetOverrides: StateFlow<Map<String, Int>>
    /**
     * Per-track *measured* lyric offsets, videoId to record — the auto
     * offset the AI measurement found for that track's exact lines, read
     * through [applicableAutoOffsetMs]. Written by the measurement job,
     * never by hand; a platform whose jobs do not measure keeps the
     * empty default and its lyrics are never auto-shifted.
     */
    val lyricsAutoOffsets: StateFlow<Map<String, LyricsAutoOffset>> get() = DefaultLyricsAutoOffsets
    /**
     * The listener's per-route output calibrations, route class to
     * offsetMs — the output-side half of the lyrics shift, kept per
     * route so a wireless calibration never reaches the speaker. Read
     * through [routeCalibrationMs]; see OutputSync.kt for the model.
     * Platforms without output tracking keep the empty default.
     */
    val lyricsRouteCalibrations: StateFlow<Map<AudioOutputKind, Int>>
        get() = DefaultLyricsRouteCalibrations
    val lyricsSourceOrder: StateFlow<List<LyricsSource>>
    val meteredConnection: StateFlow<Boolean?>
    val preferUsbDac: StateFlow<Boolean>
    val prioritizeSpotifyCanvas: StateFlow<Boolean>
    val reduceAnimation: StateFlow<Boolean>
    val reduceDynamicBlur: StateFlow<Boolean>
    val showNerdStats: StateFlow<Boolean>
    val smartAnalysis: StateFlow<SmartAnalysis>
    val smartFadeEnabled: StateFlow<Boolean>
    /** True only while an analysed Automix transition is audibly mixing two tracks. */
    val smartMixInProgress: StateFlow<Boolean>
    /** The Automix blend in flight, for the scrubber's beat glow; null between blends. */
    val smartMixBlend: StateFlow<MixBlend?>
    val smartTransitionWindow: StateFlow<TransitionWindow?>
    val spotifyCanvasAutoHide: StateFlow<Boolean>
    val syncedLyrics: StateFlow<Boolean>
    val translationLanguage: StateFlow<String>
    /**
     * The Now Playing lyrics view translates each track automatically and
     * draws the translated lines under the originals. Off by default.
     */
    val lyricsTranslationOverlay: StateFlow<Boolean>
    val versionAlignmentInProgress: StateFlow<Boolean>

    /** The Now Playing visualizer's rendering mode; [VisualizerStyle.OFF] hides the strip. */
    val visualizerStyle: StateFlow<VisualizerStyle>
    /** The visualizer strip's sensitivity; 1 is the meter's own calibration. */
    val visualizerIntensity: StateFlow<Float>
    /** Battery saver: smaller captures at half the callback rate. */
    val visualizerBatterySaver: StateFlow<Boolean>

    fun setLastPlayerScreen(value: LastPlayerScreen)
    fun setLyricsAutoScroll(value: Boolean)
    fun setLyricsFontSizeSp(value: Float)
    fun setLyricsKaraokeEffects(value: Boolean)
    fun setLyricsHighlightIntensity(value: Float) = Unit
    fun setLyricsBackground(value: LyricsBackground) = Unit
    fun setLyricsReducedMotion(value: Boolean) = Unit
    fun setLyricsOffsetMs(value: Int)
    /** Null clears the track's own offset, handing it back to the global one. */
    fun setLyricsOffsetForTrack(videoId: String, offsetMs: Int?)
    /** Null clears the route's calibration, handing it back to automatic. */
    fun setLyricsRouteCalibration(route: AudioOutputKind, offsetMs: Int?) = Unit
    fun setLyricsTranslationOverlay(value: Boolean)
    fun setVisualizerStyle(value: VisualizerStyle)
    fun setVisualizerIntensity(value: Float)
    fun setVisualizerBatterySaver(value: Boolean)
}

/** The player's name for the installed host's settings, read where AppSettings used to be. */
internal val PlayerSettings: PlayerSettingsSource
    get() = PlayerPlatform.host.settings

/** The lyric offset sheet's range, in milliseconds either way. */
const val MIN_LYRICS_OFFSET_MS = -5_000
const val MAX_LYRICS_OFFSET_MS = 5_000

/**
 * The lyric text-size slider's range, in sp. [DEFAULT_LYRICS_FONT_SIZE_SP] is
 * the panel's long-standing look; the panel scales its line sizes around it.
 */
const val MIN_LYRICS_FONT_SIZE_SP = 13f
const val MAX_LYRICS_FONT_SIZE_SP = 24f
const val DEFAULT_LYRICS_FONT_SIZE_SP = 17f

/**
 * The highlight-intensity slider's range, as a scale around the playing
 * line's bloom. 1 is the panel's long-standing strength; the phone's
 * AppSettings mirrors these.
 */
const val MIN_LYRICS_HIGHLIGHT_INTENSITY = 0.4f
const val MAX_LYRICS_HIGHLIGHT_INTENSITY = 1.6f
const val DEFAULT_LYRICS_HIGHLIGHT_INTENSITY = 1f

/** How many per-track lyric offsets are kept; the oldest edits fall off. */
const val MAX_LYRIC_OFFSET_OVERRIDES = 100

/**
 * The visualizer strip's sensitivity slider range. 1 is the meter's own
 * calibration; the phone's AppSettings mirrors these — see
 * [AppSettings][com.music.sonariq.data.settings.AppSettings].
 */
const val MIN_VISUALIZER_INTENSITY = 0.25f
const val MAX_VISUALIZER_INTENSITY = 2f
const val DEFAULT_VISUALIZER_INTENSITY = 1f

/**
 * The offset a track actually reads: its own override when one exists,
 * otherwise the global offset with the track's measured auto offset
 * composed under it.
 *
 * The layering is the manual system's own, extended by one layer at the
 * bottom: a per-track override is the listener's explicit word for that
 * track and wins outright, exactly as it has always beaten the global;
 * beneath it the global (the listener's standing nudge for everything)
 * and [autoMs] (what measurement found for this track's lines) add,
 * because they answer different questions — taste and measurement —
 * and neither was set with the other in hand. With no auto offset
 * measured, [autoMs] is 0 and the resolution is the manual one,
 * unchanged. The composed base is held to the offset range, as every
 * stored offset already is.
 */
fun resolveLyricsOffsetMs(
    overrides: Map<String, Int>,
    globalMs: Int,
    videoId: String?,
    autoMs: Int = 0,
): Int =
    videoId?.let { overrides[it] }
        ?: (globalMs + autoMs).coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)

/**
 * The auto offset that applies to [lines] for [videoId], or 0 when none
 * does: the track has no record, the record was written by another
 * record version, or — the ordinary retirement — the lines on screen no
 * longer carry the fingerprint the measurement was made against, so a
 * shift measured on one set of lines is never silently applied to
 * another. An AI-measured result keeps its provider lines' fingerprint
 * (see [lyricsLinesFingerprint]), so a measurement survives the upgrade
 * it produced and retires exactly when provider timings replace it.
 */
fun applicableAutoOffsetMs(
    autoOffsets: Map<String, LyricsAutoOffset>,
    videoId: String?,
    lines: List<LyricLine>?,
): Int {
    if (videoId.isNullOrBlank() || lines.isNullOrEmpty()) return 0
    val record = autoOffsets[videoId] ?: return 0
    if (record.version != LyricsAutoOffset.CURRENT_VERSION) return 0
    if (record.fingerprint != lyricsLinesFingerprint(lines)) return 0
    return record.offsetMs.coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
}

/**
 * A per-track offset edit applied to the stored map: null removes the track's
 * entry, a value is coerced to the offset range first.
 */
fun Map<String, Int>.withLyricOffset(videoId: String, offsetMs: Int?): Map<String, Int> =
    if (offsetMs == null) {
        this - videoId
    } else {
        this + (videoId to offsetMs.coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS))
    }

private val LYRIC_OFFSET_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

/**
 * `"videoId:ms,videoId:ms"`. A videoId is always `[A-Za-z0-9_-]{11}`, so
 * neither delimiter can appear inside one; capped at
 * [MAX_LYRIC_OFFSET_OVERRIDES], oldest edits first to fall off.
 */
fun encodeLyricOffsets(overrides: Map<String, Int>): String =
    overrides.entries
        .toList()
        .takeLast(MAX_LYRIC_OFFSET_OVERRIDES)
        .joinToString(",") { (videoId, offsetMs) -> "$videoId:$offsetMs" }

/** The inverse of [encodeLyricOffsets]; malformed entries fall out quietly. */
fun decodeLyricOffsets(stored: String): Map<String, Int> {
    if (stored.isBlank()) return emptyMap()
    return stored.split(",")
        .mapNotNull { entry ->
            val videoId = entry.substringBefore(":")
            val offsetMs = entry.substringAfter(":", "").toIntOrNull()
                ?: return@mapNotNull null
            if (!LYRIC_OFFSET_VIDEO_ID.matches(videoId)) return@mapNotNull null
            videoId to offsetMs.coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
        }
        .take(MAX_LYRIC_OFFSET_OVERRIDES)
        .toMap()
}

/**
 * `"videoId:version:ms:fingerprint,videoId:..."` — the stored form of
 * the measured auto offsets, kept beside the manual overrides' codec
 * above. A fingerprint is lowercase hex, so neither delimiter can
 * appear inside one; the record version rides in every entry so a
 * future shape retires old entries by reading, not by migration.
 * Capped like the overrides: the oldest measurements fall off first.
 */
fun encodeLyricAutoOffsets(records: Map<String, LyricsAutoOffset>): String =
    records.entries
        .toList()
        .takeLast(MAX_LYRIC_OFFSET_OVERRIDES)
        .joinToString(",") { (videoId, record) ->
            "$videoId:${record.version}:${record.offsetMs}:${record.fingerprint}"
        }

/**
 * The inverse of [encodeLyricAutoOffsets]; malformed entries — and any
 * written by a record version this build does not read — fall out
 * quietly.
 */
fun decodeLyricAutoOffsets(stored: String): Map<String, LyricsAutoOffset> {
    if (stored.isBlank()) return emptyMap()
    return stored.split(",")
        .mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 4) return@mapNotNull null
            val (videoId, version, offsetMs, fingerprint) = parts
            if (!LYRIC_OFFSET_VIDEO_ID.matches(videoId)) return@mapNotNull null
            if (version.toIntOrNull() != LyricsAutoOffset.CURRENT_VERSION) {
                return@mapNotNull null
            }
            val offset = offsetMs.toIntOrNull() ?: return@mapNotNull null
            if (fingerprint.length != 64 || !fingerprint.all { it in '0'..'9' || it in 'a'..'f' }) {
                return@mapNotNull null
            }
            videoId to LyricsAutoOffset(
                offsetMs = offset.coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS),
                fingerprint = fingerprint,
                version = LyricsAutoOffset.CURRENT_VERSION,
            )
        }
        .take(MAX_LYRIC_OFFSET_OVERRIDES)
        .toMap()
}

/** A system output level, 0..1, and the means to set it. */
interface SystemVolume {
    /** The level as the system reports it, following hardware keys and other apps. */
    val level: StateFlow<Float>
    fun set(level: Float)

    /**
     * Reads the level again. Android keeps one media volume per output and
     * swaps which is in force on a route change without announcing it.
     */
    fun refresh()
}

enum class AudioOutputKind { PHONE, WIRED, USB, BLUETOOTH, HDMI, OTHER }

data class AudioOutputDevice(
    val id: Int,
    /** Blank for a device with no name of its own; the drawer supplies one. */
    val name: String,
    val kind: AudioOutputKind,
    val isActive: Boolean = false,
)

data class OutputFormatUi(
    /** "24-bit PCM · 48 kHz" — whichever parts are known. */
    val summary: String = "",
    /** Whether the negotiated output could carry more than 16-bit / 48 kHz. */
    val carriesHiRes: Boolean = false,
)

data class PartyUi(
    val inParty: Boolean = false,
    val controlsLocked: Boolean = false,
    val members: List<PartyMember> = emptyList(),
    val you: PartyMember? = null,
    val code: String? = null,
)

sealed interface LyricsTranslationResult {
    data class Translated(val lines: List<LyricLine>) : LyricsTranslationResult
    data class SameLanguage(val language: String) : LyricsTranslationResult
    data object Unavailable : LyricsTranslationResult
}

sealed interface LyricsRomanizationResult {
    data class Romanized(val lines: List<LyricLine>) : LyricsRomanizationResult
    data object AlreadyRomanized : LyricsRomanizationResult
    data object Unavailable : LyricsRomanizationResult
}

/** How a clip fills the bounds supplied by its caller. */
enum class CanvasContentMode {
    CROP,
    FIT_PORTRAIT,
}

/** Everything [CanvasArtworkPlayer] is asked, handed to the platform's decoder as one value. */
class CanvasVideoSpec(
    val canvas: CanvasArtwork,
    val isPlaying: Boolean,
    val contentMode: CanvasContentMode,
    val alignPortraitTop: Boolean,
    val onAspectRatioChanged: (Float) -> Unit,
    val portraitRevealBounds: IntSize,
    val presentationAlpha: () -> Float,
    val onRenderedChanged: (Boolean) -> Unit,
    val onFrameCaptured: (ImageBitmap) -> Unit,
    val refreshFrameEveryMs: Long?,
    val frameCapturePx: Int,
    val onCoverChanged: (Float) -> Unit,
    val bottomFade: Float,
    val bottomFadeEndPx: Float?,
    val pausedForTransition: Boolean,
)

/**
 * The looping video that plays over a track's cover art — see
 * [PlayerHost.CanvasVideo] for the decoder behind it on each platform.
 */
@Composable
fun CanvasArtworkPlayer(
    canvas: CanvasArtwork,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    contentMode: CanvasContentMode = CanvasContentMode.CROP,
    alignPortraitTop: Boolean = false,
    onAspectRatioChanged: (Float) -> Unit = {},
    portraitRevealBounds: IntSize = IntSize.Zero,
    presentationAlpha: () -> Float = { 1f },
    onRenderedChanged: (Boolean) -> Unit = {},
    onFrameCaptured: (ImageBitmap) -> Unit = {},
    refreshFrameEveryMs: Long? = null,
    frameCapturePx: Int = CANVAS_FRAME_CAPTURE_PX,
    onCoverChanged: (Float) -> Unit = {},
    bottomFade: Float = 0f,
    bottomFadeEndPx: Float? = null,
    pausedForTransition: Boolean = false,
) {
    PlayerPlatform.host.CanvasVideo(
        CanvasVideoSpec(
            canvas = canvas,
            isPlaying = isPlaying,
            contentMode = contentMode,
            alignPortraitTop = alignPortraitTop,
            onAspectRatioChanged = onAspectRatioChanged,
            portraitRevealBounds = portraitRevealBounds,
            presentationAlpha = presentationAlpha,
            onRenderedChanged = onRenderedChanged,
            onFrameCaptured = onFrameCaptured,
            refreshFrameEveryMs = refreshFrameEveryMs,
            frameCapturePx = frameCapturePx,
            onCoverChanged = onCoverChanged,
            bottomFade = bottomFade,
            bottomFadeEndPx = bottomFadeEndPx,
            pausedForTransition = pausedForTransition,
        ),
        modifier,
    )
}

/** The longest edge of a frame handed to [CanvasArtworkPlayer]'s onFrameCaptured. */
const val CANVAS_FRAME_CAPTURE_PX = 128

/** Media3's repeat modes, by value, so the transport can speak them without Media3. */
object RepeatModes {
    const val OFF = 0
    const val ONE = 1
    const val ALL = 2
}

@Composable
internal fun rememberAudioOutputs(): List<AudioOutputDevice> = PlayerPlatform.host.rememberAudioOutputs()

@Composable
internal fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit =
    PlayerPlatform.host.rememberOutputPicker(onOpen)

@Composable
internal fun rememberRemoteArtworkUrl(song: Song?): String? =
    PlayerPlatform.host.rememberRemoteArtworkUrl(song)

@Composable
internal fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit) =
    PlayerPlatform.host.AudioPipelineDialog(hazeState, isPlaying, onDismiss)
