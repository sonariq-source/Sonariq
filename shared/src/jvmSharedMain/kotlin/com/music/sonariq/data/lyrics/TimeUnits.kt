package com.music.sonariq.data.lyrics

/**
 * The unit boundary between the audio layer and the lyrics layer.
 *
 * Everything the lyrics engine touches — line stamps, word spans, offsets,
 * playback positions — is whole milliseconds in a [Long]. The audio layer
 * underneath works in microseconds (and nanoseconds for clock stamps),
 * because that is what the platform's audio APIs speak. These two helpers
 * are the only sanctioned way across that boundary: converting anywhere
 * else invites the classic mix-ups (a microsecond value read as
 * milliseconds lands a word a thousand times too late), and rounding
 * anywhere but here accumulates.
 *
 * [microsecondsToMilliseconds] truncates toward zero rather than rounding:
 * a position is a point the playhead has *reached*, so 1_999 µs is 1 ms of
 * song heard, not 2. Rounding to nearest would let the lyrics run up to
 * half a millisecond *ahead* of the audio on every conversion.
 */
fun microsecondsToMilliseconds(microseconds: Long): Long = microseconds / 1_000L

/** The exact inverse of [microsecondsToMilliseconds]; lossless. */
fun millisecondsToMicroseconds(milliseconds: Long): Long = milliseconds * 1_000L
