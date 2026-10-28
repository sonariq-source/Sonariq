package com.music.sonariq

import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.ui.player.activeLyricRows
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricFocusTest {
    private val overlap = listOf(
        LyricLine(1_000, "upper", sungUntilMs = 4_000),
        LyricLine(2_000, "middle", sungUntilMs = 5_000),
        LyricLine(3_000, "lower", sungUntilMs = 6_000),
    )

    @Test fun upperLineKeepsAnchorAcrossThreeOverlappingVocals() {
        assertEquals(listOf(0, 1, 2), activeLyricRows(overlap, 3_500))
    }

    @Test fun anchorAdvancesExactlyWhenUpperVocalEnds() {
        assertEquals(listOf(1, 2), activeLyricRows(overlap, 4_000))
        assertEquals(listOf(2), activeLyricRows(overlap, 5_000))
    }

    @Test fun finishedMiddleRowDoesNotCutOffLongUpperVocal() {
        val lines = overlap.toMutableList()
        lines[1] = lines[1].copy(sungUntilMs = 2_900)
        assertEquals(listOf(0, 2), activeLyricRows(lines, 3_500))
    }

    @Test fun backgroundVocalKeepsItsParentRowAnchored() {
        val lines = listOf(
            LyricLine(1_000, "lead", sungUntilMs = 1_900,
                background = LyricLine(1_500, "echo", sungUntilMs = 3_000)),
            LyricLine(2_000, "next", sungUntilMs = 4_000),
        )
        assertEquals(listOf(0, 1), activeLyricRows(lines, 2_500))
        assertEquals(listOf(1), activeLyricRows(lines, 3_000))
    }

    @Test fun plainLrcAdvancesWithoutInventingAnOverlap() {
        val lines = overlap.map { it.copy(sungUntilMs = null) }
        assertEquals(listOf(1), activeLyricRows(lines, 2_500))
    }

    @Test fun backwardSeekRecomputesTheAnchor() {
        assertEquals(listOf(2), activeLyricRows(overlap, 5_500))
        assertEquals(listOf(0), activeLyricRows(overlap, 1_500))
        assertEquals(emptyList<Int>(), activeLyricRows(overlap, 500))
    }

    @Test fun plainLineBoundaryJustBeforeAtAndJustAfter() {
        // No sung-until ends: the anchor is simply the last line whose start
        // the position has reached, and the boundary belongs to the new line.
        val lines = overlap.map { it.copy(sungUntilMs = null) }
        assertEquals(listOf(0), activeLyricRows(lines, 1_999))
        assertEquals(listOf(1), activeLyricRows(lines, 2_000))
        assertEquals(listOf(1), activeLyricRows(lines, 2_001))
        assertEquals(listOf(1), activeLyricRows(lines, 2_999))
        assertEquals(listOf(2), activeLyricRows(lines, 3_000))
    }

    @Test fun repeatedIdenticalLinesKeepSeparateIdentities() {
        // A chorus sung twice is two rows with the same text; the anchor
        // follows the index the position has reached, never the text.
        val lines = listOf(
            LyricLine(1_000, "same words", sungUntilMs = 4_000),
            LyricLine(5_000, "same words", sungUntilMs = 9_000),
        )
        assertEquals(listOf(0), activeLyricRows(lines, 2_000))
        assertEquals(listOf(1), activeLyricRows(lines, 6_000))
        assertEquals(listOf(0), activeLyricRows(lines, 2_000))
    }

    @Test fun malformedDataNeverAnchorsPhantomRows() {
        assertEquals(emptyList<Int>(), activeLyricRows(emptyList(), 0))
        assertEquals(emptyList<Int>(), activeLyricRows(emptyList(), 60_000))
        // An end before its own start: the row can still be the latest one
        // reached, but it never counts as still sounding for an earlier row.
        val lines = listOf(
            LyricLine(1_000, "broken", sungUntilMs = 500),
            LyricLine(2_000, "fine", sungUntilMs = 3_000),
        )
        assertEquals(listOf(1), activeLyricRows(lines, 2_500))
        assertEquals(emptyList<Int>(), activeLyricRows(lines, 999))
    }
}
