package com.music.sonariq.data.lyrics

/**
 * Proportional word-timing interpolation for line-synced lyrics.
 *
 * Most synced lyrics only stamp whole lines — LRCLIB, KuGou, Musixmatch and
 * the like never say when a word inside the line is sung. The karaoke sweep,
 * though, runs on words ([LyricLine.revealedChars]), so without this those
 * lines can only light up whole while their word-synced neighbours sweep.
 *
 * The technique is the standard one: a line's window — its stamp to its
 * stated end ([LyricLine.sungUntilMs]), or to the next line's stamp — is
 * divided among its words in proportion to their character counts. Longer
 * words get more time, which is roughly how singing works: a held "golden"
 * stretches past a rattled-off "to the".
 *
 * The next line's stamp is an upper bound, not a duration. A line before an
 * instrumental break is routinely ten seconds from the next stamp while being
 * sung over three of them; spreading its words across the whole window made
 * the sweep crawl at a third of the singing speed. So a stamp-derived window
 * is capped at what the line plausibly takes to sing — the learned pace with
 * room for held notes, and never past [MAX_INTERPOLATED_WINDOW_MS]. A
 * genuinely stated end is trusted as-is; it was measured, not guessed.
 *
 * What this is not: measured timing. Every word it produces is marked
 * [LyricLine.wordsEstimated], the result's [LyricTiming] becomes
 * [LyricTiming.WORD_SYNCED_ESTIMATED], and the player says so on the
 * provenance indicator. An estimate that presented itself as measured would
 * be a lie about the music; this one arrives labelled.
 *
 * Plain (unstamped) lyrics are left alone — with no clock there is nothing
 * proportional to be proportional *to*. Section headers ("[Chorus]") are
 * labels, not sung words, and are skipped too.
 */
fun List<LyricLine>.withInterpolatedWordTimings(): List<LyricLine> {
    // No stamps anywhere: nothing to interpolate from.
    if (none { it.timeMs > 0 }) return this

    // The next sung line's stamp per line, and each line's raw window end.
    // A stated end identical to the next stamp is a provider echoing the stamp
    // (PaxSenix copies it across), not a measured duration — treat it as
    // unstated so the cap below still applies.
    val nextStamps = mapIndexed { index, line ->
        drop(index + 1).firstOrNull { it.timeMs > line.timeMs }?.timeMs
    }
    val rawEnds = mapIndexed { index, line ->
        val stated = line.sungUntilMs?.takeIf { it > line.timeMs }
        val next = nextStamps[index]
        if (stated != null && stated != next) stated else next
    }
    // True where the window came from a stamp rather than a measured end.
    val fromStamp = BooleanArray(size) { i ->
        val stated = this[i].sungUntilMs?.takeIf { it > this[i].timeMs }
        stated == null || stated == nextStamps[i]
    }

    // Learn the singing pace robustly: the median per-line ms/char, so the odd
    // line stretched across an instrumental gap can't drag it the way a mean
    // would — one twenty-second window would otherwise speak for the song.
    val paceSamples = mutableListOf<Double>()
    forEachIndexed { index, line ->
        val end = rawEnds[index] ?: return@forEachIndexed
        if (line.needsInterpolation && end > line.timeMs) {
            paceSamples += (end - line.timeMs).toDouble() / line.text.length
        }
    }
    val paceMsPerChar = paceSamples.sorted().let { sorted ->
        if (sorted.isEmpty()) FALLBACK_MS_PER_CHAR else sorted[sorted.size / 2]
    }

    return mapIndexed { index, line ->
        if (!line.needsInterpolation) return@mapIndexed line
        val chars = line.text.length
        // What the line plausibly takes to sing: the learned pace with room
        // for held notes, and never past the backstop. The median pace already
        // carries the usual breath between lines, so 1.5x is real headroom.
        val paceCap = (chars * paceMsPerChar * PACE_HEADROOM).toLong()
            .coerceAtMost(MAX_INTERPOLATED_WINDOW_MS)
        val span = when (val end = rawEnds[index]) {
            null -> (chars * paceMsPerChar).toLong()
                .coerceIn(MIN_ESTIMATED_LINE_MS, MAX_ESTIMATED_LINE_MS)
            else -> {
                val raw = maxOf(end - line.timeMs, 1L)
                if (fromStamp[index]) minOf(raw, maxOf(paceCap, 1L)) else raw
            }
        }
        line.copy(
            words = interpolateWords(line.text, line.timeMs, span),
            wordsEstimated = true,
            background = line.background?.let { bg ->
                // The answering vocal gets the same treatment inside its own stamps.
                if (bg.needsInterpolation) {
                    val bgEnd = bg.sungUntilMs?.takeIf { it > bg.timeMs }
                        ?: (line.timeMs + span)
                    bg.copy(
                        words = interpolateWords(
                            bg.text,
                            bg.timeMs,
                            maxOf(bgEnd - bg.timeMs, 1L),
                        ),
                        wordsEstimated = true,
                    )
                } else bg
            },
        )
    }
}

/** A line that can take interpolated words: stamped, unsplit, sung, not a label. */
private val LyricLine.needsInterpolation: Boolean
    get() = words.isEmpty() &&
        text.isNotBlank() &&
        timeMs >= 0 &&
        !isSectionHeader

/** "[Chorus]", "[Verse 1]" — a label for the section, not a sung line. */
private val LyricLine.isSectionHeader: Boolean
    get() = text.trim().let { it.length > 2 && it.startsWith("[") && it.endsWith("]") }

/**
 * Splits [text] into words and spreads [spanMs] across them by character
 * count. Boundaries are contiguous — each word ends where the next begins —
 * so the sweep never jumps, and the last word lands exactly on the window's
 * end rather than drifting short on rounding.
 */
private fun interpolateWords(text: String, startMs: Long, spanMs: Long): List<LyricWord> {
    val tokens = text.split(WHITESPACE).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return emptyList()
    val total = tokens.sumOf { it.length }.coerceAtLeast(1)
    val edges = LongArray(tokens.size + 1)
    var chars = 0
    tokens.forEachIndexed { i, token ->
        edges[i] = startMs + chars * spanMs / total
        chars += token.length
    }
    edges[tokens.size] = startMs + spanMs
    return tokens.mapIndexed { i, token ->
        LyricWord(startMs = edges[i], endMs = edges[i + 1], text = token)
    }
}

private val WHITESPACE = "\\s+".toRegex()

/** Singing pace when no sibling line teaches it: ~80 ms per character. */
private const val FALLBACK_MS_PER_CHAR = 80.0

/** Bounds for a window sized from the fallback pace rather than a real end. */
private const val MIN_ESTIMATED_LINE_MS = 500L
private const val MAX_ESTIMATED_LINE_MS = 12_000L

/**
 * Headroom over the learned pace when capping a stamp-derived window: the
 * median pace already carries the usual breath between lines, so this is room
 * for genuinely held notes, not for silence.
 */
private const val PACE_HEADROOM = 1.5

/**
 * Backstop: no estimated line-sing runs longer than this, however far the
 * next stamp is. Catches sparse songs whose stamps imply a pace no singer
 * actually sings at.
 */
private const val MAX_INTERPOLATED_WINDOW_MS = 8_000L
