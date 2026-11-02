package app.foctuner.ui.settings

import android.text.Html
import app.foctuner.config.ParamDefinition
import app.foctuner.config.ParamType
import app.foctuner.config.ParamValue
import app.foctuner.ui.components.fixed
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/*
 * How VESC Tool's editors show a value: multiplied by `editorScale`, with `editorDecimalsDouble` decimals and the
 * parameter's suffix. A double with `editAsPercentage` is instead a whole percentage of the larger of |min| and
 * |max|, and `editorScale` doesn't apply (ParamEditDouble), so 0.15 of 0..1 is 15 % and −0.5 of −5..5 is −10 %.
 */

private val ParamDefinition.percentage: Boolean get() = editAsPercentage && type == ParamType.DOUBLE

internal val ParamDefinition.displayScale: Double
    get() {
        if (!percentage) return editorScale
        val full = max(abs(maxDouble), abs(minDouble))
        return if (full > 0) 100.0 / full else 100.0
    }

internal val ParamDefinition.displaySuffix: String get() = if (percentage) " %" else suffix

private val ParamDefinition.displayDecimals: Int get() = if (percentage) 0 else decimals

internal fun ParamDefinition.bitNames(): List<String> = enumNames.ifEmpty { List(8) { "Bit $it" } }

/** The value as a row shows it; [none] is shown for a bitfield with no bit set. */
internal fun ParamDefinition.display(value: ParamValue, none: String): String = when (value) {
    is ParamValue.DoubleValue -> (value.value * displayScale).fixed(displayDecimals) + displaySuffix
    is ParamValue.TextValue -> value.value.lineSequence().firstOrNull().orEmpty()
    is ParamValue.IntValue -> when (type) {
        ParamType.ENUM -> enumNames.getOrNull(value.value.toInt()) ?: value.value.toString()
        ParamType.BITFIELD -> bitNames().filterIndexed { i, _ -> value.value and (1L shl i) != 0L }.ifEmpty { listOf(none) }.joinToString(", ")
        else -> (value.value * displayScale).fixed(0) + displaySuffix
    }
}

/** Plain text for the edit field: scaled like [display], '.' as the decimal mark, no suffix. */
internal fun ParamDefinition.editText(value: ParamValue): String = when (value) {
    is ParamValue.DoubleValue -> BigDecimal(value.value * displayScale).setScale(displayDecimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    is ParamValue.IntValue -> BigDecimal(value.value * displayScale).setScale(0, RoundingMode.HALF_UP).toPlainString()
    is ParamValue.TextValue -> value.value
}

/** Reads what was typed into the edit field back into a value, or null if it isn't a number. */
internal fun ParamDefinition.parse(text: String): ParamValue? {
    if (type == ParamType.STRING) return ParamValue.TextValue(text)
    val number = text.trim().replace(',', '.').replace('−', '-').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    return if (type == ParamType.DOUBLE) ParamValue.DoubleValue(number / displayScale) else ParamValue.IntValue((number / displayScale).roundToLong())
}

internal fun ParamDefinition.range(): Pair<String, String> = if (type == ParamType.DOUBLE) {
    (minDouble * displayScale).fixed(displayDecimals) + displaySuffix to (maxDouble * displayScale).fixed(displayDecimals) + displaySuffix
} else {
    (minInt * displayScale).fixed(0) + displaySuffix to (maxInt * displayScale).fixed(0) + displaySuffix
}

/** VESC Tool's descriptions are Qt rich-text documents; the head holds a style sheet that isn't text. */
internal fun plainDescription(html: String): String =
    Html.fromHtml(html.replace(QT_HEAD, ""), Html.FROM_HTML_MODE_LEGACY).toString().trim()

private val QT_HEAD = Regex("(?is)<!DOCTYPE[^>]*>|<head>.*?</head>")
