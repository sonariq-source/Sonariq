package com.music.sonariq.data.settings

import com.music.sonariq.data.webdav.update
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.media3.common.Player
import com.music.sonariq.BuildConfig
import com.music.sonariq.auth.AuthStore
import com.music.sonariq.data.canvas.SpotifyToken
import com.music.sonariq.data.lyrics.LyricsSource
import com.music.sonariq.data.sources.SourceKind
import com.music.sonariq.playback.EqLayout
import com.music.sonariq.playback.EqualizerPreset
import com.music.sonariq.ui.player.decodeLyricOffsets
import com.music.sonariq.ui.player.encodeLyricOffsets
import com.music.sonariq.ui.player.LyricsBackground
import com.music.sonariq.ui.player.VisualizerStyle
import com.music.sonariq.ui.player.withLyricOffset
import com.music.sonariq.ui.theme.AccentColor
import com.music.sonariq.ui.theme.accentColorFromStored
import kotlinx.coroutines.flow.MutableStateFlow


/**
 * Whether a stream started under this ceiling may be served by [kind].
 *
 * Asked per stream rather than written into
 * [SourceConfig.enabled][com.music.sonariq.data.sources.SourceConfig.enabled],
 * which is what this used to do — an `applyQualityPreset` call flipped the
 * module and JioSaavn switches the moment a rung was picked. Two things
 * were wrong with that and both were reported together: picking a rung for
 * *mobile data* turned the sources off while sitting on Wi-Fi, and nothing
 * turned them back on when the connection changed, so a Wi-Fi ceiling of
 * Lossless still had no lossless source to reach. A ceiling is a property
 * of the connection in force; the switches on the Sources screen are the
 * user's standing choice. Storing the first in the second lost the second.
 *
 * [SourceKind.YOUTUBE] is permitted on every rung: it is what [maxKbps]
 * caps, and it is the only source that can answer at all when the ones
 * above it are skipped.
 */
fun AudioQuality.permits(kind: SourceKind): Boolean = when (this) {
    AudioQuality.LOSSLESS -> true
    // No lossless answer is wanted here, and a source that can serve one is
    // the slow half of the list: an addon fronting several catalogues walks
    // all of them before it answers, which is seconds spent to land on a
    // transcode JioSaavn already has at 320.
    AudioQuality.HIGH -> !kind.canServeLossless
    AudioQuality.MEDIUM, AudioQuality.LOW -> kind == SourceKind.YOUTUBE
}

/**
 * PCM format requested from Media3's AudioTrack sink.
 *
 * FLOAT_32 is not a cosmetic "hi-res" switch: it makes Media3 convert
 * high-resolution integer PCM to IEEE-754 float and configure AudioTrack for
 * PCM_FLOAT. Android may still route/resample it according to the selected
 * output device, which is why the player exposes the negotiated format.
 */
enum class OutputPcmMode(val label: String) {
    PCM_16("16-bit PCM"),
    FLOAT_32("32-bit float"),
}

/**
 * What to keep when a track is saved to the device.
 *
 * Deliberately not [AudioQuality]. That enum budgets a *stream*, and is priced
 * per hour because the same bytes are spent again on every replay. A download is
 * the opposite trade — paid for once, kept, played from disk forever after — so
 * the figure that decides it is what one track costs, and the rung worth
 * defaulting to is the top one rather than the cheap one.
 *
 * The rungs themselves differ too. On the YouTube fallback path a download is
 * Opus-in-WebM (see
 * [StreamResolver.resolveForDownload][com.music.sonariq.data.innertube.StreamResolver.resolveForDownload]),
 * while configured sources can supply their own AAC or lossless copy. And
 * [LOSSLESS] has no streaming counterpart at all: it is the only rung that lets
 * a configured source's bit-exact file end up as a file on disk.
 */
enum class DownloadQuality(
    /** Ceiling for the YouTube Opus ladder. [Int.MAX_VALUE] means "whichever rung is best". */
    val maxKbps: Int,
    val label: String,
    val detail: String,
    /** Roughly what one four-minute track costs at this rung, sans unit context. */
    val perTrack: String,
    /** Whether a source's bit-exact file is worth keeping, or a transcode will do. */
    val keepsLossless: Boolean,
) {
    STANDARD(128, "Standard", "~128 kbps Opus · fits more on the device", "~4 MB", false),
    HIGH(Int.MAX_VALUE, "High", "Best audio on offer; source quality first", "~8 MB", false),
    LOSSLESS(
        Int.MAX_VALUE,
        "Lossless",
        "Bit-exact if a source has it, best Opus if not",
        "~35 MB",
        true,
    ),
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark")
}

/**
 * Which of the equaliser's two tabs is driving the sound.
 *
 * One at a time rather than both at once: they are two ways of describing the
 * same curve, and summing them would mean a tone pad sitting at dead centre
 * still quietly altering whatever the sliders said.
 */
enum class EqualizerMode {
    /** The tone pad: tilt, contour, and how wide each is. */
    DYNAMIC,

    /** Seven sliders and a preset list. */
    MANUAL,
}

/** Stable persisted ordering for each on-device music library. */
enum class LocalMusicSort {
    TITLE_ASC,
    TITLE_DESC,
    DATE_ADDED,
    DATE_MODIFIED,
}

/**
 * Ordering for the track list on an album or playlist page — the same idea as
 * the Downloads folder's sort, with a date option for the one thing a
 * catalogue row can still be dated by: the position it sits at. A playlist's
 * running order is the order songs were added in — YouTube Music appends each
 * addition at the foot — so read backwards it *is* a date order, newest first.
 * DetailScreen.kt holds the sort itself. Persisted app-wide rather than per
 * page: one choice, kept until the user makes another.
 */
enum class SongSort {
    DEFAULT,
    TITLE_ASC,
    TITLE_DESC,
    DATE_ADDED_ASC,
    DATE_ADDED_DESC,
}

/**
 * App settings, backed by SharedPreferences and exposed as flows.
 *
 * PlaybackService runs in the same process as the UI, so it observes these
 * same flows and applies changes to the live ExoPlayer instance immediately —
 * no restart, no rebinding.
 */
object AppSettings {

    private lateinit var prefs: SharedPreferences

    /** Only for the Discord, WebDAV and SMB secrets — everything else on here is plain prefs. */
    private lateinit var authStore: AuthStore

    /**
     * Quality ceilings, one per kind of connection — the point of the split is
     * that Wi-Fi can stay on Lossless while mobile data is capped. Both
     * default to Lossless; the mobile plan is the user's to budget, not ours
     * to assume.
     */
    val audioQualityWifi = MutableStateFlow(AudioQuality.LOSSLESS)
    val audioQualityCellular = MutableStateFlow(AudioQuality.LOSSLESS)

    /** Allowed duration difference when replacing a playing stream with one from a source. */
    val upgradeLengthSlackSeconds = MutableStateFlow(DEFAULT_UPGRADE_LENGTH_SLACK_SECONDS)

    /**
     * What a saved file should be, answered on its own terms.
     *
     * Kept apart from the two ceilings above on purpose. Those are about what
     * this minute's connection costs, and a download outlives the minute it was
     * started in — capping a permanent file at whichever network happened to be
     * in hand bakes a temporary decision into a lasting artefact, and the
     * reverse (a High ceiling on Wi-Fi implying 35MB FLACs of everything) is
     * just as wrong in the other direction.
     *
     * Data spend on a download is [wifiOnlyDownloads]' problem, not this
     * setting's, which is what lets this one be purely about the file.
     *
     * Defaults to [DownloadQuality.LOSSLESS] because that is what the download
     * path already did on an uncapped connection, and [migrateDownloadQuality]
     * keeps it that way for the people it didn't.
     */
    val downloadQuality = MutableStateFlow(DownloadQuality.LOSSLESS)

    /**
     * Refuse to start a download while the connection charges for data.
     *
     * Metered rather than literally-Wi-Fi, the same test [effectiveAudioQuality]
     * makes, because the thing worth protecting is the bill and not the radio: a
     * tethered hotspot is Wi-Fi that costs money, and an unmetered home
     * connection is worth using whether or not it arrives over Wi-Fi.
     *
     * On by default, and that is a deliberate change of behaviour for anyone
     * updating. [downloadQuality] defaulting to Lossless means a tap that used
     * to spend four megabytes of mobile data can now spend thirty-five, and of
     * the two ways to get that wrong — silently overspending a data plan, or
     * refusing with a sentence naming the switch that would allow it — only the
     * second is recoverable by the person it happens to.
     */
    val wifiOnlyDownloads = MutableStateFlow(true)

    /**
     * Keep ordinary downloads in Music/Sonariq where other music apps can see
     * them. Off (the default) keeps downloads in this app's private storage.
     * HLS downloads always stay private because they are a playlist package,
     * not one portable audio file.
     */
    val exportDownloads = MutableStateFlow(false)

    /**
     * Smart Downloads: keep the most-played tracks on the device, refreshed
     * daily by [com.music.sonariq.download.SmartDownloadWorker].
     *
     * Off by default: it spends bandwidth and storage on the listener's
     * behalf, which is not a default to take silently.
     */
    val smartDownloadsEnabled = MutableStateFlow(false)

    /** How many of the most-played tracks to keep. 25, 50 or 100. */
    val smartDownloadsCount = MutableStateFlow(50)

    /**
     * Smart Downloads' own Wi-Fi-only switch, separate from
     * [wifiOnlyDownloads] on purpose: the manual-download setting answers
     * "may *I* spend data right now", and a background job answering for
     * itself with the same switch would make turning it off for a trip also
     * silently kill the automation. Defaults on, like its manual sibling.
     */
    val smartDownloadsWifiOnly = MutableStateFlow(true)

    /**
     * True offline mode: only downloaded tracks may play, and the
     * streaming-only surfaces (Explore, Search) are hidden. Network playback
     * attempts are refused with an honest message rather than silently
     * failing — see [com.music.sonariq.playback.playSongs].
     */
    val offlineMode = MutableStateFlow(false)

    // ── Playback & audio output ────────────────────────────────────────────

    /** Keep the equaliser per output device rather than one global curve. */
    val perDeviceEqEnabled = MutableStateFlow(true)

    /** Mono downmix, for single-earbud listening. */
    val monoAudioEnabled = MutableStateFlow(false)

    /** Animated canvas-style artwork backdrop behind the player. */
    val canvasBackdropEnabled = MutableStateFlow(true)

    // ── Data & streaming ───────────────────────────────────────────────────

    /** Data saver: cap streams at [dataSaverQuality] on metered connections. */
    val dataSaverEnabled = MutableStateFlow(false)

    /** Streaming ceiling applied while [dataSaverEnabled] is on. */
    val dataSaverQuality = MutableStateFlow(AudioQuality.LOW)

    /** Stream only while on Wi-Fi; refuse playback on mobile data. */
    val wifiOnlyStreaming = MutableStateFlow(false)

    // ── Automatic downloads ────────────────────────────────────────────────

    /** Keep music on the device automatically, without manual downloads. */
    val autoDownloadsEnabled = MutableStateFlow(false)

    /** Include liked songs in automatic downloads. */
    val autoDownloadLikedSongs = MutableStateFlow(true)

    /** Playlist ids that automatic downloads keep on the device. */
    val autoDownloadPlaylistIds = MutableStateFlow<Set<String>>(emptySet())

    /** Automatic downloads only run on Wi-Fi. */
    val autoDownloadWifiOnly = MutableStateFlow(true)

    /** Run automatic downloads on a daily schedule instead of continuously. */
    val scheduledDownloadsEnabled = MutableStateFlow(false)

    /** Hour of day (0-23) at which scheduled downloads run. */
    val scheduledDownloadHour = MutableStateFlow(2)

    /** Minute of the hour at which scheduled downloads run. */
    val scheduledDownloadMinute = MutableStateFlow(0)

    /** Scheduled downloads only run on Wi-Fi. */
    val scheduledDownloadWifiOnly = MutableStateFlow(true)

    // ── Home & queues ──────────────────────────────────────────────────────

    /** Serialised home-section order, blank means the default order. */
    val homeSectionOrderJson = MutableStateFlow("")

    /** Let the home screen learn section order from usage. */
    val homeSectionLearningEnabled = MutableStateFlow(true)

    /** Serialised saved queues, blank means none. */
    val savedQueuesJson = MutableStateFlow("")

    /** Second ambient-mode variant on the TV app. */
    val tvAmbient2Enabled = MutableStateFlow(false)

    /** Gestures on artwork (tap / double-tap / swipe shortcuts). */
    val artworkGesturesEnabled = MutableStateFlow(true)

    /** Private session: pause scrobbling and shared activity while on. */
    val privateSessionEnabled = MutableStateFlow(false)

    /**
     * Concert alerts: upcoming shows by the listener's top artists (from
     * [com.music.sonariq.data.stats.ListeningStats]), shown on the Replay
     * page and announced by notification. Needs [ticketmasterApiKey] —
     * without it there is nothing to look the shows up with.
     */
    val concertAlertsEnabled = MutableStateFlow(false)

    /**
     * Ticketmaster Discovery API consumer key. The API is free (5,000 calls
     * a day) but every request needs a key, and it is the user's to bring:
     * developer.ticketmaster.com hands one out after a free signup.
     */
    val ticketmasterApiKey = MutableStateFlow("")

    /**
     * Show synced lyrics on the TV while casting.
     *
     * Needs a custom Sonariq receiver (see [castReceiverAppId]); with the
     * stock receiver there is nothing to show them on and the switch does
     * nothing. On by default so it just works once a receiver is registered.
     */
    val castTvLyricsEnabled = MutableStateFlow(true)

    /**
     * The Cast receiver App ID for the Sonariq TV receiver.
     *
     * Blank (the default) keeps the stock Default Media Receiver: audio-only
     * casting, exactly as before. A registered custom App ID — from the Cast
     * Developer Console, pointing at the hosted Sonariq receiver page — gets
     * the lyrics UI on the TV. Takes effect after an app restart, because the
     * Cast framework only reads it once at startup.
     */
    val castReceiverAppId = MutableStateFlow("")

    /** Whether the active network charges for data. `null` while offline. */
    val meteredConnection = MutableStateFlow<Boolean?>(null)

    // `losslessAudio` used to live here, behind a "Prefer lossless" switch on
    // the Sources screen. It is gone: sources are asked for their best and each
    // degrades on its own terms, so the switch's only real effect was to ask a
    // module for a worse file than it was holding. See
    // [SourceResolver.requestForNow][com.music.sonariq.data.sources.SourceResolver.requestForNow],
    // which now reads [effectiveAudioQuality] and nothing else.

    val crossfadeSeconds = MutableStateFlow(0)

    /**
     * Lets Automix's analyzer decide the transition's timing and length
     * from each track's tempo, energy and structure, replacing the fixed
     * [crossfadeSeconds] window rather than needing it set to anything first
     * — [crossfadeSeconds] only matters here as a fallback while a pair is
     * still being analysed. Off by default: analysis costs a background
     * decode per track.
     *
     * See [com.music.sonariq.playback.smart.TransitionPlanner].
     */
    val smartFadeEnabled = MutableStateFlow(false)

    /**
     * DJ mix mode: its own on/off, distinct from the plain [crossfadeSeconds]
     * duration. When on, every track boundary gets a DJ-style transition whose
     * length is decided per pair from real measured audio features — the
     * tracks' DSP-measured tempi and energy curves — rather than the fixed
     * slider: similar-tempo songs get a long blend, clashing ones a quick
     * cut. The slider only sets the *maximum* length; with it at "Off", a
     * fixed default does. Until a pair is analysed the maximum is used as-is,
     * which is the honest answer when there is no evidence yet.
     */
    val djMixEnabled = MutableStateFlow(false)

    /**
     * Fade the volume out gradually over the last 60 seconds of a deadline
     * sleep timer instead of pausing abruptly at the deadline. On by default;
     * the "pause when this song ends" timer already fades via the crossfade
     * controller and is unaffected.
     */
    val sleepTimerFadeOut = MutableStateFlow(true)

    /** The CPU budget used by Beat This! and vocal analysis for Automix. */
    val automixPerformanceMode = MutableStateFlow(AutomixPerformanceMode.BALANCED)
    val skipSilence = MutableStateFlow(false)

    /** Requested PCM representation at the Android AudioTrack boundary. */
    val outputPcmMode = MutableStateFlow(OutputPcmMode.PCM_16)

    /** Prefer an attached USB audio output over the system's normal route. */
    val preferUsbDac = MutableStateFlow(false)

    /**
     * Level every track to the same loudness, using YouTube's own
     * normalization figure for it — see
     * [com.music.sonariq.playback.PlaybackService.setupLoudnessEnhancer].
     *
     * On by default, which is the one genuinely contentious thing about it.
     * The case for it: a queue drawn from several sources is a queue of
     * several mastering eras, and the gap between a 1980s CD transfer and a
     * modern master is routinely fifteen decibels — loud enough that the
     * listener's own volume control is the wrong tool, because the setting
     * that suits one track hurts at the next. Every streaming service
     * normalizes by default for the same reason.
     *
     * The case against it is that a constant gain is still a multiplication,
     * so this is the first thing in Sonariq that is *on* out of the box and
     * alters samples. Rather than hide that, the Audio Pipeline readout names
     * it: its Bit-exact row reports the first stage in the chain that is
     * altering samples, and switching this off is the first thing a listener
     * chasing an untouched signal would do.
     */
    val loudnessNormalization = MutableStateFlow(true)

    /** Skip loudness normalization while the active output is the phone's own speaker. */
    val loudnessOffOnSpeaker = MutableStateFlow(true)

    /**
     * Whether a source offering a Dolby Atmos rendition is allowed to serve it.
     *
     * On by default: where the device can decode it, Atmos is the premium
     * rendition the catalogue holds and the one most people are paying a
     * subscription for.
     *
     * Off is a real preference and not just a safety valve. Atmos is E-AC-3,
     * which is *lossy* — a track with an Atmos master is frequently also held
     * as a FLAC, and someone listening on wired headphones may well prefer the
     * bit-exact stereo copy to a spatial mix their output can't render. Turning
     * this off is how they say so; see
     * [ModuleSource.unplayable][com.music.sonariq.data.sources.ModuleSource],
     * which is where the refusal is applied.
     *
     * Independent of whether the device *can* decode it — that question is
     * [DeviceCodecs.playsDolbyAtmos][com.music.sonariq.data.sources.DeviceCodecs],
     * and the two are deliberately not folded together: this one is the
     * listener's answer, is persisted, and must survive being read on a phone
     * that cannot honour it (a restored backup, a swapped device) without
     * quietly rewriting itself.
     */
    val dolbyAtmos = MutableStateFlow(true)

    /**
     * Widens stereo output via [com.music.sonariq.playback.SpatialAudioProcessor],
     * a stereo widening + cross-feed effect running inside ExoPlayer's own
     * pipeline. Not true object-based spatial audio — YouTube only ever hands
     * us a stereo stream, so there's no Atmos-style source to render.
     */
    val spatialAudio = MutableStateFlow(false)

    /**
     * The app's own equaliser, master switch.
     *
     * Separate from the system equaliser row beside it, which is still there and
     * still opens the device's panel. The two stack rather than compete — this
     * one runs inside ExoPlayer before the sink, that one hangs off the audio
     * session after it — so someone who prefers their OEM's can leave this off
     * and lose nothing.
     */
    val equalizerEnabled = MutableStateFlow(false)

    /** Which tab is driving it. */
    val equalizerMode = MutableStateFlow(EqualizerMode.DYNAMIC)

    /** Tone pad, horizontal: warm at -5, bright at +5. */
    val equalizerToneX = MutableStateFlow(0)

    /** Tone pad, vertical: scooped at -5, mid-forward at +5. */
    val equalizerToneY = MutableStateFlow(0)

    /** Tone pad bandwidth: Broad when false, Focused when true. */
    val equalizerFocused = MutableStateFlow(false)

    /** Left/right trim, -1 hard left to +1 hard right. Applies to both tabs. */
    val equalizerBalance = MutableStateFlow(0f)

    /** The manual tab's seven gains, in decibels, low to high. */
    val equalizerBands = MutableStateFlow(EqualizerPreset.FLAT.bands)

    /**
     * Which preset the bands currently are, or [EqualizerPreset.CUSTOM].
     *
     * Derived from [equalizerBands] rather than independent of it — see
     * [setEqualizerBands] — so a slider dragged back to where a preset left it
     * makes the row say that preset's name again instead of "Custom" forever.
     */
    val equalizerPreset = MutableStateFlow(EqualizerPreset.FLAT)

    /**
     * Bass and treble tone trims, in decibels. They add into the low and high
     * shelf sections of whichever tab is driving — see [manualCurve] and
     * [toneCurve] — so they work in both tabs rather than belonging to one.
     */
    val equalizerBass = MutableStateFlow(0f)

    /** Treble trim; see [equalizerBass]. */
    val equalizerTreble = MutableStateFlow(0f)

    /**
     * Curves the user named and kept from the manual tab. Persisted as one
     * string — see [encodeUserEqualizerPresets] — so a backup carries them the
     * way it carries the bands.
     */
    val userEqualizerPresets = MutableStateFlow<List<UserEqualizerPreset>>(emptyList())

    /**
     * The user preset the bands currently match, or null.
     *
     * Derived from [equalizerBands] the way [equalizerPreset] is derived from
     * the fixed presets — see [setEqualizerBands] — so a slider dragged back
     * onto a saved curve names that preset again instead of forgetting it.
     */
    val activeUserPresetName = MutableStateFlow<String?>(null)

    val playbackSpeed = MutableStateFlow(1.0f)
    val themeMode = MutableStateFlow(ThemeMode.DARK)

    /** The accent driving the theme's primary roles; brand lime out of the box. */
    val accentColor = MutableStateFlow(AccentColor.LIME)

    /**
     * A user-picked accent color (opaque ARGB), overriding [accentColor] when
     * set. Null means the preset above. The dynamic theme still wins over both
     * — it follows the artwork, not the settings.
     */
    val customAccentArgb = MutableStateFlow<Int?>(null)

    /** When true, the accent is extracted from the playing track's artwork. */
    val dynamicTheme = MutableStateFlow(false)

    /** Keep playing similar music once the queue runs out. */
    val autoplay = MutableStateFlow(true)

    /**
     * Vibe autoplay: when the queue runs out, the next track is picked by
     * matching the session's vibe — see
     * [VibeAutoplay][com.music.sonariq.playback.vibe.VibeAutoplay] — rather
     * than by generic similarity. The badge on the player names the vibe the
     * session is currently sitting in.
     */
    val vibeAutoplay = MutableStateFlow(true)

    /**
     * Lets vibe autoplay resurface old favorites that fit the session's
     * vibe, not just tracks that resemble the last one played. Only applies
     * while [vibeAutoplay] is on.
     */
    val vibeForgottenFavorites = MutableStateFlow(true)

    /**
     * How strictly vibe autoplay must match the session's vibe, 0.3 (pure)
     * to 0.9 (adventurous). Only applies while [vibeAutoplay] is on.
     */
    val vibeStrictness = MutableStateFlow(0.6f)

    // ── Autoplay 2.21 ──────────────────────────────────────────────────────

    /**
     * Where autoplay draws from, as relative weights — not percentages that
     * sum to a hundred, just three numbers read against each other. 40/35/25
     * is the stock split: the library first, downloads a hair behind,
     * discovery last.
     */
    val autoplayMixLibrary = MutableStateFlow(40)

    /** See [autoplayMixLibrary]. */
    val autoplayMixDownloads = MutableStateFlow(35)

    /** See [autoplayMixLibrary]. */
    val autoplayMixDiscovery = MutableStateFlow(25)

    /**
     * How far autoplay may stray: 0 = Safe, 1 = Balanced, 2 = Adventurous.
     * A plain int rather than an enum, so the engine's matching tiers map
     * straight onto it.
     */
    val autoplayExploration = MutableStateFlow(1)

    /**
     * Discovery mode: lean autoplay into songs the listener has never
     * played. Off by default — an unfamiliar track is a bigger ask than a
     * familiar one.
     */
    val autoplayDiscoveryMode = MutableStateFlow(false)

    /** Track ids autoplay must never serve, added from a song's menu. */
    val autoplayBannedSongIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Artist names autoplay must never serve, stored lowercased — see
     * [banArtist]. Added from an artist's menu.
     */
    val autoplayBannedArtists = MutableStateFlow<Set<String>>(emptySet())

    /** Autoplay only from what's downloaded; stream nothing. */
    val autoplayDownloadsOnly = MutableStateFlow(false)

    /**
     * Autoplay from the listener's motion and step sensors — a session that
     * follows the activity. Turns itself off if the device has nothing to
     * listen to.
     */
    val activityAutoplay = MutableStateFlow(false)

    /** Wind down towards [windDownBedtimeMinutes], calming the music ahead of it. */
    val windDownEnabled = MutableStateFlow(false)

    /** Bedtime, in minutes since midnight. 1380 is 23:00. */
    val windDownBedtimeMinutes = MutableStateFlow(1380)

    /** How many minutes before bedtime the wind-down begins. */
    val windDownLeadMinutes = MutableStateFlow(60)

    /**
     * Workout session: the music follows the workout's energy curve for
     * [workoutLengthMinutes] from [workoutStartedAtMs].
     */
    val workoutMode = MutableStateFlow(false)

    /** When the current workout session started, 0 when none is running. */
    val workoutStartedAtMs = MutableStateFlow(0L)

    /** How long a workout session runs, in minutes. */
    val workoutLengthMinutes = MutableStateFlow(45)

    /**
     * How a workout session's energy moves: 0 = Steady, 1 = Build,
     * 2 = Peak & Coast, 3 = Wind Down.
     */
    val energyCurve = MutableStateFlow(0)

    /** Whether the queue is held in shuffled order (Mix button). */
    val shuffleEnabled = MutableStateFlow(false)

    /** Repeat mode for the player — Off, All, or One. */
    val repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)

    /** Put the playing track's codec, bitrate and sample rate on the player. */
    val showNerdStats = MutableStateFlow(false)

    /** Freezes the main player's mesh gradient instead of letting it drift/crossfade. */
    val reduceAnimation = MutableStateFlow(false)

    /** Requests a sustained high-refresh UI. Off keeps Android's automatic policy. */
    val highPerformanceMode = MutableStateFlow(false)

    /** Preferred UI refresh rate while [highPerformanceMode] is enabled. */
    val performanceRefreshRate = MutableStateFlow(DEFAULT_PERFORMANCE_REFRESH_RATE)

    /** Stop playback when the app is swiped away from the recent apps screen. */
    val stopOnTaskRemoved = MutableStateFlow(false)

    /** Hides the volume slider on the main player, leaving the rest of the layout to reflow. */
    val hideVolumeBar = MutableStateFlow(false)

    /** Hides the "Playing from" / "Played by" caption at the top of the main player. */
    val hideSongStatus = MutableStateFlow(false)

    /** Swiping a song row plays it next instead of adding it to the end of the queue. */
    val swipeToPlayNext = MutableStateFlow(false)

    /** Once a song has been suggested or played this session, AutoPlay won't offer it again. */
    val dontRepeatSuggestions = MutableStateFlow(false)

    /**
     * Prefer the catalogue audio release when the selected result is a music
     * video. The video itself is still handed to the player first so its
     * metadata appears immediately while the catalogue match is resolved.
     */
    val preferMusicOnly = MutableStateFlow(false)

    /** Analyzes audio waveform/envelope to align matching playback moment between versions. */
    val smartVersionAlignment = MutableStateFlow(true)

    /** Drops haze blur (status bar, mini player, bottom fade, lyrics focus) for a solid-fill look. */
    val reduceDynamicBlur = MutableStateFlow(false)

    /** Real backdrop-sampled glass (blur, lens refraction) on the floating nav bar, Android 12+ only. */
    val liquidGlass = MutableStateFlow(true)

    /** Blurs unfocused lyric lines, keeping the active line sharp. */
    val lyricsBlur = MutableStateFlow(true)

    /** Positive values delay synced lyrics; negative values bring them forward. */
    val lyricsOffsetMs = MutableStateFlow(0)

    /** Per-track lyric offsets, videoId to ms; a track without an entry follows [lyricsOffsetMs]. */
    val lyricsOffsetOverrides = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Lyric line text size, in sp; the panel scales its line sizes around [DEFAULT_LYRICS_FONT_SIZE_SP]. */
    val lyricsFontSizeSp = MutableStateFlow(DEFAULT_LYRICS_FONT_SIZE_SP)

    /** Whether the lyrics panel follows the song on its own. */
    val lyricsAutoScroll = MutableStateFlow(true)

    /** Whether word-synced lines light word by word; off renders whole-line highlights instead. */
    val lyricsKaraokeEffects = MutableStateFlow(true)

    /** How strong the playing line's bloom is, as a scale around [DEFAULT_LYRICS_HIGHLIGHT_INTENSITY]. */
    val lyricsHighlightIntensity = MutableStateFlow(DEFAULT_LYRICS_HIGHLIGHT_INTENSITY)

    /** What the lyrics panel draws behind its lines. */
    val lyricsBackground = MutableStateFlow(LyricsBackground.ARTWORK)

    /** Lyrics-only reduced motion, on top of the app-wide reduce-animation setting. */
    val lyricsReducedMotion = MutableStateFlow(false)

    /**
     * The Now Playing visualizer's rendering mode. Off hides the strip
     * entirely — and the capture with it.
     */
    val visualizerStyle = MutableStateFlow(VisualizerStyle.SPECTRUM)

    /** The visualizer strip's sensitivity; 1 is the meter's own calibration. */
    val visualizerIntensity = MutableStateFlow(DEFAULT_VISUALIZER_INTENSITY)

    /** Battery saver: smaller captures at half the callback rate. */
    val visualizerBatterySaver = MutableStateFlow(false)

    /**
     * Which language the lyrics translate button translates *into*.
     *
     * Blank — the default — means "whatever the app is set to", and is stored
     * as blank rather than resolved once: someone who has never touched this
     * has expressed no preference, and switching the app to Spanish should
     * carry their lyrics with it rather than leaving them on the English they
     * happened to be reading the day the setting was written.
     */
    val translationLanguage = MutableStateFlow("")

    /**
     * Automatic translated subtitle lines under each lyric in the Now Playing
     * lyrics view. Off by default: a translation costs a network call per
     * track, and the manual translate button stays the explicit opt-in.
     */
    val lyricsTranslationOverlay = MutableStateFlow(false)

    /**
     * Plays a looping video behind the cover art on the player when one is
     * published for the track — Spotify's Canvas, Apple's motion artwork.
     *
     * Costs a video stream on top of the audio one and reaches three
     * services that have nothing to do with playback, so it stays a switch —
     * but it is the better default, and most tracks resolve to no canvas at
     * all. See [CanvasRepository][com.music.sonariq.data.canvas.CanvasRepository].
     */
    val animatedCanvas = MutableStateFlow(true)

    /**
     * Whether [animatedCanvas] is allowed to actually stream on a metered
     * connection, as distinct from the switch that turns the feature off
     * altogether.
     *
     * Off by default. A canvas clip loops for as long as its track plays,
     * and every loop past the first re-fetches the same few seconds of video
     * — see [CanvasCache][com.music.sonariq.data.canvas.CanvasCache] for why
     * that costs network at all rather than being answered from a buffer —
     * so a few-second clip behind a four-minute track on cellular is not a
     * flat video cost, it is that cost repeated dozens of times per song.
     * That is the shape of the reported 8GB day: still art costs nothing
     * here and stays up regardless of this setting.
     */
    val canvasOverCellular = MutableStateFlow(false)

    /** Automatically collapses the lower controls after Spotify Canvas settles. */
    val spotifyCanvasAutoHide = MutableStateFlow(true)

    /** Tries Spotify before Apple Music and the other animated-art providers. */
    val prioritizeSpotifyCanvas = MutableStateFlow(false)

    /**
     * Fills the whole Now Playing screen edge to edge with the cover art,
     * sitting the controls on top of it.
     *
     * On means full-cover in portrait and landscape, on phone and tablet alike.
     * Off keeps the classic centred square sleeve over the artwork backdrop.
     */
    val fullBleedArtwork = MutableStateFlow(true)

    /**
     * Puts v1.5's backdrop back on the player: four quantised blobs drifting
     * behind the whole screen, rather than the artwork's own colours hung off
     * the sleeve's bottom edge.
     *
     * Off by default, because the current backdrop replaced it for two reasons
     * that have not gone away — see [ArtworkMesh][com.music.sonariq.ui.player.ArtworkMesh]
     * for the colour one (a cover that is nine-tenths black with a red stripe
     * comes back from the quantiser as a red screen) and
     * [ArtworkMeshBackdrop][com.music.sonariq.ui.player.ArtworkMeshBackdrop]
     * for the cost one (blobs that drift are a full-screen blur redrawn while
     * they move, where a mesh is drawn once per track and then composited).
     * Kept as a switch because people asked for the old look back, and neither
     * reason is one a listener has to agree with.
     */
    val legacyMeshGradient = MutableStateFlow(false)

    /** Restores the expanded player to the surface the listener left open. */
    val lastPlayerScreen = MutableStateFlow(LastPlayerScreen.MAIN)

    /**
     * Time-synced lyrics on the player, lit up as they are sung.
     *
     * On by default — it is most of the point of the player screen — but it
     * reaches third-party lyric databases for every track played, so it stays
     * a switch, and [lyricsSources] narrows which of them get asked.
     */
    val syncedLyrics = MutableStateFlow(true)

    /**
     * Keeps lyrics ready ahead of time: the upcoming queue warms in the
     * background while music plays, and a daily idle job warms favourites
     * and heavy-rotation tracks. Off means lyrics are only fetched when the
     * panel is opened — no prefetch anywhere. Gated on [syncedLyrics] as
     * well: lyric services are not contacted at all while that is off.
     */
    val lyricsPrefetch = MutableStateFlow(true)

    /**
     * Shows the currently sung lyric line in the playback notification, so it
     * follows the user to the lock screen. On by default — it is the kind of
     * quiet delight that should just work. [PlaybackService][com.music.sonariq.playback.PlaybackService]
     * refreshes the notification's content text whenever the line changes.
     */
    val lockscreenLyrics = MutableStateFlow(true)

    /** Check GitHub Releases for a newer Sonariq on launch. On by default. */
    val autoUpdateCheck = MutableStateFlow(true)

    /** The databases [syncedLyrics] may ask. Empty is the same as off. */
    val lyricsSources = MutableStateFlow(LyricsSource.offered.toSet())

    /**
     * The order [lyricsSources] are asked in — see [LyricsRepository][com.music.sonariq.data.lyrics.LyricsRepository]:
     * every enabled source is asked at once, but a higher-priority one still
     * pending is never preempted by a lower one that happened to answer first.
     * Reordered from Settings, so this is a full permutation of
     * [LyricsSource.offered] rather than a subset — enabling and ordering are
     * independent choices.
     */
    val lyricsSourceOrder = MutableStateFlow<List<LyricsSource>>(LyricsSource.offered)

    /**
     * Off, the highest-priority source to answer at all is taken as the
     * lyrics, word-synced or not. On, a merely line-synced answer is held as
     * a fallback while the rest of [lyricsSourceOrder] is still checked for a
     * word-synced one. Defaults on: a genuinely measured word-sync is worth
     * the extra network calls, and estimated timings never outrank measured
     * ones.
     */
    val prioritizeSyllableSync = MutableStateFlow(true)

    /**
     * Whether AI word-sync measurement may only run in the background on
     * unmetered networks. On by default, like the other background jobs
     * that spend data on the user's behalf.
     */
    val aiWifiOnly = MutableStateFlow(true)

    /** User-issued credential required by api.paxsenix.org. */
    val paxSenixApiKey = MutableStateFlow("")

    /**
     * Disk budget for cached audio. [AudioCache][com.music.sonariq.playback.AudioCache] evicts past it.
     * [UNLIMITED_CACHE_LIMIT_BYTES] means no ceiling of the app's own.
     */
    val audioCacheLimitBytes = MutableStateFlow(DEFAULT_CACHE_LIMIT_BYTES)

    /**
     * Whether Library's "On device" shelf carries the Cached songs folder —
     * the YouTube and JioSaavn tracks the song cache is holding. Off by
     * default: the cache is an implementation detail most people never need
     * to look inside.
     */
    val showCacheFolder = MutableStateFlow(false)

    // ── Replay ──────────────────────────────────────────────────────────────

    /**
     * Whether Replay may work out a genre chart.
     *
     * Its own switch because it is the one part of Replay that isn't purely
     * local: everything else on that page is counted on this device and never
     * leaves it, while a genre has to be looked up by artist name — see
     * [ArtistFacts][com.music.sonariq.data.stats.ArtistFacts]. On by default,
     * since it sends a name and nothing else and the answer is what makes a
     * quarter of the page exist; off, the genre chart simply isn't drawn.
     */
    val replayGenres = MutableStateFlow(true)

    // ── Library ─────────────────────────────────────────────────────────────

    /** Hides short clips, recorder output and non-music formats from Local Music. */
    val filterNonMusicAudio = MutableStateFlow(true)

    val localMusicSort = MutableStateFlow(LocalMusicSort.TITLE_ASC)
    val downloadedMusicSort = MutableStateFlow(LocalMusicSort.TITLE_ASC)
    val localMusicViewType = MutableStateFlow(LibraryViewType.LIST)
    val downloadedMusicViewType = MutableStateFlow(LibraryViewType.LIST)
    /** Layout used by the Recents shelf on Play; compact tracks are the default. */
    val homeRecentsViewType = MutableStateFlow(LibraryViewType.LIST)
    val librarySort = MutableStateFlow(LibrarySort.DEFAULT)

    /**
     * Each album/playlist page's track-list order, keyed by browse id —
     * Spotify-style, every page keeps its own. A page never touched reads as
     * [SongSort.DEFAULT].
     */
    val detailSongSorts = MutableStateFlow<Map<String, SongSort>>(emptyMap())

    /** Empty means every MediaStore folder; otherwise this is a persisted SAF tree URI. */
    val localMusicFolderUri = MutableStateFlow("")

    // ── WebDAV ────────────────────────────────────────────────────────────

    /**
     * Remote music library over WebDAV (e.g. Nextcloud's Music folder).
     *
     * The URL and username live in plain prefs like every other setting; the
     * password is mirrored out of [AuthStore] so it stays encrypted at rest
     * and out of backup exports — see [exportPrefs]. Empty URL means
     * unconfigured, and the library simply reads as empty.
     */
    val webdavUrl = MutableStateFlow("")
    val webdavUsername = MutableStateFlow("")
    val webdavPassword = MutableStateFlow("")

    // ── SMB ───────────────────────────────────────────────────────────────

    /**
     * Remote music library on an SMB file share (a NAS, a Windows box).
     *
     * Stored like the WebDAV settings: host, share, base folder and username
     * in plain prefs, the password mirrored out of [AuthStore] so it stays
     * encrypted at rest and out of backup exports. Empty host or share means
     * unconfigured, and the library simply reads as empty.
     */
    val smbHost = MutableStateFlow("")
    val smbShare = MutableStateFlow("")
    val smbBasePath = MutableStateFlow("")
    val smbUsername = MutableStateFlow("")
    val smbPassword = MutableStateFlow("")

    /**
     * Browse ids of the playlists pinned to the top of the Library tab, in the
     * order they were pinned.
     *
     * A [List] rather than a [Set]: pin order is part of what a pin means here —
     * the whole point is a small, hand-picked front row, and a set would leave
     * that order to hash iteration. Capped at [MAX_PINNED_PLAYLISTS] by
     * [togglePinnedPlaylist], the only way this is ever written.
     */
    val pinnedPlaylists = MutableStateFlow<List<String>>(emptyList())

    /** How many playlists [pinnedPlaylists] can hold at once. */
    const val MAX_PINNED_PLAYLISTS = 5

    // ── Scrobbling ──────────────────────────────────────────────────────

    /** One release gate shared by the settings UI and the playback service. */
    val scrobblingAvailable = true

    val lastfmEnabled = MutableStateFlow(false)
    val lastfmUsername = MutableStateFlow("")
    val lastfmSessionKey = MutableStateFlow("")
    val lastfmApiKey = MutableStateFlow("")
    val lastfmSecret = MutableStateFlow("")
    val lastfmEndpoint = MutableStateFlow("")
    val lastfmScrobbleEnabled = MutableStateFlow(false)
    val lastfmNowPlaying = MutableStateFlow(false)
    val lastfmPrimaryArtistOnly = MutableStateFlow(false)
    val scrobbleMinDuration = MutableStateFlow(30)
    val scrobbleDelayPercent = MutableStateFlow(0.5f)
    val scrobbleDelaySeconds = MutableStateFlow(180)
    val listenBrainzEnabled = MutableStateFlow(false)
    val listenBrainzToken = MutableStateFlow("")
    val listenBrainzPrimaryArtistOnly = MutableStateFlow(false)
    val spotifySpdcToken = MutableStateFlow("")

    // ── Discord Rich Presence ───────────────────────────────────────────

    /**
     * The connected Discord account's token, mirrored out of [AuthStore] so
     * [PlaybackService][com.music.sonariq.playback.PlaybackService] can pick
     * up a login without polling for one. Empty means not connected.
     *
     * Only the mirror is here — the persisted copy is encrypted, because unlike
     * a scrobbler key this one is the account itself.
     */
    val discordToken = MutableStateFlow("")

    /**
     * Who the token belongs to, cached at login. Kept so the settings screen
     * can show the account without a round trip every time it opens, and can
     * still show it offline.
     */
    val discordUsername = MutableStateFlow("")
    val discordName = MutableStateFlow("")
    val discordAvatar = MutableStateFlow("")

    val discordRpcEnabled = MutableStateFlow(true)

    /** Put the track title on the bold profile line, in place of the artist. */
    val discordUseDetails = MutableStateFlow(false)

    /** Show measured Hi-Res, Lossless, or Dolby specs on the presence card. */
    val discordShowAudioQuality = MutableStateFlow(true)

    /** Reveals the presence-shape controls: status, activity type/name, buttons. */
    val discordAdvancedMode = MutableStateFlow(false)

    val discordStatus = MutableStateFlow("online")
    val discordActivityType = MutableStateFlow("listening")

    /** Overrides the "Listening to ___" line; empty means the app's own name. */
    val discordActivityName = MutableStateFlow("")

    val discordButton1Text = MutableStateFlow("")
    val discordButton1Visible = MutableStateFlow(true)
    val discordButton2Text = MutableStateFlow("")
    val discordButton2Visible = MutableStateFlow(true)

    /** The notice about what connecting an account actually does has been read. */
    val discordInfoDismissed = MutableStateFlow(false)

    /** Published by PlaybackService so the UI can open the system equalizer. */
    val audioSessionId = MutableStateFlow(0)

    /**
     * True only while a Automix transition that is actually *mixing* is
     * audible — one that beat-matched, cued the incoming track into its
     * arrangement, or rode a filter.
     *
     * Deliberately not "a crossfade is running". The fallback case, where
     * neither track was analysed in time and the incoming one starts from 0:00
     * under a plain equal-power fade, is exactly what this must stay dark for:
     * the whole point is that seeing it means the analysis landed and did
     * something a plain crossfade could not.
     */
    val smartMixInProgress = MutableStateFlow(false)

    /**
     * The Automix blend in flight — its progress and the beat it runs on — or
     * null between blends. Published by the crossfade controller every fade
     * tick; read it in draw, not in composition.
     */
    val smartMixBlend = MutableStateFlow<MixBlend?>(null)

    /**
     * True while a version switch is fetching and analysing the other cut
     * before playback actually moves. Drains into the loading bar drawn along
     * the scrubber itself — `ThinSlider.loading` — so the wait reads as work
     * in progress rather than as a player frozen on a version that is about to
     * change, and lights the toggle button's spinner through the half of the
     * switch that has nothing else showing.
     */
    val versionAlignmentInProgress = MutableStateFlow(false)

    /**
     * How much of the *upcoming* transition has been analysed, for stats for
     * nerds. Published by the crossfade controller, which is the only thing
     * that knows which two tracks the next transition is between.
     */
    val smartAnalysis = MutableStateFlow(SmartAnalysis())

    /**
     * Where on the *playing* track the next transition is planned to happen, as
     * fractions of its duration, or null when there is nothing worth drawing.
     *
     * Only published once both tracks are measured. Before that the planner is
     * still working from a fallback window that moves as evidence arrives, and
     * a marker that slides around the bar would be worse than no marker.
     */
    val smartTransitionWindow = MutableStateFlow<TransitionWindow?>(null)

    /** The ceiling that applies to a stream started right now. */
    val effectiveAudioQuality: AudioQuality
        get() = if (meteredConnection.value == true) {
            audioQualityCellular.value
        } else {
            audioQualityWifi.value
        }

    /**
     * Whether a download may start on the connection in hand.
     *
     * A null [meteredConnection] means there is no active network, and that is
     * deliberately allowed through: a download with nothing to download over
     * fails on the network and says so, which is true, where refusing it here
     * would blame a Wi-Fi setting for an outage.
     */
    val downloadsAllowedNow: Boolean
        get() = !wifiOnlyDownloads.value || meteredConnection.value != true

    /**
     * [authStore] is the application's own, passed in rather than opened again:
     * each open of the encrypted store is a keystore round trip, and a second
     * one here was a measurable slice of cold start.
     */
    fun init(context: Context, authStore: AuthStore) {
        prefs = context.getSharedPreferences("sonariq_settings", Context.MODE_PRIVATE)
        this.authStore = authStore
        com.music.sonariq.data.spotify.LocalPlaylistStore.init(context)
        readAll()
        watchConnection(context)
    }

    /**
     * Re-reads every setting off disk.
     *
     * The one caller is an import ([Backup][com.music.sonariq.data.stats.Backup]),
     * which writes the whole preference file underneath these flows. Nothing
     * else in the app changes a preference without going through the setter
     * beside it, so nothing else has a reason to ask.
     *
     * Deliberately not re-registering the network callback: that watches the
     * device, not the preferences, and a second one would have both firing.
     */
    fun reload() {
        if (!this::prefs.isInitialized) return
        readAll()
    }

    private fun readAll() {
        migrateSingleQuality()
        audioQualityWifi.value = readQuality(KEY_QUALITY_WIFI)
        audioQualityCellular.value = readQuality(KEY_QUALITY_CELLULAR)
        upgradeLengthSlackSeconds.value = prefs.getInt(
            KEY_UPGRADE_LENGTH_SLACK_SECONDS,
            DEFAULT_UPGRADE_LENGTH_SLACK_SECONDS,
        ).coerceIn(MIN_UPGRADE_LENGTH_SLACK_SECONDS, MAX_UPGRADE_LENGTH_SLACK_SECONDS)
        migrateDownloadQuality()
        downloadQuality.value = readDownloadQuality()
        wifiOnlyDownloads.value = prefs.getBoolean(KEY_WIFI_ONLY_DOWNLOADS, true)
        exportDownloads.value = prefs.getBoolean(KEY_EXPORT_DOWNLOADS, false)
        smartDownloadsEnabled.value = prefs.getBoolean(KEY_SMART_DOWNLOADS_ENABLED, false)
        smartDownloadsCount.value = prefs.getInt(KEY_SMART_DOWNLOADS_COUNT, 50).coerceIn(25, 100)
        smartDownloadsWifiOnly.value = prefs.getBoolean(KEY_SMART_DOWNLOADS_WIFI_ONLY, true)
        offlineMode.value = prefs.getBoolean(KEY_OFFLINE_MODE, false)
        concertAlertsEnabled.value = prefs.getBoolean(KEY_CONCERT_ALERTS_ENABLED, false)
        ticketmasterApiKey.value = prefs.getString(KEY_TICKETMASTER_API_KEY, "").orEmpty()
        castTvLyricsEnabled.value = prefs.getBoolean(KEY_CAST_TV_LYRICS_ENABLED, true)
        castReceiverAppId.value = prefs.getString(KEY_CAST_RECEIVER_APP_ID, "").orEmpty()
        crossfadeSeconds.value = prefs.getInt(KEY_CROSSFADE, 0)
        smartFadeEnabled.value = prefs.getBoolean(KEY_SMART_FADE, false)
        djMixEnabled.value = prefs.getBoolean(KEY_DJ_MIX, false)
        sleepTimerFadeOut.value = prefs.getBoolean(KEY_SLEEP_TIMER_FADE_OUT, true)
        automixPerformanceMode.value = runCatching {
            AutomixPerformanceMode.valueOf(
                prefs.getString(KEY_AUTOMIX_PERFORMANCE_MODE, null) ?: AutomixPerformanceMode.BALANCED.name,
            )
        }.getOrDefault(AutomixPerformanceMode.BALANCED)
        skipSilence.value = prefs.getBoolean(KEY_SKIP_SILENCE, false)
        outputPcmMode.value = runCatching {
            OutputPcmMode.valueOf(
                prefs.getString(KEY_OUTPUT_PCM_MODE, OutputPcmMode.PCM_16.name)
                    ?: OutputPcmMode.PCM_16.name,
            )
        }.getOrDefault(OutputPcmMode.PCM_16)
        preferUsbDac.value = prefs.getBoolean(KEY_PREFER_USB_DAC, false)
        loudnessNormalization.value = prefs.getBoolean(KEY_LOUDNESS_NORMALIZATION, true)
        loudnessOffOnSpeaker.value = prefs.getBoolean(KEY_LOUDNESS_OFF_ON_SPEAKER, true)
        dolbyAtmos.value = prefs.getBoolean(KEY_DOLBY_ATMOS, true)
        spatialAudio.value = prefs.getBoolean(KEY_SPATIAL_AUDIO, false)
        equalizerEnabled.value = prefs.getBoolean(KEY_EQ_ENABLED, false)
        equalizerMode.value = runCatching {
            EqualizerMode.valueOf(prefs.getString(KEY_EQ_MODE, null) ?: EqualizerMode.DYNAMIC.name)
        }.getOrDefault(EqualizerMode.DYNAMIC)
        equalizerToneX.value = prefs.getInt(KEY_EQ_TONE_X, 0).coerceIn(-EqLayout.TONE_STEPS, EqLayout.TONE_STEPS)
        equalizerToneY.value = prefs.getInt(KEY_EQ_TONE_Y, 0).coerceIn(-EqLayout.TONE_STEPS, EqLayout.TONE_STEPS)
        equalizerFocused.value = prefs.getBoolean(KEY_EQ_FOCUSED, false)
        equalizerBalance.value = prefs.getFloat(KEY_EQ_BALANCE, 0f).coerceIn(-1f, 1f)
        equalizerBands.value = readEqualizerBands()
        equalizerPreset.value = EqualizerPreset.matching(equalizerBands.value)
        equalizerBass.value = prefs.getFloat(KEY_EQ_BASS, 0f)
            .coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        equalizerTreble.value = prefs.getFloat(KEY_EQ_TREBLE, 0f)
            .coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        userEqualizerPresets.value =
            decodeUserEqualizerPresets(prefs.getString(KEY_EQ_USER_PRESETS, null))
        // A restored backup can name a preset whose curve it no longer
        // matches, or one that no longer exists: only the name that still
        // describes the restored bands survives the restart.
        activeUserPresetName.value = prefs.getString(KEY_EQ_USER_PRESET_ACTIVE, null)
            ?.takeIf { name ->
                userEqualizerPresets.value.any { preset ->
                    preset.name == name && eqBandsMatch(preset.bandsDb, equalizerBands.value)
                }
            }
        accentColor.value = accentColorFromStored(prefs.getString(KEY_ACCENT_COLOR, null))
        customAccentArgb.value =
            if (prefs.contains(KEY_ACCENT_COLOR_CUSTOM)) prefs.getInt(KEY_ACCENT_COLOR_CUSTOM, 0) else null
        dynamicTheme.value = prefs.getBoolean(KEY_DYNAMIC_THEME, false)
        playbackSpeed.value = prefs.getFloat(KEY_SPEED, 1.0f)
        themeMode.value = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "DARK")
        }.getOrDefault(ThemeMode.DARK)
        autoplay.value = prefs.getBoolean(KEY_AUTOPLAY, true)
        vibeAutoplay.value = prefs.getBoolean(KEY_VIBE_AUTOPLAY, true)
        vibeForgottenFavorites.value = prefs.getBoolean(KEY_VIBE_FORGOTTEN_FAVORITES, true)
        vibeStrictness.value = prefs.getFloat(KEY_VIBE_STRICTNESS, DEFAULT_VIBE_STRICTNESS)
            .coerceIn(MIN_VIBE_STRICTNESS, MAX_VIBE_STRICTNESS)
        autoplayMixLibrary.value = prefs.getInt(KEY_AUTOPLAY_MIX_LIBRARY, 40).coerceIn(0, 100)
        autoplayMixDownloads.value = prefs.getInt(KEY_AUTOPLAY_MIX_DOWNLOADS, 35).coerceIn(0, 100)
        autoplayMixDiscovery.value = prefs.getInt(KEY_AUTOPLAY_MIX_DISCOVERY, 25).coerceIn(0, 100)
        autoplayExploration.value = prefs.getInt(KEY_AUTOPLAY_EXPLORATION, 1).coerceIn(0, 2)
        autoplayDiscoveryMode.value = prefs.getBoolean(KEY_AUTOPLAY_DISCOVERY_MODE, false)
        autoplayBannedSongIds.value = prefs.getStringSet(KEY_AUTOPLAY_BANNED_SONG_IDS, emptySet()).orEmpty()
        autoplayBannedArtists.value = prefs.getStringSet(KEY_AUTOPLAY_BANNED_ARTISTS, emptySet()).orEmpty()
        autoplayDownloadsOnly.value = prefs.getBoolean(KEY_AUTOPLAY_DOWNLOADS_ONLY, false)
        activityAutoplay.value = prefs.getBoolean(KEY_ACTIVITY_AUTOPLAY, false)
        windDownEnabled.value = prefs.getBoolean(KEY_WIND_DOWN_ENABLED, false)
        windDownBedtimeMinutes.value = prefs.getInt(KEY_WIND_DOWN_BEDTIME_MINUTES, 1380).coerceIn(0, 1439)
        windDownLeadMinutes.value = prefs.getInt(KEY_WIND_DOWN_LEAD_MINUTES, 60).coerceIn(0, 720)
        workoutMode.value = prefs.getBoolean(KEY_WORKOUT_MODE, false)
        workoutStartedAtMs.value = prefs.getLong(KEY_WORKOUT_STARTED_AT_MS, 0L)
        workoutLengthMinutes.value = prefs.getInt(KEY_WORKOUT_LENGTH_MINUTES, 45).coerceIn(1, 480)
        energyCurve.value = prefs.getInt(KEY_ENERGY_CURVE, 0).coerceIn(0, 3)
        shuffleEnabled.value = prefs.getBoolean(KEY_SHUFFLE_ENABLED, false)
        repeatMode.value = prefs.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF)
        showNerdStats.value = prefs.getBoolean(KEY_NERD_STATS, false)
        reduceAnimation.value = prefs.getBoolean(KEY_REDUCE_ANIMATION, false)
        highPerformanceMode.value = prefs.getBoolean(KEY_HIGH_PERFORMANCE_MODE, false)
        performanceRefreshRate.value = normalizePerformanceRefreshRate(
            prefs.getInt(KEY_PERFORMANCE_REFRESH_RATE, DEFAULT_PERFORMANCE_REFRESH_RATE),
        )
        stopOnTaskRemoved.value = prefs.getBoolean(KEY_STOP_ON_TASK_REMOVED, false)
        hideVolumeBar.value = prefs.getBoolean(KEY_HIDE_VOLUME_BAR, false)
        hideSongStatus.value = prefs.getBoolean(KEY_HIDE_SONG_STATUS, false)
        swipeToPlayNext.value = prefs.getBoolean(KEY_SWIPE_TO_PLAY_NEXT, false)
        dontRepeatSuggestions.value = prefs.getBoolean(KEY_DONT_REPEAT_SUGGESTIONS, false)
        preferMusicOnly.value = prefs.getBoolean(KEY_PREFER_MUSIC_ONLY, false)
        smartVersionAlignment.value = prefs.getBoolean(KEY_SMART_VERSION_ALIGNMENT, true)
        reduceDynamicBlur.value = prefs.getBoolean(KEY_REDUCE_BLUR, false)
        liquidGlass.value = prefs.getBoolean(KEY_LIQUID_GLASS, true)
        lyricsBlur.value = prefs.getBoolean(KEY_LYRICS_BLUR, true)
        lyricsOffsetMs.value = prefs.getInt(KEY_LYRICS_OFFSET_MS, 0)
            .coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
        lyricsOffsetOverrides.value =
            decodeLyricOffsets(prefs.getString(KEY_LYRICS_OFFSET_OVERRIDES, null).orEmpty())
        lyricsFontSizeSp.value =
            prefs.getFloat(KEY_LYRICS_FONT_SIZE_SP, DEFAULT_LYRICS_FONT_SIZE_SP)
                .coerceIn(MIN_LYRICS_FONT_SIZE_SP, MAX_LYRICS_FONT_SIZE_SP)
        lyricsAutoScroll.value = prefs.getBoolean(KEY_LYRICS_AUTO_SCROLL, true)
        lyricsKaraokeEffects.value = prefs.getBoolean(KEY_LYRICS_KARAOKE, true)
        lyricsHighlightIntensity.value =
            prefs.getFloat(KEY_LYRICS_HIGHLIGHT_INTENSITY, DEFAULT_LYRICS_HIGHLIGHT_INTENSITY)
                .coerceIn(MIN_LYRICS_HIGHLIGHT_INTENSITY, MAX_LYRICS_HIGHLIGHT_INTENSITY)
        lyricsBackground.value = LyricsBackground.fromPersistedName(
            prefs.getString(KEY_LYRICS_BACKGROUND, null),
        )
        lyricsReducedMotion.value = prefs.getBoolean(KEY_LYRICS_REDUCED_MOTION, false)
        visualizerStyle.value = VisualizerStyle.fromPersistedName(
            prefs.getString(KEY_VISUALIZER_STYLE, null),
        )
        visualizerIntensity.value =
            prefs.getFloat(KEY_VISUALIZER_INTENSITY, DEFAULT_VISUALIZER_INTENSITY)
                .coerceIn(MIN_VISUALIZER_INTENSITY, MAX_VISUALIZER_INTENSITY)
        visualizerBatterySaver.value = prefs.getBoolean(KEY_VISUALIZER_BATTERY_SAVER, false)
        translationLanguage.value = prefs.getString(KEY_TRANSLATION_LANGUAGE, "").orEmpty()
        lyricsTranslationOverlay.value = prefs.getBoolean(KEY_LYRICS_TRANSLATION_OVERLAY, false)
        if (highPerformanceMode.value) {
            reduceAnimation.value = false
            reduceDynamicBlur.value = false
        }
        animatedCanvas.value = prefs.getBoolean(KEY_ANIMATED_CANVAS, true)
        canvasOverCellular.value = prefs.getBoolean(KEY_CANVAS_OVER_CELLULAR, false)
        spotifyCanvasAutoHide.value = prefs.getBoolean(KEY_SPOTIFY_CANVAS_AUTO_HIDE, true)
        prioritizeSpotifyCanvas.value = prefs.getBoolean(KEY_PRIORITIZE_SPOTIFY_CANVAS, false)
        fullBleedArtwork.value = prefs.getBoolean(KEY_FULL_BLEED_ARTWORK, true)
        legacyMeshGradient.value = prefs.getBoolean(KEY_LEGACY_MESH_GRADIENT, false)
        lastPlayerScreen.value = runCatching {
            LastPlayerScreen.valueOf(
                prefs.getString(KEY_LAST_PLAYER_SCREEN, null) ?: LastPlayerScreen.MAIN.name,
            )
        }.getOrDefault(LastPlayerScreen.MAIN)
        syncedLyrics.value = prefs.getBoolean(KEY_SYNCED_LYRICS, true)
        lyricsPrefetch.value = prefs.getBoolean(KEY_LYRICS_PREFETCH, true)
        lockscreenLyrics.value = prefs.getBoolean(KEY_LOCKSCREEN_LYRICS, true)
        autoUpdateCheck.value = prefs.getBoolean(KEY_AUTO_UPDATE_CHECK, true)
        lyricsSources.value = readLyricsSources()
        lyricsSourceOrder.value = readLyricsSourceOrder()
        prioritizeSyllableSync.value = prefs.getBoolean(KEY_PRIORITIZE_SYLLABLE_SYNC, true)
        aiWifiOnly.value = prefs.getBoolean(KEY_AI_WIFI_ONLY, true)
        paxSenixApiKey.value = prefs.getString(KEY_PAXSENIX_API_KEY, "").orEmpty()
        com.music.sonariq.data.lyrics.PaxSenix.setApiKey(paxSenixApiKey.value)
        audioCacheLimitBytes.value = clampCacheLimit(prefs.getLong(KEY_CACHE_LIMIT, DEFAULT_CACHE_LIMIT_BYTES))
        showCacheFolder.value = prefs.getBoolean(KEY_SHOW_CACHE_FOLDER, false)
        lastfmEnabled.value = prefs.getBoolean(KEY_LASTFM_ENABLED, false)
        lastfmUsername.value = prefs.getString(KEY_LASTFM_USERNAME, "").orEmpty()
        lastfmSessionKey.value = prefs.getString(KEY_LASTFM_SESSION_KEY, "").orEmpty()
        lastfmApiKey.value = prefs.getString(KEY_LASTFM_API_KEY, "").orEmpty().ifBlank { BuildConfig.LASTFM_API_KEY }
        lastfmSecret.value = prefs.getString(KEY_LASTFM_SECRET, "").orEmpty().ifBlank { BuildConfig.LASTFM_SECRET }
        lastfmEndpoint.value = prefs.getString(KEY_LASTFM_ENDPOINT, "").orEmpty()
        lastfmScrobbleEnabled.value = prefs.getBoolean(KEY_LASTFM_SCROBBLE_ENABLED, false)
        lastfmNowPlaying.value = prefs.getBoolean(KEY_LASTFM_NOW_PLAYING, false) && lastfmScrobbleEnabled.value
        lastfmPrimaryArtistOnly.value = prefs.getBoolean(KEY_LASTFM_PRIMARY_ARTIST_ONLY, false)
        scrobbleMinDuration.value = prefs.getInt(KEY_SCROBBLE_MIN_DURATION, 30)
        scrobbleDelayPercent.value = prefs.getFloat(KEY_SCROBBLE_DELAY_PERCENT, 0.5f)
        scrobbleDelaySeconds.value = prefs.getInt(KEY_SCROBBLE_DELAY_SECONDS, 180)
        listenBrainzEnabled.value = prefs.getBoolean(KEY_LISTENBRAINZ_ENABLED, false)
        listenBrainzToken.value = prefs.getString(KEY_LISTENBRAINZ_TOKEN, "").orEmpty()
        listenBrainzPrimaryArtistOnly.value = prefs.getBoolean(KEY_LISTENBRAINZ_PRIMARY_ARTIST_ONLY, false)
        spotifySpdcToken.value = prefs.getString(KEY_SPOTIFY_SPDC_TOKEN, "").orEmpty()
        replayGenres.value = prefs.getBoolean(KEY_REPLAY_GENRES, true)
        filterNonMusicAudio.value = prefs.getBoolean(KEY_FILTER_NON_MUSIC_AUDIO, true)
        localMusicSort.value = readLocalMusicSort(KEY_LOCAL_MUSIC_SORT)
        downloadedMusicSort.value = readLocalMusicSort(KEY_DOWNLOADED_MUSIC_SORT)
        localMusicViewType.value = readLibraryViewType(KEY_LOCAL_MUSIC_VIEW_TYPE)
        downloadedMusicViewType.value = readLibraryViewType(KEY_DOWNLOADED_MUSIC_VIEW_TYPE)
        homeRecentsViewType.value = readLibraryViewType(KEY_HOME_RECENTS_VIEW_TYPE)
        librarySort.value = prefs.getString(KEY_LIBRARY_SORT, null)
            ?.let { saved -> LibrarySort.entries.firstOrNull { it.name == saved } }
            ?: LibrarySort.DEFAULT
        detailSongSorts.value = readDetailSongSorts()
        localMusicFolderUri.value = prefs.getString(KEY_LOCAL_MUSIC_FOLDER_URI, "").orEmpty()
        webdavUrl.value = prefs.getString(KEY_WEBDAV_URL, "").orEmpty()
        webdavUsername.value = prefs.getString(KEY_WEBDAV_USERNAME, "").orEmpty()
        webdavPassword.value = authStore.webdavPassword.orEmpty()
        smbHost.value = prefs.getString(KEY_SMB_HOST, "").orEmpty()
        smbShare.value = prefs.getString(KEY_SMB_SHARE, "").orEmpty()
        smbBasePath.value = prefs.getString(KEY_SMB_BASE_PATH, "").orEmpty()
        smbUsername.value = prefs.getString(KEY_SMB_USERNAME, "").orEmpty()
        smbPassword.value = authStore.smbPassword.orEmpty()
        com.music.sonariq.data.smb.SmbAuth.update(
            smbHost.value,
            smbShare.value,
            smbBasePath.value,
            smbUsername.value,
            smbPassword.value,
        )
        com.music.sonariq.data.webdav.WebDavAuth.update(
            webdavUrl.value,
            webdavUsername.value,
            webdavPassword.value,
        )
        pinnedPlaylists.value = readPinnedPlaylists()
        discordToken.value = authStore.discordToken.orEmpty()
        discordUsername.value = prefs.getString(KEY_DISCORD_USERNAME, "").orEmpty()
        discordName.value = prefs.getString(KEY_DISCORD_NAME, "").orEmpty()
        discordAvatar.value = prefs.getString(KEY_DISCORD_AVATAR, "").orEmpty()
        discordRpcEnabled.value = prefs.getBoolean(KEY_DISCORD_RPC_ENABLED, true)
        discordUseDetails.value = prefs.getBoolean(KEY_DISCORD_USE_DETAILS, false)
        discordShowAudioQuality.value = prefs.getBoolean(KEY_DISCORD_SHOW_AUDIO_QUALITY, true)
        discordAdvancedMode.value = prefs.getBoolean(KEY_DISCORD_ADVANCED_MODE, false)
        discordStatus.value = prefs.getString(KEY_DISCORD_STATUS, "online").orEmpty()
        discordActivityType.value = prefs.getString(KEY_DISCORD_ACTIVITY_TYPE, "listening").orEmpty()
        discordActivityName.value = prefs.getString(KEY_DISCORD_ACTIVITY_NAME, "").orEmpty()
        discordButton1Text.value = prefs.getString(KEY_DISCORD_BUTTON_1_TEXT, "").orEmpty()
        discordButton1Visible.value = prefs.getBoolean(KEY_DISCORD_BUTTON_1_VISIBLE, true)
        discordButton2Text.value = prefs.getString(KEY_DISCORD_BUTTON_2_TEXT, "").orEmpty()
        discordButton2Visible.value = prefs.getBoolean(KEY_DISCORD_BUTTON_2_VISIBLE, true)
        discordInfoDismissed.value = prefs.getBoolean(KEY_DISCORD_INFO_DISMISSED, false)
        perDeviceEqEnabled.value = prefs.getBoolean(KEY_PER_DEVICE_EQ, true)
        monoAudioEnabled.value = prefs.getBoolean(KEY_MONO_AUDIO, false)
        canvasBackdropEnabled.value = prefs.getBoolean(KEY_CANVAS_BACKDROP, true)
        dataSaverEnabled.value = prefs.getBoolean(KEY_DATA_SAVER, false)
        dataSaverQuality.value = prefs.getString(KEY_DATA_SAVER_QUALITY, null)
            ?.let { stored -> runCatching { AudioQuality.valueOf(stored) }.getOrDefault(AudioQuality.LOW) }
            ?: AudioQuality.LOW
        wifiOnlyStreaming.value = prefs.getBoolean(KEY_WIFI_ONLY_STREAMING, false)
        autoDownloadsEnabled.value = prefs.getBoolean(KEY_AUTO_DOWNLOADS_ENABLED, false)
        autoDownloadLikedSongs.value = prefs.getBoolean(KEY_AUTO_DOWNLOAD_LIKED_SONGS, true)
        autoDownloadPlaylistIds.value = prefs.getStringSet(KEY_AUTO_DOWNLOAD_PLAYLIST_IDS, emptySet()).orEmpty()
        autoDownloadWifiOnly.value = prefs.getBoolean(KEY_AUTO_DOWNLOAD_WIFI_ONLY, true)
        scheduledDownloadsEnabled.value = prefs.getBoolean(KEY_SCHEDULED_DOWNLOADS_ENABLED, false)
        scheduledDownloadHour.value = prefs.getInt(KEY_SCHEDULED_DOWNLOAD_HOUR, 2)
        scheduledDownloadMinute.value = prefs.getInt(KEY_SCHEDULED_DOWNLOAD_MINUTE, 0)
        scheduledDownloadWifiOnly.value = prefs.getBoolean(KEY_SCHEDULED_DOWNLOAD_WIFI_ONLY, true)
        homeSectionOrderJson.value = prefs.getString(KEY_HOME_SECTION_ORDER, "").orEmpty()
        homeSectionLearningEnabled.value = prefs.getBoolean(KEY_HOME_SECTION_LEARNING, true)
        savedQueuesJson.value = prefs.getString(KEY_SAVED_QUEUES, "").orEmpty()
        tvAmbient2Enabled.value = prefs.getBoolean(KEY_TV_AMBIENT_2, false)
        artworkGesturesEnabled.value = prefs.getBoolean(KEY_ARTWORK_GESTURES, true)
        privateSessionEnabled.value = prefs.getBoolean(KEY_PRIVATE_SESSION, false)
    }

    /**
     * True the first time this is called after [currentVersionCode] rises above
     * whatever was last recorded — i.e. once per update, on the first launch
     * after it installs. A fresh install has nothing to compare against, so
     * the very first call seeds the stored value from [currentVersionCode]
     * rather than reporting an update.
     *
     * Sonariq ships sideloaded (see [com.music.sonariq.data.AppUpdateChecker]),
     * so installing a new APK over the old one is the only "update" there is —
     * app data, this pref included, survives it exactly like a Play Store
     * update. Call once per process start, before anything reads a cache that
     * an update should invalidate.
     */
    fun consumeVersionUpdate(currentVersionCode: Int): Boolean {
        val last = prefs.getInt(KEY_LAST_VERSION_CODE, currentVersionCode)
        if (last != currentVersionCode) {
            prefs.edit().putInt(KEY_LAST_VERSION_CODE, currentVersionCode).apply()
        }
        return currentVersionCode > last
    }

    /**
     * A ceiling saved when there was only one applies to both connections.
     * Someone who picked Low to protect a data plan would not thank us for
     * quietly putting Wi-Fi *and* mobile back on High.
     */
    private fun migrateSingleQuality() {
        val legacy = prefs.getString(KEY_QUALITY_LEGACY, null) ?: return
        prefs.edit()
            .putString(KEY_QUALITY_WIFI, legacy)
            .putString(KEY_QUALITY_CELLULAR, legacy)
            .remove(KEY_QUALITY_LEGACY)
            .apply()
    }

    private fun readQuality(key: String): AudioQuality {
        val stored = prefs.getString(key, null) ?: return AudioQuality.LOSSLESS
        return runCatching { AudioQuality.valueOf(stored) }.getOrDefault(AudioQuality.LOSSLESS)
    }

    /**
     * Write down what the download path was already doing, before it starts
     * being asked instead.
     *
     * Download quality used to be derived rather than chosen: a lossless copy
     * was kept when `SourceResolver.requestForNow()` said Lossless, which meant
     * a download quietly turned on the lossless preference and off again with
     * it. Someone who switched that off on the Sources screen was getting AAC
     * downloads on purpose, and defaulting them to Lossless now would answer a
     * question they had already answered — with thirty-five megabytes a track.
     *
     * The ceilings are deliberately *not* consulted. They were only in that
     * derivation because there was nowhere else to say "not on mobile data",
     * and [wifiOnlyDownloads] is now where that is said.
     */
    private fun migrateDownloadQuality() {
        if (prefs.contains(KEY_QUALITY_DOWNLOAD)) return
        // Was derived from the old `losslessAudio` switch, which defaulted to
        // on; LOSSLESS is what that produced for all but the few installs that
        // had turned it off, and is the default a fresh install gets anyway.
        prefs.edit().putString(KEY_QUALITY_DOWNLOAD, DownloadQuality.LOSSLESS.name).apply()
    }

    private fun readDownloadQuality(): DownloadQuality {
        val stored = prefs.getString(KEY_QUALITY_DOWNLOAD, null) ?: return DownloadQuality.LOSSLESS
        return runCatching { DownloadQuality.valueOf(stored) }.getOrDefault(DownloadQuality.LOSSLESS)
    }

    /**
     * Track the active network so [effectiveAudioQuality] can answer without
     * touching ConnectivityManager. Stream resolution happens off the main
     * thread mid-playback; a callback keeps that lookup off the hot path and
     * lets the settings page show which ceiling is currently in force.
     */
    private fun watchConnection(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val refresh = {
            meteredConnection.value = runCatching {
                if (manager.activeNetwork == null) null else manager.isActiveNetworkMetered
            }.getOrNull()
        }
        refresh()
        runCatching {
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = refresh()
                    override fun onLost(network: Network) = refresh()
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) = refresh()
                },
            )
        }
    }

    fun setAutoplay(value: Boolean) {
        autoplay.value = value
        prefs.edit().putBoolean(KEY_AUTOPLAY, value).apply()
    }

    fun setVibeAutoplay(value: Boolean) {
        vibeAutoplay.value = value
        prefs.edit().putBoolean(KEY_VIBE_AUTOPLAY, value).apply()
    }

    fun setVibeForgottenFavorites(value: Boolean) {
        vibeForgottenFavorites.value = value
        prefs.edit().putBoolean(KEY_VIBE_FORGOTTEN_FAVORITES, value).apply()
    }

    fun setVibeStrictness(value: Float) {
        val normalized = value.coerceIn(MIN_VIBE_STRICTNESS, MAX_VIBE_STRICTNESS)
        vibeStrictness.value = normalized
        prefs.edit().putFloat(KEY_VIBE_STRICTNESS, normalized).apply()
    }

    fun setAutoplayMixLibrary(value: Int) {
        val normalized = value.coerceIn(0, 100)
        autoplayMixLibrary.value = normalized
        prefs.edit().putInt(KEY_AUTOPLAY_MIX_LIBRARY, normalized).apply()
    }

    fun setAutoplayMixDownloads(value: Int) {
        val normalized = value.coerceIn(0, 100)
        autoplayMixDownloads.value = normalized
        prefs.edit().putInt(KEY_AUTOPLAY_MIX_DOWNLOADS, normalized).apply()
    }

    fun setAutoplayMixDiscovery(value: Int) {
        val normalized = value.coerceIn(0, 100)
        autoplayMixDiscovery.value = normalized
        prefs.edit().putInt(KEY_AUTOPLAY_MIX_DISCOVERY, normalized).apply()
    }

    fun setAutoplayExploration(value: Int) {
        val normalized = value.coerceIn(0, 2)
        autoplayExploration.value = normalized
        prefs.edit().putInt(KEY_AUTOPLAY_EXPLORATION, normalized).apply()
    }

    fun setAutoplayDiscoveryMode(value: Boolean) {
        autoplayDiscoveryMode.value = value
        prefs.edit().putBoolean(KEY_AUTOPLAY_DISCOVERY_MODE, value).apply()
    }

    fun setAutoplayBannedSongIds(value: Set<String>) {
        autoplayBannedSongIds.value = value.toSet()
        prefs.edit().putStringSet(KEY_AUTOPLAY_BANNED_SONG_IDS, value.toSet()).apply()
    }

    fun setAutoplayBannedArtists(value: Set<String>) {
        val normalized = value.map { it.lowercase() }.toSet()
        autoplayBannedArtists.value = normalized
        prefs.edit().putStringSet(KEY_AUTOPLAY_BANNED_ARTISTS, normalized).apply()
    }

    /** Adds [id] to the songs autoplay must never serve. */
    fun banSong(id: String) {
        setAutoplayBannedSongIds(autoplayBannedSongIds.value + id)
    }

    /** Lifts the ban on [id]. */
    fun unbanSong(id: String) {
        setAutoplayBannedSongIds(autoplayBannedSongIds.value - id)
    }

    /**
     * Adds [name] to the artists autoplay must never serve, normalised to
     * lowercase so "The Beatles" and "the beatles" are the same ban.
     */
    fun banArtist(name: String) {
        setAutoplayBannedArtists(autoplayBannedArtists.value + name.lowercase())
    }

    /** Lifts the ban on [name]. */
    fun unbanArtist(name: String) {
        setAutoplayBannedArtists(autoplayBannedArtists.value - name.lowercase())
    }

    fun setAutoplayDownloadsOnly(value: Boolean) {
        autoplayDownloadsOnly.value = value
        prefs.edit().putBoolean(KEY_AUTOPLAY_DOWNLOADS_ONLY, value).apply()
    }

    fun setActivityAutoplay(value: Boolean) {
        activityAutoplay.value = value
        prefs.edit().putBoolean(KEY_ACTIVITY_AUTOPLAY, value).apply()
    }

    fun setWindDownEnabled(value: Boolean) {
        windDownEnabled.value = value
        prefs.edit().putBoolean(KEY_WIND_DOWN_ENABLED, value).apply()
    }

    fun setWindDownBedtimeMinutes(value: Int) {
        val normalized = value.coerceIn(0, 1439)
        windDownBedtimeMinutes.value = normalized
        prefs.edit().putInt(KEY_WIND_DOWN_BEDTIME_MINUTES, normalized).apply()
    }

    fun setWindDownLeadMinutes(value: Int) {
        val normalized = value.coerceIn(0, 720)
        windDownLeadMinutes.value = normalized
        prefs.edit().putInt(KEY_WIND_DOWN_LEAD_MINUTES, normalized).apply()
    }

    /**
     * Switching on stamps the session's start; switching off clears it, so
     * the engine never inherits a stale session.
     */
    fun setWorkoutMode(value: Boolean) {
        workoutMode.value = value
        prefs.edit().putBoolean(KEY_WORKOUT_MODE, value).apply()
        setWorkoutStartedAtMs(if (value) System.currentTimeMillis() else 0L)
    }

    fun setWorkoutStartedAtMs(value: Long) {
        workoutStartedAtMs.value = value
        prefs.edit().putLong(KEY_WORKOUT_STARTED_AT_MS, value).apply()
    }

    fun setWorkoutLengthMinutes(value: Int) {
        val normalized = value.coerceIn(1, 480)
        workoutLengthMinutes.value = normalized
        prefs.edit().putInt(KEY_WORKOUT_LENGTH_MINUTES, normalized).apply()
    }

    fun setEnergyCurve(value: Int) {
        val normalized = value.coerceIn(0, 3)
        energyCurve.value = normalized
        prefs.edit().putInt(KEY_ENERGY_CURVE, normalized).apply()
    }

    fun setShuffleEnabled(value: Boolean) {
        shuffleEnabled.value = value
        prefs.edit().putBoolean(KEY_SHUFFLE_ENABLED, value).apply()
    }

    fun setRepeatMode(value: Int) {
        repeatMode.value = value
        prefs.edit().putInt(KEY_REPEAT_MODE, value).apply()
    }

    fun setAudioQualityWifi(value: AudioQuality) {
        audioQualityWifi.value = value
        prefs.edit().putString(KEY_QUALITY_WIFI, value.name).apply()
    }

    fun setAudioQualityCellular(value: AudioQuality) {
        audioQualityCellular.value = value
        prefs.edit().putString(KEY_QUALITY_CELLULAR, value.name).apply()
    }

    fun setUpgradeLengthSlackSeconds(value: Int) {
        val normalized = value.coerceIn(
            MIN_UPGRADE_LENGTH_SLACK_SECONDS,
            MAX_UPGRADE_LENGTH_SLACK_SECONDS,
        )
        upgradeLengthSlackSeconds.value = normalized
        prefs.edit().putInt(KEY_UPGRADE_LENGTH_SLACK_SECONDS, normalized).apply()
    }

    fun setDownloadQuality(value: DownloadQuality) {
        downloadQuality.value = value
        prefs.edit().putString(KEY_QUALITY_DOWNLOAD, value.name).apply()
    }

    fun setWifiOnlyDownloads(value: Boolean) {
        wifiOnlyDownloads.value = value
        prefs.edit().putBoolean(KEY_WIFI_ONLY_DOWNLOADS, value).apply()
    }

    fun setCrossfadeSeconds(value: Int) {
        crossfadeSeconds.value = value
        prefs.edit().putInt(KEY_CROSSFADE, value).apply()
    }

    fun setSmartFadeEnabled(value: Boolean) {
        smartFadeEnabled.value = value
        prefs.edit().putBoolean(KEY_SMART_FADE, value).apply()
    }

    fun setDjMixEnabled(value: Boolean) {
        djMixEnabled.value = value
        prefs.edit().putBoolean(KEY_DJ_MIX, value).apply()
    }

    fun setSleepTimerFadeOut(value: Boolean) {
        sleepTimerFadeOut.value = value
        prefs.edit().putBoolean(KEY_SLEEP_TIMER_FADE_OUT, value).apply()
    }

    fun setAutomixPerformanceMode(value: AutomixPerformanceMode) {
        automixPerformanceMode.value = value
        prefs.edit().putString(KEY_AUTOMIX_PERFORMANCE_MODE, value.name).apply()
    }

    fun setSkipSilence(value: Boolean) {
        skipSilence.value = value
        prefs.edit().putBoolean(KEY_SKIP_SILENCE, value).apply()
    }

    fun setDolbyAtmos(value: Boolean) {
        dolbyAtmos.value = value
        prefs.edit().putBoolean(KEY_DOLBY_ATMOS, value).apply()
    }

    fun setSpatialAudio(value: Boolean) {
        spatialAudio.value = value
        prefs.edit().putBoolean(KEY_SPATIAL_AUDIO, value).apply()
    }

    fun setEqualizerEnabled(value: Boolean) {
        equalizerEnabled.value = value
        prefs.edit().putBoolean(KEY_EQ_ENABLED, value).apply()
    }

    fun setEqualizerMode(value: EqualizerMode) {
        equalizerMode.value = value
        prefs.edit().putString(KEY_EQ_MODE, value.name).apply()
    }

    fun setEqualizerTone(x: Int, y: Int) {
        val steps = EqLayout.TONE_STEPS
        val clampedX = x.coerceIn(-steps, steps)
        val clampedY = y.coerceIn(-steps, steps)
        equalizerToneX.value = clampedX
        equalizerToneY.value = clampedY
        prefs.edit().putInt(KEY_EQ_TONE_X, clampedX).putInt(KEY_EQ_TONE_Y, clampedY).apply()
    }

    fun setEqualizerFocused(value: Boolean) {
        equalizerFocused.value = value
        prefs.edit().putBoolean(KEY_EQ_FOCUSED, value).apply()
    }

    fun setEqualizerBalance(value: Float) {
        val clamped = value.coerceIn(-1f, 1f)
        equalizerBalance.value = clamped
        prefs.edit().putFloat(KEY_EQ_BALANCE, clamped).apply()
    }

    /**
     * Writes the manual tab's seven gains, and renames the preset row to suit.
     *
     * Stored as text rather than seven keys of their own so that a backup
     * carries them: [exportPrefs] copies the preference file as it stands, and
     * one string is one thing to keep in step rather than seven.
     *
     * Both preset rows are re-derived here: the fixed one from
     * [EqualizerPreset.matching], the user's from [userEqualizerPresets]. A
     * slider dragged onto a saved curve names that preset again; dragged off
     * it, the name goes.
     */
    fun setEqualizerBands(values: List<Float>) {
        val clamped = clampEqualizerBands(values)
        equalizerBands.value = clamped
        equalizerPreset.value = EqualizerPreset.matching(clamped)
        activeUserPresetName.value = userEqualizerPresets.value.firstOrNull { preset ->
            eqBandsMatch(preset.bandsDb, clamped)
        }?.name
        prefs.edit()
            .putString(KEY_EQ_BANDS, clamped.joinToString(","))
            .putString(KEY_EQ_USER_PRESET_ACTIVE, activeUserPresetName.value)
            .apply()
    }

    /** Applies a preset's curve. [EqualizerPreset.CUSTOM] carries none, so it does nothing. */
    fun setEqualizerPreset(preset: EqualizerPreset) {
        if (preset == EqualizerPreset.CUSTOM) return
        setEqualizerBands(preset.bands)
    }

    /** Applies a user preset's curve; the active name is re-derived by [setEqualizerBands]. */
    fun applyUserEqualizerPreset(preset: UserEqualizerPreset) {
        setEqualizerBands(preset.bandsDb)
    }

    /**
     * Saves the current bands under [name]. A name that is blank after
     * sanitising is refused (false); an existing preset with the same name is
     * replaced, so saving twice does not pile up duplicates. Past
     * [MAX_USER_EQ_PRESETS] the oldest saved goes.
     */
    fun saveUserEqualizerPreset(name: String, bands: List<Float>): Boolean {
        if (sanitizePresetName(name).isEmpty()) return false
        val updated = upsertUserPreset(userEqualizerPresets.value, name, bands)
        userEqualizerPresets.value = updated
        prefs.edit().putString(KEY_EQ_USER_PRESETS, encodeUserEqualizerPresets(updated)).apply()
        // Names the preset row for the curve just saved.
        setEqualizerBands(bands)
        return true
    }

    /** Deletes the named user preset. The bands stay where they were. */
    fun deleteUserEqualizerPreset(name: String) {
        val updated = removeUserPreset(userEqualizerPresets.value, name)
        if (updated.size == userEqualizerPresets.value.size) return
        userEqualizerPresets.value = updated
        val editor = prefs.edit().putString(KEY_EQ_USER_PRESETS, encodeUserEqualizerPresets(updated))
        if (activeUserPresetName.value == name) {
            activeUserPresetName.value = null
            editor.putString(KEY_EQ_USER_PRESET_ACTIVE, null)
        }
        editor.apply()
    }

    private fun clampEqualizerBands(values: List<Float>): List<Float> =
        List(EqLayout.MANUAL_COUNT) {
            values.getOrElse(it) { 0f }.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        }

    fun setEqualizerBass(value: Float) {
        val clamped = value.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        equalizerBass.value = clamped
        prefs.edit().putFloat(KEY_EQ_BASS, clamped).apply()
    }

    fun setEqualizerTreble(value: Float) {
        val clamped = value.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        equalizerTreble.value = clamped
        prefs.edit().putFloat(KEY_EQ_TREBLE, clamped).apply()
    }

    private fun readEqualizerBands(): List<Float> {
        val stored = prefs.getString(KEY_EQ_BANDS, null)
            ?.split(",")
            ?.mapNotNull { it.trim().toFloatOrNull() }
            .orEmpty()
        // Padded rather than rejected: a backup written by a build with a
        // different number of bands should restore the ones it does have.
        return List(EqLayout.MANUAL_COUNT) {
            stored.getOrElse(it) { 0f }.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        }
    }

    fun setPlaybackSpeed(value: Float) {
        playbackSpeed.value = value
        prefs.edit().putFloat(KEY_SPEED, value).apply()
    }

    fun setShowNerdStats(value: Boolean) {
        showNerdStats.value = value
        prefs.edit().putBoolean(KEY_NERD_STATS, value).apply()
    }

    fun setThemeMode(value: ThemeMode) {
        themeMode.value = value
        prefs.edit().putString(KEY_THEME, value.name).apply()
    }

    fun setAccentColor(value: AccentColor) {
        accentColor.value = value
        // A preset is a whole choice: it takes over from any custom color, so
        // tapping one always visibly re-themes rather than silently losing to
        // the override.
        customAccentArgb.value = null
        prefs.edit()
            .putString(KEY_ACCENT_COLOR, value.name)
            .remove(KEY_ACCENT_COLOR_CUSTOM)
            .apply()
    }

    /**
     * Overrides the preset accent with a user-picked color, live. Null clears
     * the override and the preset takes over again.
     */
    fun setCustomAccentArgb(argb: Int?) {
        customAccentArgb.value = argb
        val editor = prefs.edit()
        if (argb == null) editor.remove(KEY_ACCENT_COLOR_CUSTOM)
        else editor.putInt(KEY_ACCENT_COLOR_CUSTOM, argb)
        editor.apply()
    }

    fun setDynamicTheme(value: Boolean) {
        dynamicTheme.value = value
        prefs.edit().putBoolean(KEY_DYNAMIC_THEME, value).apply()
    }

    fun setReduceAnimation(value: Boolean) {
        reduceAnimation.value = value
        if (value) highPerformanceMode.value = false
        val editor = prefs.edit().putBoolean(KEY_REDUCE_ANIMATION, value)
        if (value) editor.putBoolean(KEY_HIGH_PERFORMANCE_MODE, false)
        editor.apply()
    }

    fun setStopOnTaskRemoved(value: Boolean) {
        stopOnTaskRemoved.value = value
        prefs.edit().putBoolean(KEY_STOP_ON_TASK_REMOVED, value).apply()
    }

    fun setHideVolumeBar(value: Boolean) {
        hideVolumeBar.value = value
        prefs.edit().putBoolean(KEY_HIDE_VOLUME_BAR, value).apply()
    }

    fun setHideSongStatus(value: Boolean) {
        hideSongStatus.value = value
        prefs.edit().putBoolean(KEY_HIDE_SONG_STATUS, value).apply()
    }

    fun setSwipeToPlayNext(value: Boolean) {
        swipeToPlayNext.value = value
        prefs.edit().putBoolean(KEY_SWIPE_TO_PLAY_NEXT, value).apply()
    }

    fun setDontRepeatSuggestions(value: Boolean) {
        dontRepeatSuggestions.value = value
        prefs.edit().putBoolean(KEY_DONT_REPEAT_SUGGESTIONS, value).apply()
    }

    fun setPreferMusicOnly(value: Boolean) {
        preferMusicOnly.value = value
        prefs.edit().putBoolean(KEY_PREFER_MUSIC_ONLY, value).apply()
    }

    fun setSmartVersionAlignment(value: Boolean) {
        smartVersionAlignment.value = value
        prefs.edit().putBoolean(KEY_SMART_VERSION_ALIGNMENT, value).apply()
    }

    fun setReduceDynamicBlur(value: Boolean) {
        reduceDynamicBlur.value = value
        if (value) highPerformanceMode.value = false
        val editor = prefs.edit().putBoolean(KEY_REDUCE_BLUR, value)
        if (value) editor.putBoolean(KEY_HIGH_PERFORMANCE_MODE, false)
        editor.apply()
    }

    fun setLiquidGlass(value: Boolean) {
        liquidGlass.value = value
        prefs.edit().putBoolean(KEY_LIQUID_GLASS, value).apply()
    }

    fun setHighPerformanceMode(value: Boolean) {
        highPerformanceMode.value = value
        if (value) {
            reduceAnimation.value = false
            reduceDynamicBlur.value = false
        }
        val editor = prefs.edit().putBoolean(KEY_HIGH_PERFORMANCE_MODE, value)
        if (value) {
            editor.putBoolean(KEY_REDUCE_ANIMATION, false)
            editor.putBoolean(KEY_REDUCE_BLUR, false)
        }
        editor.apply()
    }

    fun setPerformanceRefreshRate(value: Int) {
        val normalized = normalizePerformanceRefreshRate(value)
        performanceRefreshRate.value = normalized
        prefs.edit().putInt(KEY_PERFORMANCE_REFRESH_RATE, normalized).apply()
    }

    fun setLyricsBlur(value: Boolean) {
        lyricsBlur.value = value
        prefs.edit().putBoolean(KEY_LYRICS_BLUR, value).apply()
    }

    fun setLyricsOffsetMs(value: Int) {
        val normalized = value.coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
        if (lyricsOffsetMs.value == normalized) return
        lyricsOffsetMs.value = normalized
        prefs.edit().putInt(KEY_LYRICS_OFFSET_MS, normalized).apply()
    }

    /** Null removes the track's own offset, handing it back to the global one. */
    fun setLyricsOffsetForTrack(videoId: String, offsetMs: Int?) {
        val updated = lyricsOffsetOverrides.value.withLyricOffset(videoId, offsetMs)
        if (updated == lyricsOffsetOverrides.value) return
        lyricsOffsetOverrides.value = updated
        prefs.edit().putString(KEY_LYRICS_OFFSET_OVERRIDES, encodeLyricOffsets(updated)).apply()
    }

    fun setLyricsFontSizeSp(value: Float) {
        val normalized = value.coerceIn(MIN_LYRICS_FONT_SIZE_SP, MAX_LYRICS_FONT_SIZE_SP)
        if (lyricsFontSizeSp.value == normalized) return
        lyricsFontSizeSp.value = normalized
        prefs.edit().putFloat(KEY_LYRICS_FONT_SIZE_SP, normalized).apply()
    }

    fun setLyricsAutoScroll(value: Boolean) {
        if (lyricsAutoScroll.value == value) return
        lyricsAutoScroll.value = value
        prefs.edit().putBoolean(KEY_LYRICS_AUTO_SCROLL, value).apply()
    }

    fun setLyricsKaraokeEffects(value: Boolean) {
        if (lyricsKaraokeEffects.value == value) return
        lyricsKaraokeEffects.value = value
        prefs.edit().putBoolean(KEY_LYRICS_KARAOKE, value).apply()
    }

    fun setLyricsHighlightIntensity(value: Float) {
        val normalized = value.coerceIn(MIN_LYRICS_HIGHLIGHT_INTENSITY, MAX_LYRICS_HIGHLIGHT_INTENSITY)
        if (lyricsHighlightIntensity.value == normalized) return
        lyricsHighlightIntensity.value = normalized
        prefs.edit().putFloat(KEY_LYRICS_HIGHLIGHT_INTENSITY, normalized).apply()
    }

    fun setLyricsBackground(value: LyricsBackground) {
        if (lyricsBackground.value == value) return
        lyricsBackground.value = value
        prefs.edit().putString(KEY_LYRICS_BACKGROUND, value.persistedName).apply()
    }

    fun setLyricsReducedMotion(value: Boolean) {
        if (lyricsReducedMotion.value == value) return
        lyricsReducedMotion.value = value
        prefs.edit().putBoolean(KEY_LYRICS_REDUCED_MOTION, value).apply()
    }

    fun setVisualizerStyle(value: VisualizerStyle) {
        if (visualizerStyle.value == value) return
        visualizerStyle.value = value
        prefs.edit().putString(KEY_VISUALIZER_STYLE, value.persistedName).apply()
    }

    fun setVisualizerIntensity(value: Float) {
        val normalized = value.coerceIn(MIN_VISUALIZER_INTENSITY, MAX_VISUALIZER_INTENSITY)
        if (visualizerIntensity.value == normalized) return
        visualizerIntensity.value = normalized
        prefs.edit().putFloat(KEY_VISUALIZER_INTENSITY, normalized).apply()
    }

    fun setVisualizerBatterySaver(value: Boolean) {
        if (visualizerBatterySaver.value == value) return
        visualizerBatterySaver.value = value
        prefs.edit().putBoolean(KEY_VISUALIZER_BATTERY_SAVER, value).apply()
    }

    /** Blank restores "follow the app language"; see [translationLanguage]. */
    fun setTranslationLanguage(value: String) {
        translationLanguage.value = value
        prefs.edit().putString(KEY_TRANSLATION_LANGUAGE, value).apply()
    }

    fun setLyricsTranslationOverlay(value: Boolean) {
        if (lyricsTranslationOverlay.value == value) return
        lyricsTranslationOverlay.value = value
        prefs.edit().putBoolean(KEY_LYRICS_TRANSLATION_OVERLAY, value).apply()
    }

    fun setSyncedLyrics(value: Boolean) {
        syncedLyrics.value = value
        prefs.edit().putBoolean(KEY_SYNCED_LYRICS, value).apply()
    }

    fun setLyricsPrefetch(value: Boolean) {
        if (lyricsPrefetch.value == value) return
        lyricsPrefetch.value = value
        prefs.edit().putBoolean(KEY_LYRICS_PREFETCH, value).apply()
        // The warmup job follows the switch immediately, not on the next restart.
        com.music.sonariq.download.LyricsWarmup.applySchedule()
    }

    fun setLockscreenLyrics(value: Boolean) {
        lockscreenLyrics.value = value
        prefs.edit().putBoolean(KEY_LOCKSCREEN_LYRICS, value).apply()
    }

    fun setAutoUpdateCheck(value: Boolean) {
        autoUpdateCheck.value = value
        prefs.edit().putBoolean(KEY_AUTO_UPDATE_CHECK, value).apply()
    }

    fun setLyricsSources(value: Set<LyricsSource>) {
        lyricsSources.value = value
        prefs.edit()
            .putString(KEY_LYRICS_SOURCES, value.joinToString(",") { it.name })
            // Everything that was on the list this choice was made from, so a
            // later build can tell a source the user turned off from one they
            // have never been shown. See [readLyricsSources].
            .putString(KEY_LYRICS_SOURCES_SEEN, LyricsSource.offered.joinToString(",") { it.name })
            .apply()
    }

    /**
     * Stored as a joined list of names rather than a string set: a name that
     * no longer exists — a source dropped in a later build — has to fall out
     * quietly, and the default when nothing has been saved is "all of them",
     * which a missing key and an empty set would otherwise be unable to tell
     * apart.
     *
     * A source *added* by an upgrade is enabled rather than left out. Absence
     * from a saved list is a decision only about the sources that list was
     * chosen from; a new one was never on it, so its absence says nothing, and
     * treating it as "off" would ship a source nobody could discover without
     * first going and looking for it. [KEY_LYRICS_SOURCES_SEEN] is what makes
     * the two cases distinguishable — before it existed, [LEGACY_SOURCES]
     * stands in as the list of everything there was to have an opinion about.
     */
    private fun readLyricsSources(): Set<LyricsSource> {
        val stored = prefs.getString(KEY_LYRICS_SOURCES, null)
            ?: return LyricsSource.offered.toSet()
        val chosen = stored.split(",").toSources()
        val seen = prefs.getString(KEY_LYRICS_SOURCES_SEEN, null)
            ?.split(",")?.toSources()
            ?: LEGACY_SOURCES
        return (chosen + LyricsSource.entries.filter { it !in seen }).filterNot { it.hidden }.toSet()
    }

    private fun List<String>.toSources(): Set<LyricsSource> =
        mapNotNull { name -> LyricsSource.entries.firstOrNull { it.name == name } }.toSet()

    /**
     * The sources that existed before [KEY_LYRICS_SOURCES_SEEN] was written.
     * Fixed forever: it describes what an old build could have saved, so it
     * does not grow when [LyricsSource] does.
     */
    private val LEGACY_SOURCES = setOf(
        LyricsSource.LYRICS_PLUS,
        LyricsSource.PAXSENIX,
        LyricsSource.BETTER_LYRICS,
        LyricsSource.SIMP_MUSIC,
        LyricsSource.KUGOU,
        LyricsSource.LRCLIB,
        LyricsSource.MUSIXMATCH,
        LyricsSource.GENIUS,
    )

    fun setLyricsSourceOrder(value: List<LyricsSource>) {
        lyricsSourceOrder.value = value
        prefs.edit().putString(KEY_LYRICS_SOURCE_ORDER, value.joinToString(",") { it.name }).apply()
    }

    /**
     * A named source dropped from the stored order — an upgrade removed or
     * hid it since it was saved — falls out on read; one added since slots in
     * after its declared neighbour (see [LyricsSource.ordered]), so a fresh
     * install and an upgraded one agree on where a new source lands until the
     * user says otherwise.
     */
    private fun readLyricsSourceOrder(): List<LyricsSource> {
        val stored = prefs.getString(KEY_LYRICS_SOURCE_ORDER, null)
            ?: return LyricsSource.offered
        val saved = stored.split(",")
            .mapNotNull { name -> LyricsSource.entries.firstOrNull { it.name == name } }
        return LyricsSource.ordered(saved)
    }

    fun setPrioritizeSyllableSync(value: Boolean) {
        prioritizeSyllableSync.value = value
        prefs.edit().putBoolean(KEY_PRIORITIZE_SYLLABLE_SYNC, value).apply()
    }

    fun setAiWifiOnly(value: Boolean) {
        aiWifiOnly.value = value
        prefs.edit().putBoolean(KEY_AI_WIFI_ONLY, value).apply()
    }

    fun setPaxSenixApiKey(value: String) {
        val normalized = com.music.sonariq.data.lyrics.normalizePaxSenixApiKey(value)
        paxSenixApiKey.value = normalized
        prefs.edit().putString(KEY_PAXSENIX_API_KEY, normalized).apply()
        com.music.sonariq.data.lyrics.PaxSenix.setApiKey(normalized)
    }

    /**
     * Puts the source list, its order and [prioritizeSyllableSync] back the
     * way a fresh install finds them. [syncedLyrics] itself is left alone —
     * this is "start over on *which* lyrics", not "turn lyrics off".
     */
    fun resetLyricsSourceSettings() {
        setLyricsSources(LyricsSource.offered.toSet())
        setLyricsSourceOrder(LyricsSource.offered)
        setPrioritizeSyllableSync(true)
        setAiWifiOnly(true)
    }

    fun setAnimatedCanvas(value: Boolean) {
        animatedCanvas.value = value
        prefs.edit().putBoolean(KEY_ANIMATED_CANVAS, value).apply()
    }

    fun setCanvasOverCellular(value: Boolean) {
        canvasOverCellular.value = value
        prefs.edit().putBoolean(KEY_CANVAS_OVER_CELLULAR, value).apply()
    }

    fun setSpotifyCanvasAutoHide(value: Boolean) {
        spotifyCanvasAutoHide.value = value
        prefs.edit().putBoolean(KEY_SPOTIFY_CANVAS_AUTO_HIDE, value).apply()
    }

    fun setPrioritizeSpotifyCanvas(value: Boolean) {
        prioritizeSpotifyCanvas.value = value
        prefs.edit().putBoolean(KEY_PRIORITIZE_SPOTIFY_CANVAS, value).apply()
    }

    fun setFullBleedArtwork(value: Boolean) {
        fullBleedArtwork.value = value
        prefs.edit().putBoolean(KEY_FULL_BLEED_ARTWORK, value).apply()
    }

    fun setLegacyMeshGradient(value: Boolean) {
        legacyMeshGradient.value = value
        prefs.edit().putBoolean(KEY_LEGACY_MESH_GRADIENT, value).apply()
    }

    fun setLastPlayerScreen(value: LastPlayerScreen) {
        if (lastPlayerScreen.value == value) return
        lastPlayerScreen.value = value
        prefs.edit().putString(KEY_LAST_PLAYER_SCREEN, value.name).apply()
    }

    /**
     * Clamped to [DEFAULT_CACHE_LIMIT_BYTES]..[MAX_CACHE_LIMIT_BYTES] — the floor is the default, not zero.
     * Anything past [MAX_CACHE_LIMIT_BYTES] is [UNLIMITED_CACHE_LIMIT_BYTES].
     */
    fun setAudioCacheLimitBytes(value: Long) {
        val clamped = clampCacheLimit(value)
        audioCacheLimitBytes.value = clamped
        prefs.edit().putLong(KEY_CACHE_LIMIT, clamped).apply()
    }

    private fun clampCacheLimit(value: Long): Long =
        if (value > MAX_CACHE_LIMIT_BYTES) UNLIMITED_CACHE_LIMIT_BYTES
        else value.coerceAtLeast(DEFAULT_CACHE_LIMIT_BYTES)

    fun setShowCacheFolder(value: Boolean) {
        showCacheFolder.value = value
        prefs.edit().putBoolean(KEY_SHOW_CACHE_FOLDER, value).apply()
    }

    fun setLastfmEnabled(value: Boolean) {
        lastfmEnabled.value = value
        prefs.edit().putBoolean(KEY_LASTFM_ENABLED, value).apply()
    }

    fun setLastfmUsername(value: String) {
        lastfmUsername.value = value
        prefs.edit().putString(KEY_LASTFM_USERNAME, value).apply()
    }

    fun setLastfmSessionKey(value: String) {
        lastfmSessionKey.value = value
        prefs.edit().putString(KEY_LASTFM_SESSION_KEY, value).apply()
    }

    fun setLastfmApiKey(value: String) {
        lastfmApiKey.value = value
        prefs.edit().putString(KEY_LASTFM_API_KEY, value).apply()
    }

    fun setLastfmSecret(value: String) {
        lastfmSecret.value = value
        prefs.edit().putString(KEY_LASTFM_SECRET, value).apply()
    }

    fun setLastfmEndpoint(value: String) {
        lastfmEndpoint.value = value
        prefs.edit().putString(KEY_LASTFM_ENDPOINT, value).apply()
    }

    fun setSpotifySpdcToken(value: String) {
        spotifySpdcToken.value = value
        prefs.edit().putString(KEY_SPOTIFY_SPDC_TOKEN, value).apply()
        SpotifyToken.invalidate()
    }

    fun setLastfmScrobbleEnabled(value: Boolean) {
        lastfmScrobbleEnabled.value = value
        if (!value) lastfmNowPlaying.value = false
        prefs.edit()
            .putBoolean(KEY_LASTFM_SCROBBLE_ENABLED, value)
            .putBoolean(KEY_LASTFM_NOW_PLAYING, if (value) lastfmNowPlaying.value else false)
            .apply()
    }

    fun setLastfmNowPlaying(value: Boolean) {
        if (!lastfmScrobbleEnabled.value && value) return
        lastfmNowPlaying.value = value
        prefs.edit().putBoolean(KEY_LASTFM_NOW_PLAYING, value).apply()
    }

    fun setOutputPcmMode(value: OutputPcmMode) {
        outputPcmMode.value = value
        prefs.edit().putString(KEY_OUTPUT_PCM_MODE, value.name).apply()
    }

    fun setPreferUsbDac(value: Boolean) {
        preferUsbDac.value = value
        prefs.edit().putBoolean(KEY_PREFER_USB_DAC, value).apply()
    }

    fun setLoudnessOffOnSpeaker(value: Boolean) {
        loudnessOffOnSpeaker.value = value
        prefs.edit().putBoolean(KEY_LOUDNESS_OFF_ON_SPEAKER, value).apply()
    }

    fun setLoudnessNormalization(value: Boolean) {
        loudnessNormalization.value = value
        prefs.edit().putBoolean(KEY_LOUDNESS_NORMALIZATION, value).apply()
    }

    fun setExportDownloads(value: Boolean) {
        exportDownloads.value = value
        prefs.edit().putBoolean(KEY_EXPORT_DOWNLOADS, value).apply()
    }

    fun setSmartDownloadsEnabled(value: Boolean) {
        smartDownloadsEnabled.value = value
        prefs.edit().putBoolean(KEY_SMART_DOWNLOADS_ENABLED, value).apply()
        // The worker follows the switch immediately, not on the next restart.
        com.music.sonariq.download.SmartDownloads.applySchedule()
    }

    fun setSmartDownloadsCount(value: Int) {
        val normalized = value.coerceIn(25, 100)
        smartDownloadsCount.value = normalized
        prefs.edit().putInt(KEY_SMART_DOWNLOADS_COUNT, normalized).apply()
    }

    fun setSmartDownloadsWifiOnly(value: Boolean) {
        smartDownloadsWifiOnly.value = value
        prefs.edit().putBoolean(KEY_SMART_DOWNLOADS_WIFI_ONLY, value).apply()
        // Constraints are baked at schedule time; re-schedule so the new
        // network requirement takes effect on the next run.
        com.music.sonariq.download.SmartDownloads.applySchedule()
    }

    fun setPerDeviceEqEnabled(value: Boolean) {
        perDeviceEqEnabled.value = value
        prefs.edit().putBoolean(KEY_PER_DEVICE_EQ, value).apply()
    }

    fun setMonoAudioEnabled(value: Boolean) {
        monoAudioEnabled.value = value
        prefs.edit().putBoolean(KEY_MONO_AUDIO, value).apply()
    }

    fun setCanvasBackdropEnabled(value: Boolean) {
        canvasBackdropEnabled.value = value
        prefs.edit().putBoolean(KEY_CANVAS_BACKDROP, value).apply()
    }

    fun setDataSaverEnabled(value: Boolean) {
        dataSaverEnabled.value = value
        prefs.edit().putBoolean(KEY_DATA_SAVER, value).apply()
    }

    fun setDataSaverQuality(value: AudioQuality) {
        dataSaverQuality.value = value
        prefs.edit().putString(KEY_DATA_SAVER_QUALITY, value.name).apply()
    }

    fun setWifiOnlyStreaming(value: Boolean) {
        wifiOnlyStreaming.value = value
        prefs.edit().putBoolean(KEY_WIFI_ONLY_STREAMING, value).apply()
    }

    fun setAutoDownloadsEnabled(value: Boolean) {
        autoDownloadsEnabled.value = value
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOADS_ENABLED, value).apply()
    }

    fun setAutoDownloadLikedSongs(value: Boolean) {
        autoDownloadLikedSongs.value = value
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_LIKED_SONGS, value).apply()
    }

    fun setAutoDownloadPlaylistIds(value: Set<String>) {
        autoDownloadPlaylistIds.value = value
        prefs.edit().putStringSet(KEY_AUTO_DOWNLOAD_PLAYLIST_IDS, value).apply()
    }

    fun setAutoDownloadWifiOnly(value: Boolean) {
        autoDownloadWifiOnly.value = value
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_WIFI_ONLY, value).apply()
    }

    fun setScheduledDownloadsEnabled(value: Boolean) {
        scheduledDownloadsEnabled.value = value
        prefs.edit().putBoolean(KEY_SCHEDULED_DOWNLOADS_ENABLED, value).apply()
    }

    fun setScheduledDownloadHour(value: Int) {
        scheduledDownloadHour.value = value
        prefs.edit().putInt(KEY_SCHEDULED_DOWNLOAD_HOUR, value).apply()
    }

    fun setScheduledDownloadMinute(value: Int) {
        scheduledDownloadMinute.value = value
        prefs.edit().putInt(KEY_SCHEDULED_DOWNLOAD_MINUTE, value).apply()
    }

    fun setScheduledDownloadWifiOnly(value: Boolean) {
        scheduledDownloadWifiOnly.value = value
        prefs.edit().putBoolean(KEY_SCHEDULED_DOWNLOAD_WIFI_ONLY, value).apply()
    }

    fun setHomeSectionOrderJson(value: String) {
        homeSectionOrderJson.value = value
        prefs.edit().putString(KEY_HOME_SECTION_ORDER, value).apply()
    }

    fun setHomeSectionLearningEnabled(value: Boolean) {
        homeSectionLearningEnabled.value = value
        prefs.edit().putBoolean(KEY_HOME_SECTION_LEARNING, value).apply()
    }

    fun setSavedQueuesJson(value: String) {
        savedQueuesJson.value = value
        prefs.edit().putString(KEY_SAVED_QUEUES, value).apply()
    }

    fun setTvAmbient2Enabled(value: Boolean) {
        tvAmbient2Enabled.value = value
        prefs.edit().putBoolean(KEY_TV_AMBIENT_2, value).apply()
    }

    fun setArtworkGesturesEnabled(value: Boolean) {
        artworkGesturesEnabled.value = value
        prefs.edit().putBoolean(KEY_ARTWORK_GESTURES, value).apply()
    }

    fun setPrivateSessionEnabled(value: Boolean) {
        privateSessionEnabled.value = value
        prefs.edit().putBoolean(KEY_PRIVATE_SESSION, value).apply()
    }

    fun setOfflineMode(value: Boolean) {
        offlineMode.value = value
        prefs.edit().putBoolean(KEY_OFFLINE_MODE, value).apply()
    }

    fun setConcertAlertsEnabled(value: Boolean) {
        concertAlertsEnabled.value = value
        prefs.edit().putBoolean(KEY_CONCERT_ALERTS_ENABLED, value).apply()
        // The daily check follows the switch immediately, like Smart
        // Downloads' worker does — see SmartDownloads.applySchedule.
        com.music.sonariq.data.concerts.ConcertsRepository.applySchedule()
    }

    fun setTicketmasterApiKey(value: String) {
        ticketmasterApiKey.value = value
        prefs.edit().putString(KEY_TICKETMASTER_API_KEY, value).apply()
        com.music.sonariq.data.concerts.ConcertsRepository.applySchedule()
    }

    fun setCastTvLyricsEnabled(value: Boolean) {
        castTvLyricsEnabled.value = value
        prefs.edit().putBoolean(KEY_CAST_TV_LYRICS_ENABLED, value).apply()
    }

    fun setCastReceiverAppId(value: String) {
        val normalized = value.trim()
        castReceiverAppId.value = normalized
        prefs.edit().putString(KEY_CAST_RECEIVER_APP_ID, normalized).apply()
    }

    fun setLastfmPrimaryArtistOnly(value: Boolean) {
        lastfmPrimaryArtistOnly.value = value
        prefs.edit().putBoolean(KEY_LASTFM_PRIMARY_ARTIST_ONLY, value).apply()
    }

    fun setScrobbleMinDuration(value: Int) {
        scrobbleMinDuration.value = value
        prefs.edit().putInt(KEY_SCROBBLE_MIN_DURATION, value).apply()
    }

    fun setScrobbleDelayPercent(value: Float) {
        scrobbleDelayPercent.value = value
        prefs.edit().putFloat(KEY_SCROBBLE_DELAY_PERCENT, value).apply()
    }

    fun setScrobbleDelaySeconds(value: Int) {
        scrobbleDelaySeconds.value = value
        prefs.edit().putInt(KEY_SCROBBLE_DELAY_SECONDS, value).apply()
    }

    fun setListenBrainzEnabled(value: Boolean) {
        listenBrainzEnabled.value = value
        prefs.edit().putBoolean(KEY_LISTENBRAINZ_ENABLED, value).apply()
    }

    fun setListenBrainzToken(value: String) {
        listenBrainzToken.value = value
        prefs.edit().putString(KEY_LISTENBRAINZ_TOKEN, value).apply()
    }

    fun setListenBrainzPrimaryArtistOnly(value: Boolean) {
        listenBrainzPrimaryArtistOnly.value = value
        prefs.edit().putBoolean(KEY_LISTENBRAINZ_PRIMARY_ARTIST_ONLY, value).apply()
    }

    /** Writes through to the encrypted store; pass "" to disconnect. */
    fun setDiscordToken(value: String) {
        discordToken.value = value
        authStore.discordToken = value.ifEmpty { null }
    }

    fun setDiscordAccount(username: String, name: String, avatar: String?) {
        discordUsername.value = username
        discordName.value = name
        discordAvatar.value = avatar.orEmpty()
        prefs.edit()
            .putString(KEY_DISCORD_USERNAME, username)
            .putString(KEY_DISCORD_NAME, name)
            .putString(KEY_DISCORD_AVATAR, avatar.orEmpty())
            .apply()
    }

    fun setDiscordRpcEnabled(value: Boolean) {
        discordRpcEnabled.value = value
        prefs.edit().putBoolean(KEY_DISCORD_RPC_ENABLED, value).apply()
    }

    fun setDiscordUseDetails(value: Boolean) {
        discordUseDetails.value = value
        prefs.edit().putBoolean(KEY_DISCORD_USE_DETAILS, value).apply()
    }

    fun setDiscordShowAudioQuality(value: Boolean) {
        discordShowAudioQuality.value = value
        prefs.edit().putBoolean(KEY_DISCORD_SHOW_AUDIO_QUALITY, value).apply()
    }

    fun setDiscordAdvancedMode(value: Boolean) {
        discordAdvancedMode.value = value
        prefs.edit().putBoolean(KEY_DISCORD_ADVANCED_MODE, value).apply()
    }

    fun setDiscordStatus(value: String) {
        discordStatus.value = value
        prefs.edit().putString(KEY_DISCORD_STATUS, value).apply()
    }

    fun setDiscordActivityType(value: String) {
        discordActivityType.value = value
        prefs.edit().putString(KEY_DISCORD_ACTIVITY_TYPE, value).apply()
    }

    fun setDiscordActivityName(value: String) {
        discordActivityName.value = value
        prefs.edit().putString(KEY_DISCORD_ACTIVITY_NAME, value).apply()
    }

    fun setDiscordButton1Text(value: String) {
        discordButton1Text.value = value
        prefs.edit().putString(KEY_DISCORD_BUTTON_1_TEXT, value).apply()
    }

    fun setDiscordButton1Visible(value: Boolean) {
        discordButton1Visible.value = value
        prefs.edit().putBoolean(KEY_DISCORD_BUTTON_1_VISIBLE, value).apply()
    }

    fun setDiscordButton2Text(value: String) {
        discordButton2Text.value = value
        prefs.edit().putString(KEY_DISCORD_BUTTON_2_TEXT, value).apply()
    }

    fun setDiscordButton2Visible(value: Boolean) {
        discordButton2Visible.value = value
        prefs.edit().putBoolean(KEY_DISCORD_BUTTON_2_VISIBLE, value).apply()
    }

    fun setDiscordInfoDismissed(value: Boolean) {
        discordInfoDismissed.value = value
        prefs.edit().putBoolean(KEY_DISCORD_INFO_DISMISSED, value).apply()
    }

    fun setReplayGenres(value: Boolean) {
        replayGenres.value = value
        prefs.edit().putBoolean(KEY_REPLAY_GENRES, value).apply()
    }

    fun setFilterNonMusicAudio(value: Boolean) {
        filterNonMusicAudio.value = value
        prefs.edit().putBoolean(KEY_FILTER_NON_MUSIC_AUDIO, value).apply()
    }

    fun setLocalMusicSort(value: LocalMusicSort) {
        localMusicSort.value = value
        prefs.edit().putString(KEY_LOCAL_MUSIC_SORT, value.name).apply()
    }

    fun setDownloadedMusicSort(value: LocalMusicSort) {
        downloadedMusicSort.value = value
        prefs.edit().putString(KEY_DOWNLOADED_MUSIC_SORT, value.name).apply()
    }

    fun setLibrarySort(value: LibrarySort) {
        librarySort.value = value
        prefs.edit().putString(KEY_LIBRARY_SORT, value.name).apply()
    }

    fun setDetailSongSort(browseId: String, value: SongSort) {
        detailSongSorts.value = detailSongSorts.value + (browseId to value)
        prefs.edit().putString(
            KEY_DETAIL_SONG_SORTS,
            detailSongSorts.value.entries.joinToString(",") { (id, sort) -> "$id=${sort.name}" },
        ).apply()
    }

    fun setLocalMusicViewType(value: LibraryViewType) {
        localMusicViewType.value = value
        prefs.edit().putString(KEY_LOCAL_MUSIC_VIEW_TYPE, value.name).apply()
    }

    fun setDownloadedMusicViewType(value: LibraryViewType) {
        downloadedMusicViewType.value = value
        prefs.edit().putString(KEY_DOWNLOADED_MUSIC_VIEW_TYPE, value.name).apply()
    }

    fun setHomeRecentsViewType(value: LibraryViewType) {
        homeRecentsViewType.value = value
        prefs.edit().putString(KEY_HOME_RECENTS_VIEW_TYPE, value.name).apply()
    }

    fun setLocalMusicFolderUri(value: String) {
        localMusicFolderUri.value = value
        prefs.edit().putString(KEY_LOCAL_MUSIC_FOLDER_URI, value).apply()
    }

    fun setWebDavUrl(value: String) {
        val normalized = value.trim().trimEnd('/')
        webdavUrl.value = normalized
        prefs.edit().putString(KEY_WEBDAV_URL, normalized).apply()
        publishWebDavAuth()
    }

    fun setWebDavUsername(value: String) {
        val normalized = value.trim()
        webdavUsername.value = normalized
        prefs.edit().putString(KEY_WEBDAV_USERNAME, normalized).apply()
        publishWebDavAuth()
    }

    /** Writes through to the encrypted store; pass "" to forget. */
    fun setWebDavPassword(value: String) {
        webdavPassword.value = value
        authStore.webdavPassword = value.ifEmpty { null }
        publishWebDavAuth()
    }

    fun clearWebDav() {
        setWebDavUrl("")
        setWebDavUsername("")
        setWebDavPassword("")
    }

    fun setSmbHost(value: String) {
        val normalized = value.trim()
        smbHost.value = normalized
        prefs.edit().putString(KEY_SMB_HOST, normalized).apply()
        publishSmbAuth()
    }

    fun setSmbShare(value: String) {
        val normalized = value.trim().trim('/')
        smbShare.value = normalized
        prefs.edit().putString(KEY_SMB_SHARE, normalized).apply()
        publishSmbAuth()
    }

    fun setSmbBasePath(value: String) {
        val normalized = value.trim().trim('/')
        smbBasePath.value = normalized
        prefs.edit().putString(KEY_SMB_BASE_PATH, normalized).apply()
        publishSmbAuth()
    }

    fun setSmbUsername(value: String) {
        val normalized = value.trim()
        smbUsername.value = normalized
        prefs.edit().putString(KEY_SMB_USERNAME, normalized).apply()
        publishSmbAuth()
    }

    /** Writes through to the encrypted store; pass "" to forget. */
    fun setSmbPassword(value: String) {
        smbPassword.value = value
        authStore.smbPassword = value.ifEmpty { null }
        publishSmbAuth()
    }

    fun clearSmb() {
        setSmbHost("")
        setSmbShare("")
        setSmbBasePath("")
        setSmbUsername("")
        setSmbPassword("")
    }

    private fun publishSmbAuth() {
        if (!this::prefs.isInitialized || !this::authStore.isInitialized) return
        com.music.sonariq.data.smb.SmbAuth.update(
            smbHost.value,
            smbShare.value,
            smbBasePath.value,
            smbUsername.value,
            smbPassword.value,
        )
    }

    private fun publishWebDavAuth() {
        if (!this::prefs.isInitialized || !this::authStore.isInitialized) return
        com.music.sonariq.data.webdav.WebDavAuth.update(
            webdavUrl.value,
            webdavUsername.value,
            webdavPassword.value,
        )
    }

    private fun readLocalMusicSort(key: String): LocalMusicSort =
        prefs.getString(key, null)
            ?.let { saved -> LocalMusicSort.entries.firstOrNull { it.name == saved } }
            ?: LocalMusicSort.TITLE_ASC

    private fun readLibraryViewType(key: String): LibraryViewType =
        prefs.getString(key, null)
            ?.let { saved -> LibraryViewType.entries.firstOrNull { it.name == saved } }
            ?: LibraryViewType.LIST

    /**
     * Pins or unpins [browseId], returning whether it is pinned afterwards.
     *
     * Pinning past [MAX_PINNED_PLAYLISTS] is refused rather than evicting the
     * oldest pin: a silent swap would mean a playlist someone pinned on purpose
     * disappears from the row without them ever having touched it, the moment
     * they pin a sixth. Unpinning always succeeds.
     */
    fun togglePinnedPlaylist(browseId: String): Boolean {
        val current = pinnedPlaylists.value
        val updated = when {
            browseId in current -> current - browseId
            current.size >= MAX_PINNED_PLAYLISTS -> return false
            else -> current + browseId
        }
        pinnedPlaylists.value = updated
        prefs.edit().putString(KEY_PINNED_PLAYLISTS, updated.joinToString(",")).apply()
        return browseId in updated
    }

    private fun readDetailSongSorts(): Map<String, SongSort> =
        prefs.getString(KEY_DETAIL_SONG_SORTS, null)
            ?.split(",")
            ?.mapNotNull { entry ->
                val id = entry.substringBefore('=', "")
                val sort = SongSort.entries.firstOrNull { it.name == entry.substringAfter('=', "") }
                if (id.isBlank() || sort == null) null else id to sort
            }
            ?.toMap()
            ?: emptyMap()

    private fun readPinnedPlaylists(): List<String> {
        val stored = prefs.getString(KEY_PINNED_PLAYLISTS, null) ?: return emptyList()
        return stored.split(",").filter { it.isNotBlank() }
    }

    /** Forgets the account: token and cached profile. */
    fun clearDiscordAccount() {
        setDiscordToken("")
        setDiscordAccount("", "", null)
    }

    // ── Backup ──────────────────────────────────────────────────────────────

    /**
     * Every stored preference, for an export.
     *
     * Read off the preference file wholesale rather than assembled from the
     * flows above, so a setting added in a later build is in the backup the day
     * it is added instead of the day somebody remembers to list it here. What is
     * *left out* is therefore the part worth stating explicitly, and it is
     * [SECRETS]: an export is a file the user is about to put in Drive or a
     * chat, and a scrobbler session key or an API secret in it is a credential
     * that has left the device in plain text. Signing back in after a restore is
     * a minute; a leaked session key is not recoverable at all.
     *
     * The Discord token is not here for the same reason and one more: it never
     * reaches this file. It lives in the encrypted store — see [AuthStore] — and
     * so does the YouTube cookie, which means neither can be exported by
     * accident.
     */
    fun exportPrefs(): Map<String, Any?> {
        if (!this::prefs.isInitialized) return emptyMap()
        return prefs.all.filterKeys { it !in SECRETS && it !in DEVICE_LOCAL }
    }

    /**
     * Replaces the preference file with [values] and re-reads it.
     *
     * A replace, not a merge: a partial restore leaves a device holding half of
     * one configuration and half of another, which is the one outcome nobody
     * asked for. Keys in [SECRETS] survive untouched — they were never in the
     * file being restored from, and clearing them would sign the user out of
     * services the backup has nothing to say about.
     */
    fun importPrefs(values: Map<String, Any?>) {
        if (!this::prefs.isInitialized) return
        val kept = prefs.all.filterKeys { it in SECRETS || it in DEVICE_LOCAL }
        prefs.edit().apply {
            clear()
            val incoming = values.filterKeys { it !in SECRETS && it !in DEVICE_LOCAL }
            (kept + incoming).forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> Unit
                }
            }
        }.apply()
        reload()
    }

    /**
     * Preferences an export must not carry — credentials, not configuration.
     * See [exportPrefs].
     */
    private val SECRETS = setOf(
        KEY_LASTFM_SESSION_KEY,
        KEY_LASTFM_API_KEY,
        KEY_LASTFM_SECRET,
        KEY_LISTENBRAINZ_TOKEN,
        KEY_SPOTIFY_SPDC_TOKEN,
        KEY_PAXSENIX_API_KEY,
    )

    /**
     * Preferences that describe *this device* rather than this configuration,
     * and so are neither exported nor overwritten by an import.
     *
     * [Downloads][com.music.sonariq.download.Downloads] keeps its record of
     * what is saved in this same preference file, and that record is a list of
     * files on this phone's storage. Carrying it into a backup would restore a
     * folder full of tracks that are not here; clearing it on import would leave
     * the files on disk with nothing pointing at them, which is worse — the
     * Downloads page would read as empty while the space stayed used.
     */
    private val DEVICE_LOCAL = setOf(
        "downloaded_tracks",
        "downloaded_tracks_metadata",
        "downloaded_collections",
        KEY_LOCAL_MUSIC_FOLDER_URI,
        KEY_LAST_VERSION_CODE,
    )

    const val DEFAULT_CACHE_LIMIT_BYTES = 512L * 1024 * 1024
    const val MAX_CACHE_LIMIT_BYTES = 10L * 1024 * 1024 * 1024

    /** The cache limit with no ceiling: only free storage bounds it. */
    const val UNLIMITED_CACHE_LIMIT_BYTES = Long.MAX_VALUE

    const val MIN_LYRICS_OFFSET_MS = -5_000
    const val MAX_LYRICS_OFFSET_MS = 5_000

    const val MIN_LYRICS_FONT_SIZE_SP = 13f
    const val MAX_LYRICS_FONT_SIZE_SP = 24f
    const val DEFAULT_LYRICS_FONT_SIZE_SP = 17f

    /** The highlight-intensity slider range; mirrors the player's own constants. */
    const val MIN_LYRICS_HIGHLIGHT_INTENSITY = 0.4f
    const val MAX_LYRICS_HIGHLIGHT_INTENSITY = 1.6f
    const val DEFAULT_LYRICS_HIGHLIGHT_INTENSITY = 1f

    /** The visualizer strip's sensitivity slider range; 1 is the meter's own calibration. */
    const val MIN_VISUALIZER_INTENSITY = 0.25f
    const val MAX_VISUALIZER_INTENSITY = 2f
    const val DEFAULT_VISUALIZER_INTENSITY = 1f

    const val DEFAULT_UPGRADE_LENGTH_SLACK_SECONDS = 3
    const val MIN_UPGRADE_LENGTH_SLACK_SECONDS = 0
    const val MAX_UPGRADE_LENGTH_SLACK_SECONDS = 10

    /** Vibe strictness slider range: pure at 0.3, adventurous at 0.9. */
    const val MIN_VIBE_STRICTNESS = 0.3f
    const val MAX_VIBE_STRICTNESS = 0.9f
    const val DEFAULT_VIBE_STRICTNESS = 0.6f

    private const val DEFAULT_PERFORMANCE_REFRESH_RATE = 120

    private fun normalizePerformanceRefreshRate(value: Int): Int =
        value.takeIf { it in 50..240 } ?: DEFAULT_PERFORMANCE_REFRESH_RATE

    private const val KEY_QUALITY_LEGACY = "audio_quality"
    private const val KEY_QUALITY_WIFI = "audio_quality_wifi"
    private const val KEY_QUALITY_CELLULAR = "audio_quality_cellular"
    private const val KEY_UPGRADE_LENGTH_SLACK_SECONDS = "upgrade_length_slack_seconds"
    private const val KEY_QUALITY_DOWNLOAD = "audio_quality_download"
    private const val KEY_WIFI_ONLY_DOWNLOADS = "wifi_only_downloads"
    private const val KEY_EXPORT_DOWNLOADS = "export_downloads"
    private const val KEY_SMART_DOWNLOADS_ENABLED = "smart_downloads_enabled"
    private const val KEY_SMART_DOWNLOADS_COUNT = "smart_downloads_count"
    private const val KEY_SMART_DOWNLOADS_WIFI_ONLY = "smart_downloads_wifi_only"
    private const val KEY_OFFLINE_MODE = "offline_mode"
    private const val KEY_CONCERT_ALERTS_ENABLED = "concert_alerts_enabled"
    private const val KEY_TICKETMASTER_API_KEY = "ticketmaster_api_key"
    private const val KEY_CAST_TV_LYRICS_ENABLED = "cast_tv_lyrics_enabled"
    private const val KEY_CAST_RECEIVER_APP_ID = "cast_receiver_app_id"
    private const val KEY_LOSSLESS = "lossless_audio"
    private const val KEY_CROSSFADE = "crossfade_seconds"
    private const val KEY_SMART_FADE = "smart_fade_enabled"
    private const val KEY_DJ_MIX = "dj_mix_enabled"
    private const val KEY_SLEEP_TIMER_FADE_OUT = "sleep_timer_fade_out"
    private const val KEY_AUTOMIX_PERFORMANCE_MODE = "automix_performance_mode"
    private const val KEY_SKIP_SILENCE = "skip_silence"
    private const val KEY_OUTPUT_PCM_MODE = "output_pcm_mode"
    private const val KEY_PREFER_USB_DAC = "prefer_usb_dac"
    private const val KEY_LOUDNESS_NORMALIZATION = "loudness_normalization"
    private const val KEY_LOUDNESS_OFF_ON_SPEAKER = "loudness_off_on_speaker"
    private const val KEY_DOLBY_ATMOS = "dolby_atmos"
    private const val KEY_SPATIAL_AUDIO = "spatial_audio"
    private const val KEY_EQ_ENABLED = "equalizer_enabled"
    private const val KEY_EQ_MODE = "equalizer_mode"
    private const val KEY_EQ_TONE_X = "equalizer_tone_x"
    private const val KEY_EQ_TONE_Y = "equalizer_tone_y"
    private const val KEY_EQ_FOCUSED = "equalizer_focused"
    private const val KEY_EQ_BALANCE = "equalizer_balance"
    private const val KEY_EQ_BANDS = "equalizer_bands"
    private const val KEY_EQ_BASS = "equalizer_bass"
    private const val KEY_EQ_TREBLE = "equalizer_treble"
    private const val KEY_EQ_USER_PRESETS = "equalizer_user_presets"
    private const val KEY_EQ_USER_PRESET_ACTIVE = "equalizer_user_preset_active"
    private const val KEY_SPEED = "playback_speed"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_ACCENT_COLOR = "accent_color"
    private const val KEY_ACCENT_COLOR_CUSTOM = "accent_color_custom"
    private const val KEY_DYNAMIC_THEME = "dynamic_theme"
    private const val KEY_AUTOPLAY = "autoplay"
    private const val KEY_VIBE_AUTOPLAY = "vibe_autoplay"
    private const val KEY_VIBE_FORGOTTEN_FAVORITES = "vibe_forgotten_favorites"
    private const val KEY_VIBE_STRICTNESS = "vibe_strictness"
    private const val KEY_AUTOPLAY_MIX_LIBRARY = "autoplay_mix_library"
    private const val KEY_AUTOPLAY_MIX_DOWNLOADS = "autoplay_mix_downloads"
    private const val KEY_AUTOPLAY_MIX_DISCOVERY = "autoplay_mix_discovery"
    private const val KEY_AUTOPLAY_EXPLORATION = "autoplay_exploration"
    private const val KEY_AUTOPLAY_DISCOVERY_MODE = "autoplay_discovery_mode"
    private const val KEY_AUTOPLAY_BANNED_SONG_IDS = "autoplay_banned_song_ids"
    private const val KEY_AUTOPLAY_BANNED_ARTISTS = "autoplay_banned_artists"
    private const val KEY_AUTOPLAY_DOWNLOADS_ONLY = "autoplay_downloads_only"
    private const val KEY_ACTIVITY_AUTOPLAY = "activity_autoplay"
    private const val KEY_WIND_DOWN_ENABLED = "wind_down_enabled"
    private const val KEY_WIND_DOWN_BEDTIME_MINUTES = "wind_down_bedtime_minutes"
    private const val KEY_WIND_DOWN_LEAD_MINUTES = "wind_down_lead_minutes"
    private const val KEY_WORKOUT_MODE = "workout_mode"
    private const val KEY_WORKOUT_STARTED_AT_MS = "workout_started_at_ms"
    private const val KEY_WORKOUT_LENGTH_MINUTES = "workout_length_minutes"
    private const val KEY_ENERGY_CURVE = "energy_curve"
    private const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_NERD_STATS = "show_nerd_stats"
    private const val KEY_CACHE_LIMIT = "audio_cache_limit_bytes"
    private const val KEY_REDUCE_ANIMATION = "reduce_animation"
    private const val KEY_HIGH_PERFORMANCE_MODE = "high_performance_mode"
    private const val KEY_PERFORMANCE_REFRESH_RATE = "performance_refresh_rate"
    private const val KEY_STOP_ON_TASK_REMOVED = "stop_on_task_removed"
    private const val KEY_HIDE_VOLUME_BAR = "hide_volume_bar"
    private const val KEY_HIDE_SONG_STATUS = "hide_song_status"
    private const val KEY_SWIPE_TO_PLAY_NEXT = "swipe_to_play_next"
    private const val KEY_DONT_REPEAT_SUGGESTIONS = "dont_repeat_suggestions"
    private const val KEY_PREFER_MUSIC_ONLY = "prefer_music_only"
    private const val KEY_SMART_VERSION_ALIGNMENT = "smart_version_alignment"
    private const val KEY_REDUCE_BLUR = "reduce_dynamic_blur"
    private const val KEY_LIQUID_GLASS = "liquid_glass"
    private const val KEY_LYRICS_BLUR = "lyrics_blur"
    private const val KEY_LYRICS_OFFSET_MS = "lyrics_offset_ms"
    private const val KEY_LYRICS_OFFSET_OVERRIDES = "lyrics_offset_overrides"
    private const val KEY_LYRICS_FONT_SIZE_SP = "lyrics_font_size_sp"
    private const val KEY_LYRICS_AUTO_SCROLL = "lyrics_auto_scroll"
    private const val KEY_LYRICS_KARAOKE = "lyrics_karaoke"
    private const val KEY_LYRICS_HIGHLIGHT_INTENSITY = "lyrics_highlight_intensity"
    private const val KEY_LYRICS_BACKGROUND = "lyrics_background"
    private const val KEY_LYRICS_REDUCED_MOTION = "lyrics_reduced_motion"
    private const val KEY_VISUALIZER_STYLE = "visualizer_style"
    private const val KEY_VISUALIZER_INTENSITY = "visualizer_intensity"
    private const val KEY_VISUALIZER_BATTERY_SAVER = "visualizer_battery_saver"
    private const val KEY_TRANSLATION_LANGUAGE = "translation_language"
    private const val KEY_LYRICS_TRANSLATION_OVERLAY = "lyrics_translation_overlay"
    private const val KEY_ANIMATED_CANVAS = "animated_canvas"
    private const val KEY_CANVAS_OVER_CELLULAR = "canvas_over_cellular"
    private const val KEY_SPOTIFY_CANVAS_AUTO_HIDE = "spotify_canvas_auto_hide"
    private const val KEY_PRIORITIZE_SPOTIFY_CANVAS = "prioritize_spotify_canvas"
    private const val KEY_FULL_BLEED_ARTWORK = "full_bleed_artwork"
    private const val KEY_LEGACY_MESH_GRADIENT = "legacy_mesh_gradient"
    private const val KEY_LAST_PLAYER_SCREEN = "last_player_screen"
    private const val KEY_SYNCED_LYRICS = "synced_lyrics"
    private const val KEY_LYRICS_PREFETCH = "lyrics_prefetch"
    private const val KEY_LOCKSCREEN_LYRICS = "lockscreen_lyrics"
    private const val KEY_AUTO_UPDATE_CHECK = "auto_update_check"
    private const val KEY_LYRICS_SOURCES = "lyrics_sources"
    private const val KEY_LYRICS_SOURCES_SEEN = "lyrics_sources_seen"
    private const val KEY_LYRICS_SOURCE_ORDER = "lyrics_source_order"
    private const val KEY_PRIORITIZE_SYLLABLE_SYNC = "prioritize_syllable_sync"
    private const val KEY_AI_WIFI_ONLY = "ai_wifi_only"
    private const val KEY_PAXSENIX_API_KEY = "paxsenix_api_key"
    private const val KEY_REPLAY_GENRES = "replay_genres"
    private const val KEY_FILTER_NON_MUSIC_AUDIO = "filter_non_music_audio"
    private const val KEY_SHOW_CACHE_FOLDER = "show_cache_folder"
    private const val KEY_LOCAL_MUSIC_SORT = "local_music_sort"
    private const val KEY_DOWNLOADED_MUSIC_SORT = "downloaded_music_sort"
    private const val KEY_LIBRARY_SORT = "library_sort"
    private const val KEY_DETAIL_SONG_SORTS = "detail_song_sorts"
    private const val KEY_LOCAL_MUSIC_VIEW_TYPE = "local_music_view_type"
    private const val KEY_DOWNLOADED_MUSIC_VIEW_TYPE = "downloaded_music_view_type"
    private const val KEY_HOME_RECENTS_VIEW_TYPE = "home_recents_view_type"
    private const val KEY_LOCAL_MUSIC_FOLDER_URI = "local_music_folder_uri"
    private const val KEY_WEBDAV_URL = "webdav_url"
    private const val KEY_WEBDAV_USERNAME = "webdav_username"
    private const val KEY_SMB_HOST = "smb_host"
    private const val KEY_SMB_SHARE = "smb_share"
    private const val KEY_SMB_BASE_PATH = "smb_base_path"
    private const val KEY_SMB_USERNAME = "smb_username"
    private const val KEY_PINNED_PLAYLISTS = "pinned_playlists"

    private const val KEY_PER_DEVICE_EQ = "per_device_eq"
    private const val KEY_MONO_AUDIO = "mono_audio"
    private const val KEY_CANVAS_BACKDROP = "canvas_backdrop"
    private const val KEY_DATA_SAVER = "data_saver"
    private const val KEY_DATA_SAVER_QUALITY = "data_saver_quality"
    private const val KEY_WIFI_ONLY_STREAMING = "wifi_only_streaming"
    private const val KEY_AUTO_DOWNLOADS_ENABLED = "auto_downloads_enabled"
    private const val KEY_AUTO_DOWNLOAD_LIKED_SONGS = "auto_download_liked_songs"
    private const val KEY_AUTO_DOWNLOAD_PLAYLIST_IDS = "auto_download_playlist_ids"
    private const val KEY_AUTO_DOWNLOAD_WIFI_ONLY = "auto_download_wifi_only"
    private const val KEY_SCHEDULED_DOWNLOADS_ENABLED = "scheduled_downloads_enabled"
    private const val KEY_SCHEDULED_DOWNLOAD_HOUR = "scheduled_download_hour"
    private const val KEY_SCHEDULED_DOWNLOAD_MINUTE = "scheduled_download_minute"
    private const val KEY_SCHEDULED_DOWNLOAD_WIFI_ONLY = "scheduled_download_wifi_only"
    private const val KEY_HOME_SECTION_ORDER = "home_section_order_json"
    private const val KEY_HOME_SECTION_LEARNING = "home_section_learning"
    private const val KEY_SAVED_QUEUES = "saved_queues_json"
    private const val KEY_TV_AMBIENT_2 = "tv_ambient_2_enabled"
    private const val KEY_ARTWORK_GESTURES = "artwork_gestures_enabled"
    private const val KEY_PRIVATE_SESSION = "private_session_enabled"

    private const val KEY_LASTFM_ENABLED = "lastfm_enabled"
    private const val KEY_LASTFM_USERNAME = "lastfm_username"
    private const val KEY_LASTFM_SESSION_KEY = "lastfm_session_key"
    private const val KEY_LASTFM_API_KEY = "lastfm_api_key"
    private const val KEY_LASTFM_SECRET = "lastfm_secret"
    private const val KEY_LASTFM_ENDPOINT = "lastfm_endpoint"
    private const val KEY_LASTFM_SCROBBLE_ENABLED = "lastfm_scrobble_enabled"
    private const val KEY_LASTFM_NOW_PLAYING = "lastfm_now_playing"
    private const val KEY_LASTFM_PRIMARY_ARTIST_ONLY = "lastfm_primary_artist_only"
    private const val KEY_SCROBBLE_MIN_DURATION = "scrobble_min_duration"
    private const val KEY_SCROBBLE_DELAY_PERCENT = "scrobble_delay_percent"
    private const val KEY_SCROBBLE_DELAY_SECONDS = "scrobble_delay_seconds"
    private const val KEY_LISTENBRAINZ_ENABLED = "listenbrainz_enabled"
    private const val KEY_LISTENBRAINZ_TOKEN = "listenbrainz_token"
    private const val KEY_LISTENBRAINZ_PRIMARY_ARTIST_ONLY = "listenbrainz_primary_artist_only"
    private const val KEY_SPOTIFY_SPDC_TOKEN = "spotify_spdc_token"

    private const val KEY_DISCORD_USERNAME = "discord_username"
    private const val KEY_DISCORD_NAME = "discord_name"
    private const val KEY_DISCORD_AVATAR = "discord_avatar"
    private const val KEY_DISCORD_RPC_ENABLED = "discord_rpc_enabled"
    private const val KEY_DISCORD_USE_DETAILS = "discord_use_details"
    private const val KEY_DISCORD_SHOW_AUDIO_QUALITY = "discord_show_audio_quality"
    private const val KEY_DISCORD_ADVANCED_MODE = "discord_advanced_mode"
    private const val KEY_DISCORD_STATUS = "discord_status"
    private const val KEY_DISCORD_ACTIVITY_TYPE = "discord_activity_type"
    private const val KEY_DISCORD_ACTIVITY_NAME = "discord_activity_name"
    private const val KEY_DISCORD_BUTTON_1_TEXT = "discord_button_1_text"
    private const val KEY_DISCORD_BUTTON_1_VISIBLE = "discord_button_1_visible"
    private const val KEY_DISCORD_BUTTON_2_TEXT = "discord_button_2_text"
    private const val KEY_DISCORD_BUTTON_2_VISIBLE = "discord_button_2_visible"
    private const val KEY_DISCORD_INFO_DISMISSED = "discord_info_dismissed"
    private const val KEY_LAST_VERSION_CODE = "last_version_code"
}



