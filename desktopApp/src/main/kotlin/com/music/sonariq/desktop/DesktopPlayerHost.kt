package com.music.sonariq.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.music.sonariq.data.canvas.CanvasArtwork
import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.lyrics.LyricsSource
import com.music.sonariq.data.lyrics.LyricsTranslation
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.settings.AudioQuality
import com.music.sonariq.data.settings.LastPlayerScreen
import com.music.sonariq.data.settings.MixBlend
import com.music.sonariq.data.settings.SmartAnalysis
import com.music.sonariq.data.settings.TransitionWindow
import com.music.sonariq.ui.player.AudioOutputDevice
import com.music.sonariq.ui.player.AudioOutputKind
import com.music.sonariq.ui.player.CanvasVideoSpec
import com.music.sonariq.ui.player.DEFAULT_LYRICS_FONT_SIZE_SP
import com.music.sonariq.ui.player.DEFAULT_VISUALIZER_INTENSITY
import com.music.sonariq.ui.player.LyricsRomanizationResult
import com.music.sonariq.ui.player.LyricsTranslationResult
import com.music.sonariq.ui.player.MAX_LYRICS_FONT_SIZE_SP
import com.music.sonariq.ui.player.MAX_LYRICS_OFFSET_MS
import com.music.sonariq.ui.player.MAX_VISUALIZER_INTENSITY
import com.music.sonariq.ui.player.MIN_LYRICS_FONT_SIZE_SP
import com.music.sonariq.ui.player.MIN_LYRICS_OFFSET_MS
import com.music.sonariq.ui.player.MIN_VISUALIZER_INTENSITY
import com.music.sonariq.ui.player.OutputFormatUi
import com.music.sonariq.ui.player.PartyUi
import com.music.sonariq.ui.player.PlayerHost
import com.music.sonariq.ui.player.PlayerSettingsSource
import com.music.sonariq.ui.player.SystemVolume
import com.music.sonariq.ui.player.VisualizerStyle
import com.music.sonariq.ui.player.decodeLyricOffsets
import com.music.sonariq.ui.player.encodeLyricOffsets
import com.music.sonariq.ui.player.withLyricOffset
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The desktop's answers to the shared player's [PlayerHost].
 *
 * The player itself is the phone's, line for line; this is only what sits
 * under it here — the desktop's settings, its FFmpeg Canvas decoder, its mixers
 * as outputs, its party, its translation client. The window keeps the few
 * pieces of live state it owns ([volumeLevel], [pipelineDialog]) up to date.
 */
internal object DesktopPlayerHost : PlayerHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val settings: DesktopPlayerSettings = DesktopPlayerSettings

    override fun cachedCanvas(song: Song): CanvasArtwork? = DesktopCanvasClient.cached(song)?.toShared()

    override suspend fun canvasFor(song: Song): CanvasArtwork? =
        withContext(Dispatchers.IO) { DesktopCanvasClient.lookup(song) }?.toShared()

    @Composable
    override fun CanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) = DesktopCanvasVideo(spec, modifier)

    // Local files carry their art on the row already; there is no remote store to ask.
    @Composable
    override fun rememberRemoteArtworkUrl(song: Song?): String? = song?.thumbnailUrl

    /** The player's volume, 0..1, as the window holds it. */
    val volumeLevel = MutableStateFlow(1f)

    /** Where a drag on the player's volume bar goes — set by the window. */
    @Volatile
    var onVolumeChange: (Float) -> Unit = { volumeLevel.value = it }

    override val volume: SystemVolume = object : SystemVolume {
        override val level: StateFlow<Float> = volumeLevel.asStateFlow()
        override fun set(level: Float) = onVolumeChange(level.coerceIn(0f, 1f))
        override fun refresh() = Unit
    }

    /** The last list handed to the drawer, for turning a row's id back into a mixer. */
    @Volatile
    private var listed: List<String> = emptyList()

    @Composable
    override fun rememberAudioOutputs(): List<AudioOutputDevice> {
        val selected by DesktopAudioDevices.selected.collectAsState()
        val changes by DesktopAudioDevices.changes.collectAsState()
        val devices = remember(changes) { DesktopAudioDevices.available() }
        return remember(selected, devices) {
            val ids = listOf(DesktopAudioDevices.SYSTEM_DEFAULT) + devices.map { it.id }
            listed = ids
            ids.mapIndexed { index, id ->
                val name = if (id == DesktopAudioDevices.SYSTEM_DEFAULT) {
                    DesktopStrings["d_system_default", "System default"]
                } else {
                    devices.first { it.id == id }.name
                }
                AudioOutputDevice(
                    id = index,
                    name = name,
                    kind = kindOf(name),
                    isActive = id == selected,
                )
            }
        }
    }

    @Composable
    override fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit = onOpen

    override fun selectAudioOutput(id: Int?) {
        val device = id?.let { listed.getOrNull(it) } ?: DesktopAudioDevices.SYSTEM_DEFAULT
        DesktopAudioDevices.select(device)
    }

    /** The engine's output stage, kept current by the window. */
    val pipeline = MutableStateFlow(DesktopAudioPipeline())

    override val outputFormat: StateFlow<OutputFormatUi> = pipeline
        .map { it.toOutputFormat() }
        .stateIn(scope, SharingStarted.Eagerly, OutputFormatUi())

    override val party: StateFlow<PartyUi> = DesktopListenTogether.state
        .map {
            PartyUi(
                inParty = it.inParty,
                controlsLocked = it.controlsLocked,
                members = it.members,
                you = it.you,
                code = it.code,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, PartyUi())

    override suspend fun translateLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsTranslationResult =
        when (val result = LyricsTranslation.translate(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.Result.Translated -> LyricsTranslationResult.Translated(result.lines)
            is LyricsTranslation.Result.SameLanguage -> LyricsTranslationResult.SameLanguage(result.language)
            LyricsTranslation.Result.Unavailable -> LyricsTranslationResult.Unavailable
        }

    override suspend fun romanizeLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsRomanizationResult =
        when (val result = LyricsTranslation.romanize(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.RomanizationResult.Romanized -> LyricsRomanizationResult.Romanized(result.lines)
            LyricsTranslation.RomanizationResult.AlreadyRomanized -> LyricsRomanizationResult.AlreadyRomanized
            LyricsTranslation.RomanizationResult.Unavailable -> LyricsRomanizationResult.Unavailable
        }

    /** The newest notice, for the window to show and clear. */
    val messages = MutableStateFlow<String?>(null)

    override fun showMessage(message: String) {
        messages.value = message
    }

    /** The desktop's signal-chain readout, supplied by the window that has the engine. */
    @Volatile
    var pipelineDialog: (@Composable (onDismiss: () -> Unit) -> Unit)? = null

    @Composable
    override fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit) {
        pipelineDialog?.invoke(onDismiss)
    }
}

/**
 * The shared player's settings, as flows the desktop window feeds from its own
 * state. What the desktop has no setting for keeps the phone's default.
 */
internal object DesktopPlayerSettings : PlayerSettingsSource {
    private val persistence by lazy { DesktopPersistence() }

    override val animatedCanvas = MutableStateFlow(true)
    override val audioQualityCellular = MutableStateFlow(AudioQuality.LOSSLESS)
    override val audioQualityWifi = MutableStateFlow(AudioQuality.LOSSLESS)
    override val canvasOverCellular = MutableStateFlow(true)
    override val fullBleedArtwork = MutableStateFlow(false)
    override val hideSongStatus = MutableStateFlow(false)
    override val hideVolumeBar = MutableStateFlow(false)
    override val lastPlayerScreen = MutableStateFlow(
        runCatching { LastPlayerScreen.valueOf(persistence.string(KEY_LAST_SCREEN, "MAIN")) }
            .getOrDefault(LastPlayerScreen.MAIN),
    )
    override val legacyMeshGradient = MutableStateFlow(false)
    override val lyricsAutoScroll = MutableStateFlow(persistence.boolean(KEY_LYRICS_AUTO_SCROLL, true))
    override val lyricsBlur = MutableStateFlow(true)
    override val lyricsFontSizeSp = MutableStateFlow(
        persistence.float(KEY_LYRICS_FONT_SIZE_SP, DEFAULT_LYRICS_FONT_SIZE_SP)
            .coerceIn(MIN_LYRICS_FONT_SIZE_SP, MAX_LYRICS_FONT_SIZE_SP),
    )
    override val lyricsKaraokeEffects = MutableStateFlow(persistence.boolean(KEY_LYRICS_KARAOKE, true))
    override val lyricsOffsetMs = MutableStateFlow(
        persistence.int(KEY_LYRICS_OFFSET, 0).coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS),
    )
    override val lyricsOffsetOverrides = MutableStateFlow(
        decodeLyricOffsets(persistence.string(KEY_LYRICS_OFFSET_OVERRIDES)),
    )
    override val lyricsSourceOrder = MutableStateFlow<List<LyricsSource>>(LyricsSource.offered)

    // A desktop is never on a metered link as far as this app can tell.
    override val meteredConnection = MutableStateFlow<Boolean?>(false)
    override val preferUsbDac = MutableStateFlow(false)
    override val prioritizeSpotifyCanvas = MutableStateFlow(false)
    override val reduceAnimation = MutableStateFlow(false)
    override val reduceDynamicBlur = MutableStateFlow(false)
    override val showNerdStats = MutableStateFlow(false)
    override val smartAnalysis = MutableStateFlow(SmartAnalysis())
    override val smartFadeEnabled = MutableStateFlow(false)
    override val smartMixInProgress = MutableStateFlow(false)
    override val smartMixBlend = MutableStateFlow<MixBlend?>(null)
    override val smartTransitionWindow = MutableStateFlow<TransitionWindow?>(null)
    override val spotifyCanvasAutoHide = MutableStateFlow(true)
    override val syncedLyrics = MutableStateFlow(true)
    override val translationLanguage = MutableStateFlow("")
    override val lyricsTranslationOverlay = MutableStateFlow(false)
    override val versionAlignmentInProgress = MutableStateFlow(false)
    // The desktop has no capture backend, so these are in-memory only; the
    // strip stays hidden behind NoVisualizer regardless.
    override val visualizerStyle = MutableStateFlow(VisualizerStyle.SPECTRUM)
    override val visualizerIntensity = MutableStateFlow(DEFAULT_VISUALIZER_INTENSITY)
    override val visualizerBatterySaver = MutableStateFlow(false)

    override fun setLastPlayerScreen(value: LastPlayerScreen) {
        lastPlayerScreen.value = value
        persistence.saveString(KEY_LAST_SCREEN, value.name)
    }

    override fun setLyricsOffsetMs(value: Int) {
        val clamped = value.coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
        lyricsOffsetMs.value = clamped
        persistence.saveInt(KEY_LYRICS_OFFSET, clamped)
    }

    override fun setLyricsOffsetForTrack(videoId: String, offsetMs: Int?) {
        val updated = lyricsOffsetOverrides.value.withLyricOffset(videoId, offsetMs)
        if (updated == lyricsOffsetOverrides.value) return
        lyricsOffsetOverrides.value = updated
        persistence.saveString(KEY_LYRICS_OFFSET_OVERRIDES, encodeLyricOffsets(updated))
    }

    override fun setLyricsFontSizeSp(value: Float) {
        val clamped = value.coerceIn(MIN_LYRICS_FONT_SIZE_SP, MAX_LYRICS_FONT_SIZE_SP)
        lyricsFontSizeSp.value = clamped
        persistence.saveFloat(KEY_LYRICS_FONT_SIZE_SP, clamped)
    }

    override fun setLyricsAutoScroll(value: Boolean) {
        lyricsAutoScroll.value = value
        persistence.saveBoolean(KEY_LYRICS_AUTO_SCROLL, value)
    }

    override fun setLyricsKaraokeEffects(value: Boolean) {
        lyricsKaraokeEffects.value = value
        persistence.saveBoolean(KEY_LYRICS_KARAOKE, value)
    }

    override fun setLyricsTranslationOverlay(value: Boolean) {
        lyricsTranslationOverlay.value = value
    }

    override fun setVisualizerStyle(value: VisualizerStyle) {
        visualizerStyle.value = value
    }

    override fun setVisualizerIntensity(value: Float) {
        visualizerIntensity.value = value.coerceIn(MIN_VISUALIZER_INTENSITY, MAX_VISUALIZER_INTENSITY)
    }

    override fun setVisualizerBatterySaver(value: Boolean) {
        visualizerBatterySaver.value = value
    }

    private const val KEY_LAST_SCREEN = "last_player_screen"
    private const val KEY_LYRICS_OFFSET = "lyrics_offset_ms"
    private const val KEY_LYRICS_OFFSET_OVERRIDES = "lyrics_offset_overrides"
    private const val KEY_LYRICS_FONT_SIZE_SP = "lyrics_font_size_sp"
    private const val KEY_LYRICS_AUTO_SCROLL = "lyrics_auto_scroll"
    private const val KEY_LYRICS_KARAOKE = "lyrics_karaoke"
}

/** Which glyph a mixer gets, from the only thing Java Sound says about it: its name. */
private fun kindOf(name: String): AudioOutputKind {
    val n = name.lowercase(Locale.ROOT)
    return when {
        "bluetooth" in n || "airpods" in n || "buds" in n -> AudioOutputKind.BLUETOOTH
        "usb" in n || "dac" in n -> AudioOutputKind.USB
        "hdmi" in n || "display" in n || "nvidia" in n -> AudioOutputKind.HDMI
        "headphone" in n || "headset" in n -> AudioOutputKind.WIRED
        else -> AudioOutputKind.OTHER
    }
}

private fun DesktopAudioPipeline.toOutputFormat(): OutputFormatUi {
    val depth = when {
        outputIsFloat == true -> "32-bit float"
        outputBytesPerSample != null -> "${outputBytesPerSample * 8}-bit PCM"
        else -> null
    }
    val rate = outputSampleRateHz?.takeIf { it > 0 }
        ?.let { "${"%.1f".format(Locale.ROOT, it / 1000f).removeSuffix(".0")} kHz" }
    return OutputFormatUi(
        summary = listOfNotNull(depth, rate).joinToString(" · "),
        carriesHiRes = outputIsFloat == true || (outputBytesPerSample ?: 2) > 2 ||
            (outputSampleRateHz ?: 0) > 48_000,
    )
}

/** The shared provider a desktop source name refers to, matched on its label. */
internal fun lyricsSourceNamed(name: String): LyricsSource? =
    LyricsSource.entries.firstOrNull { it.label.equals(name, ignoreCase = true) }
        ?: LyricsSource.entries.firstOrNull { it.name.equals(name.replace(' ', '_'), ignoreCase = true) }
