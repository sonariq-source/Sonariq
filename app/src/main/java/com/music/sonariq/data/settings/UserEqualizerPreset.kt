package com.music.sonariq.data.settings

import com.music.sonariq.playback.EqLayout
import kotlin.math.abs

/**
 * A manual-tab curve the user named and kept: seven gains in decibels, low to
 * high, matching [EqLayout.MANUAL_BANDS_HZ].
 *
 * The fixed [EqualizerPreset][com.music.sonariq.playback.EqualizerPreset] enum
 * is deliberately left alone — it is the factory starting points, and a user
 * curve is data, not a new enum entry. These live in the settings store beside
 * the bands themselves.
 */
data class UserEqualizerPreset(val name: String, val bandsDb: List<Float>)

/**
 * How many user presets are kept. One entry is a name and seven numbers, so
 * the whole list is a couple of kilobytes; past this the oldest saved goes,
 * which is documented on the save path rather than a silent surprise.
 */
const val MAX_USER_EQ_PRESETS = 50

/** Names are shown in rows and sheets; longer than this is a paragraph, not a name. */
const val MAX_PRESET_NAME_CHARS = 40

/**
 * Two band lists describe the same curve when every band agrees to a
 * twentieth of a decibel — the same tolerance
 * [EqualizerPreset.matching][com.music.sonariq.playback.EqualizerPreset.Companion.matching]
 * uses, so a restored curve names its preset the same way a dragged one does.
 */
fun eqBandsMatch(a: List<Float>, b: List<Float>): Boolean =
    a.size == b.size && a.indices.all { abs(a[it] - b[it]) < 0.05f }

/**
 * Makes a name safe for the [encodeUserEqualizerPresets] format and for
 * display: trimmed, single-spaced, capped, and stripped of the two control
 * characters the format uses as separators.
 */
fun sanitizePresetName(name: String): String =
    name.trim()
        .replace(WHITESPACE_RUN, " ")
        .replace(RECORD_SEPARATOR, " ")
        .replace(UNIT_SEPARATOR, " ")
        .take(MAX_PRESET_NAME_CHARS)

/**
 * Inserts the named preset, replacing any earlier one with the same name so
 * saving twice does not pile up duplicates. Newest goes last; past
 * [MAX_USER_EQ_PRESETS] the oldest saved goes first. A blank name after
 * sanitising refuses the save by returning the list unchanged.
 *
 * Pure list surgery so the save path is unit-testable without a
 * SharedPreferences — [AppSettings.saveUserEqualizerPreset] persists whatever
 * this returns.
 */
fun upsertUserPreset(
    presets: List<UserEqualizerPreset>,
    name: String,
    bandsDb: List<Float>,
): List<UserEqualizerPreset> {
    val clean = sanitizePresetName(name)
    if (clean.isEmpty()) return presets
    val entry = UserEqualizerPreset(
        clean,
        List(EqLayout.MANUAL_COUNT) {
            bandsDb.getOrElse(it) { 0f }.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        },
    )
    return (presets.filterNot { it.name == clean } + entry).takeLast(MAX_USER_EQ_PRESETS)
}

/**
 * Removes the named preset, or returns the list unchanged when it isn't
 * there. The bands are untouched — deleting a preset un-names the curve, it
 * does not flatten it.
 */
fun removeUserPreset(presets: List<UserEqualizerPreset>, name: String): List<UserEqualizerPreset> =
    presets.filterNot { it.name == name }

/**
 * Serialises the list to one preference string: entries separated by
 * [RECORD_SEPARATOR], each a sanitised name, a [UNIT_SEPARATOR], and seven
 * comma-separated gains. One string rather than a set of keys so a backup
 * carries the presets the way it carries the bands — see
 * [AppSettings.setEqualizerBands].
 */
fun encodeUserEqualizerPresets(presets: List<UserEqualizerPreset>): String =
    presets.take(MAX_USER_EQ_PRESETS).joinToString(RECORD_SEPARATOR) { preset ->
        sanitizePresetName(preset.name) + UNIT_SEPARATOR +
            preset.bandsDb.take(EqLayout.MANUAL_COUNT).joinToString(",")
    }

/**
 * Parses [encodeUserEqualizerPresets]' output back. Malformed entries are
 * dropped rather than failing the whole list, and short gain lists are padded
 * with flat bands the way [AppSettings.readEqualizerBands] pads a backup from
 * a build with a different band count — one corrupt entry must not eat the
 * other forty-nine.
 */
fun decodeUserEqualizerPresets(stored: String?): List<UserEqualizerPreset> {
    if (stored.isNullOrEmpty()) return emptyList()
    return stored.split(RECORD_SEPARATOR).mapNotNull { record ->
        val rawName = record.substringBefore(UNIT_SEPARATOR)
        val gains = record.substringAfter(UNIT_SEPARATOR, "")
            .split(",")
            .mapNotNull { it.trim().toFloatOrNull() }
        if (rawName.isBlank() || gains.isEmpty()) return@mapNotNull null
        val bands = List(EqLayout.MANUAL_COUNT) {
            gains.getOrElse(it) { 0f }.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        }
        UserEqualizerPreset(sanitizePresetName(rawName), bands)
    }.take(MAX_USER_EQ_PRESETS)
}

private const val RECORD_SEPARATOR = "\u001E"
private const val UNIT_SEPARATOR = "\u001F"
private val WHITESPACE_RUN = Regex("\\s+")
