package com.music.sonariq.ui.player

import com.music.sonariq.data.lyrics.LyricLine

/**
 * The index of the line current at [positionMs]: the last line whose
 * stamp has been reached, or -1 before the first line.
 *
 * A binary search over the lines' stamps, which every provider hands
 * over sorted — the per-frame lookups (the panel, the strip, the
 * scroll lead) ask this on every frame of playback, and a linear scan
 * made each frame cost a walk of the whole song. Identical answers to
 * the scan it replaces, including on duplicate stamps (the last of
 * them wins, as it did before).
 */
internal fun List<LyricLine>.lineIndexAt(positionMs: Long): Int {
    var low = 0
    var high = size - 1
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid].timeMs <= positionMs) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}

/** Keep every unfinished vocal visible, including overlaps spanning more than two rows. */
internal fun activeLyricRows(lines: List<LyricLine>, positionMs: Long): List<Int> {
    val latest = lines.lineIndexAt(positionMs)
    if (latest < 0) return emptyList()
    return (0..latest).filter { index ->
        val line = lines[index]
        index == latest || (!line.isGap &&
            (line.hasKnownEnd || line.background?.hasKnownEnd == true) &&
            line.timeMs <= positionMs && positionMs < line.endMs)
    }
}
