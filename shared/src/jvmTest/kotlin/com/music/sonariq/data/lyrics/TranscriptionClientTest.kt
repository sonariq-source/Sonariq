package com.music.sonariq.data.lyrics

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TranscriptionClient] against a local server: the words it parses out of
 * a `verbose_json` answer, the null it answers with for every failure, and
 * the shape of the one request it makes — a multipart POST carrying only
 * the audio, the model and the format fields, under the caller's key.
 */
class TranscriptionClientTest {

    private lateinit var server: MockWebServer
    private lateinit var audio: File

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
        val directory = Files.createTempDirectory("transcription-test").toFile()
        audio = File(directory, "track.m4a")
        audio.writeBytes("AUDIOBYTES".toByteArray())
    }

    @AfterTest
    fun stop() = server.shutdown()

    private fun endpoint(): String =
        server.url("/openai/v1/audio/transcriptions").toString()

    @Test
    fun wordsAreParsedFromSecondsToMilliseconds() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"words":[
                    {"word":" hello","start":0.12,"end":0.34},
                    {"word":"world","start":0.5,"end":0.92},
                    {"word":"   ","start":1.0,"end":1.2},
                    {"word":"backwards","start":2.0,"end":1.5}
                ]}""",
            ),
        )

        val tokens = TranscriptionClient.transcribe(
            audio = audio,
            mimeType = "audio/mp4",
            apiKey = "test-key",
            endpointUrl = endpoint(),
        )

        // Blank and backwards entries are skipped, not repaired; seconds
        // land as rounded milliseconds.
        assertEquals(
            listOf(
                TranscribedWord("hello", 120, 340),
                TranscribedWord("world", 500, 920),
            ),
            tokens,
        )
    }

    @Test
    fun theRequestIsOneMultipartPostWithTheKeyAsBearer() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"words":[{"word":"hi","start":0.0,"end":0.2}]}"""))

        TranscriptionClient.transcribe(
            audio = audio,
            mimeType = "audio/mp4",
            apiKey = "test-key",
            endpointUrl = endpoint(),
        )

        val request = server.takeRequest(5, TimeUnit.SECONDS)
        checkNotNull(request)
        assertEquals("POST", request.method)
        assertEquals("/openai/v1/audio/transcriptions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("""name="file""""), "the audio is the file part")
        assertTrue(body.contains("""filename="track.m4a""""))
        assertTrue(body.contains("AUDIOBYTES"), "the file part carries the audio bytes")
        assertTrue(body.contains("""name="model""""))
        assertTrue(body.contains(TranscriptionClient.DEFAULT_MODEL))
        assertTrue(body.contains("""name="response_format""""))
        assertTrue(body.contains("verbose_json"))
        // Both granularities are asked for, as two parts of the same name.
        assertEquals(2, body.split("""name="timestamp_granularities[]"""").size - 1)
        assertTrue(body.contains("word"))
        assertTrue(body.contains("segment"))
    }

    @Test
    fun aRefusalAnswersNull() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("no key configured"))
        assertNull(
            TranscriptionClient.transcribe(audio, "audio/mp4", "test-key", endpoint()),
        )
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertNull(
            TranscriptionClient.transcribe(audio, "audio/mp4", "test-key", endpoint()),
        )
    }

    @Test
    fun anEmptyOrMalformedAnswerAnswersNull() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"words":[]}"""))
        assertNull(
            TranscriptionClient.transcribe(audio, "audio/mp4", "test-key", endpoint()),
        )
        server.enqueue(MockResponse().setBody("""{"text":"no words here"}"""))
        assertNull(
            TranscriptionClient.transcribe(audio, "audio/mp4", "test-key", endpoint()),
        )
        server.enqueue(MockResponse().setBody("not json at all"))
        assertNull(
            TranscriptionClient.transcribe(audio, "audio/mp4", "test-key", endpoint()),
        )
    }

    @Test
    fun aBlankKeySendsNothing() = runBlocking {
        // Dormant: a build without the injected key never makes the call.
        assertNull(
            TranscriptionClient.transcribe(audio, "audio/mp4", "  ", endpoint()),
        )
        assertEquals(0, server.requestCount)
    }
}
