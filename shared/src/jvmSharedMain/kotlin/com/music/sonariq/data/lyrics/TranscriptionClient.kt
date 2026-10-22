package com.music.sonariq.data.lyrics

import com.music.sonariq.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
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
 * Analysis reads the track's audio file only. There is no microphone and
 * no capture of anything the device plays: the bytes sent are the bytes of
 * a file the app already has or has just fetched for this track.
 *
 * Null on any failure at all — a refusal, an unreachable host, a body that
 * does not parse, an empty word list. A failed transcription is not a
 * verdict about the track, so nothing is remembered anywhere: the next
 * attempt, whenever it comes, simply asks again.
 */
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
     * The blocking OkHttp call runs on [Dispatchers.IO]; the multipart
     * build only stats the file. Never logs [apiKey] — or the audio.
     */
    suspend fun transcribe(
        audio: File,
        mimeType: String,
        apiKey: String,
        endpointUrl: String = DEFAULT_ENDPOINT,
        model: String = DEFAULT_MODEL,
        language: String? = null,
    ): List<TranscribedWord>? {
        if (apiKey.isBlank() || !audio.isFile || audio.length() == 0L) return null
        return withContext(Dispatchers.IO) {
            runCatching {
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
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string()?.let { parseWords(it) }
                }
            }.getOrNull()
        }
    }

    /**
     * The `words` array of a `verbose_json` answer, as [TranscribedWord]s.
     *
     * Seconds become milliseconds by rounding, so a word the service timed
     * at 0.12s lands at 120ms rather than 119. Entries with no usable text
     * or no usable span are skipped rather than repaired; a body with no
     * words left after that is a failure (null), like any other.
     */
    internal fun parseWords(body: String): List<TranscribedWord>? {
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
        return tokens.takeIf { it.isNotEmpty() }
    }
}
