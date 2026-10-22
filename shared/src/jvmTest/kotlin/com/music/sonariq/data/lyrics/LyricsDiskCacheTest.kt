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

class LyricsDiskCacheTest {

    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun clearCacheDir() {
        LyricsRepository.cacheDir = null
    }

    @Test
    fun cacheKeyPrefersVideoId() {
        val withBoth = LyricsRepository.cacheKey("video-abc", "USRC123")
        val withVideoOnly = LyricsRepository.cacheKey("video-abc", null)
        assertEquals(withVideoOnly, withBoth)
    }

    @Test
    fun cacheKeyFallsBackToIsrc() {
        val byIsrc = LyricsRepository.cacheKey("", "USRC123")
        assertEquals(LyricsRepository.cacheKey("   ", "USRC123"), byIsrc)
        assertEquals(LyricsRepository.cacheKey("", null), null)
        assertEquals(LyricsRepository.cacheKey("   ", "  "), null)
    }

    @Test
    fun cacheKeyIsStableAndHashed() {
        val first = LyricsRepository.cacheKey("video-abc", "USRC123")
        val second = LyricsRepository.cacheKey("video-abc", "other-isrc")
        assertEquals(first, second)
        // "lyrics:" plus a SHA-256 hex digest, never the raw id.
        assertEquals(7 + 64, first!!.length)
    }

    @Test
    fun lyricLineSerializationRoundTrip() {
        val line = LyricLine(
            timeMs = 12_000,
            text = "golden hour",
            words = listOf(
                LyricWord(
                    startMs = 12_000,
                    endMs = 12_800,
                    text = "golden",
                    syllables = listOf(
                        LyricSyllable(12_000, 12_400, 0, 4),
                        LyricSyllable(12_400, 12_800, 4, 6),
                    ),
                ),
                LyricWord(startMs = 12_900, endMs = 14_500, text = "hour"),
            ),
            sungUntilMs = 15_000,
            background = LyricLine(
                timeMs = 13_500,
                text = "(ooh)",
                words = listOf(LyricWord(13_500, 14_200, "(ooh)")),
            ),
        )
        val decoded = json.decodeFromString<LyricLine>(json.encodeToString(line))
        assertEquals(line, decoded)
    }

    @Test
    fun lyricTiming() {
        val wordSynced = listOf(
            LyricLine(0, "plain"),
            LyricLine(5_000, "timed", words = listOf(LyricWord(5_000, 5_600, "timed"))),
        )
        assertEquals(LyricTiming.WORD_SYNCED, wordSynced.lyricTiming())

        val lineSynced = listOf(
            LyricLine(0, "intro"),
            LyricLine(4_000, "verse", sungUntilMs = 8_000),
        )
        assertEquals(LyricTiming.LINE_SYNCED, lineSynced.lyricTiming())

        val plain = listOf(LyricLine(0, "one"), LyricLine(0, "two"))
        assertEquals(LyricTiming.PLAIN, plain.lyricTiming())
    }

    @Test
    fun readCacheReturnsNullWithoutCacheDir() = runBlocking {
        assertNull(LyricsRepository.readCache("video-abc", null))
    }

    @Test
    fun readCacheReadsWhatWasWritten() = runBlocking {
        val root = Files.createTempDirectory("lyrics-cache-test").toFile()
        val lines = listOf(
            LyricLine(
                timeMs = 4_000,
                text = "verse",
                words = listOf(LyricWord(4_000, 4_600, "verse")),
                sungUntilMs = 8_000,
            ),
        )
        val key = LyricsRepository.cacheKey("video-abc", null)!!
        val directory = File(root, "lyrics_v1")
        check(directory.mkdirs())
        val payload = buildString {
            append("""{"version":1,"source":"LRCLIB","timing":"LINE_SYNCED","lines":""")
            append(json.encodeToString(lines))
            append('}')
        }
        GZIPOutputStream(File(directory, "$key.json.gz").outputStream()).bufferedWriter().use {
            it.write(payload)
        }

        LyricsRepository.cacheDir = root
        val hit = LyricsRepository.readCache("video-abc", null)

        checkNotNull(hit)
        assertEquals(LyricsSource.LRCLIB, hit.source)
        assertEquals(lines, hit.lines)
        assertEquals(LyricTiming.LINE_SYNCED, hit.timing)
    }

    @Test
    fun knownMissReadsFreshDiskEntry() = runBlocking {
        val root = Files.createTempDirectory("lyrics-miss-test").toFile()
        val key = LyricsRepository.cacheKey("video-miss", null)!!
        val directory = File(root, "lyrics_v1")
        check(directory.mkdirs())
        File(directory, "$key.miss").writeText(System.currentTimeMillis().toString())

        LyricsRepository.cacheDir = root
        assertTrue(LyricsRepository.isKnownMiss("video-miss", null))
        assertFalse(LyricsRepository.isKnownMiss("video-other", null))
    }

    @Test
    fun knownMissExpiresDiskEntryPastTtl() = runBlocking {
        val root = Files.createTempDirectory("lyrics-miss-test").toFile()
        val key = LyricsRepository.cacheKey("video-stale", null)!!
        val directory = File(root, "lyrics_v1")
        check(directory.mkdirs())
        val stale = System.currentTimeMillis() - LyricsLookupPolicy.NEGATIVE_TTL_MS - 1_000L
        val file = File(directory, "$key.miss")
        file.writeText(stale.toString())

        LyricsRepository.cacheDir = root
        assertFalse(LyricsRepository.isKnownMiss("video-stale", null))
        assertFalse(file.exists())
    }
}
