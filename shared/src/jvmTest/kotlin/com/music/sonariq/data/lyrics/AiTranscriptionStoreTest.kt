package com.music.sonariq.data.lyrics

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * The AI transcription's place in the disk cache ([LyricsRepository.storeAiTranscription]),
 * pinned against the measurement's ([LyricsRepository.storeAiResult]):
 * a transcription is stored only where the cache holds nothing at all,
 * never over any provider result or measurement, stays provisional so
 * the hunt for provider lyrics never closes — and is the one AI entry
 * a later measurement *does* replace, because the measurement times a
 * provider's own words.
 */
class AiTranscriptionStoreTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val transcribedLines = listOf(
        LyricLine(
            timeMs = 4_000,
            text = "golden hour",
            words = listOf(
                LyricWord(4_100, 4_700, "golden"),
                LyricWord(4_800, 5_400, "hour"),
            ),
            sungUntilMs = 8_000,
            wordsAiTranscribed = true,
        ),
        LyricLine(
            timeMs = 9_000,
            text = "second line",
            words = listOf(
                LyricWord(9_100, 9_600, "second"),
                LyricWord(9_700, 10_200, "line"),
            ),
            sungUntilMs = 12_000,
            wordsAiTranscribed = true,
        ),
    )

    private val measuredLines = listOf(
        LyricLine(
            timeMs = 4_000,
            text = "golden hour",
            words = listOf(
                LyricWord(4_100, 4_700, "golden"),
                LyricWord(4_800, 5_400, "hour"),
            ),
            sungUntilMs = 8_000,
            wordsAiMeasured = true,
        ),
    )

    private val providerLines = listOf(
        LyricLine(
            timeMs = 4_000,
            text = "golden hour",
            words = listOf(
                LyricWord(4_100, 4_700, "golden"),
                LyricWord(4_800, 5_400, "hour"),
            ),
            sungUntilMs = 8_000,
        ),
    )

    private val lineOnlyLines = listOf(
        LyricLine(timeMs = 4_000, text = "golden hour", sungUntilMs = 8_000),
        LyricLine(timeMs = 9_000, text = "second line", sungUntilMs = 12_000),
    )

    private fun transcriptionResult(lines: List<LyricLine> = transcribedLines) =
        LyricsRepository.Result(LyricsSource.AI_TRANSCRIPTION, lines)

    private fun measuredResult() =
        LyricsRepository.Result(LyricsSource.LRCLIB, measuredLines)

    private fun tempRoot(): File {
        val root = Files.createTempDirectory("lyrics-transcription-store-test").toFile()
        LyricsRepository.cacheDir = root
        return root
    }

    /** Writes a cache entry as a raw v1 payload, as [AiLyricsStoreTest] does. */
    private fun seedCacheEntry(
        root: File,
        videoId: String,
        source: LyricsSource,
        timing: LyricTiming,
        lines: List<LyricLine>,
    ) {
        val key = LyricsRepository.cacheKey(videoId, null)!!
        val directory = File(root, "lyrics_v1")
        check(directory.mkdirs() || directory.isDirectory)
        val payload = buildString {
            append("""{"version":1,"source":"${source.name}","timing":"${timing.name}","lines":""")
            append(json.encodeToString(lines))
            append('}')
        }
        GZIPOutputStream(File(directory, "$key.json.gz").outputStream()).bufferedWriter().use {
            it.write(payload)
        }
    }

    @Test
    fun aTranscriptionIsStoredWhereNothingIsCached() = runBlocking {
        tempRoot()

        assertTrue(
            LyricsRepository.storeAiTranscription("video-none", null, transcriptionResult()),
        )

        val stored = LyricsRepository.readCache("video-none", null)
        assertNotNull(stored)
        assertEquals(LyricsSource.AI_TRANSCRIPTION, stored.source)
        assertEquals(LyricTiming.WORD_SYNCED_AI_TRANSCRIBED, stored.timing)
        assertEquals(transcribedLines, stored.lines)
        // Provisional: tier 4, never final, verdict AI_TRANSCRIBED —
        // every later lookup keeps hunting for provider lyrics.
        assertEquals(4, LyricsLookupPolicy.tierOf(stored))
        assertFalse(LyricsRepository.hasFinalLyrics("video-none"))
        assertEquals(
            LyricsVerdict.AI_TRANSCRIBED,
            LyricsValidation.classify(stored, 0L),
        )
    }

    @Test
    fun aTranscriptionNeverOverwritesAFinalProviderEntry() = runBlocking {
        val root = tempRoot()
        seedCacheEntry(root, "video-final", LyricsSource.LRCLIB, LyricTiming.WORD_SYNCED, providerLines)

        assertFalse(
            LyricsRepository.storeAiTranscription("video-final", null, transcriptionResult()),
        )

        val stored = LyricsRepository.readCache("video-final", null)
        assertNotNull(stored)
        assertEquals(LyricTiming.WORD_SYNCED, stored.timing)
        assertEquals(providerLines, stored.lines)
    }

    @Test
    fun aTranscriptionNeverOverwritesEvenAProvidersUntimedText() = runBlocking {
        // The sharpest form of the rule: a provider's plain text, the
        // lowest thing a provider can return, still outranks a hearing.
        val root = tempRoot()
        seedCacheEntry(root, "video-plain", LyricsSource.LRCLIB, LyricTiming.PLAIN, lineOnlyLines)

        assertFalse(
            LyricsRepository.storeAiTranscription("video-plain", null, transcriptionResult()),
        )

        val stored = LyricsRepository.readCache("video-plain", null)
        assertNotNull(stored)
        assertEquals(LyricTiming.PLAIN, stored.timing)
        assertEquals(lineOnlyLines, stored.lines)
    }

    @Test
    fun aTranscriptionNeverOverwritesAnAiMeasurement() = runBlocking {
        tempRoot()
        assertTrue(LyricsRepository.storeAiResult("video-measured", null, measuredResult()))

        assertFalse(
            LyricsRepository.storeAiTranscription("video-measured", null, transcriptionResult()),
        )

        val stored = LyricsRepository.readCache("video-measured", null)
        assertNotNull(stored)
        assertEquals(LyricTiming.WORD_SYNCED_AI_MEASURED, stored.timing)
    }

    @Test
    fun aMeasurementReplacesATranscription() = runBlocking {
        // The providers later found the song's words and the aligner
        // timed them: the provider's text, measured, takes the stand-in
        // down — the one replacement the shared tier 4 allows, by kind.
        tempRoot()
        assertTrue(
            LyricsRepository.storeAiTranscription("video-upgrade", null, transcriptionResult()),
        )

        assertTrue(LyricsRepository.storeAiResult("video-upgrade", null, measuredResult()))

        val stored = LyricsRepository.readCache("video-upgrade", null)
        assertNotNull(stored)
        assertEquals(LyricTiming.WORD_SYNCED_AI_MEASURED, stored.timing)
        assertEquals(measuredLines, stored.lines)
    }

    @Test
    fun aPlaceholderEntryDoesNotBlockATranscription() = runBlocking {
        // The reported bug's own flow: the only "lyrics" the track ever
        // had was a source's instrumental card, cached by an older
        // build. The card reads as no entry, so the transcription the
        // track is offered afterwards stores into the space it left.
        val root = tempRoot()
        seedCacheEntry(
            root,
            "video-placeholder",
            LyricsSource.KUGOU,
            LyricTiming.LINE_SYNCED,
            listOf(LyricLine(timeMs = 0, text = "纯音乐，请欣赏")),
        )
        assertNull(LyricsRepository.readCache("video-placeholder", null))

        assertTrue(
            LyricsRepository.storeAiTranscription(
                "video-placeholder", null, transcriptionResult(),
            ),
        )

        val stored = LyricsRepository.readCache("video-placeholder", null)
        assertNotNull(stored)
        assertEquals(LyricTiming.WORD_SYNCED_AI_TRANSCRIBED, stored.timing)
        assertEquals(transcribedLines, stored.lines)
    }

    @Test
    fun theTranscriptionStoreRefusesAnythingThatIsNotATranscription() = runBlocking {
        tempRoot()
        // A provider's line sync offered at this door is refused: the
        // door writes hearings, nothing else.
        assertFalse(
            LyricsRepository.storeAiTranscription(
                "video-lines",
                null,
                LyricsRepository.Result(LyricsSource.LRCLIB, lineOnlyLines),
            ),
        )
        assertNull(LyricsRepository.readCache("video-lines", null))
    }
}
