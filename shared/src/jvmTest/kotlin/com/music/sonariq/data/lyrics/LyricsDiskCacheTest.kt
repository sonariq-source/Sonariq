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

        val aiMeasured = listOf(
            LyricLine(
                5_000,
                "timed",
                words = listOf(LyricWord(5_000, 5_600, "timed")),
                wordsAiMeasured = true,
            ),
        )
        assertEquals(LyricTiming.WORD_SYNCED_AI_MEASURED, aiMeasured.lyricTiming())
    }

    @Test
    fun lyricLineSerializationRoundTripKeepsAiMeasuredFlag() {
        val line = LyricLine(
            timeMs = 12_000,
            text = "golden hour",
            words = listOf(
                LyricWord(startMs = 12_000, endMs = 12_800, text = "golden"),
                LyricWord(startMs = 12_900, endMs = 14_500, text = "hour"),
            ),
            sungUntilMs = 15_000,
            wordsAiMeasured = true,
        )
        val decoded = json.decodeFromString<LyricLine>(json.encodeToString(line))
        assertEquals(line, decoded)
        assertTrue(decoded.wordsAiMeasured)
        assertEquals(LyricTiming.WORD_SYNCED_AI_MEASURED, listOf(decoded).lyricTiming())
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

    /** Writes a cache entry as a raw payload, exactly as [readCacheReadsWhatWasWritten] does. */
    private fun writeCacheEntry(root: File, videoId: String, payload: String) {
        val key = LyricsRepository.cacheKey(videoId, null)!!
        val directory = File(root, "lyrics_v1")
        check(directory.mkdirs())
        GZIPOutputStream(File(directory, "$key.json.gz").outputStream()).bufferedWriter().use {
            it.write(payload)
        }
    }

    /**
     * A v1-shaped payload: no `provisional` / `storedAtMs` fields, as 2.24
     * wrote it. [aiAlignmentVersion] is likewise omitted unless a test asks
     * for it, exactly as every payload written before the field existed
     * omits it — those payloads decode with version 0.
     */
    private fun cachePayload(
        source: LyricsSource,
        timing: LyricTiming,
        lines: List<LyricLine>,
        aiAlignmentVersion: Int? = null,
    ): String = buildString {
        append("""{"version":1,"source":"${source.name}","timing":"${timing.name}"""")
        if (aiAlignmentVersion != null) {
            append(""","aiAlignmentVersion":$aiAlignmentVersion""")
        }
        append(""","lines":""")
        append(json.encodeToString(lines))
        append('}')
    }

    @Test
    fun cachedWordSyncedEntryCountsAsFinal() = runBlocking {
        val root = Files.createTempDirectory("lyrics-cache-test").toFile()
        val lines = listOf(
            LyricLine(
                timeMs = 4_000,
                text = "verse",
                words = listOf(LyricWord(4_000, 4_600, "verse")),
                sungUntilMs = 8_000,
            ),
        )
        writeCacheEntry(
            root,
            "video-final",
            cachePayload(LyricsSource.LRCLIB, LyricTiming.WORD_SYNCED, lines),
        )

        LyricsRepository.cacheDir = root
        assertTrue(LyricsRepository.hasFinalLyrics("video-final"))
        assertTrue(LyricsRepository.hasFinalLyrics("video-final", null))
        assertFalse(LyricsRepository.hasFinalLyrics("video-absent"))
    }

    @Test
    fun cachedLineSyncedEntryIsProvisionalButStillReadable() = runBlocking {
        val root = Files.createTempDirectory("lyrics-cache-test").toFile()
        val lines = listOf(
            LyricLine(timeMs = 4_000, text = "verse", sungUntilMs = 8_000),
        )
        writeCacheEntry(
            root,
            "video-provisional",
            cachePayload(LyricsSource.LRCLIB, LyricTiming.LINE_SYNCED, lines),
        )

        LyricsRepository.cacheDir = root
        // Not final: a later lookup may re-hunt for real word sync.
        assertFalse(LyricsRepository.hasFinalLyrics("video-provisional"))
        // But the cached lines still read back for instant display.
        val hit = LyricsRepository.readCache("video-provisional", null)
        checkNotNull(hit)
        assertEquals(lines, hit.lines)
        assertEquals(LyricTiming.LINE_SYNCED, hit.timing)
    }

    @Test
    fun cachedAiMeasuredEntryKeepsTimingAndFlagButIsNotFinal() = runBlocking {
        val root = Files.createTempDirectory("lyrics-cache-test").toFile()
        val lines = listOf(
            LyricLine(
                timeMs = 4_000,
                text = "verse line",
                words = listOf(
                    LyricWord(4_000, 4_400, "verse"),
                    LyricWord(4_500, 4_900, "line"),
                ),
                sungUntilMs = 8_000,
                wordsAiMeasured = true,
            ),
        )
        // Stamped with the current alignment version: this is a measurement
        // the current aligner still stands behind.
        writeCacheEntry(
            root,
            "video-ai",
            cachePayload(
                LyricsSource.LRCLIB,
                LyricTiming.WORD_SYNCED_AI_MEASURED,
                lines,
                aiAlignmentVersion = AiLyricAligner.ALIGNMENT_VERSION,
            ),
        )

        LyricsRepository.cacheDir = root
        val hit = LyricsRepository.readCache("video-ai", null)

        checkNotNull(hit)
        // The measured words and the flag that says where they came from
        // survive the round trip — nothing is stripped, nothing promoted.
        assertEquals(lines, hit.lines)
        assertTrue(hit.lines.single().wordsAiMeasured)
        assertEquals(LyricTiming.WORD_SYNCED_AI_MEASURED, hit.timing)
        // But AI-measured is provisional: a later lookup still re-hunts
        // for provider timings, so this never counts as final.
        assertFalse(LyricsRepository.hasFinalLyrics("video-ai"))
    }

    @Test
    fun staleAiMeasuredEntryWithoutVersionIsStrippedOnRead() = runBlocking {
        val root = Files.createTempDirectory("lyrics-cache-test").toFile()
        // An entry from before the alignment version was stamped: the
        // payload carries no aiAlignmentVersion at all, so it decodes as
        // version 0 — a measurement the current alignment has moved past.
        // Its background line carries a measurement of its own.
        val lines = listOf(
            LyricLine(
                timeMs = 4_000,
                text = "verse line",
                words = listOf(
                    LyricWord(4_000, 4_400, "verse"),
                    LyricWord(4_500, 4_900, "line"),
                ),
                sungUntilMs = 8_000,
                background = LyricLine(
                    timeMs = 4_500,
                    text = "(ooh)",
                    words = listOf(LyricWord(4_500, 5_200, "(ooh)")),
                    wordsAiMeasured = true,
                ),
                wordsAiMeasured = true,
            ),
        )
        writeCacheEntry(
            root,
            "video-ai-stale",
            cachePayload(LyricsSource.LRCLIB, LyricTiming.WORD_SYNCED_AI_MEASURED, lines),
        )

        LyricsRepository.cacheDir = root
        val hit = LyricsRepository.readCache("video-ai-stale", null)

        checkNotNull(hit)
        val line = hit.lines.single()
        // The superseded measurement is gone, on the lead line and its
        // background alike, and the flags that claimed it went with it.
        assertTrue(line.words.isEmpty())
        assertFalse(line.wordsAiMeasured)
        val background = checkNotNull(line.background)
        assertTrue(background.words.isEmpty())
        assertFalse(background.wordsAiMeasured)
        // What is left is the honest line sync the measurement was derived
        // from, so the track is offered for re-measurement.
        assertEquals(LyricTiming.LINE_SYNCED, hit.timing)
        assertFalse(LyricsRepository.hasFinalLyrics("video-ai-stale"))
    }

    @Test
    fun staleAiMeasuredEntryStampedVersionZeroIsStrippedOnRead() = runBlocking {
        val root = Files.createTempDirectory("lyrics-cache-test").toFile()
        // The same retirement, with the version present and explicitly 0:
        // anything below the current alignment version is superseded.
        val lines = listOf(
            LyricLine(
                timeMs = 4_000,
                text = "verse line",
                words = listOf(
                    LyricWord(4_000, 4_400, "verse"),
                    LyricWord(4_500, 4_900, "line"),
                ),
                sungUntilMs = 8_000,
                wordsAiMeasured = true,
            ),
        )
        writeCacheEntry(
            root,
            "video-ai-v0",
            cachePayload(
                LyricsSource.LRCLIB,
                LyricTiming.WORD_SYNCED_AI_MEASURED,
                lines,
                aiAlignmentVersion = 0,
            ),
        )

        LyricsRepository.cacheDir = root
        val hit = LyricsRepository.readCache("video-ai-v0", null)

        checkNotNull(hit)
        assertTrue(hit.lines.single().words.isEmpty())
        assertFalse(hit.lines.single().wordsAiMeasured)
        assertEquals(LyricTiming.LINE_SYNCED, hit.timing)
    }

    @Test
    fun legacyEstimatedEntryIsSanitisedOnRead() = runBlocking {
        val root = Files.createTempDirectory("lyrics-cache-test").toFile()
        // A legacy entry written before estimated words were dropped: the
        // lines carry interpolated words and say so.
        val lines = listOf(
            LyricLine(
                timeMs = 4_000,
                text = "verse",
                words = listOf(LyricWord(4_000, 4_600, "verse")),
                sungUntilMs = 8_000,
                background = LyricLine(
                    timeMs = 4_500,
                    text = "(ooh)",
                    words = listOf(LyricWord(4_500, 5_200, "(ooh)")),
                    wordsEstimated = true,
                ),
                wordsEstimated = true,
            ),
        )
        writeCacheEntry(
            root,
            "video-legacy",
            cachePayload(LyricsSource.LRCLIB, LyricTiming.WORD_SYNCED_ESTIMATED, lines),
        )

        LyricsRepository.cacheDir = root
        val hit = LyricsRepository.readCache("video-legacy", null)

        checkNotNull(hit)
        val line = hit.lines.single()
        // The invented words are gone, on the lead line and its background.
        assertTrue(line.words.isEmpty())
        assertFalse(line.wordsEstimated)
        val background = checkNotNull(line.background)
        assertTrue(background.words.isEmpty())
        assertFalse(background.wordsEstimated)
        // And the timing is honestly recomputed as line sync, not word sync.
        assertEquals(LyricTiming.LINE_SYNCED, hit.timing)
        assertFalse(LyricsRepository.hasFinalLyrics("video-legacy"))
    }

    @Test
    fun v1PayloadWithoutProvisionalFieldsStillDecodes() = runBlocking {
        val root = Files.createTempDirectory("lyrics-cache-test").toFile()
        val lines = listOf(
            LyricLine(
                timeMs = 4_000,
                text = "verse",
                words = listOf(LyricWord(4_000, 4_600, "verse")),
                sungUntilMs = 8_000,
            ),
        )
        // cachePayload carries only the v1 fields (version/source/timing/
        // lines) — nothing named provisional or storedAtMs.
        writeCacheEntry(
            root,
            "video-v1",
            cachePayload(LyricsSource.LRC_RED, LyricTiming.WORD_SYNCED, lines),
        )

        LyricsRepository.cacheDir = root
        val hit = LyricsRepository.readCache("video-v1", null)

        checkNotNull(hit)
        assertEquals(LyricsSource.LRC_RED, hit.source)
        assertEquals(lines, hit.lines)
        assertEquals(LyricTiming.WORD_SYNCED, hit.timing)
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
