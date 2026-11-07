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

// Sonariq identity: the emerald system. Legacy symbol names remain so the
// player and detail screens keep compiling; their values are emerald-family.
val AccentRed = Color(0xFF35E887)
val SonariqLime = Color(0xFF1DB954)
val SonariqGreen = Color(0xFF35E887)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF1DB954),
    onPrimary = Color(0xFF04140B),
    primaryContainer = Color(0xFF0E2E1B),
    onPrimaryContainer = Color(0xFFB9F5D2),
    secondary = Color(0xFF35E887),
    onSecondary = Color(0xFF00201A),
    tertiary = Color(0xFF8EF5BD),
    onTertiary = Color(0xFF0E2417),
    background = Color(0xFF080D0B),
    onBackground = Color(0xFFF5FAF6),
    surface = Color(0xFF0D1511),
    onSurface = Color(0xFFF5FAF6),
    surfaceVariant = Color(0xFF121D17),
    surfaceContainer = Color(0xFF121D17),
    surfaceContainerHigh = Color(0xFF17251D),
    onSurfaceVariant = Color(0xFFA1B0A6),
    outline = Color(0xFF26362B),
    outlineVariant = Color(0xFF121D17),
    error = Color(0xFFFF6B5E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF147A3D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F2D8),
    onPrimaryContainer = Color(0xFF0A2415),
    secondary = Color(0xFF0E7A44),
    onSecondary = Color.White,
    tertiary = Color(0xFF1F5C3D),
    onTertiary = Color.White,
    background = Color(0xFFEFF7F1),
    onBackground = Color(0xFF0E2417),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0E2417),
    surfaceVariant = Color(0xFFDCEEE2),
    onSurfaceVariant = Color(0xFF4E6355),
    outline = Color(0xFFBFD6C7),
    outlineVariant = Color(0xFFD5E8DB),
    error = Color(0xFFB3261E),
)

// Smooth, glass-like corners shared by every surface in the emerald system.
val SonariqShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
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
        DarkColors.copy(primary = primary, onPrimary = Color(0xFF04140B))
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
