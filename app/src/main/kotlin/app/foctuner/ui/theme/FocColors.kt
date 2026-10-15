package app.foctuner.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The whole palette. Contrast ratios against [background] are listed in docs/DESIGN.md; [accent] is
 * reserved for live data and the active selection, the semantic colors only ever mean ok/warning/fault.
 */
@Immutable
data class FocColors(
    val isDark: Boolean,
    val background: Color,
    /** Bars, input fields and anything that sits above the page. */
    val surface: Color,
    /** Pressed and selected rows, chips. */
    val surfaceHigh: Color,
    /** Dividers. Decorative, so it may sit below 3:1. */
    val hairline: Color,
    /** Boundaries of controls the user has to find (text fields, outlined buttons): at least 3:1. */
    val outline: Color,
    val text: Color,
    val textSecondary: Color,
    /** Units, hints, inactive navigation. Still AA for small text on every surface. */
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val ok: Color,
    val warning: Color,
    val warningContainer: Color,
    val fault: Color,
    val faultContainer: Color,
)

val DarkColors = FocColors(
    isDark = true,
    background = Color(0xFF131517),
    surface = Color(0xFF1A1D20),
    surfaceHigh = Color(0xFF23272B),
    hairline = Color(0xFF2B3034),
    outline = Color(0xFF68707A),
    text = Color(0xFFE4E7EA),
    textSecondary = Color(0xFFA2A9B0),
    textTertiary = Color(0xFF8A929A),
    accent = Color(0xFF4CC2DA),
    onAccent = Color(0xFF0B2A31),
    ok = Color(0xFF5DBE7E),
    warning = Color(0xFFE2A23B),
    warningContainer = Color(0xFF2D2416),
    fault = Color(0xFFF2695C),
    faultContainer = Color(0xFF3A1E1C),
)

val LightColors = FocColors(
    isDark = false,
    background = Color(0xFFF3F4F5),
    surface = Color(0xFFFAFAFB),
    surfaceHigh = Color(0xFFE5E8EB),
    hairline = Color(0xFFD7DBDF),
    outline = Color(0xFF878E96),
    text = Color(0xFF15181B),
    textSecondary = Color(0xFF454D55),
    textTertiary = Color(0xFF5F6770),
    accent = Color(0xFF006F88),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF1A7340),
    warning = Color(0xFF8F5A00),
    warningContainer = Color(0xFFFBF0DC),
    fault = Color(0xFFB42A20),
    faultContainer = Color(0xFFFBE9E7),
)

internal val LocalFocColors = staticCompositionLocalOf { DarkColors }
