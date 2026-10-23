package com.music.sonariq.desktop

import com.music.sonariq.data.innertube.StreamResolver
import com.music.sonariq.data.lyrics.AiLyricAligner
import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.lyrics.LyricTiming
import com.music.sonariq.data.lyrics.LyricsLookupPolicy
import com.music.sonariq.data.lyrics.LyricsRepository
import com.music.sonariq.data.lyrics.TranscriptionClient
import com.music.sonariq.data.lyrics.buildTranscriptionPrompt
import com.music.sonariq.data.lyrics.LyricLanguageDetector
import com.music.sonariq.data.lyrics.lyricTiming
import com.music.sonariq.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVInputFormat
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether one AI word-sync job may start: the feature is switched on, this
 * build carries a transcription key, and no job for the same track is
 * already running. The desktop twin of the phone's gate, kept pure for the
 * same reason — a build whose key is blank fails it for every track, and
 * the app behaves exactly as it did before this feature existed.
 */
internal fun desktopAiWordSyncGateAllows(
    enabled: Boolean,
    keyBlank: Boolean,
    alreadyInFlight: Boolean,
): Boolean = enabled && !keyBlank && !alreadyInFlight

/**
 * Whether a settled lyrics result is worth offering for measurement: it
 * has words worth timing, and it is not already word-synced — not by a
 * provider and not by an earlier measurement of the same track.
 */
internal fun List<LyricLine>.isDesktopAiWordSyncCandidate(): Boolean {
    val timing = lyricTiming()
    return any { it.text.isNotBlank() } &&
        timing != LyricTiming.WORD_SYNCED &&
        timing != LyricTiming.WORD_SYNCED_AI_MEASURED
}

/** One rung of a job's attempt ladder — see [desktopAiAttemptPlan]. */
internal data class DesktopAiAttemptSpec(
    val model: String,
    val language: String?,
    val withPrompt: Boolean,
)

/**
 * The order a job tries to time one song in, first to last — the same
 * ladder the phone climbs, for the same reasons: each attempt removes one
 * steering assumption the previous one made. Identical specs collapse.
 */
internal fun desktopAiAttemptPlan(detectedLanguage: String?): List<DesktopAiAttemptSpec> =
    listOf(
        DesktopAiAttemptSpec(
            model = TranscriptionClient.DEFAULT_MODEL,
            language = detectedLanguage,
            withPrompt = true,
        ),
        DesktopAiAttemptSpec(
            model = TranscriptionClient.DEFAULT_MODEL,
            language = null,
            withPrompt = true,
        ),
        DesktopAiAttemptSpec(
            model = TranscriptionClient.ALTERNATE_MODEL,
            language = detectedLanguage,
            withPrompt = true,
        ),
        DesktopAiAttemptSpec(
            model = TranscriptionClient.ALTERNATE_MODEL,
            language = null,
            withPrompt = false,
        ),
    ).distinct()

/**
 * Which of a job's alignment attempts, if any, earned storing: the first
 * complete one, in ladder order. An attempt is complete only when its
 * outcome carries lines, which the aligner grants solely when every
 * non-gap lead line earned measured words — a ladder of incomplete
 * attempts stores nothing at all, so the track keeps its honest line sync
 * whole rather than a patchwork of word-synced and line-synced lines.
 */
internal fun pickDesktopAiAttempt(
    outcomes: List<AiLyricAligner.AlignOutcome?>,
): AiLyricAligner.AlignOutcome? = outcomes.firstOrNull { it?.lines != null }

/**
 * Background AI word sync for the desktop, the phone feature's twin.
 *
 * When a track's settled lyrics have lines but no word timings, a job
 * here transcribes the track's *own audio* through [TranscriptionClient],
 * lines the heard words up against the known lyric lines with
 * [AiLyricAligner], and stores the measured result through
 * [LyricsRepository.storeAiResult], which ranks it below every provider
 * result and keeps it provisional — the ordinary lookup keeps hunting for
 * provider timings and replaces these when it finds them.
 *
 * The audio is only ever the track's file, and it has to be the *same*
 * audio the player would play: a local file or a download stands in for
 * fetching the stream only when its own probed duration matches the
 * track's within a couple of seconds, exactly as on the phone. Nothing
 * is recorded — no microphone, no capture of what the device is playing.
 *
 * Dormant without a key: the credential is the owner's one key, injected
 * at build time as the `sonariq.groq.key` system property (see
 * desktopApp/build.gradle.kts); it is never stored, logged, or put in a
 * setting. A job that exhausts its ladder is remembered for a day by the
 * repository, as on the phone.
 *
 * One desktop difference, stated plainly: a stored result lives in the
 * lyrics cache and is not written back into a downloaded file's tags
 * (the phone does that with its own tagger, which has no desktop twin
 * yet). Lyrics that came out of a file's own tags are therefore not
 * offered here — the repository path below only ever sees provider
 * results, which is where a measurement can actually be served from
 * afterwards.
 */
object DesktopAiWordSync {

    /** The most audio one job will fetch or copy for transcription. */
    private const val MAX_AUDIO_BYTES = 30L * 1024 * 1024

    /** How far a local file's own duration may sit from the track's and still count as the same audio. */
    private const val DURATION_MATCH_TOLERANCE_MS = 2_000L

    /** The settings key for the on/off switch; on by default, as on the phone. */
    internal const val KEY_AI_WORD_SYNC = "ai_word_sync_enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Video ids with a job running, so a track is only ever measured once at a time. */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * How many measurements have landed this session. The window collects
     * it as a lyrics-effect key, so a song whose words were just measured
     * is looked up again and shown word-synced without waiting for the
     * next track change — the desktop form of the phone's onAligned.
     */
    val upgrades = MutableStateFlow(0)

    /** The build-time transcription key, or blank where the build carries none. */
    private val apiKey: String get() = System.getProperty("sonariq.groq.key").orEmpty()

    /**
     * Offers [song]'s settled provider lyrics for measurement; returns
     * immediately either way. [result] is the repository result the
     * lookup settled on — its lines are what get timed, its source is
     * what a stored result keeps.
     */
    fun maybeAlign(song: Song, durationMs: Long, result: LyricsRepository.Result) {
        if (song.videoId.isBlank()) return
        val key = apiKey
        val enabled = runCatching { DesktopPersistence().boolean(KEY_AI_WORD_SYNC, true) }
            .getOrDefault(true)
        if (!desktopAiWordSyncGateAllows(
                enabled = enabled,
                keyBlank = key.isBlank(),
                alreadyInFlight = song.videoId in inFlight,
            )
        ) {
            return
        }
        if (!result.lines.isDesktopAiWordSyncCandidate()) return
        // The gate read the set; this add is the arbiter.
        if (!inFlight.add(song.videoId)) return
        scope.launch {
            try {
                runAlign(song, durationMs, result, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed measurement is a non-event: the track keeps
                // its line sync and a later offer may try again.
                DesktopTrackLog.log("AI word sync skipped for ${song.videoId}: ${e.message}")
            } finally {
                inFlight.remove(song.videoId)
            }
        }
    }

    /** The job itself. Every early return is a quiet, retryable dead end. */
    private suspend fun runAlign(
        song: Song,
        durationMs: Long,
        settled: LyricsRepository.Result,
        key: String,
    ) {
        val videoId = song.videoId
        // A cached result that is already word-synced — by a provider, or
        // by an earlier measurement of this same track — settles it: the
        // transcription service is never asked twice for the same words.
        val cached = LyricsRepository.readCache(videoId, null)
        if (cached != null &&
            (cached.timing == LyricTiming.WORD_SYNCED ||
                cached.timing == LyricTiming.WORD_SYNCED_AI_MEASURED)
        ) {
            return
        }
        // The retry gate: a job that exhausted the whole ladder recently
        // is not run again yet, whichever offer arrives.
        if (!LyricsLookupPolicy.isAiRetryDue(
                LyricsRepository.aiAttemptExhaustedAtMs(videoId, null),
                System.currentTimeMillis(),
            )
        ) {
            return
        }

        val acquired = acquireAudio(song, durationMs) ?: return
        try {
            val songEndMs = durationMs.takeIf { it > 0 }
            // Built once, before the ladder starts: the prompt from the
            // settled lines themselves, and the language those lines are
            // in where that can be told at all.
            val prompt = buildTranscriptionPrompt(settled.lines)
            val plan = desktopAiAttemptPlan(LyricLanguageDetector.detect(settled.lines))

            val outcomes = mutableListOf<AiLyricAligner.AlignOutcome?>()
            var winning: AiLyricAligner.AlignOutcome? = null
            for (spec in plan) {
                val tokens = TranscriptionClient.transcribe(
                    audio = acquired.file,
                    mimeType = acquired.mimeType,
                    apiKey = key,
                    model = spec.model,
                    language = spec.language,
                    prompt = if (spec.withPrompt) prompt else null,
                )
                val outcome = tokens
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { AiLyricAligner.alignDetailed(settled.lines, it, songEndMs) }
                outcomes += outcome
                winning = pickDesktopAiAttempt(outcomes)
                if (winning != null) break
            }
            if (winning == null) {
                // No rung timed the whole song. Store none of them — and
                // remember the exhaustion, because audio was acquired
                // and every attempt ran.
                DesktopTrackLog.log(
                    "AI word sync incomplete for $videoId: " +
                        "${outcomes.filterNotNull().maxOfOrNull { it.passedLeadLines } ?: 0} lead lines passed",
                )
                LyricsRepository.noteAiAttemptExhausted(videoId, null)
                return
            }

            val aligned = winning.lines ?: return
            val aiResult = LyricsRepository.Result(settled.source, aligned)
            if (!LyricsRepository.storeAiResult(videoId, null, aiResult)) return
            // The window's next lookup for this track should serve the
            // stored measurement, not the client's own cached lines.
            DesktopLyricsClient.invalidate(videoId)
            upgrades.update { it + 1 }
        } finally {
            // A temp file exists only for this job. The track's own files
            // are never deleted here.
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
     * The track's audio, under the phone's same-audio guarantee: the
     * resolved stream is the reference; a file the app already has (the
     * track's download, or a local file) may stand in for fetching it
     * only when its probed duration matches [durationMs] within
     * [DURATION_MATCH_TOLERANCE_MS]; where the track's duration is
     * unknown a file is accepted only when no stream could be resolved.
     * Otherwise the resolved stream is fetched to a temp file, held to
     * [MAX_AUDIO_BYTES] like every other candidate.
     */
    private suspend fun acquireAudio(song: Song, durationMs: Long): AcquiredAudio? {
        val stream = runCatching {
            StreamResolver.resolveForDownload(song.videoId, Int.MAX_VALUE, requireM4a = false)
        }.getOrNull()
        val streamResolved = stream != null

        val local = DesktopDownloadManager.savedFile(song)?.toFile()
            ?: song.localPath?.let(::File)
        local?.let { candidate ->
            fileCandidate(candidate, durationMs, streamResolved)?.let { return it }
        }

        if (stream == null) return null
        val temp = tempAudioFile("." + stream.downloadExtension)
        val written = runCatching {
            fetchToFile(stream.url, temp)
        }.getOrDefault(0L)
        if (written !in 1..MAX_AUDIO_BYTES) {
            temp.delete()
            return null
        }
        return AcquiredAudio(temp, stream.downloadMimeType, owned = true)
    }

    /**
     * [file] as this job's audio, or null when it may not stand in for
     * the stream: not a file at all, empty, past [MAX_AUDIO_BYTES], or
     * failing the duration test in [durationAcceptable]. A file that
     * passes is used in place and never owned.
     */
    private fun fileCandidate(
        file: File,
        referenceMs: Long,
        streamResolved: Boolean,
    ): AcquiredAudio? {
        if (!file.isFile || file.length() !in 1..MAX_AUDIO_BYTES) return null
        if (!durationAcceptable(probeDurationMs(file), referenceMs, streamResolved)) return null
        return AcquiredAudio(file, mimeTypeFor(file.name), owned = false)
    }

    /**
     * Whether a file probed at [probedMs] may be measured in place of
     * fetching the resolved stream — the phone's test, unchanged: a
     * known track duration demands a probe within tolerance (an
     * unverifiable file proves nothing); an unknown one accepts the
     * file only where no stream could be resolved.
     */
    internal fun durationAcceptable(
        probedMs: Long?,
        referenceMs: Long,
        streamResolved: Boolean,
    ): Boolean =
        if (referenceMs > 0) {
            probedMs != null &&
                kotlin.math.abs(probedMs - referenceMs) <= DURATION_MATCH_TOLERANCE_MS
        } else {
            !streamResolved
        }

    /**
     * [file]'s duration in milliseconds, read from its own container
     * through the same FFmpeg the audio engine decodes with, or null
     * where that cannot be read. Failure-proof by construction: an
     * unreadable file answers null, never an exception into the job.
     */
    private fun probeDurationMs(file: File): Long? = runCatching {
        val context = AVFormatContext(null)
        val status = avformat_open_input(context, file.absolutePath, null as AVInputFormat?, null as AVDictionary?)
        if (status < 0) return@runCatching null
        try {
            if (avformat_find_stream_info(context, null as AVDictionary?) < 0) return@runCatching null
            context.duration()
                .takeIf { it != AV_NOPTS_VALUE && it > 0 }
                ?.let { it / 1_000L }
        } finally {
            avformat_close_input(context)
        }
    }.getOrNull()

    /** Fetches [url] into [target], refusing anything past [MAX_AUDIO_BYTES]. Returns bytes written. */
    private suspend fun fetchToFile(url: String, target: File): Long = withContext(Dispatchers.IO) {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .header("User-Agent", "Sonariq")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() in 200..299) { "audio fetch refused (HTTP ${response.statusCode()})" }
        response.body().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    check(total <= MAX_AUDIO_BYTES) { "Track audio is over the size cap" }
                    output.write(buffer, 0, read)
                }
                total
            }
        }
    }

    /** A temp file for one job's audio, under the app's own cache. */
    private fun tempAudioFile(suffix: String): File {
        val directory = File(DesktopMediaCache.directory.toFile(), "ai_word_sync").apply { mkdirs() }
        return File.createTempFile("align-", suffix, directory)
    }

    /** The MIME type a filename's container carries, as on the phone. */
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
}
