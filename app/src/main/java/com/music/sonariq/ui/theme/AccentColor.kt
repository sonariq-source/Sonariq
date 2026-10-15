package com.music.sonariq.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.music.sonariq.R

/**
 * Accent choices for the Sonariq visual system: amber, kelp, ice,
 * anemone, sulfur, and violet. Enum identifiers remain stable for saved-preference
 * compatibility while the displayed colors and labels use the new palette.
 */
enum class AccentColor(
    val darkArgb: Long,
    val lightArgb: Long,
) {
    // Identifiers retained for preference compatibility; the actual palette is
    // the Sonariq amber/kelp/ice/anemone/sulfur/violet system.
    LIME(0xFFFFB020, 0xFF9A5600),
    EMERALD(0xFF4FD1B5, 0xFF00796B),
    TEAL(0xFF7DC4FF, 0xFF005C99),
    GREEN(0xFFFF8FB1, 0xFFB0285A),
    YELLOW(0xFFE6E15A, 0xFF6B6A00),
    AMBER(0xFFB6A4FF, 0xFF5B45B8),
}
/**
 * Stored name back to an accent. Anything unknown — a fresh install, a
 * hand-edited backup, an entry from a build with a different set — falls back
 * to the brand lime rather than crashing the theme.
 */
fun accentColorFromStored(stored: String?): AccentColor =
    runCatching { AccentColor.valueOf(stored ?: "") }.getOrDefault(AccentColor.LIME)

@Composable
fun AccentColor.localizedLabel(): String = stringResource(
    when (this) {
        AccentColor.LIME -> R.string.accent_lime
        AccentColor.EMERALD -> R.string.accent_emerald
        AccentColor.TEAL -> R.string.accent_teal
        AccentColor.GREEN -> R.string.accent_green
        AccentColor.YELLOW -> R.string.accent_yellow
        AccentColor.AMBER -> R.string.accent_amber
    },
)
