package app.foctuner.ui.ridedash

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.foctuner.R

/**
 * Everything that differs between the two appearances: colours, corner radii and type faces.
 * [Classic] is the original look. Read it through [Palette] so call sites stay short.
 */
@Immutable
class AppStyle(
    val emoto: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val outline: Color,
    val fg: Color,
    val textDim: Color,
    val warn: Color,
    val danger: Color,
    val good: Color,
    val defaultAccent: Color,
    /** Dialog background. */
    val dialog: Color,
    /** Top bar and side rail (the E-moto display). */
    val chrome: Color,
    /** Power/torque channel colour. */
    val cyan: Color,
    /** Regen/brake channel colour and the active-tab marker. */
    val red: Color,
    val cardRadius: Dp,
    val innerRadius: Dp,
    /** Face for titles, captions and big numbers; null = the default sans. */
    val display: FontFamily?,
) {
    companion object {
        val Classic = AppStyle(
            emoto = false,
            bg = Color(0xFF08080A), surface = Color(0xFF131316), surface2 = Color(0xFF1C1C21), outline = Color(0xFF2B2B32),
            fg = Color(0xFFF2F2F5), textDim = Color(0xFF8B8B96),
            warn = Color(0xFFFFB020), danger = Color(0xFFFF3B30), good = Color(0xFF30D158), defaultAccent = Color(0xFFFF6A00),
            dialog = Color(0xFF1C1C21), chrome = Color(0xFF131316),
            cyan = Color(0xFF29B6F6), red = Color(0xFFFF3B30),
            cardRadius = 20.dp, innerRadius = 14.dp, display = null,
        )

    }
}

val LocalAppStyle = staticCompositionLocalOf { AppStyle.Classic }

/** The active appearance's colours. Classic's values are the originals; the E-moto display's swap in through [LocalAppStyle]. */
object Palette {
    val Bg: Color @Composable @ReadOnlyComposable get() = LocalAppStyle.current.bg
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalAppStyle.current.surface
    val Surface2: Color @Composable @ReadOnlyComposable get() = LocalAppStyle.current.surface2
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalAppStyle.current.outline
    val Fg: Color @Composable @ReadOnlyComposable get() = LocalAppStyle.current.fg
    val TextDim: Color @Composable @ReadOnlyComposable get() = LocalAppStyle.current.textDim
    val Warn: Color @Composable @ReadOnlyComposable get() = LocalAppStyle.current.warn
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalAppStyle.current.danger
    val Good: Color @Composable @ReadOnlyComposable get() = LocalAppStyle.current.good
    val DefaultAccent: Color @Composable @ReadOnlyComposable get() = LocalAppStyle.current.defaultAccent
    val Dialog: Color @Composable @ReadOnlyComposable get() = LocalAppStyle.current.dialog
    val Chrome: Color @Composable @ReadOnlyComposable get() = LocalAppStyle.current.chrome
    val Cyan: Color @Composable @ReadOnlyComposable get() = LocalAppStyle.current.cyan
    val Red: Color @Composable @ReadOnlyComposable get() = LocalAppStyle.current.red
    val emoto: Boolean @Composable @ReadOnlyComposable get() = LocalAppStyle.current.emoto
    val cardShape: RoundedCornerShape @Composable @ReadOnlyComposable get() = RoundedCornerShape(LocalAppStyle.current.cardRadius)
    val innerShape: RoundedCornerShape @Composable @ReadOnlyComposable get() = RoundedCornerShape(LocalAppStyle.current.innerRadius)
    val display: FontFamily? @Composable @ReadOnlyComposable get() = LocalAppStyle.current.display
}

/** Wide geometric face used by the landscape ride screen (Michroma, SIL OFL). */
val WideFont = FontFamily(Font(R.font.michroma))

/** Heavier wide face for the E-moto display's big digits (Krona One, SIL OFL). */
val HeavyWideFont = FontFamily(Font(R.font.krona_one))

/** Tabular figures so numbers don't jitter as they change. */
val NumberStyle = TextStyle(fontFeatureSettings = "tnum")

/**
 * What dashboard widgets draw with. The Dash tab uses [dash], the app palette at fixed sizes;
 * the ride screen provides its own colours and face, and sizes text from each widget's box.
 */
class WidgetTheme(
    val surface: Color,
    val fg: Color,
    val dim: Color,
    val outline: Color,
    val warn: Color,
    val danger: Color,
    val number: TextStyle,
    val label: TextStyle,
    /** Text and padding grow with the widget's box instead of using the Dash tab's fixed sizes. */
    val scaleToBox: Boolean,
    /** Gauges, bars and graph lines carry a soft halo in their own colour. */
    val glow: Boolean = false,
    /** Dash-tab size of the caption above a widget, in sp. */
    val captionSp: Float = 11f,
    /** The wide digits run bigger than the default ones, so the Dash tab shrinks them a little. */
    val digitScale: Float = 1f,
) {
    /** [dash] on the Dash tab; on the ride screen [frac] of [ref] (a length from the widget), kept within [min]..[max]. */
    fun scale(dash: Float, ref: Float, frac: Float, min: Float = 0f, max: Float = Float.MAX_VALUE): Float =
        if (scaleToBox) (ref * frac).coerceIn(min, max) else dash

    /** Label size: 11 sp on the Dash tab; on the ride screen [frac] of [ref], but always a small caption. */
    fun labelSize(ref: Float, frac: Float): Float = scale(captionSp, ref, frac, min = 9f, max = 13f)

    companion object {
        fun dash(s: AppStyle) =
            WidgetTheme(
                s.surface, s.fg, s.textDim, s.outline, s.warn, s.danger,
                NumberStyle, TextStyle(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp), scaleToBox = false,
            )
    }
}

val LocalWidgetTheme = staticCompositionLocalOf { WidgetTheme.dash(AppStyle.Classic) }

