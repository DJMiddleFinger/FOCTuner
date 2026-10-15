package app.foctuner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object FocTheme {
    val colors: FocColors
        @Composable @ReadOnlyComposable get() = LocalFocColors.current

    val type: FocType
        @Composable @ReadOnlyComposable get() = LocalFocType.current
}

/** Follows the system light/dark setting. Dynamic color is deliberately not used. */
@Composable
fun FocTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalFocColors provides colors, LocalFocType provides FocTypography) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = FocTypography.toMaterial(),
            shapes = FocShapes,
            content = content,
        )
    }
}

private val FocShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

// Material components read these; mapping every role keeps stock purple from leaking in anywhere.
private fun FocColors.toColorScheme() = (if (isDark) darkColorScheme() else lightColorScheme()).copy(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = surfaceHigh,
    onPrimaryContainer = text,
    inversePrimary = accent,
    secondary = textSecondary,
    onSecondary = background,
    secondaryContainer = surfaceHigh,
    onSecondaryContainer = text,
    tertiary = textSecondary,
    onTertiary = background,
    tertiaryContainer = surfaceHigh,
    onTertiaryContainer = text,
    background = background,
    onBackground = text,
    surface = background,
    onSurface = text,
    surfaceVariant = surfaceHigh,
    onSurfaceVariant = textSecondary,
    surfaceTint = Color.Transparent,
    inverseSurface = text,
    inverseOnSurface = background,
    error = fault,
    onError = background,
    errorContainer = faultContainer,
    onErrorContainer = text,
    outline = outline,
    outlineVariant = hairline,
    scrim = Color.Black.copy(alpha = 0.5f),
    surfaceBright = surfaceHigh,
    surfaceDim = background,
    surfaceContainerLowest = background,
    surfaceContainerLow = surface,
    surfaceContainer = surface,
    surfaceContainerHigh = surface,
    surfaceContainerHighest = surfaceHigh,
)
