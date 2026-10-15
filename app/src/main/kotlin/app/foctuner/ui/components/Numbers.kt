package app.foctuner.ui.components

import java.util.Locale

private const val MINUS = '−'
const val NO_VALUE = "—"

/**
 * Fixed decimals, so a readout only changes width when its integer part does. Uses a real minus sign
 * (same advance as a digit in Plex Mono) and never shows "-0.0" for values that round to zero.
 */
fun Float.fixed(decimals: Int): String {
    if (isNaN() || isInfinite()) return NO_VALUE
    return String.format(Locale.getDefault(), "%.${decimals}f", this).withMinus()
}

fun Double.fixed(decimals: Int): String {
    if (isNaN() || isInfinite()) return NO_VALUE
    return String.format(Locale.getDefault(), "%.${decimals}f", this).withMinus()
}

/** Whole number with locale digit grouping, e.g. ERPM. */
fun Float.grouped(): String {
    if (isNaN() || isInfinite()) return NO_VALUE
    return String.format(Locale.getDefault(), "%,.0f", this).withMinus()
}

private fun String.withMinus(): String {
    if (!startsWith('-')) return this
    val roundsToZero = none { Character.isDigit(it) && Character.getNumericValue(it) != 0 }
    return if (roundsToZero) substring(1) else MINUS + substring(1)
}
