package com.music.sonariq.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.music.sonariq.R

/**
 * Accent choices for the Sonariq visual system: shades of the emerald
 * family. Enum identifiers remain stable for saved-preference compatibility
 * while the displayed colors use the emerald palette.
 */
enum class AccentColor(
    val darkArgb: Long,
    val lightArgb: Long,
) {
    // Identifiers retained for preference compatibility; the actual palette
    // is the emerald family, from deep emerald to pale mint.
    LIME(0xFF1DB954, 0xFF147A3D),
    EMERALD(0xFF35E887, 0xFF0E7A44),
    TEAL(0xFF3ECF8E, 0xFF177A52),
    GREEN(0xFF8EF5BD, 0xFF2E7D46),
    YELLOW(0xFFB9E769, 0xFF5F7A12),
    AMBER(0xFF7ED491, 0xFF3E7A34),
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
