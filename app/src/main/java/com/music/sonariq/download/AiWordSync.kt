package com.music.sonariq.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.music.sonariq.BuildConfig
import com.music.sonariq.data.DebugLog as Log
import com.music.sonariq.data.innertube.StreamResolver
import com.music.sonariq.data.lyrics.AiLyricAligner
import com.music.sonariq.data.lyrics.LyricTiming
import com.music.sonariq.data.lyrics.LyricsRepository
import com.music.sonariq.data.lyrics.TranscriptionClient
import com.music.sonariq.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether one AI word-sync job may start: the feature is switched on, this
 * build carries a transcription key, and no job for the same track is
 * already running.
 *
 * Pure, so the dormant behaviour is testable without a device: a build
 * whose key is blank (a fresh checkout, a fork — the key is injected at
 * build time and is not in the source) fails this gate for every track,
 * and the app behaves exactly as it did before this feature existed.
 */
internal fun aiWordSyncGateAllows(
    enabled: Boolean,
    keyBlank: Boolean,
    alreadyInFlight: Boolean,
): Boolean = enabled && !keyBlank && !alreadyInFlight

/**
 * Whether a settled lyrics result is worth offering to [AiWordSync]: it
 * has words worth timing, and it is not already word-synced — not by a
 * provider ([LyricTiming.WORD_SYNCED]) and not by an earlier measurement
 * of the same track ([LyricTiming.WORD_SYNCED_AI_MEASURED], which
 * [LyricsRepository.storeAiResult] would refuse to overwrite anyway).
 * Shared by every trigger so they all offer exactly the same results.
 */
internal fun LyricsRepository.Result.isAiWordSyncCandidate(): Boolean =
    lines.any { it.text.isNotBlank() } &&
        timing != LyricTiming.WORD_SYNCED &&
        timing != LyricTiming.WORD_SYNCED_AI_MEASURED

/**
 * Background word sync for tracks no lyrics provider could word-sync.
 *
 * When a track's settled lyrics have lines but no word timings, a job here
 * transcribes the track's *own audio* through [TranscriptionClient], lines
 * the heard words up against the known lyric lines with [AiLyricAligner],
 * and stores the measured result through
 * [LyricsRepository.storeAiResult], which ranks it below every provider
 * result and keeps it provisional — the ordinary lookup keeps hunting for
 * provider timings and replaces these when it finds them.
 *
 * The audio is only ever the track's file: a local file handed in by the
 * caller, the track's own download, or the track's stream fetched to a
 * temp file for this one job and deleted after it. Nothing is recorded —
 * no microphone, no capture of what the device is playing — and no
 * permission is asked for, because none of that is happening.
 *
 * Dormant without a key: the transcription credential is the owner's one
 * key, injected at build time ([BuildConfig.GROQ_API_KEY]); a build
 * without it fails [aiWordSyncGateAllows] and every entry point below is a
 * no-op, which is the pre-feature behaviour exactly. The key is read here,
 * at the app layer, and handed to the shared client per call — it is never
 * stored, logged, or put in a setting.
 *
 * Jobs are fire-and-forget and cheap to offer: every trigger in the app
 * calls [maybeAlign] and moves on, and the gates inside (enabled, key,
 * per-track dedupe, and for background jobs the Wi-Fi-only switch) decide
 * whether anything actually runs. A job that fails at any step — no
 * lyrics, no audio, a refused or unreachable transcription, an alignment
 * the aligner would not sign — simply ends. Nothing is written on failure,
 * not even a marker: a later play or prefetch may try again.
 */
object AiWordSync {

    private const val TAG = "Sonariq"

    /**
     * The most audio one job will fetch or copy for transcription.
     *
     * A ceiling, not an expectation: a song at the highest rung the
     * resolver offers is a fraction of this. Anything past it is not a
     * song this feature should be spending data on, and the job ends
     * rather than trimming the audio — a partial transcription would time
     * a partial song, which is worse than none.
     */
    private const val MAX_AUDIO_BYTES = 30L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Video ids with a job running, so a track is only ever measured once at a time. */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var appContext: Context? = null

    /**
     * Hands the object its context, once, from the Application — the same
     * pattern as [LyricsWarmup.init], and for the same reason: two of the
     * three triggers (the warm-up worker aside, the list prefetch) have no
     * context of their own to pass, and the gates below need one for the
     * network check, the downloads record and the cache directory. No-op
     * jobs until this has run: every [maybeAlign] before [init] returns
     * without starting anything.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Offers [videoId] for AI word sync; returns immediately either way.
     *
     * [immediate] is the player's offer for the track on screen: it runs
     * whatever the connection, because the listener is waiting on the
     * words. Background offers (the warm-up worker, the list prefetch)
     * pass false and are held to the Wi-Fi-only setting: while
     * [AppSettings.aiWifiOnly] is on they only run on an unmetered
     * connection, checked at offer time.
     *
     * [localAudio] is the track's own file where the caller already knows
     * it (the player, for a local track); the job prefers it over the
     * downloads record and the stream, in that order.
     *
     * [onAligned] is called only when a result was actually stored, with
     * the result as stored, and always on the main dispatcher — the one
     * caller that uses it (the player) applies it straight to its state.
     */
    fun maybeAlign(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        isrc: String? = null,
        localAudio: File? = null,
        immediate: Boolean,
        onAligned: ((LyricsRepository.Result) -> Unit)? = null,
    ) {
        if (videoId.isBlank()) return
        val key = BuildConfig.GROQ_API_KEY
        if (!aiWordSyncGateAllows(
                enabled = AppSettings.aiWordSyncEnabled.value,
                keyBlank = key.isBlank(),
                alreadyInFlight = videoId in inFlight,
            )
        ) {
            return
        }
        val context = appContext ?: return
        if (!immediate && AppSettings.aiWifiOnly.value && !isUnmetered(context)) return
        // The gate read the set; this add is the arbiter. Two offers racing
        // can both pass the gate, but only one of them adds.
        if (!inFlight.add(videoId)) return
        scope.launch {
            try {
                runAlign(context, videoId, title, artist, durationMs, isrc, localAudio, key, onAligned)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed measurement is a non-event: the track keeps its
                // line sync, nothing was written, and a later offer may try
                // again. Said in the log and nowhere else.
                Log.d(TAG, "AI word sync skipped for $videoId: ${e.message}")
            } finally {
                inFlight.remove(videoId)
            }
        }
    }

    /** The job itself. Every early return is a quiet, retryable dead end. */
    private suspend fun runAlign(
        context: Context,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        isrc: String?,
        localAudio: File?,
        apiKey: String,
        onAligned: ((LyricsRepository.Result) -> Unit)?,
    ) {
        // The lines to measure timings onto: the cache first, and only
        // where the cache has nothing, the ordinary lookup with the user's
        // own sources, order and word-sync preference — the same call the
        // warm-up worker makes, so a job never asks for lyrics in a way the
        // listener has switched off.
        val sources = AppSettings.lyricsSources.value
        if (sources.isEmpty()) return
        val cached = LyricsRepository.readCache(videoId, isrc)
        val result = cached ?: LyricsRepository.lyrics(
            videoId = videoId,
            title = title,
            artist = artist,
            durationMs = durationMs,
            sources = sources,
            order = AppSettings.lyricsSourceOrder.value,
            prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
            isrc = isrc,
        ) ?: return
        if (result.lines.none { it.text.isNotBlank() }) return
        // Already word-synced — by a provider, or by an earlier measurement
        // of this same track. Provider timings settle it outright, and an
        // AI result is not re-measured: storeAiResult refuses a second one
        // anyway, so asking the transcription service again would spend
        // the call for nothing.
        if (result.timing == LyricTiming.WORD_SYNCED ||
            result.timing == LyricTiming.WORD_SYNCED_AI_MEASURED
        ) {
            return
        }

        val acquired = acquireAudio(context, videoId, localAudio) ?: return
        try {
            val tokens = TranscriptionClient.transcribe(acquired.file, acquired.mimeType, apiKey)
                ?: return
            if (tokens.isEmpty()) return
            val aligned = AiLyricAligner.align(result.lines, tokens) ?: return
            val aiResult = LyricsRepository.Result(result.source, aligned)
            if (!LyricsRepository.storeAiResult(videoId, isrc, aiResult)) return
            if (onAligned != null) {
                // Hand back the result as stored, read off the cache the
                // store just wrote; the constructed one only if that read
                // somehow fails, since it is the same lines and timing.
                val stored = LyricsRepository.readCache(videoId, isrc) ?: aiResult
                withContext(Dispatchers.Main) { onAligned(stored) }
            }
        } finally {
            // A temp file exists only for this job. The track's own files —
            // a local file, a download — are never deleted here.
            if (acquired.owned) acquired.file.delete()
        }
    }

    /** Audio in hand for one job: the file, its container's type, and whether this job owns it. */
    private class AcquiredAudio(
        val file: File,
        val mimeType: String,
        /** True for a temp copy the job must delete; false for the track's own file. */
        val owned: Boolean,
    )

    /**
     * The track's audio, from the nearest source outwards:
     *
     *  1. [localAudio], where the caller already knows the file.
     *  2. The track's download, from the [Downloads] record — a `file://`
     *     record is used in place; a `content://` one (a Music-folder
     *     export) is copied to a temp file, since the transcription client
     *     posts a [File].
     *  3. The track's stream, resolved the way a download resolves it —
     *     [StreamResolver.resolveForDownload] runs the same client walk as
     *     playback — and fetched with [Downloader.fetch], whose ranged
     *     requests and client-matched headers are what the stream's host
     *     answers to. Fetched to a temp file under the app cache.
     *
     * Every fetched or copied byte stream is held to [MAX_AUDIO_BYTES].
     * Null when none of the three can produce a file.
     */
    private suspend fun acquireAudio(
        context: Context,
        videoId: String,
        localAudio: File?,
    ): AcquiredAudio? {
        localAudio
            ?.takeIf { it.isFile && it.length() in 1..MAX_AUDIO_BYTES }
            ?.let { return AcquiredAudio(it, mimeTypeFor(it.name), owned = false) }

        Downloads.verifiedSavedUri(videoId)?.let { recorded ->
            val uri = Uri.parse(recorded)
            if (uri.scheme == "file") {
                uri.path?.let { path ->
                    val file = File(path)
                    if (file.isFile && file.length() in 1..MAX_AUDIO_BYTES) {
                        return AcquiredAudio(file, mimeTypeFor(file.name), owned = false)
                    }
                }
            }
        }
        Downloads.savedUri(context, videoId)?.let { uri ->
            when (uri.scheme) {
                "file" -> uri.path?.let { path ->
                    val file = File(path)
                    if (file.isFile && file.length() in 1..MAX_AUDIO_BYTES) {
                        return AcquiredAudio(file, mimeTypeFor(file.name), owned = false)
                    }
                }
                "content" -> copyContentToTemp(context, uri)?.let { temp ->
                    return AcquiredAudio(temp, mimeTypeFor(temp.name), owned = true)
                }
            }
        }

        val stream = runCatching {
            StreamResolver.resolveForDownload(videoId, Int.MAX_VALUE, requireM4a = false)
        }.getOrNull() ?: return null
        val temp = tempAudioFile(context, "." + stream.downloadExtension)
        val written = runCatching {
            temp.outputStream().use { sink ->
                Downloader.fetch(
                    videoId = videoId,
                    stream = stream,
                    maxKbps = Int.MAX_VALUE,
                    requireM4a = false,
                    sink = sink,
                    onProgress = { _, total ->
                        // The fetch states the whole size up front, so an
                        // oversized track is refused on its first chunk
                        // rather than after its last one.
                        if (total > MAX_AUDIO_BYTES) {
                            throw IllegalStateException("Track audio is over the size cap")
                        }
                    },
                )
            }
        }.getOrNull()
        if (written == null || written !in 1..MAX_AUDIO_BYTES) {
            temp.delete()
            return null
        }
        return AcquiredAudio(temp, stream.downloadMimeType, owned = true)
    }

    /** [uri]'s bytes in a temp file, or null — past the cap, unreadable, or empty. */
    private suspend fun copyContentToTemp(context: Context, uri: Uri): File? =
        withContext(Dispatchers.IO) {
            val temp = tempAudioFile(context, extensionForContent(context, uri))
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            total += read
                            if (total > MAX_AUDIO_BYTES) {
                                throw IllegalStateException("Track audio is over the size cap")
                            }
                            output.write(buffer, 0, read)
                        }
                        total
                    }
                }
            }.getOrNull()
            if (copied == null || copied == 0L) {
                temp.delete()
                null
            } else {
                temp
            }
        }

    /** A temp file for one job's audio, under a cache subfolder of its own. */
    private fun tempAudioFile(context: Context, suffix: String): File {
        val directory = File(context.cacheDir, "ai_word_sync").apply { mkdirs() }
        return File.createTempFile("align-", suffix, directory)
    }

    /** The extension a `content://` download's copy should carry, from the type the store recorded. */
    private fun extensionForContent(context: Context, uri: Uri): String =
        when (context.contentResolver.getType(uri)) {
            "audio/mp4" -> ".m4a"
            "audio/webm" -> ".webm"
            "audio/mpeg" -> ".mp3"
            "audio/flac" -> ".flac"
            "audio/ogg" -> ".ogg"
            else -> ".m4a"
        }

    /**
     * The MIME type a filename's container carries, for the files this app
     * writes and keeps: AAC in MP4 is `.m4a`, the resolver's other container
     * is Opus in WebM, and device files add the ordinary `.mp3` / `.flac` /
     * `.ogg`. Anything unrecognised is declared MP4 — the commonest
     * container the resolver produces — rather than nothing at all.
     */
    private fun mimeTypeFor(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "m4a", "mp4" -> "audio/mp4"
            "webm" -> "audio/webm"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/x-wav"
            else -> "audio/mp4"
        }

    /**
     * Whether the active connection is unmetered, asked the way the brief
     * for this feature states it: the active network's capabilities carry
     * [NetworkCapabilities.NET_CAPABILITY_NOT_METERED]. No active network,
     * or nothing that will say, counts as metered — a background job that
     * cannot prove the connection is free does not run on it.
     */
    private fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
