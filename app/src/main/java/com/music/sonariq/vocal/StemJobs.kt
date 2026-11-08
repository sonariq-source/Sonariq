package com.music.sonariq.vocal

import android.content.Context
import android.media.MediaDataSource
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.music.sonariq.data.DebugLog as Log
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.playback.AudioCache
import com.music.sonariq.playback.smart.AudioDecoder
import com.music.sonariq.playback.smart.LocalAudioSource
import com.music.sonariq.playback.smart.MdxSeparator
import com.music.sonariq.playback.smart.StereoStems
import com.music.sonariq.playback.smart.UmxSeparator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where a stem job gets a track's audio, resolved on the job thread.
 *
 * A descriptor rather than an opened source, because opening must happen off
 * the caller's thread and the answer can change between the offer and the
 * run (a stream finishes caching, a download lands). [expectedDurationSeconds]
 * is the caller's metadata duration, used only when the container will not
 * state its own — see [StemJobs] for the order they are trusted in.
 */
sealed interface StemSource {
    val expectedDurationSeconds: Double?

    /** A file already on the device: a download, or a local library track. Opened via [LocalAudioSource]. */
    data class LocalFile(
        val uri: Uri,
        override val expectedDurationSeconds: Double? = null,
    ) : StemSource

    /**
     * A streamed track, read out of [AudioCache] the way `TrackAnalyzer`
     * opens cached tracks — a complete rendition only. A stream that is not
     * fully cached yields no source at all: the job records nothing and
     * returns, and the track earns its stem once it is downloaded or has
     * finished caching (a later play, when it is complete, offers it again).
     */
    data class CachedStream(
        val uri: Uri,
        override val expectedDurationSeconds: Double? = null,
    ) : StemSource
}

/**
 * The track the listener is hearing, as the playback service publishes it —
 * the cheapest place the player UI (via AndroidPlayerHost) and the vocal
 * sheet can learn the current stem key and how to prepare it, without either
 * of them growing a line into the service.
 */
object StemCurrentTrack {

    data class Entry(val key: String, val source: StemSource)

    private val _current = MutableStateFlow<Entry?>(null)
    val current: StateFlow<Entry?> = _current.asStateFlow()

    fun publish(key: String, source: StemSource) {
        _current.value = Entry(key, source)
    }

    fun clear() {
        _current.value = null
    }
}

/**
 * Background separation jobs for the Vocal Remover's AI layer.
 *
 * ## Track keys
 *
 * Callers key tracks the way the queue does: `Song.videoId` (the MediaItem
 * mediaId). For streamed and downloaded tracks that is the catalogue
 * videoId — a downloaded track's Song carries its videoId with the file in
 * `localUri`, so the same track keeps one key (and one stem) across the
 * stream/download boundary. For a local library track it is the MediaStore
 * content URI (LocalMediaRepository sets `videoId` to the URI itself unless
 * the file is a recognised download), and for an unrecorded file in the
 * downloads folder it is the file URI. [StemStore] hashes whatever it is
 * given; the convention lives here and in PlaybackService's stem wiring.
 *
 * ## The pipeline
 *
 * One job at a time, on a background dispatcher, per-key in-flight dedupe in
 * the [AiWordSync][com.music.sonariq.download.AiWordSync] shape: open the
 * source, decode the whole track with [AudioDecoder.decodeRegionStereo],
 * separate, store. Tier choice:
 *
 *  1. The MDX model is Ready — one [MdxSeparator] pass, stored as MDX.
 *  2. Otherwise — the bundled UMX model, run *segmented*: decoded PCM is
 *     split into ≤60 s segments with 1 s overlap, each separated on its own
 *     and crossfade-joined. The whole-track UMX spectrogram would take
 *     ~340 MB on a four-minute song; a segment's is bounded and freed
 *     before the next starts.
 *  3. Upgrade: with a UMX stem stored (or an MDX pass still owed), if the
 *     MDX model is or becomes Ready — background jobs may fetch it only
 *     under the background gates below; a user-driven [prepareNow] may
 *     fetch it because the user asked — an MDX pass replaces the stem.
 *
 * ## Gates
 *
 * Automatic triggers ([maybePrepare] with `background = true`) mirror
 * AiWordSync's: while [AppSettings.aiWifiOnly] is on they run only on an
 * unmetered connection, and the same gate covers auto-downloading the
 * model. [prepareNow] is the listener's own ask (the vocal slider lowered,
 * the sheet's button) and runs whatever the connection.
 *
 * ## Cancellation
 *
 * A [prepareNow] for a key supersedes a background job for the same key:
 * the old job's flag is set and its coroutine cancelled; both separators
 * poll that flag per chunk/window, so the old pass stops promptly and the
 * partial result is discarded.
 *
 * Nothing here records or captures audio: the only inputs are files and
 * cache entries the app already holds, decoded exactly as Automix's
 * analyzer decodes them.
 */
object StemJobs {

    private const val TAG = "Sonariq"

    /** UMX segment length and overlap, in seconds — see the class doc for why segmented at all. */
    private const val UMX_SEGMENT_SECONDS = 60.0
    private const val UMX_OVERLAP_SECONDS = 1.0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialises the pipeline: separation is the most expensive pass in the app; jobs queue. */
    private val pipelineMutex = kotlinx.coroutines.sync.Mutex()

    private class Running(val job: Job, val superseded: AtomicBoolean)

    private val running = ConcurrentHashMap<String, Running>()

    @Volatile
    private var appContext: Context? = null

    /** The bundled-tier separator, kept across jobs; its session is released when the queue drains. */
    @Volatile
    private var umxSeparator: UmxSeparator? = null

    /**
     * Hands the object its context, once, from the Application — next to
     * `AiWordSync.init`, and for the same reason: some triggers have no
     * context of their own to pass. Also opens [StemStore] and
     * [MdxModelManager], whose files live under the same filesDir.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        StemStore.init(context)
        MdxModelManager.init(context)
    }

    /**
     * Offers [key] for background preparation. Returns immediately; the
     * gates (already ready at the best tier, already in flight, Wi-Fi-only)
     * decide whether anything runs.
     */
    fun maybePrepare(key: String, source: StemSource, background: Boolean) {
        if (key.isBlank()) return
        val context = appContext ?: return
        if (background && !backgroundGatesAllow(context)) return
        launch(key, source, userDriven = false)
    }

    /**
     * Prepares [key] now, at the listener's ask: no Wi-Fi gate, and a
     * background job for the same key is superseded (cancelled) in favour
     * of this one.
     */
    fun prepareNow(key: String, source: StemSource) {
        if (key.isBlank()) return
        appContext ?: return
        launch(key, source, userDriven = true)
    }

    private fun launch(key: String, source: StemSource, userDriven: Boolean) {
        if (StemStore.tierOf(key) == StemTier.MDX) return
        val existing = running[key]
        if (existing != null) {
            if (!userDriven) return
            // Supersede: flag first (the separators poll it), then cancel.
            existing.superseded.set(true)
            existing.job.cancel()
        }
        // The flag exists before the job does, so the pipeline reads the
        // very instance a later supersede will set — no map lookup race.
        val superseded = AtomicBoolean(false)
        var entry: Running? = null
        val job = scope.launch {
            try {
                runPipeline(key, source, userDriven, superseded)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A superseded job's failure is its replacement's business
                // now; it must not stamp Failed over the new job's state.
                if (!superseded.get()) {
                    Log.d(TAG, "Stem preparation failed for $key: ${e.message}")
                    StemStore.noteFailed(key)
                }
            } finally {
                // Only the entry still registered under this key removes
                // itself; a superseded job must not evict its replacement.
                val finished = entry
                if (finished != null && running[key] === finished) running.remove(key)
                if (running.isEmpty()) umxSeparator?.let { runCatching { it.release() } }
            }
        }
        entry = Running(job, superseded)
        running[key] = entry
    }

    private suspend fun runPipeline(
        key: String,
        source: StemSource,
        userDriven: Boolean,
        supersededFlag: AtomicBoolean,
    ) {
        pipelineMutex.lock()
        try {
            runPipelineLocked(key, source, userDriven, supersededFlag)
        } finally {
            pipelineMutex.unlock()
        }
    }

    private suspend fun runPipelineLocked(
        key: String,
        source: StemSource,
        userDriven: Boolean,
        supersededFlag: AtomicBoolean,
    ) {
        val context = appContext ?: return
        val job = currentCoroutineContext()[Job]
        val cancelled: () -> Boolean = { supersededFlag.get() || job?.isCancelled == true }
        if (StemStore.tierOf(key) == StemTier.MDX) return

        val modelReadyAtStart = MdxModelManager.modelFile() != null

        // Open + decode. A source that cannot be opened — above all a
        // CachedStream with no complete rendition — records nothing and
        // returns, per StemSource.CachedStream's contract: no Preparing or
        // Failed state, no retry record; a later offer (after the download
        // lands, after the cache completes) starts from a clean slate.
        // That is why Preparing is only noted after the decode below has
        // audio in hand: before that point the job has promised nothing.
        val dataSource = openSource(context, source) ?: return
        val decoded = dataSource.use { opened ->
            val duration = AudioDecoder.containerDurationSeconds(opened)
                ?: source.expectedDurationSeconds?.takeIf { it > 0 }
                ?: return
            AudioDecoder.decodeRegionStereo(opened, 0.0, duration) { cancelled() }
        } ?: run {
            if (!cancelled()) StemStore.noteFailed(key)
            return
        }
        val (pcm, actualStartSeconds) = decoded
        val (left, right) = padForActualStart(pcm.left, pcm.right, pcm.sampleRate, actualStartSeconds)
        if (cancelled()) return
        StemStore.notePreparing(key, if (modelReadyAtStart) StemTier.MDX else StemTier.UMX, 0f)

        var haveStem = StemStore.tierOf(key) != null

        // Tier 1: the pro model, when it is already here.
        if (modelReadyAtStart) {
            val stems = mdxPass(key, left, right, pcm.sampleRate, cancelled)
            if (stems != null && StemStore.writeStem(key, StemTier.MDX, stems)) {
                return
            }
            if (cancelled()) return
            // An MDX failure with no stem at all falls through to the
            // bundled tier rather than leaving the track unseparated.
        }

        // Tier 2: the bundled model, segmented.
        if (!haveStem) {
            val stems = umxPassSegmented(key, left, right, pcm.sampleRate, cancelled)
            if (stems != null && StemStore.writeStem(key, StemTier.UMX, stems)) {
                haveStem = true
            }
            if (cancelled()) return
        }

        // Upgrade: fetch the pro model where allowed, then re-run at MDX.
        if (StemStore.tierOf(key) != StemTier.MDX) {
            val mayFetchModel = userDriven || backgroundGatesAllow(context)
            if (mayFetchModel && MdxModelManager.modelFile() == null) {
                MdxModelManager.ensureModel()
            }
            if (MdxModelManager.modelFile() != null && !cancelled()) {
                val stems = mdxPass(key, left, right, pcm.sampleRate, cancelled)
                if (stems != null) StemStore.writeStem(key, StemTier.MDX, stems)
            }
        }

        if (StemStore.tierOf(key) == null && !cancelled()) {
            StemStore.noteFailed(key)
        }
    }

    /** The decode starts at the sync sample at or before 0, which can be a few frames in; pad it back to the track's true head. */
    private fun padForActualStart(
        left: FloatArray,
        right: FloatArray,
        sampleRate: Double,
        actualStartSeconds: Double,
    ): Pair<FloatArray, FloatArray> {
        val padFrames = (actualStartSeconds * sampleRate).roundToInt()
        if (padFrames <= 0) return left to right
        val paddedLeft = FloatArray(padFrames + left.size)
        val paddedRight = FloatArray(padFrames + right.size)
        left.copyInto(paddedLeft, padFrames)
        right.copyInto(paddedRight, padFrames)
        return paddedLeft to paddedRight
    }

    /**
     * Resolves [source] to a readable [MediaDataSource] on this (job)
     * thread, or null when no complete local copy is obtainable. Callers
     * must close the result.
     */
    private fun openSource(context: Context, source: StemSource): MediaDataSource? =
        when (source) {
            is StemSource.LocalFile ->
                LocalAudioSource.open(context.contentResolver, source.uri)

            is StemSource.CachedStream -> {
                // TrackAnalyzer's own way in: the lightest complete
                // rendition (renditionsOf answers cheapest-first), else the
                // player's own rendition when AudioCache calls it complete.
                val complete = AudioCache.renditionsOf(source.uri).firstOrNull { it.isComplete }
                if (complete != null) {
                    AudioCache.renditionDataSource(source.uri, complete)
                } else {
                    AudioCache.mediaDataSource(source.uri)
                }
            }
        }

    private fun mdxPass(
        key: String,
        left: FloatArray,
        right: FloatArray,
        sampleRate: Double,
        cancelled: () -> Boolean,
    ): StereoStems? {
        val model = MdxModelManager.modelFile() ?: return null
        val separator = MdxSeparator(
            modelFile = model,
            inferenceThreads = { AppSettings.automixPerformanceMode.value.inferenceThreads },
            configureOptions = nnapiHook,
        )
        return try {
            separator.separate(
                left = left,
                right = right,
                sampleRate = sampleRate,
                onProgress = { progress -> StemStore.notePreparing(key, StemTier.MDX, progress) },
                cancelled = cancelled,
            )
        } finally {
            separator.release()
        }
    }

    /**
     * The bundled tier over the whole track, in ≤60 s segments with 1 s
     * overlap, crossfade-joined at 44.1 kHz. See the class doc for the
     * memory reasoning; each segment's spectrogram is the separator's own
     * business and is gone before the next segment is cut.
     */
    private fun umxPassSegmented(
        key: String,
        left: FloatArray,
        right: FloatArray,
        sampleRate: Double,
        cancelled: () -> Boolean,
    ): StereoStems? {
        val separator = umxSeparator()
        val totalSeconds = left.size / sampleRate
        if (totalSeconds <= 0) return null
        val stepSeconds = UMX_SEGMENT_SECONDS - UMX_OVERLAP_SECONDS
        val segmentCount = maxOf(1, ceil(totalSeconds / stepSeconds).toInt())
        val outFrames = (left.size * STEM_RATE / sampleRate).roundToInt()
        val outLeft = FloatArray(outFrames)
        val outRight = FloatArray(outFrames)
        var writtenUpTo = 0
        var segmentIndex = 0
        var startSeconds = 0.0
        while (startSeconds < totalSeconds) {
            if (cancelled()) return null
            val endSeconds = min(startSeconds + UMX_SEGMENT_SECONDS, totalSeconds)
            val from = (startSeconds * sampleRate).roundToInt().coerceIn(0, left.size)
            val to = (endSeconds * sampleRate).roundToInt().coerceIn(from, left.size)
            if (to <= from) break
            val index = segmentIndex
            val stems = separator.separate(
                left = left.copyOfRange(from, to),
                right = right.copyOfRange(from, to),
                sampleRate = sampleRate,
                onProgress = { progress ->
                    StemStore.notePreparing(
                        key,
                        StemTier.UMX,
                        ((index + progress) / segmentCount).coerceIn(0f, 1f),
                    )
                },
                cancelled = cancelled,
            ) ?: return null

            val destStart = (startSeconds * STEM_RATE).roundToInt()
            val overlapFrames = if (segmentIndex == 0) {
                0
            } else {
                minOf((UMX_OVERLAP_SECONDS * STEM_RATE).roundToInt(), stems.left.size, writtenUpTo - destStart)
                    .coerceAtLeast(0)
            }
            for (i in stems.left.indices) {
                val pos = destStart + i
                if (pos >= outFrames) break
                if (i < overlapFrames && pos < writtenUpTo) {
                    // Linear crossfade across the overlap: the outgoing
                    // segment fades out as the incoming one fades in, so
                    // the seam is a blend of two estimates of the same
                    // second rather than a cut between them.
                    val weight = i.toFloat() / overlapFrames
                    outLeft[pos] = outLeft[pos] * (1f - weight) + stems.left[i] * weight
                    outRight[pos] = outRight[pos] * (1f - weight) + stems.right[i] * weight
                } else {
                    outLeft[pos] = stems.left[i]
                    outRight[pos] = stems.right[i]
                }
            }
            writtenUpTo = maxOf(writtenUpTo, destStart + stems.left.size)
            segmentIndex += 1
            startSeconds += stepSeconds
        }
        if (writtenUpTo <= 0) return null
        return StereoStems(
            outLeft.copyOf(writtenUpTo),
            outRight.copyOf(writtenUpTo),
            STEM_RATE,
        )
    }

    private fun umxSeparator(): UmxSeparator {
        umxSeparator?.let { return it }
        val created = UmxSeparator(
            modelPath = { unpackedUmxAsset() },
            inferenceThreads = { AppSettings.automixPerformanceMode.value.inferenceThreads },
        )
        umxSeparator = created
        return created
    }

    /**
     * The bundled UMX weights on disk, unpacked from assets the first time
     * they are asked for — TrackAnalyzer's `unpackedAsset` arrangement,
     * which exists because ONNX Runtime wants a file it can memory-map and
     * an asset inside the APK is not one.
     */
    private fun unpackedUmxAsset(): String {
        val context = appContext ?: error("StemJobs used before init")
        val file = File(context.filesDir, UmxSeparator.MODEL_ASSET)
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(UmxSeparator.MODEL_ASSET).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file.absolutePath
    }

    /**
     * An attempt at NNAPI acceleration for the MDX session, resolved by
     * reflection: `addNnapi` exists on the Android ONNX Runtime artifact
     * but not on the JVM artifact this module also compiles against in
     * tests, and a hard reference would break the latter. Any failure —
     * no such method, the provider refusing the device — is swallowed
     * here and again by [MdxSeparator], which treats a throwing hook as
     * "continue on CPU". Accelerated inference is an optimisation, never
     * a requirement.
     */
    private val nnapiHook: (ai.onnxruntime.OrtSession.SessionOptions) -> Unit = { options ->
        runCatching {
            val methods = options.javaClass.methods.filter { it.name == "addNnapi" }
            val noArg = methods.firstOrNull { it.parameterCount == 0 }
            if (noArg != null) {
                noArg.invoke(options)
            } else {
                val withFlags = methods.firstOrNull { it.parameterCount == 1 }
                val flagsClass = runCatching {
                    Class.forName("ai.onnxruntime.providers.NNAPIFlags")
                }.getOrNull()
                if (withFlags != null && flagsClass != null && flagsClass.isEnum) {
                    @Suppress("UNCHECKED_CAST")
                    val empty = java.util.EnumSet.noneOf(flagsClass as Class<out Enum<*>>)
                    withFlags.invoke(options, empty)
                }
            }
        }
    }

    /** The background gates, mirroring AiWordSync's: the Wi-Fi-only switch plus a metered check. */
    private fun backgroundGatesAllow(context: Context): Boolean =
        !AppSettings.aiWifiOnly.value || isUnmetered(context)

    /**
     * Whether the active connection is unmetered, asked the same way
     * AiWordSync asks it. No active network, or nothing that will say,
     * counts as metered — a background job that cannot prove the
     * connection is free does not run on it.
     */
    private fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private const val STEM_RATE = 44_100.0
}
