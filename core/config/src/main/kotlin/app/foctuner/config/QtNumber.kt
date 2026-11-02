package app.foctuner.config

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/**
 * [value] as Qt's `QString::number(double)` writes it (format 'g', 6 significant digits, trailing zeros removed,
 * two-digit exponent), which is how VESC Tool stores doubles in its XML files. Digits are rounded from the exact
 * binary value with ties away from zero, like the double-conversion library Qt uses.
 */
internal fun qtNumber(value: Double): String {
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
    if (value == 0.0) return "0"
    val rounded = BigDecimal(value).round(MathContext(SIGNIFICANT_DIGITS, RoundingMode.HALF_UP))
    val point = rounded.precision() - rounded.scale()
    val digits = rounded.unscaledValue().abs().toString().trimEnd('0')
    val sign = if (value < 0) "-" else ""
    if (point <= -4 || point > SIGNIFICANT_DIGITS) {
        val exponent = point - 1
        val mantissa = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
        return sign + mantissa + "e" + (if (exponent < 0) "-" else "+") + abs(exponent).toString().padStart(2, '0')
    }
    return sign + when {
        point <= 0 -> "0." + "0".repeat(-point) + digits
        point >= digits.length -> digits + "0".repeat(point - digits.length)
        else -> digits.substring(0, point) + "." + digits.substring(point)
    }
}

private const val SIGNIFICANT_DIGITS = 6
