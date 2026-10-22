package com.music.sonariq.download

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.music.sonariq.BuildConfig
import com.music.sonariq.data.DebugLog as Log
import com.music.sonariq.data.innertube.StreamResolver
import com.music.sonariq.data.lyrics.AiLyricAligner
import com.music.sonariq.data.lyrics.LyricLanguageDetector
import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.lyrics.LyricTiming
import com.music.sonariq.data.lyrics.LyricsLookupPolicy
import com.music.sonariq.data.lyrics.LyricsRepository
import com.music.sonariq.data.lyrics.TranscriptionClient
import com.music.sonariq.data.lyrics.buildTranscriptionPrompt
import com.music.sonariq.data.lyrics.lyricTiming
import com.music.sonariq.data.lyrics.toEnhancedLrc
import com.music.sonariq.data.lyrics.toLrc
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
 * The tag-side twin of the [LyricsRepository.Result] candidate check
 * above, for lyrics that came out of a file and never were a repository
 * result: lines read back from a download's own tags by the embedded
 * lyrics reader, offered for measurement by the player wiring.
 *
 * The tests are the same, answered from the lines themselves: there are
 * words worth timing, and the lines' own timing — [lyricTiming], the
 * shared classification the repository also stamps its results with —
 * is neither provider word sync ([LyricTiming.WORD_SYNCED]) nor an
 * earlier measurement ([LyricTiming.WORD_SYNCED_AI_MEASURED]). Any other
 * bucket qualifies, including estimated word sync, whose timings the
 * repository strips on sight anyway: what is left underneath them is
 * honest line sync, and honest line sync is exactly what a job here
 * exists to upgrade.
 */
internal fun List<LyricLine>.isAiWordSyncCandidate(): Boolean {
    val timing = lyricTiming()
    return any { it.text.isNotBlank() } &&
        timing != LyricTiming.WORD_SYNCED &&
        timing != LyricTiming.WORD_SYNCED_AI_MEASURED
}

/**
 * One rung of a job's attempt ladder: which model transcribes, which
 * language hint it is given (null sends no hint at all, leaving the far
 * side to detect the language from the audio), and whether the lyric
 * prompt built from the settled lines goes with the audio.
 *
 * A value, not a behaviour, so the whole ladder a job will climb is
 * testable without a device or a network — see [aiAttemptPlan].
 */
internal data class AiAttemptSpec(
    val model: String,
    val language: String?,
    val withPrompt: Boolean,
)

/**
 * The order a job tries to time one song in, first to last.
 *
 * Each attempt removes one steering assumption the previous one made,
 * because a failed attempt says something was wrong with the steering
 * as often as with the listening:
 *
 *  1. The primary model, the detected language, and the prompt — every
 *     steer the job has, and the attempt that settles most songs.
 *  2. The primary model and the prompt, but no language: where the
 *     detector misread the lyrics, an unsteered listen is the different
 *     opinion worth having before a different model is.
 *  3. The alternate model, with the language and the prompt restored:
 *     a different listener, given the same full brief the first one got.
 *  4. The alternate model, no language, and no prompt — the bare listen
 *     this feature shipped with before the prompt existed, kept as the
 *     last rung so a prompt that somehow steers wrong can cost the
 *     first three attempts but never the job.
 *
 * The ladder stops at the first attempt that times the whole song (see
 * [pickAiAttempt]); later rungs are never paid for once an earlier one
 * has landed. Identical specs collapse: with no detected language the
 * first two rungs are the same call, so the plan runs to three entries
 * rather than paying for that call twice.
 */
internal fun aiAttemptPlan(detectedLanguage: String?): List<AiAttemptSpec> =
    listOf(
        AiAttemptSpec(
            model = TranscriptionClient.DEFAULT_MODEL,
            language = detectedLanguage,
            withPrompt = true,
        ),
        AiAttemptSpec(
            model = TranscriptionClient.DEFAULT_MODEL,
            language = null,
            withPrompt = true,
        ),
        AiAttemptSpec(
            model = TranscriptionClient.ALTERNATE_MODEL,
            language = detectedLanguage,
            withPrompt = true,
        ),
        AiAttemptSpec(
            model = TranscriptionClient.ALTERNATE_MODEL,
            language = null,
            withPrompt = false,
        ),
    ).distinct()

/**
 * Which of a job's alignment attempts, if any, earned storing: the
 * first complete one, in ladder order.
 *
 * An attempt is complete only when its outcome carries lines — which
 * [AiLyricAligner.alignDetailed] grants solely when *every* non-gap lead
 * line earned measured words, never for a partial song. So the earliest
 * complete attempt wins outright, a later complete attempt wins over
 * every incomplete one before it, and a ladder of incomplete attempts
 * stores nothing at all: the track keeps its honest line sync whole
 * rather than a patchwork of word-synced and line-synced lines.
 *
 * Any attempt may be null — a transcription that failed or heard
 * nothing produces no outcome to judge. Pure, so the decision is
 * testable without a device or a network.
 */
internal fun pickAiAttempt(
    outcomes: List<AiLyricAligner.AlignOutcome?>,
): AiLyricAligner.AlignOutcome? = outcomes.firstOrNull { it?.lines != null }

/**
 * The two-attempt form of [pickAiAttempt] over a list, kept for the
 * callers and tests written against the original two-attempt ladder.
 */
internal fun pickAiAttempt(
    first: AiLyricAligner.AlignOutcome?,
    second: AiLyricAligner.AlignOutcome?,
): AiLyricAligner.AlignOutcome? = pickAiAttempt(listOf(first, second))

/**
 * The best of a job's attempts, whether or not any earned storing —
 * the attempt a log line should describe when [pickAiAttempt] stored
 * nothing.
 *
 * Complete beats incomplete, because a complete attempt timed the whole
 * song. Between two incomplete attempts, the one that passed more lead
 * lines got closer; a tie keeps the earlier attempt, which is also the
 * more fully steered one — the ladder's order is its own tiebreak.
 * "Better" here is a report, never a licence to store: an incomplete
 * best is still not stored, which is [pickAiAttempt]'s separate call.
 */
internal fun bestAiAttempt(
    outcomes: List<AiLyricAligner.AlignOutcome?>,
): AiLyricAligner.AlignOutcome? {
    var best: AiLyricAligner.AlignOutcome? = null
    for (outcome in outcomes) {
        val candidate = outcome ?: continue
        val current = best
        when {
            current == null -> best = candidate
            // An earlier complete attempt is never displaced: not by a
            // later complete one (the ladder already preferred it) and
            // certainly not by an incomplete one.
            current.lines != null -> Unit
            candidate.lines != null -> best = candidate
            candidate.passedLeadLines > current.passedLeadLines -> best = candidate
        }
    }
    return best
}

/**
 * The two-attempt form of [bestAiAttempt] over a list, kept for the
 * callers and tests written against the original two-attempt ladder.
 */
internal fun bestAiAttempt(
    first: AiLyricAligner.AlignOutcome?,
    second: AiLyricAligner.AlignOutcome?,
): AiLyricAligner.AlignOutcome? = bestAiAttempt(listOf(first, second))

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
 * One job climbs a ladder of attempts before it gives up — see
 * [aiAttemptPlan] for the rungs and why they are ordered as they are.
 * The prompt is built once, from the settled lines, by
 * [buildTranscriptionPrompt], and the language is detected once, by
 * [LyricLanguageDetector]; the rungs then differ only in which of those
 * steers they carry and which model listens. Every rung transcribes the
 * one audio file the job acquired and aligns per line against the
 * provider line timings. Only a complete result — every non-gap lead
 * line earning measured words — is stored, and the ladder stops at the
 * first one. If every rung comes back incomplete, nothing is stored at
 * all: a half-timed song is worse than an honestly line-synced one.
 *
 * A job that exhausts the ladder that way is remembered for a day
 * ([LyricsRepository.noteAiAttemptExhausted]): until
 * [LyricsLookupPolicy.AI_RETRY_INTERVAL_MS] has passed, no trigger —
 * a play, a prefetch, a warm-up — starts the ladder for that track
 * again, because four steered listens failing in the morning is not a
 * reason to pay for four more at noon. The daily warm-up is the
 * guaranteed retry: it offers every candidate again, and by the time
 * it runs the day has passed and the gate is open. The record is
 * written only for a genuine exhaustion — audio in hand, every rung
 * run — never for the transient dead ends (no lyrics, no audio, the
 * gate itself closed), which may clear at any moment and must not be
 * remembered as failures.
 *
 * The audio is only ever the track's file, and it has to be the *same*
 * audio the player would play: the stream the resolver settles on is the
 * reference, and a local file or a download stands in for fetching it
 * only when its own duration matches the track's within a couple of
 * seconds. A different edit of the same song would time the words of one
 * recording against the lines of another. Nothing is recorded — no
 * microphone, no capture of what the device is playing — and no
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
 * per-track dedupe, the retry gate above, and for background jobs the
 * Wi-Fi-only switch) decide whether anything actually runs. A job that
 * fails at any step — no lyrics, no audio, a refused or unreachable
 * transcription, an alignment the aligner would not sign — simply ends,
 * and writes nothing but the exhaustion record described above, and
 * that only when the ladder itself ran dry.
 *
 * A stored result is also written back into the track's own download,
 * where there is one — see [persistUpgradeToDownload] — so a downloaded
 * track carries its upgrade in the file itself and keeps it offline,
 * rather than depending on the lyrics cache surviving.
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

    /**
     * How far a local file's own duration may sit from the track's
     * duration and still count as the same audio — see [acquireAudio].
     *
     * Two seconds absorbs the rounding and the padding different
     * containers put around the same recording, and is far too narrow
     * to absorb a different edit: a radio cut, an extended mix or a
     * sped-up copy shifts the length by more than this, and timing
     * against any of those would put every word in the wrong place.
     */
    private const val DURATION_MATCH_TOLERANCE_MS = 2_000L

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
     * it (the player, for a local track). It is a candidate ahead of the
     * downloads record, but candidacy is not trust: like any file on the
     * device, it is only used when its duration matches the track's, so
     * the audio measured is the audio the stream would have carried —
     * see [acquireAudio].
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
                // line sync, no result was stored, and a later offer may
                // try again. Said in the log and nowhere else.
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
        // The retry gate, checked here — after the settled lyrics are in
        // hand and the already-word-synced returns above, before any
        // audio is acquired: a job that exhausted the whole ladder
        // recently is not run again yet, whichever trigger offered the
        // track this time. The refusal is quiet and writes nothing; the
        // record that closed the gate is the one the exhausting job
        // left, and it ages out on its own.
        if (!LyricsLookupPolicy.isAiRetryDue(
                LyricsRepository.aiAttemptExhaustedAtMs(videoId, isrc),
                System.currentTimeMillis(),
            )
        ) {
            return
        }

        val acquired = acquireAudio(context, videoId, localAudio, durationMs) ?: return
        try {
            // Every attempt measures the one file acquired above — a
            // later rung transcribes the same audio again under another
            // spec, it never fetches another copy of the track.
            val songEndMs = durationMs.takeIf { it > 0 }

            // Built once, before the ladder starts: the prompt from the
            // settled lines themselves, and the language those lines are
            // in where that can be told at all — a transcription
            // listening for the wrong language mishears in ways no
            // alignment can line up afterwards, which is why the ladder
            // also carries rungs that drop the hint rather than trusting
            // it blindly. Each rung then only decides whether it sends
            // what was built here.
            val prompt = buildTranscriptionPrompt(result.lines)
            val plan = aiAttemptPlan(LyricLanguageDetector.detect(result.lines))

            val outcomes = mutableListOf<AiLyricAligner.AlignOutcome?>()
            var winning: AiLyricAligner.AlignOutcome? = null
            for (spec in plan) {
                val tokens = TranscriptionClient.transcribe(
                    audio = acquired.file,
                    mimeType = acquired.mimeType,
                    apiKey = apiKey,
                    model = spec.model,
                    language = spec.language,
                    prompt = if (spec.withPrompt) prompt else null,
                )
                // A transcription that failed, or heard nothing, is an
                // attempt that produced no outcome at all: it takes its
                // place in the list as null — so the reporting below
                // stays in ladder order — and the ladder climbs on.
                val outcome = tokens
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { AiLyricAligner.alignDetailed(result.lines, it, songEndMs) }
                outcomes += outcome
                // The ladder stops at the first attempt that times the
                // whole song; the rungs after it are never paid for.
                winning = pickAiAttempt(outcomes)
                if (winning != null) break
            }
            if (winning == null) {
                // No rung timed the whole song. Report the best of them
                // in the log and store none of them: the track stays
                // line-synced. Unlike every early return above, this one
                // is a genuine exhaustion — audio was acquired and every
                // attempt ran — so it is remembered, and the retry gate
                // holds every trigger off this track for a day.
                bestAiAttempt(outcomes)?.let { best ->
                    Log.d(
                        TAG,
                        "AI word sync incomplete for $videoId: " +
                            "${best.passedLeadLines}/${best.leadLineCount} lead lines passed",
                    )
                }
                LyricsRepository.noteAiAttemptExhausted(videoId, isrc)
                return
            }

            val aligned = winning?.lines ?: return
            val aiResult = LyricsRepository.Result(result.source, aligned)
            if (!LyricsRepository.storeAiResult(videoId, isrc, aiResult)) return
            // The result as stored, read off the cache the store just
            // wrote; the constructed one only if that read somehow
            // fails, since it is the same lines and timing. The stored
            // form is what is persisted to the download and handed back:
            // both readers should see exactly what the cache holds.
            val stored = LyricsRepository.readCache(videoId, isrc) ?: aiResult
            persistUpgradeToDownload(context, videoId, stored)
            if (onAligned != null) {
                withContext(Dispatchers.Main) { onAligned(stored) }
            }
        } finally {
            // A temp file exists only for this job. The track's own files —
            // a local file, a download — are never deleted here.
            if (acquired.owned) acquired.file.delete()
        }
    }

    /**
     * Writes a stored AI result back into the track's own download, so
     * the upgrade lives in the file the listener keeps rather than only
     * in the lyrics cache — a cache the system may clear, and which a
     * copied file leaves behind entirely.
     *
     * Best-effort by construction, and wrapped so it can never fail the
     * job that calls it: the store to the cache has already landed by
     * the time this runs, so the worst any failure here costs is the
     * write-back itself, which a later measurement can redo. The whole
     * body sits in a [runCatching] whose only trace is a log line.
     *
     * Where the upgrade lands depends on what the download is:
     *
     *  - Not a download at all — [Downloads.verifiedSavedUri] has no
     *    live record for the track: there is nothing to persist to, and
     *    the cache alone holds the upgrade.
     *  - An offline package: a `file://` uri naming a `.m3u8` playlist.
     *    Its lyrics live in a sidecar, not in a tag, so the sidecar
     *    beside the playlist is rewritten in exactly the form
     *    [OfflineHls] wrote at download time — the enhanced form where
     *    the stored lines produce one, the plain form otherwise.
     *  - A taggable file: the extension is read off the uri's last path
     *    segment, and where [MediaTagger.carriesTags] knows the
     *    container, the stored lines are embedded through
     *    [MediaTagger.embed] under the download's own recorded Song
     *    metadata, with its artwork fetched the way a download fetches
     *    it. The one refusal is a FLAC with no artwork to write: the
     *    FLAC tagger replaces the picture block as it rewrites, so
     *    embedding without a cover in hand would trade the download's
     *    cover for its words — and the cache keeps the upgrade either
     *    way, so nothing is lost by leaving that file alone.
     *
     * Writing tags into a file tagged at download time is safe for a
     * reason worth stating: a line-only download carries no word-lyrics
     * field at all — [toEnhancedLrc] writes nothing when nothing on the
     * lines is word-synced — and every reader of the tag prefers that
     * field over the plain one. So the embed below does not disturb
     * what is already there; it adds the upgrade in the one field the
     * player actually looks at first, which is where it will be found.
     */
    private suspend fun persistUpgradeToDownload(
        context: Context,
        videoId: String,
        stored: LyricsRepository.Result,
    ) {
        runCatching {
            val uriString = Downloads.verifiedSavedUri(videoId) ?: return@runCatching
            val plain = stored.lines.toLrc()
            val enhanced = stored.lines.toEnhancedLrc().takeIf { it.isNotBlank() }

            val parsed = Uri.parse(uriString)
            val path = parsed.path
            if (parsed.scheme == "file" && path != null &&
                path.endsWith(".m3u8", ignoreCase = true)
            ) {
                val parent = File(path).parentFile ?: return@runCatching
                File(parent, "lyrics.lrc").writeText(enhanced ?: plain)
                return@runCatching
            }

            val extension = uriString
                .substringBefore('?')
                .substringAfterLast('/')
                .substringAfterLast('.', "")
                .lowercase()
            if (extension.isBlank() || !MediaTagger.carriesTags(extension)) {
                return@runCatching
            }
            val song = Downloads.savedSong(context, videoId) ?: return@runCatching
            val artwork = MediaTagger.artworkFor(context, song)
            // The FLAC rule, from the doc above: no artwork in hand
            // means no write, never a cover traded for words.
            if (extension == "flac" && artwork == null) return@runCatching
            MediaTagger.embed(
                context = context,
                uri = Uri.parse(uriString),
                track = song,
                extension = extension,
                lyrics = LyricsTag.Embeddable(plain = plain, enhanced = enhanced),
                artwork = artwork,
            )
        }.onFailure {
            Log.d(TAG, "AI word sync upgrade not written to download for $videoId: ${it.message}")
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
     * The track's audio, under a same-audio guarantee: the words are
     * timed against the recording the player plays, never against a
     * different edit of the same song that happens to be on the device.
     *
     * The stream is the reference, so it is settled first:
     *
     *  1. The track's stream is resolved the way a download resolves it —
     *     [StreamResolver.resolveForDownload] runs the same client walk
     *     as playback. Whatever is handed back here is what would be
     *     fetched, and its existence is what every file below is judged
     *     against.
     *  2. A file the app already has — [localAudio] where the caller
     *     knows it, else the track's download from the [Downloads]
     *     record — may stand in for fetching that stream, but only when
     *     the file's own probed duration matches the track's
     *     [durationMs] within [DURATION_MATCH_TOLERANCE_MS]. That match
     *     is the evidence the file is the same recording; without it the
     *     file is passed over, however convenient it would have been.
     *     Where [durationMs] is not known (not positive), there is
     *     nothing to match against, so a file is accepted only when the
     *     stream could not be resolved at all — when a stream exists,
     *     fetching the known-right audio beats trusting an unchecked
     *     file. A `content://` download (a Music-folder export) is
     *     probed through its uri in place and copied to a temp file only
     *     once it has been chosen, since the transcription client posts
     *     a [File].
     *  3. Otherwise the resolved stream is fetched with
     *     [Downloader.fetch], whose ranged requests and client-matched
     *     headers are what the stream's host answers to, to a temp file
     *     under the app cache.
     *
     * If the stream cannot be resolved, a file whose probed duration
     * matches — or any file, where the track's duration is unknown —
     * is still used, per the same rule in step 2; with neither a stream
     * nor an acceptable file, there is no audio this job may honestly
     * measure, and the answer is null.
     *
     * Every fetched or copied byte stream is held to [MAX_AUDIO_BYTES].
     */
    private suspend fun acquireAudio(
        context: Context,
        videoId: String,
        localAudio: File?,
        durationMs: Long,
    ): AcquiredAudio? {
        val stream = runCatching {
            StreamResolver.resolveForDownload(videoId, Int.MAX_VALUE, requireM4a = false)
        }.getOrNull()
        val streamResolved = stream != null

        localAudio?.let { candidate ->
            fileCandidate(candidate, durationMs, streamResolved)?.let { return it }
        }

        Downloads.verifiedSavedUri(videoId)?.let { recorded ->
            val uri = Uri.parse(recorded)
            if (uri.scheme == "file") {
                uri.path?.let { path ->
                    fileCandidate(File(path), durationMs, streamResolved)?.let { return it }
                }
            }
        }
        Downloads.savedUri(context, videoId)?.let { uri ->
            when (uri.scheme) {
                "file" -> uri.path?.let { path ->
                    fileCandidate(File(path), durationMs, streamResolved)?.let { return it }
                }
                "content" -> {
                    val probed = probeDurationMs(context, uri)
                    if (durationAcceptable(probed, durationMs, streamResolved)) {
                        copyContentToTemp(context, uri)?.let { temp ->
                            return AcquiredAudio(temp, mimeTypeFor(temp.name), owned = true)
                        }
                    }
                }
            }
        }

        if (stream == null) return null
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

    /**
     * [file] as this job's audio, or null when it may not stand in for
     * the stream: not a file at all, empty, past [MAX_AUDIO_BYTES], or
     * failing the duration test in [durationAcceptable]. A file that
     * passes is used in place and never owned — the job deletes only
     * what it fetched or copied itself.
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
     * fetching the resolved stream — the same-audio test at the heart of
     * [acquireAudio], kept apart so each of its cases reads plainly:
     *
     *  - The track's duration is known: the probe must land within
     *    [DURATION_MATCH_TOLERANCE_MS] of it. A probe that produced no
     *    duration proves nothing, so it fails — an unverifiable file is
     *    not the same audio merely because it is the only one around.
     *  - The track's duration is not known: there is nothing to match
     *    against, so the file is accepted only where no stream could be
     *    resolved. Where a stream exists it is fetched instead, because
     *    it is known to be this track's audio and the file is not.
     */
    private fun durationAcceptable(
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
     * metadata, or null where that cannot be read.
     *
     * Failure-proof by construction: an unreadable file, a container
     * with no duration in it, or a retriever that throws all answer
     * null — a duration unknown, which [durationAcceptable] then treats
     * by its own rule — never an exception into the job. The retriever
     * is released whatever happens.
     */
    private fun probeDurationMs(file: File): Long? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    /**
     * As [probeDurationMs] for a `content://` download: the uri is
     * probed in place, through the content resolver, so choosing whether
     * the file may be used costs no copy — the copy in
     * [copyContentToTemp] happens only for a file already chosen.
     */
    private fun probeDurationMs(context: Context, uri: Uri): Long? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

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
