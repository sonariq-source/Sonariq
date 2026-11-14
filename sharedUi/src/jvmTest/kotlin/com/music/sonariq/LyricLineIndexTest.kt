package com.music.sonariq

import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.ui.player.lineIndexAt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The binary-search line lookup must answer exactly what the linear
 * scan answered — it runs on every frame, over the whole song — at
 * every boundary a position can fall on.
 */
class LyricLineIndexTest {
    private val lines = listOf(
        LyricLine(1_000, "one"),
        LyricLine(3_000, "two"),
        LyricLine(6_500, "three"),
        LyricLine(9_000, "four"),
    )

    @Test
    fun emptyListHasNoCurrentLine() {
        assertEquals(-1, emptyList<LyricLine>().lineIndexAt(5_000))
    }

    @Test
    fun beforeTheFirstLineIsMinusOne() {
        assertEquals(-1, lines.lineIndexAt(0))
        assertEquals(-1, lines.lineIndexAt(999))
    }

    @Test
    fun exactlyOnAStampIsThatLine() {
        assertEquals(0, lines.lineIndexAt(1_000))
        assertEquals(2, lines.lineIndexAt(6_500))
    }

    @Test
    fun insideAGapThePreviousLineStaysCurrent() {
        assertEquals(1, lines.lineIndexAt(4_000))
        assertEquals(1, lines.lineIndexAt(6_499))
    }

    @Test
    fun afterTheLastLineIsTheLastLine() {
        assertEquals(3, lines.lineIndexAt(9_000))
        assertEquals(3, lines.lineIndexAt(3_600_000))
    }

    @Test
    fun duplicateStampsResolveToTheLastOfThem() {
        val dupes = listOf(
            LyricLine(2_000, "a"),
            LyricLine(2_000, "b"),
            LyricLine(2_000, "c"),
            LyricLine(5_000, "d"),
        )
        assertEquals(2, dupes.lineIndexAt(2_000))
        assertEquals(2, dupes.lineIndexAt(4_999))
    }

    @Test
    fun matchesTheLinearScanOnGeneratedSongs() {
        val random = Random(42)
        repeat(200) {
            val count = random.nextInt(0, 300)
            var stamp = 0L
            val song = List(count) {
                stamp += random.nextLong(0, 4_000)
                LyricLine(stamp, "line")
            }
            val horizon = (stamp + 5_000).toInt()
            repeat(25) {
                val position = if (horizon > 0) random.nextInt(0, horizon).toLong() else 0L
                assertEquals(
                    "count=$count position=$position",
                    song.indexOfLast { line -> line.timeMs <= position },
                    song.lineIndexAt(position),
                )
            }
        }
    }

    @Test
    fun largeWordSyncedSongsResolveAtTheEdges() {
        // A long song's worth of lines, as a word-synced file carries.
        val song = List(10_000) { LyricLine(it * 1_800L, "line $it") }
        assertEquals(-1, song.lineIndexAt(-1))
        assertEquals(0, song.lineIndexAt(0))
        assertEquals(5_555, song.lineIndexAt(5_555 * 1_800L))
        assertEquals(9_999, song.lineIndexAt(Long.MAX_VALUE / 2))
    }
}
