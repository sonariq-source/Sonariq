package com.music.sonariq

import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.lyrics.LyricWord
import com.music.sonariq.data.lyrics.LyricsAutoOffset
import com.music.sonariq.data.lyrics.lyricsLinesFingerprint
import com.music.sonariq.ui.player.applicableAutoOffsetMs
import com.music.sonariq.ui.player.decodeLyricAutoOffsets
import com.music.sonariq.ui.player.encodeLyricAutoOffsets
import com.music.sonariq.ui.player.resolveLyricsOffsetMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LyricsAutoOffsetTest {

    private val videoId = "dQw4w9WgXcQ"

    /** Plain line sync, as a measurement is made against it. */
    private val lineSync = listOf(
        LyricLine(10_000, "verse one"),
        LyricLine(20_000, "verse two"),
    )

    private fun record(
        lines: List<LyricLine>,
        offsetMs: Int = 1_500,
    ) = LyricsAutoOffset(offsetMs, lyricsLinesFingerprint(lines))

    // -- Resolution composition ------------------------------------------

    @Test
    fun `auto offset composes under the global offset`() {
        assertEquals(
            1_300,
            resolveLyricsOffsetMs(
                overrides = emptyMap(),
                globalMs = -200,
                videoId = videoId,
                autoMs = 1_500,
            ),
        )
    }

    @Test
    fun `per-track override wins outright over the auto offset`() {
        // The listener's own word for this track: the measurement does
        // not dilute it, exactly as it has never diluted it under the
        // global.
        assertEquals(
            300,
            resolveLyricsOffsetMs(
                overrides = mapOf(videoId to 300),
                globalMs = -200,
                videoId = videoId,
                autoMs = 1_500,
            ),
        )
    }

    @Test
    fun `composed base is held to the offset range`() {
        assertEquals(
            5_000,
            resolveLyricsOffsetMs(
                overrides = emptyMap(),
                globalMs = 4_800,
                videoId = videoId,
                autoMs = 1_500,
            ),
        )
    }

    // -- Applicability -----------------------------------------------------

    @Test
    fun `record applies to the very lines it was measured against`() {
        val records = mapOf(videoId to record(lineSync))
        assertEquals(1_500, applicableAutoOffsetMs(records, videoId, lineSync))
    }

    @Test
    fun `record survives the ai-measured upgrade of the same lines`() {
        // The AI result is the provider's lines with measured words laid
        // over them: same stamps, same text, so the measurement still
        // names what is on screen.
        val measured = lineSync.map { line ->
            line.copy(
                words = listOf(
                    LyricWord(line.timeMs, line.timeMs + 500, line.text.substringBefore(' ')),
                ),
                wordsAiMeasured = true,
            )
        }
        val records = mapOf(videoId to record(lineSync))
        assertEquals(1_500, applicableAutoOffsetMs(records, videoId, measured))
    }

    @Test
    fun `record retires when provider word timings replace the lines`() {
        // The same stamps and text, but now carrying the provider's own
        // timings: a different thing from what was measured, and the
        // shift measured on line sync must not move verified timings.
        val wordSynced = lineSync.map { line ->
            line.copy(
                words = listOf(
                    LyricWord(line.timeMs, line.timeMs + 500, line.text.substringBefore(' ')),
                ),
            )
        }
        val records = mapOf(videoId to record(lineSync))
        assertEquals(0, applicableAutoOffsetMs(records, videoId, wordSynced))
    }

    @Test
    fun `record does not apply to a different track or other lines`() {
        val records = mapOf(videoId to record(lineSync))
        assertEquals(0, applicableAutoOffsetMs(records, "9bZkp7q19f0", lineSync))
        assertEquals(
            0,
            applicableAutoOffsetMs(
                records,
                videoId,
                listOf(LyricLine(10_000, "another song entirely")),
            ),
        )
        assertEquals(0, applicableAutoOffsetMs(records, null, lineSync))
        assertEquals(0, applicableAutoOffsetMs(records, videoId, null))
        assertEquals(0, applicableAutoOffsetMs(records, videoId, emptyList()))
    }

    @Test
    fun `record from another version does not apply`() {
        val future = LyricsAutoOffset(
            offsetMs = 1_500,
            fingerprint = lyricsLinesFingerprint(lineSync),
            version = LyricsAutoOffset.CURRENT_VERSION + 1,
        )
        assertEquals(0, applicableAutoOffsetMs(mapOf(videoId to future), videoId, lineSync))
    }

    @Test
    fun `zero offset record is a measurement, not an absence`() {
        // Checked and found aligned: the record exists, applies, and
        // shifts nothing.
        val records = mapOf(videoId to record(lineSync, offsetMs = 0))
        assertEquals(0, applicableAutoOffsetMs(records, videoId, lineSync))
        assertNotEquals(0, records.size)
    }

    // -- Fingerprint -------------------------------------------------------

    @Test
    fun `fingerprint changes with stamps and text`() {
        val base = lyricsLinesFingerprint(lineSync)
        assertEquals(base, lyricsLinesFingerprint(lineSync))
        assertNotEquals(base, lyricsLinesFingerprint(listOf(LyricLine(11_000, "verse one"))))
        assertNotEquals(base, lyricsLinesFingerprint(listOf(LyricLine(10_000, "verse uno"))))
    }

    // -- Codec ---------------------------------------------------------------

    @Test
    fun `codec round trips records`() {
        val records = mapOf(
            videoId to LyricsAutoOffset(1_500, lyricsLinesFingerprint(lineSync)),
            "9bZkp7q19f0" to LyricsAutoOffset(-250, lyricsLinesFingerprint(lineSync)),
        )
        assertEquals(records, decodeLyricAutoOffsets(encodeLyricAutoOffsets(records)))
        assertEquals(emptyMap<String, LyricsAutoOffset>(), decodeLyricAutoOffsets(""))
    }

    @Test
    fun `codec drops malformed and foreign-version entries quietly`() {
        val good = encodeLyricAutoOffsets(mapOf(videoId to record(lineSync)))
        val stored = listOf(
            good,
            "tiny:1:100:${"a".repeat(64)}",
            "$videoId:99:100:${"a".repeat(64)}",
            "$videoId:1:notanumber:${"a".repeat(64)}",
            "$videoId:1:100:short",
            "garbage",
        ).joinToString(",")
        val decoded = decodeLyricAutoOffsets(stored)
        assertEquals(mapOf(videoId to record(lineSync)), decoded)
    }
}
