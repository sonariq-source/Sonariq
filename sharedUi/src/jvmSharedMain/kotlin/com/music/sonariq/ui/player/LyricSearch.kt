package com.music.sonariq.ui.player

import com.music.sonariq.data.lyrics.LyricLine

/**
 * Pure search/seek helpers for the lyrics panel.
 *
 * No composition here — the panel keeps every gesture, scroll and animation,
 * and this file only answers "which lines match" and "where does this line
 * seek to", so the logic is unit-testable. Used by the in-lyrics search and
 * by tap-to-seek.
 *
 * Fork of https://github.com/kushagrasinghx/BitChord (GPL-3.0).
 */

/**
 * Indices of the lines whose text contains [query], case-insensitive.
 *
 * A blank query matches nothing: the panel shows no highlight until the
 * reader has typed something to look for, rather than lighting up the
 * whole song on open.
 */
fun searchLyricLines(lines: List<LyricLine>, query: String): List<Int> {
    if (query.isBlank()) return emptyList()
    return lines.mapIndexedNotNull { index, line ->
        index.takeIf { line.text.contains(query, ignoreCase = true) }
    }
}

/**
 * Steps [current] — an index into [matches], or -1 for "none yet" — forward,
 * wrapping past the last match back to the first. Returns -1 when there is
 * nothing to step through.
 */
fun nextSearchMatch(matches: List<Int>, current: Int): Int {
    if (matches.isEmpty()) return -1
    if (current < 0) return 0
    return (current + 1) % matches.size
}

/**
 * Steps [current] backward, wrapping past the first match to the last.
 * From "none yet" (-1) lands on the last match. Returns -1 when empty.
 */
fun prevSearchMatch(matches: List<Int>, current: Int): Int {
    if (matches.isEmpty()) return -1
    if (current < 0) return matches.size - 1
    return (current - 1 + matches.size) % matches.size
}

/**
 * The seek target for a tap on [line], or null when the line carries no
 * timing.
 *
 * Plain (unsynced) lyrics are reading, not seeking: a tap on an untimed
 * line must not jump the song to zero. Timed lines — including instrumental
 * gaps, which the providers stamp — seek to their stamp.
 */
fun lyricLineSeekMs(line: LyricLine): Long? = line.timeMs.takeIf { it > 0L }
