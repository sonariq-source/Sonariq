package com.music.sonariq.data.lyrics

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store rules for AI-measured lyrics — see
 * [LyricsRepository.storeAiResult].
 *
 * Uses the same disk-cache harness as [LyricsDiskCacheTest]: seed entries
 * are written as raw gzipped payloads in the v1 shape, and the store's
 * answer is read back through [LyricsRepository.readCache], so what is
 * asserted is what a later lookup would actually see.
 */
class AiLyricsStoreTest {

    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun clearCacheDir() {
        LyricsRepository.cacheDir = null
    }

    private val lineOnlyLines = listOf(
        LyricLine(timeMs = 4_000, text = "golden hour", sungUntilMs = 8_000),
        LyricLine(timeMs = 9_000, text = "second line", sungUntilMs = 12_000),
    )

    private val providerLines = listOf(
        LyricLine(
            timeMs = 4_000,
            text = "golden hour",
            words = listOf(
                LyricWord(4_000, 4_600, "golden"),
                LyricWord(4_700, 5_300, "hour"),
            ),
            sungUntilMs = 8_000,
        ),
        LyricLine(timeMs = 9_000, text = "second line", sungUntilMs = 12_000),
    )

    private val aiLines = listOf(
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
        LyricLine(timeMs = 9_000, text = "second line", sungUntilMs = 12_000),
    )

    private fun aiResult(lines: List<LyricLine> = aiLines) =
        LyricsRepository.Result(LyricsSource.LRCLIB, lines)

    /** Writes a cache entry as a raw v1 payload, as [LyricsDiskCacheTest] does. */
    private fun seedCacheEntry(
        root: File,
        videoId: String,
        timing: LyricTiming,
        lines: List<LyricLine>,
    ) {
        val key = LyricsRepository.cacheKey(videoId, null)!!
        val directory = File(root, "lyrics_v1")
        check(directory.mkdirs() || directory.isDirectory)
        val payload = buildString {
            append("""{"version":1,"source":"LRCLIB","timing":"${timing.name}","lines":""")
            append(json.encodeToString(lines))
            append('}')
        }
        GZIPOutputStream(File(directory, "$key.json.gz").outputStream()).bufferedWriter().use {
            it.write(payload)
        }
    }

    private fun tempRoot(): File {
        val root = Files.createTempDirectory("lyrics-ai-store-test").toFile()
        LyricsRepository.cacheDir = root
        return root
    }

    @Test
    fun aiResultIsStoredOverALineOnlyEntry() = runBlocking {
        val root = tempRoot()
        seedCacheEntry(root, "video-ai", LyricTiming.LINE_SYNCED, lineOnlyLines)

        assertTrue(LyricsRepository.storeAiResult("video-ai", null, aiResult()))

        val stored = LyricsRepository.readCache("video-ai", null)
        checkNotNull(stored)
        assertEquals(LyricTiming.WORD_SYNCED_AI_MEASURED, stored.timing)
        assertEquals(aiLines, stored.lines)
        assertEquals(4, LyricsLookupPolicy.tierOf(stored))
    }

    @Test
    fun aiResultIsStoredWhenNothingIsCached() = runBlocking {
        tempRoot()

        assertTrue(LyricsRepository.storeAiResult("video-new", null, aiResult()))

        val stored = LyricsRepository.readCache("video-new", null)
        checkNotNull(stored)
        assertEquals(LyricTiming.WORD_SYNCED_AI_MEASURED, stored.timing)
    }

    @Test
    fun aiResultNeverOverwritesAFinalProviderEntry() = runBlocking {
        val root = tempRoot()
        seedCacheEntry(root, "video-final", LyricTiming.WORD_SYNCED, providerLines)

        assertFalse(LyricsRepository.storeAiResult("video-final", null, aiResult()))

        // The provider entry is exactly what a later lookup still sees.
        val stored = LyricsRepository.readCache("video-final", null)
        checkNotNull(stored)
        assertEquals(LyricTiming.WORD_SYNCED, stored.timing)
        assertEquals(providerLines, stored.lines)
        assertTrue(LyricsRepository.hasFinalLyrics("video-final"))
    }

    @Test
    fun aSecondAiStoreIsRefused() = runBlocking {
        val root = tempRoot()
        seedCacheEntry(root, "video-twice", LyricTiming.LINE_SYNCED, lineOnlyLines)

        assertTrue(LyricsRepository.storeAiResult("video-twice", null, aiResult()))
        val secondAttempt = aiResult(
            listOf(
                LyricLine(
                    timeMs = 4_000,
                    text = "golden hour",
                    words = listOf(
                        LyricWord(4_200, 4_800, "golden"),
                        LyricWord(4_900, 5_500, "hour"),
                    ),
                    sungUntilMs = 8_000,
                    wordsAiMeasured = true,
                ),
                LyricLine(timeMs = 9_000, text = "second line", sungUntilMs = 12_000),
            ),
        )
        assertFalse(LyricsRepository.storeAiResult("video-twice", null, secondAttempt))

        // What is cached is still the first measurement, untouched.
        val stored = LyricsRepository.readCache("video-twice", null)
        checkNotNull(stored)
        assertEquals(aiLines, stored.lines)
    }

    @Test
    fun aStoredAiEntryStaysProvisionalSoTheProviderHuntContinues() = runBlocking {
        val root = tempRoot()
        seedCacheEntry(root, "video-provisional", LyricTiming.LINE_SYNCED, lineOnlyLines)

        assertTrue(LyricsRepository.storeAiResult("video-provisional", null, aiResult()))

        val stored = LyricsRepository.readCache("video-provisional", null)
        checkNotNull(stored)
        // Tier 4 is not final: a later lookup re-races the providers and a
        // provider word sync, when it lands, replaces the measurement.
        assertFalse(LyricsLookupPolicy.isFinal(stored))
        assertTrue(LyricsLookupPolicy.isProvisional(stored))
        assertFalse(LyricsRepository.hasFinalLyrics("video-provisional"))
    }

    @Test
    fun storeAiResultStripsEstimatedWordsAndRecomputesTiming() = runBlocking {
        val root = tempRoot()
        seedCacheEntry(root, "video-estimated", LyricTiming.LINE_SYNCED, lineOnlyLines)
        val mixed = LyricsRepository.Result(
            LyricsSource.LRCLIB,
            listOf(
                LyricLine(
                    timeMs = 4_000,
                    text = "golden hour",
                    words = listOf(LyricWord(4_000, 4_600, "golden")),
                    sungUntilMs = 8_000,
                    wordsEstimated = true,
                ),
                aiLines[1].copy(
                    text = "second line",
                    words = listOf(
                        LyricWord(9_100, 9_700, "second"),
                        LyricWord(9_800, 10_400, "line"),
                    ),
                    wordsAiMeasured = true,
                ),
            ),
        )

        assertTrue(LyricsRepository.storeAiResult("video-estimated", null, mixed))

        val stored = LyricsRepository.readCache("video-estimated", null)
        checkNotNull(stored)
        // The estimated line's invented words are gone; the measured line
        // keeps its own, and the timing was recomputed off what survived.
        assertTrue(stored.lines[0].words.isEmpty())
        assertFalse(stored.lines[0].wordsEstimated)
        assertTrue(stored.lines[1].words.isNotEmpty())
        assertTrue(stored.lines[1].wordsAiMeasured)
        assertEquals(LyricTiming.WORD_SYNCED_AI_MEASURED, stored.timing)
    }

    @Test
    fun aResultThatIsNotAiMeasuredIsRefused() = runBlocking {
        tempRoot()
        val lineOnly = LyricsRepository.Result(LyricsSource.LRCLIB, lineOnlyLines)

        assertFalse(LyricsRepository.storeAiResult("video-plain", null, lineOnly))
        assertNull(LyricsRepository.readCache("video-plain", null))
    }
}
