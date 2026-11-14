package com.music.sonariq.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The audio-to-lyrics unit boundary: conversions truncate toward zero
 * (a position is a point already reached, never rounded up into the
 * future) and the round trip loses nothing but the sub-millisecond
 * remainder.
 */
class TimeUnitsTest {

    @Test
    fun microsecondsConvertByTruncationNotRounding() {
        assertEquals(0L, microsecondsToMilliseconds(0L))
        assertEquals(0L, microsecondsToMilliseconds(999L))
        assertEquals(1L, microsecondsToMilliseconds(1_000L))
        assertEquals(1L, microsecondsToMilliseconds(1_999L))
        assertEquals(123_456L, microsecondsToMilliseconds(123_456_789L))
        assertEquals(-1L, microsecondsToMilliseconds(-1_999L))
    }

    @Test
    fun millisecondsConvertExactly() {
        assertEquals(0L, millisecondsToMicroseconds(0L))
        assertEquals(1_000L, millisecondsToMicroseconds(1L))
        assertEquals(-2_500_000L, millisecondsToMicroseconds(-2_500L))
    }

    @Test
    fun roundTripLosesOnlyTheSubMillisecondRemainder() {
        for (microseconds in listOf(0L, 1L, 999L, 1_001L, 250_300L, 3_599_999_999L)) {
            val back = millisecondsToMicroseconds(microsecondsToMilliseconds(microseconds))
            assertTrue(back <= microseconds && microseconds - back < 1_000L)
        }
    }
}
