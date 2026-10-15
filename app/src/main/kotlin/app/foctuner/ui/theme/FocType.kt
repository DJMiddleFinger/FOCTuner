package app.foctuner.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.foctuner.R

val PlexSans = FontFamily(
    Font(R.font.plex_sans_regular, FontWeight.Normal),
    Font(R.font.plex_sans_medium, FontWeight.Medium),
    Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
)

/** Every glyph has the same advance, so numbers don't shift sideways as they change. */
val PlexMono = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
)

@Immutable
data class FocType(
    val title: TextStyle,
    /** Section labels: small caps-height, tracked out, never bold. */
    val heading: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val supporting: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    /** Primary readouts on the overview; sized to be read at arm's length. */
    val readoutLarge: TextStyle,
    val readout: TextStyle,
    val value: TextStyle,
    val code: TextStyle,
    val unit: TextStyle,
    val unitLarge: TextStyle,
)

private val centeredLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun sans(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = PlexSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    lineHeightStyle = centeredLines,
)

private fun mono(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = PlexMono,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    lineHeightStyle = centeredLines,
)

val FocTypography = FocType(
    title = sans(20, 28, FontWeight.SemiBold),
    heading = sans(12, 16, FontWeight.Medium).copy(letterSpacing = 0.08.em),
    body = sans(16, 24),
    bodyStrong = sans(16, 24, FontWeight.Medium),
    supporting = sans(14, 20),
    label = sans(14, 20, FontWeight.Medium),
    caption = sans(12, 16),
    readoutLarge = mono(44, 48, FontWeight.Medium).copy(letterSpacing = (-0.04).em),
    readout = mono(28, 36).copy(letterSpacing = (-0.02).em),
    value = mono(16, 24),
    code = mono(13, 20),
    unit = sans(14, 20),
    unitLarge = sans(18, 24),
)

internal val LocalFocType = staticCompositionLocalOf { FocTypography }

internal fun FocType.toMaterial() = Typography(
    displayLarge = readoutLarge,
    headlineMedium = title,
    titleLarge = title,
    titleMedium = bodyStrong,
    titleSmall = label,
    bodyLarge = body,
    bodyMedium = supporting,
    bodySmall = caption,
    labelLarge = label,
    labelMedium = caption.copy(fontWeight = FontWeight.Medium),
    labelSmall = caption,
)
