package com.music.sonariq.data.lyrics

import com.music.sonariq.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToLong

/**
 * Asks the transcription endpoint what words it can hear in a track's own
 * audio, and when it hears them.
 *
 * The endpoint is Whisper-compatible: one multipart POST of the audio file,
 * answered with `verbose_json` whose top-level `words` array carries a
 * start and an end, in seconds, for every word it made out. Those tokens
 * are the raw material [AiLyricAligner] lines up against the lyric text a
 * provider already gave us — this client knows nothing about lyrics, and
 * the aligner knows nothing about the network.
 *
 * The credential is the owner's one key, injected into the app at build
 * time and handed in by the caller ([apiKey]); it never appears in source,
 * in Settings, or in a log line. A build without it passes a blank key and
 * gets null back without a request being made — the feature is dormant,
 * exactly as though it did not exist.
 *
 * The model asked for by default is [DEFAULT_MODEL], the endpoint's most
 * accurate transcription model; accuracy is what the alignment is built
 * on, and a transcription is paid for once and then cached, so it is not
 * the place to trade quality for speed. [ALTERNATE_MODEL] is the faster
 * model, and exists for the caller's retry: when a first attempt comes
 * back with too little the aligner can use, the caller asks again with
 * the alternate model rather than this client second-guessing the result
 * it was handed.
 *
 * A caller that knows the track's language may pass it as a hint
 * ([transcribe]'s `language`); a caller that does not passes nothing and
 * sends no language part at all, leaving the far side to detect the
 * language from the audio itself. A wrong hint is worse than none, so the
 * choice is the caller's, made from whatever the track's own metadata
 * actually says.
 *
 * A caller may also hand the service the lyric text it already has, as
 * [transcribe]'s `prompt`: the words a provider already gave us, offered
 * as context so the transcription is biased toward the words actually
 * sung rather than the words the audio most resembles. That matters most
 * where a line is quiet, fast, or in a language the primary model hears
 * less often — exactly the places a mishearing would otherwise send the
 * alignment chasing a word that was never sung. The prompt is omitted
 * entirely when the caller has no text worth sending, and it is never
 * logged, for the same reason the key is not.
 *
 * Analysis reads the track's audio file only. There is no microphone and
 * no capture of anything the device plays: the bytes sent are the bytes of
 * a file the app already has or has just fetched for this track.
 *
 * Null on any failure at all — a refusal, an unreachable host, a body that
 * does not parse, an empty word list. A failed transcription is not a
 * verdict about the track, so nothing is remembered anywhere: the next
 * attempt, whenever it comes, simply asks again.
 *
 * Transient failures — a dropped connection, a rate limit, a server
 * error — are retried inside one call, on the [retryDelaysMs] backoff:
 * they are the far side having a moment, and the caller's attempt ladder
 * should not be spent re-asking a question the network fumbled. A
 * refusal (any other 4xx) and a successful answer that does not parse
 * are not retried: asking again would get the same answer slower.
 *
 * The request runs as an asynchronous call tied to the caller's
 * coroutine, so cancelling the caller cancels the upload on the wire —
 * a job abandoned because the track changed does not keep paying for a
 * transcription nobody is waiting for.
 *
 * One request carries the whole track: there is no chunking, because
 * the audio here is a compressed rendition of one song — a few megabytes
 * in practice — and splitting compressed audio honestly would mean
 * decoding and re-encoding it, which this app does not do. What the
 * size limit below therefore means in practice is a refusal, not a
 * truncation: an upload past the endpoint's own cap
 * ([MAX_AUDIO_BYTES]) is never sent, rather than sent to fail.
 */
/**
 * One stretch of a transcription, as the endpoint segments it: the
 * words it heard between [startMs] and [endMs], in its own text.
 *
 * Segments are the transcription's phrasing — a breath, a line's worth
 * of singing — and they are what [TranscriptionLines] groups words
 * into lines by, where a track's lyrics are built from a transcription
 * rather than timed from a provider's text. The aligner never looks at
 * them: alignment works word to word, and a segment boundary is not
 * evidence about any single word.
 */
data class TranscribedSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Everything one transcription heard: the words, each with its own
 * measured span ([TranscribedWord]), and the segments the endpoint
 * grouped them into ([TranscribedSegment]).
 */
data class Transcription(
    val words: List<TranscribedWord>,
    val segments: List<TranscribedSegment>,
)

object TranscriptionClient {

    /** The transcription endpoint this build talks to. */
    const val DEFAULT_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"

    /** The transcription model asked for, named by the endpoint's catalogue. */
    const val DEFAULT_MODEL = "whisper-large-v3"

    /**
     * The faster transcription model, named by the endpoint's catalogue.
     *
     * Never the default: it exists so a caller whose first attempt came
     * back unusable can retry once against a different model — a second
     * opinion — by passing this as [transcribe]'s `model`.
     */
    const val ALTERNATE_MODEL = "whisper-large-v3-turbo"

    /**
     * How long one transcription may take, end to end.
     *
     * Far longer than [LyricsHttp]'s deadlines, on purpose: this is one
     * request for a whole song's audio — uploaded, then transcribed on the
     * far side — and it runs in the background for a result that is cached
     * once it lands. A deadline sized for a lyrics lookup would cut off
     * exactly the long tracks that need this most.
     */
    private const val TRANSCRIPTION_TIMEOUT_SECONDS = 180L

    /**
     * The largest upload the endpoint accepts, in bytes. A track whose
     * audio runs past it cannot be transcribed by this client at all —
     * see the class doc on why the answer is a refusal rather than a
     * chunked upload.
     */
    const val MAX_AUDIO_BYTES = 25L * 1024 * 1024

    /**
     * The waits between the retries of one [transcribe] call, in
     * milliseconds — its length is the retry count, and each wait is
     * twice the last: a rate limit clears on its own schedule, and
     * backing off is what keeps a retry from being the same failure
     * twice more. A `var` so tests can run the policy at full speed.
     */
    internal var retryDelaysMs: List<Long> = listOf(1_000L, 2_000L)

    private val client by lazy {
        // Derived from the shared client, so the connection pool and DNS
        // stay common — only the deadline differs, as in LyricsHttp.
        Http.client.newBuilder()
            .callTimeout(TRANSCRIPTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The words heard in [audio], in the order they were sung, or null.
     *
     * [mimeType] is the container's type as the caller knows it
     * (`audio/mp4` for an .m4a, `audio/webm` for a .webm, `audio/mpeg`
     * for an .mp3) and is sent as the file part's content type.
     * [endpointUrl] exists so tests can point the client at a local server;
     * every real caller takes the default.
     *
     * [language] is an optional hint naming the language the track is sung
     * in, in the endpoint's own codes. A non-blank one is sent as its own
     * form part; null (or blank) sends no language part at all, so the far
     * side detects the language from the audio rather than being told.
     *
     * [prompt] is optional context for the transcription — in practice
     * the lyric text the caller already has for this track, built by
     * [buildTranscriptionPrompt]. A non-blank one is sent as its own form
     * part; null (or blank) sends no prompt part at all.
     *
     * The request itself is asynchronous and cancellable (see
     * [requestOnce]); retries wait on [retryDelaysMs] between attempts.
     * Never logs [apiKey], [prompt] — or the audio.
     */
    suspend fun transcribe(
        audio: File,
        mimeType: String,
        apiKey: String,
        endpointUrl: String = DEFAULT_ENDPOINT,
        model: String = DEFAULT_MODEL,
        language: String? = null,
        prompt: String? = null,
    ): List<TranscribedWord>? = transcribeFull(
        audio, mimeType, apiKey, endpointUrl, model, language, prompt,
    )?.words

    /**
     * [transcribe], with the endpoint's segments kept alongside the
     * words — the form a caller building lyric lines out of the hearing
     * itself needs (see [TranscriptionLines]). Same request, same retry
     * policy, same null on any failure; the words-only [transcribe] is
     * this with the segments dropped, so the two can never disagree
     * about what was heard.
     */
    suspend fun transcribeFull(
        audio: File,
        mimeType: String,
        apiKey: String,
        endpointUrl: String = DEFAULT_ENDPOINT,
        model: String = DEFAULT_MODEL,
        language: String? = null,
        prompt: String? = null,
    ): Transcription? {
        if (apiKey.isBlank() || !audio.isFile || audio.length() == 0L) return null
        // Past the endpoint's own cap the upload would only be refused
        // at the far end, after paying to send it. Refuse here instead.
        if (audio.length() > MAX_AUDIO_BYTES) return null
        return withContext(Dispatchers.IO) {
            var attempt = 0
            var heard: Transcription? = null
            var settled = false
            while (!settled) {
                when (
                    val outcome = requestOnce(
                        audio, mimeType, apiKey, endpointUrl, model, language, prompt,
                    )
                ) {
                    is Attempt.Words -> {
                        heard = outcome.transcription
                        settled = true
                    }
                    Attempt.Failed -> settled = true
                    Attempt.Retryable -> {
                        val waitMs = retryDelaysMs.getOrNull(attempt)
                        if (waitMs == null) {
                            settled = true
                        } else {
                            delay(waitMs)
                            attempt += 1
                        }
                    }
                }
            }
            heard
        }
    }

    /** How one transcription request ended, for [transcribeFull]'s retry policy. */
    private sealed interface Attempt {
        /** What the endpoint heard. */
        data class Words(val transcription: Transcription) : Attempt

        /** A refusal, or an answer that does not parse: final, not retried. */
        data object Failed : Attempt

        /** A dropped connection, a rate limit, a server error: worth another ask. */
        data object Retryable : Attempt
    }

    /**
     * One request, run as an asynchronous call bound to the caller's
     * coroutine: cancelling the caller cancels the call on the wire (see
     * the class doc). The multipart body is built per attempt — a body
     * is consumed by being sent, and a retry must send a whole one.
     */
    private suspend fun requestOnce(
        audio: File,
        mimeType: String,
        apiKey: String,
        endpointUrl: String,
        model: String,
        language: String?,
        prompt: String?,
    ): Attempt = suspendCancellableCoroutine { continuation ->
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                audio.name,
                audio.asRequestBody(mimeType.toMediaTypeOrNull()),
            )
            .addFormDataPart("model", model)
            .apply {
                // The language hint, only where the caller actually
                // has one: with no part sent, the far side detects
                // the language itself instead of trusting a guess.
                if (!language.isNullOrBlank()) {
                    addFormDataPart("language", language)
                }
                // The prompt, only where the caller actually has
                // lyric text worth sending: with no part sent, the
                // far side transcribes the audio on its own terms
                // rather than being steered by nothing.
                if (!prompt.isNullOrBlank()) {
                    addFormDataPart("prompt", prompt)
                }
            }
            .addFormDataPart("response_format", "verbose_json")
            // Asked for twice, once per granularity: the words are
            // what the aligner consumes, and the segments keep the
            // answer shaped like the endpoint's full verbose form,
            // which is the form its word timings are calibrated in.
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .build()
        val request = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resume(Attempt.Retryable)
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val outcome = response.use {
                    when {
                        it.isSuccessful -> it.body?.string()
                            ?.let { text -> parseTranscription(text) }
                            ?.let { heard -> Attempt.Words(heard) }
                            ?: Attempt.Failed
                        it.code == 429 || it.code >= 500 -> Attempt.Retryable
                        else -> Attempt.Failed
                    }
                }
                if (continuation.isActive) continuation.resume(outcome)
            }
        })
    }

    /**
     * The `words` array of a `verbose_json` answer, as [TranscribedWord]s
     * — [parseTranscription] with the segments dropped, kept for the
     * callers (and the alignment) that only ever consume words.
     */
    internal fun parseWords(body: String): List<TranscribedWord>? =
        parseTranscription(body)?.words

    /**
     * A `verbose_json` answer as a [Transcription]: its `words` array
     * as [TranscribedWord]s and its `segments` array as
     * [TranscribedSegment]s.
     *
     * Seconds become milliseconds by rounding, so a word the service
     * timed at 0.12s lands at 120ms rather than 119. Entries with no
     * usable text or no usable span are skipped rather than repaired;
     * a body with no words left after that is a failure (null), like
     * any other. Segments are held to the same standard but not to the
     * words' verdict: an answer whose segments are missing or unusable
     * still answers, with an empty segment list — the words are the
     * hearing, the segments only its phrasing, and a caller grouping
     * words into lines falls back to the pauses between them.
     */
    internal fun parseTranscription(body: String): Transcription? {
        val root = runCatching { lyricsJson.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return null
        val words = runCatching { root["words"]?.jsonArray }.getOrNull() ?: return null
        val tokens = words.mapNotNull { element ->
            val entry = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val text = entry["word"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val start = entry["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val end = entry["end"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            TranscribedWord(
                text = text,
                startMs = (start * 1000.0).roundToLong(),
                endMs = (end * 1000.0).roundToLong(),
            )
        }
        if (tokens.isEmpty()) return null
        val segments = runCatching { root["segments"]?.jsonArray }.getOrNull()
            ?.mapNotNull { element ->
                val entry = runCatching { element.jsonObject }.getOrNull()
                    ?: return@mapNotNull null
                val text = entry["text"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val start = entry["start"]?.jsonPrimitive?.doubleOrNull
                    ?: return@mapNotNull null
                val end = entry["end"]?.jsonPrimitive?.doubleOrNull
                    ?: return@mapNotNull null
                if (end <= start) return@mapNotNull null
                TranscribedSegment(
                    text = text,
                    startMs = (start * 1000.0).roundToLong(),
                    endMs = (end * 1000.0).roundToLong(),
                )
            }
            .orEmpty()
        return Transcription(words = tokens, segments = segments)
    }
}
