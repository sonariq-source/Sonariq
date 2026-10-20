package com.music.sonariq.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricGapsTest {

    /**
     * A line whose singing end is known: [LyricLine.hasKnownEnd] holds
     * because [LyricLine.sungUntilMs] is set. Breaks are only ever drawn off
     * lines like this — see [withInstrumentalGaps].
     */
    private fun endedLine(timeMs: Long, sungUntilMs: Long, text: String = "line") =
        LyricLine(timeMs = timeMs, text = text, sungUntilMs = sungUntilMs)

    @Test
    fun fourSecondSilenceIsNotAGap() {
        val lines = listOf(
            endedLine(0L, 1_000L, "first"),
            endedLine(5_000L, 6_000L, "second"),
        )
        val out = lines.withInstrumentalGaps()
        assertEquals(2, out.size)
        assertFalse(out.any { it.isGap })
    }

    @Test
    fun sixSecondSilenceIsAGap() {
        val lines = listOf(
            endedLine(0L, 1_000L, "first"),
            endedLine(7_000L, 8_000L, "second"),
        )
        val out = lines.withInstrumentalGaps()
        assertEquals(3, out.size)
        val gap = out[1]
        assertTrue(gap.isGap)
        // The marker sits where the singing stopped, not where it resumes.
        assertEquals(1_000L, gap.timeMs)
    }

    @Test
    fun silenceWithoutAKnownEndIsNotAGap() {
        // No words and no sungUntilMs: the distance to the next stamp is the
        // line's own slot, not evidence of a break.
        val lines = listOf(
            LyricLine(0L, "first"),
            LyricLine(30_000L, "second"),
        )
        val out = lines.withInstrumentalGaps()
        assertFalse(out.any { it.isGap })
    }

    @Test
    fun introGapWhenFirstLineStartsAtOrAfterFiveSeconds() {
        val atThreshold = listOf(endedLine(5_000L, 6_000L))
        val outAt = atThreshold.withInstrumentalGaps()
        assertEquals(2, outAt.size)
        assertTrue(outAt.first().isGap)
        assertEquals(0L, outAt.first().timeMs)

        val late = listOf(endedLine(9_000L, 10_000L))
        assertTrue(late.withInstrumentalGaps().first().isGap)
    }

    @Test
    fun noIntroGapWhenFirstLineStartsBeforeFiveSeconds() {
        val lines = listOf(endedLine(4_000L, 5_000L))
        val out = lines.withInstrumentalGaps()
        assertEquals(1, out.size)
        assertFalse(out.first().isGap)
    }
}
