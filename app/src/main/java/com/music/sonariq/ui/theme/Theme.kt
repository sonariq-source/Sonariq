package com.music.sonariq.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.view.WindowCompat
import com.music.sonariq.R

// Sonariq identity. Legacy symbol names remain so the player and detail
// screens keep compiling; their values are the new sonar amber and kelp teal.
val AccentRed = Color(0xFFFF6B5E)
val SonariqLime = Color(0xFFFFB020)
val SonariqGreen = Color(0xFF4FD1B5)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB020),
    onPrimary = Color(0xFF1F1400),
    primaryContainer = Color(0xFF3A2A06),
    onPrimaryContainer = Color(0xFFFFDDA0),
    secondary = Color(0xFF4FD1B5),
    onSecondary = Color(0xFF00201A),
    tertiary = Color(0xFFCFE6F0),
    onTertiary = Color(0xFF0B1E27),
    background = Color(0xFF06121A),
    onBackground = Color(0xFFE8F2F5),
    surface = Color(0xFF0C1D28),
    onSurface = Color(0xFFE8F2F5),
    surfaceVariant = Color(0xFF132A38),
    onSurfaceVariant = Color(0xFF8FAAB8),
    outline = Color(0xFF24404F),
    outlineVariant = Color(0xFF173141),
    error = Color(0xFFFF6B5E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF9A5600),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE2B0),
    onPrimaryContainer = Color(0xFF2E1900),
    secondary = Color(0xFF00796B),
    onSecondary = Color.White,
    tertiary = Color(0xFF2B5870),
    onTertiary = Color.White,
    background = Color(0xFFF1F7F8),
    onBackground = Color(0xFF0B1E27),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B1E27),
    surfaceVariant = Color(0xFFE2EDF0),
    onSurfaceVariant = Color(0xFF4B6572),
    outline = Color(0xFFC3D5DB),
    outlineVariant = Color(0xFFD8E5E9),
    error = Color(0xFFB3261E),
)

// Tighter, more instrument-like corners than the previous soft Sonariq radii.
val SonariqShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Headline face: a bookish serif so titles read like record-sleeve typography. */
val SonariqDisplay = FontFamily(
    Font(R.font.caladea_regular, FontWeight.W400),
    Font(R.font.caladea_regular, FontWeight.W500),
    Font(R.font.caladea_bold, FontWeight.W600),
    Font(R.font.caladea_bold, FontWeight.W700),
)

// Kept for source compatibility with screens that still name the old family.
val SpaceGrotesk = SonariqDisplay

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.W400),
    Font(R.font.inter_medium, FontWeight.W500),
    Font(R.font.inter_semibold, FontWeight.W600),
    Font(R.font.inter_bold, FontWeight.W700),
)

private val SonariqTypography = Typography(
    displayLarge = TextStyle(fontFamily = SonariqDisplay, fontWeight = FontWeight.W700, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.8).sp),
    displayMedium = TextStyle(fontFamily = SonariqDisplay, fontWeight = FontWeight.W700, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp),
    displaySmall = TextStyle(fontFamily = SonariqDisplay, fontWeight = FontWeight.W700, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp),
    headlineLarge = TextStyle(fontFamily = SonariqDisplay, fontWeight = FontWeight.W700, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = SonariqDisplay, fontWeight = FontWeight.W700, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = SonariqDisplay, fontWeight = FontWeight.W700, fontSize = 21.sp, lineHeight = 25.sp, letterSpacing = (-0.15).sp),
    titleLarge = TextStyle(fontFamily = SonariqDisplay, fontWeight = FontWeight.W700, fontSize = 22.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W600, fontSize = 15.sp, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W600, fontSize = 13.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W400, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W400, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W400, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W600, fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W600, fontSize = 12.sp, letterSpacing = 0.1.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W600, fontSize = 11.sp, letterSpacing = 0.2.sp),
)

@Composable
fun SonariqTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: AccentColor = AccentColor.LIME,
    /**
     * A user-picked accent override for [accent]. When non-null it replaces
     * the preset's primary in both modes — normalised the same way, so a
     * near-black pick still reads as an accent. Null means [accent] as usual.
     * The dynamic artwork accent still wins over both; see [dynamicAccentArgb].
     */
    customAccentArgb: Int? = null,
    /**
     * Artwork-driven accent override for the dynamic theme. When non-null it
     * replaces [accent]'s primary in both modes — darkened for light mode so
     * white on-primary text keeps its contrast. Null means [accent] as usual.
     */
    dynamicAccentArgb: Int? = null,
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) {
        val primary = dynamicAccentArgb?.let { Color(accentForDark(it)) }
            ?: customAccentArgb?.let { Color(accentForDark(it)) }
            ?: Color(accent.darkArgb)
        DarkColors.copy(primary = primary, onPrimary = Color(0xFF14110A))
    } else {
        val primary = dynamicAccentArgb?.let { Color(accentForLight(it)) }
            ?: customAccentArgb?.let { Color(accentForLight(it)) }
            ?: Color(accent.lightArgb)
        LightColors.copy(primary = primary, onPrimary = Color.White)
    }
    MaterialTheme(colorScheme = scheme, typography = SonariqTypography, shapes = SonariqShapes, content = content)
}

/**
 * Keeps an extracted artwork color legible as the dark-mode primary: vivid
 * enough to read against near-black on-primary text, never so dark it sinks
 * into the midnight surfaces.
 */
private fun accentForDark(argb: Int): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.35f)
    hsv[2] = hsv[2].coerceIn(0.45f, 1f)
    return android.graphics.Color.HSVToColor(argb ushr 24, hsv)
}

/**
 * The light scheme's primary carries white on-primary text, so the artwork
 * color is darkened into the same readable band the fixed light accents live
 * in rather than being used raw.
 */
private fun accentForLight(argb: Int): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.5f)
    hsv[2] = (hsv[2] * 0.62f).coerceIn(0.3f, 0.75f)
    return android.graphics.Color.HSVToColor(argb ushr 24, hsv)
}

/**
 * Draws the status and navigation bar glyphs dark or light.
 *
 * `enableEdgeToEdge()` decides this from the *system* dark-mode setting, which
 * is the wrong input the moment the in-app theme disagrees with it: Light theme
 * on a phone in dark mode left white icons on a white bar, invisible. The bars
 * have to follow the content the app is actually painting. The player supplies
 * its own stable light-icon value and paints contrast behind it; every other
 * surface follows the theme. Hence a parameter rather than reading it here.
 */
@Composable
fun SystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = findWindow(view) ?: return
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = dark
            isAppearanceLightNavigationBars = dark
        }
    }
}
