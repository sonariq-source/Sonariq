package com.music.sonariq.playback.smart

/**
 * Musical-key compatibility on the Camelot wheel, shared by the transition
 * policy, the transition planner and Autoplay's candidate scorer, so all
 * three judge a pair of keys the same way.
 *
 * Keys arrive as the analyzer spells them: a pitch name (sharps and flats
 * as ♯/♭) and a mode word, e.g. "C major" or "A minor". Anything that does
 * not parse is unknown, and unknown is never compatible and never a clash:
 * an unread key is no evidence either way.
 *
 * The wheel: each key is a number 1..12 around the circle of fifths and a
 * letter — B for major, A for minor. Two keys are *compatible* when a DJ
 * can blend them without retuning either: the same code, the relative
 * major/minor pair (same number, other letter), or one step around the
 * wheel in the same letter (a perfect fifth away). Wider moves score
 * partially in [camelotScore] — a two-step energy lift is a real move, just
 * not a blend — and the far side of the wheel scores nothing.
 */

/** One key's place on the Camelot wheel: [wheel] 1..12, [major] for the B side. */
data class CamelotKey(val wheel: Int, val major: Boolean)

private val PITCH_INDEX = mapOf(
    "C" to 0, "C♯" to 1, "D♭" to 1, "D" to 2, "D♯" to 3, "E♭" to 3,
    "E" to 4, "F" to 5, "F♯" to 6, "G♭" to 6, "G" to 7, "G♯" to 8,
    "A♭" to 8, "A" to 9, "A♯" to 10, "B♭" to 10, "B" to 11,
)

/**
 * The Camelot code for an analyzer key string, or null when the string
 * does not name a key this understands.
 */
fun camelotKeyOf(key: String): CamelotKey? {
    val parts = key.trim().split(Regex("\\s+"))
    if (parts.size < 2) return null
    val pitch = PITCH_INDEX[parts[0]] ?: return null
    val major = when (parts[1].lowercase()) {
        "major", "maj" -> true
        "minor", "min" -> false
        else -> return null
    }
    // Position on the circle of fifths, C at 0. The Camelot number is that
    // position rotated: major keys start counting at C = 8B, minor at
    // A = 8A, which are the same rotation seen from each side.
    val fifths = (7 * pitch) % 12
    val wheel = if (major) (fifths + 7) % 12 + 1 else (fifths + 4) % 12 + 1
    return CamelotKey(wheel, major)
}

/** Steps between two wheel numbers, the short way around. */
private fun wheelDistance(a: Int, b: Int): Int {
    val raw = kotlin.math.abs(a - b) % 12
    return minOf(raw, 12 - raw)
}

/**
 * How well two key strings mix, 0..1, or null when either key is unknown.
 *
 * Same code 1.0; relative major/minor 0.95; one wheel step in the same
 * letter 0.85; the diagonal one-step moves 0.5; a two-step same-letter
 * lift 0.4; three steps 0.1; the far side of the wheel 0.
 */
fun camelotScore(left: String, right: String): Double? {
    val a = camelotKeyOf(left) ?: return null
    val b = camelotKeyOf(right) ?: return null
    val distance = wheelDistance(a.wheel, b.wheel)
    return when {
        a.major == b.major && distance == 0 -> 1.0
        a.major != b.major && distance == 0 -> 0.95
        a.major == b.major && distance == 1 -> 0.85
        a.major != b.major && distance == 1 -> 0.5
        a.major == b.major && distance == 2 -> 0.4
        distance == 3 -> 0.1
        else -> 0.0
    }
}

/** The score at or above which two keys count as blend-compatible. */
const val CAMELOT_COMPATIBLE_SCORE = 0.8

/**
 * Whether two key strings are Camelot-compatible (same code, relative
 * pair, or one wheel step). Unknown keys are never compatible.
 */
fun camelotCompatible(left: String, right: String): Boolean =
    (camelotScore(left, right) ?: 0.0) >= CAMELOT_COMPATIBLE_SCORE

/**
 * Below this the analyzer's key is treated as no key at all. Shared by the
 * policy and the planner so a key one of them distrusts cannot authorize
 * — or veto — anything in the other.
 */
const val MIN_KEY_CONFIDENCE = 0.25

/** A track's key as far as mixing decisions may trust it, or null. */
fun trustedKeyOf(analysis: TrackAnalysis): String? =
    analysis.key.takeIf { it.isNotBlank() && analysis.keyConfidence >= MIN_KEY_CONFIDENCE }

/**
 * Whether two analyses carry trusted keys that clash: both keys are known
 * and neither is Camelot-compatible with the other. A key either side
 * lacks, or lacks the confidence to state, makes this false — absence of
 * evidence never vetoes a blend.
 */
fun keyClashBetween(outgoing: TrackAnalysis, incoming: TrackAnalysis): Boolean {
    val left = trustedKeyOf(outgoing) ?: return false
    val right = trustedKeyOf(incoming) ?: return false
    val score = camelotScore(left, right) ?: return false
    return score < CAMELOT_COMPATIBLE_SCORE
}
