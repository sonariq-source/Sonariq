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
 * [PlaceholderLyrics]: the card a source answers with when it has no
 * words for a track — "instrumental, please enjoy" and its kin — is a
 * miss, never lyrics. These tests pin the three places that stand
 * between that card and the player's screen: the classifier itself,
 * the repository's ingestion funnel, and the disk cache read.
 */
class PlaceholderLyricsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun line(text: String, timeMs: Long = 0L) =
        LyricLine(timeMs = timeMs, text = text)

    @Test
    fun theReportedInstrumentalCardIsAPlaceholder() {
        // The exact card the bug report showed on a vocal track.
        assertTrue(PlaceholderLyrics.isPlaceholderOnly(listOf(line("纯音乐，请欣赏"))))
    }

    @Test
    fun markerVariantsArePlaceholders() {
        val variants = listOf(
            "纯音乐",
            "纯音乐请欣赏",
            "暂无歌词",
            "暂无歌词，敬请期待",
            "[Instrumental]",
            "(Instrumental)",
            "Instrumental",
            "♪ ♪ ♪",
            "No lyrics found",
            "No lyrics available",
            "Lyrics not available for this song",
        )
        for (variant in variants) {
            assertTrue(
                PlaceholderLyrics.isPlaceholderOnly(listOf(line(variant))),
                "expected a placeholder: $variant",
            )
        }
        // The same card repeated, as some sources stamp it per section.
        assertTrue(
            PlaceholderLyrics.isPlaceholderOnly(
                listOf(line("纯音乐，请欣赏"), line("纯音乐，请欣赏", 30_000)),
            ),
        )
        // Nothing at all, and nothing but blanks, are equally no lyrics.
        assertTrue(PlaceholderLyrics.isPlaceholderOnly(emptyList()))
        assertTrue(PlaceholderLyrics.isPlaceholderOnly(listOf(line(""), line("   ", 5_000))))
    }

    @Test
    fun aBodyWithAnInstrumentalSectionIsNotAPlaceholder() {
        // An [Instrumental] line inside a sung song is structure, not a
        // card: the body around it is the song's words and stays.
        val body = listOf(
            line("sing the opening line", 1_000),
            line("[Instrumental]", 30_000),
            line("and the closing line", 60_000),
        )
        assertFalse(PlaceholderLyrics.isPlaceholderOnly(body))
    }

    @Test
    fun aSungLineMentioningInstrumentalIsNotAMarker() {
        // The word inside real sung text, at real sentence length.
        assertFalse(
            PlaceholderLyrics.isMarkerLine(
                "you were instrumental in saving my life tonight",
            ),
        )
    }

    @Test
    fun theFunnelReturnsNoResultForAPlaceholder() {
        // A provider answering with only the card has missed: the
        // funnel hands back no result, so the race moves on to the
        // remaining sources and nothing is cached or shown.
        val result = LyricsRepository.result(
            LyricsSource.KUGOU,
            listOf(line("纯音乐，请欣赏")),
            durationMs = 180_000,
        )
        assertNull(result)
    }

    @Test
    fun theFunnelKeepsABodyWithAnInstrumentalSection() {
        val result = LyricsRepository.result(
            LyricsSource.LRCLIB,
            listOf(
                line("sing the opening line", 1_000),
                line("[Instrumental]", 30_000),
                line("and the closing line", 60_000),
            ),
            durationMs = 180_000,
        )
        assertNotNull(result)
        assertEquals(3, result.lines.size)
    }

    @Test
    fun aCachedPlaceholderIsNeverServed() = runBlocking {
        // An entry a build from before the funnel learned to refuse
        // these wrote to the disk cache: the read refuses it too, so
        // the bad card already sitting on a device stops being served
        // without anyone clearing the cache by hand.
        val root = Files.createTempDirectory("lyrics-placeholder-test").toFile()
        LyricsRepository.cacheDir = root
        seedCacheEntry(root, "video-placeholder", listOf(line("纯音乐，请欣赏")))
        assertNull(LyricsRepository.readCache("video-placeholder", null))
    }

    /** Writes a cache entry as a raw v1 payload, as [AiLyricsStoreTest] does. */
    private fun seedCacheEntry(root: File, videoId: String, lines: List<LyricLine>) {
        val key = LyricsRepository.cacheKey(videoId, null)!!
        val directory = File(root, "lyrics_v1")
        check(directory.mkdirs() || directory.isDirectory)
        val payload = buildString {
            append("""{"version":1,"source":"KUGOU","timing":"LINE_SYNCED","lines":""")
            append(json.encodeToString(lines))
            append('}')
        }
        GZIPOutputStream(File(directory, "$key.json.gz").outputStream()).bufferedWriter().use {
            it.write(payload)
        }
    }
}
