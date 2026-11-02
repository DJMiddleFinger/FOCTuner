package app.foctuner.ui.settings

import app.foctuner.config.ParamDefinition
import app.foctuner.config.ParamType
import app.foctuner.config.ParamValue
import app.foctuner.ui.settings.curves.niceStep
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/** A range small enough to drag across in full: at most this many of the definition's steps. */
private const val FULL_RANGE_STEPS = 200

/** Positions a track is divided into, before rounding the step to a round number. */
private const val TRACK_POSITIONS = 500

/** How far past the largest of the current and stored values a windowed track reaches. */
private const val WINDOW_REACH = 2.5

/**
 * The stretch of a number setting a slider covers, in the units its editor shows (scaled, or percent). [min]..[max]
 * is what the track spans and [step] the grid it snaps to; [limitMin]..[limitMax] is what the firmware allows. A
 * track narrower than the limits grows when the value is let go at one of its ends ([NumberTracks.track]).
 */
internal data class NumberTrack(val min: Double, val max: Double, val step: Double, val limitMin: Double, val limitMax: Double) {
    val span: Double get() = max - min

    fun fraction(value: Double): Float = if (span > 0) ((value - min) / span).coerceIn(0.0, 1.0).toFloat() else 0f

    /** The value at [fraction] of the track, on the grid; the ends are exact so the limits can always be reached. */
    fun at(fraction: Float): Double = when {
        fraction <= 0f -> min
        fraction >= 1f -> max
        else -> snap(min + fraction * span).coerceIn(min, max)
    }

    fun snap(value: Double): Double = clean((value / step).roundToLong() * step).coerceIn(limitMin, limitMax)

    /** The next grid value above or below [value], within the limits rather than the track. */
    fun nudge(value: Double, up: Boolean): Double {
        val next = if (up) floor(value / step + 1e-9) * step + step else ceil(value / step - 1e-9) * step - step
        return clean(next).coerceIn(limitMin, limitMax)
    }

    /** Drops the binary noise of multiplying by the step, so 3 × 0.1 is 0.3. */
    private fun clean(value: Double): Double {
        val digits = (-floor(log10(step)).toInt() + 3).coerceIn(0, 15)
        val scale = 10.0.pow(digits)
        return (value * scale).roundToLong() / scale
    }
}

internal object NumberTracks {
    /**
     * The track for a value in display units. Limits up to [FULL_RANGE_STEPS] of [definitionStep] apart are dragged
     * across in full (temperatures, duty cycles, percentages). Wider limits (currents to ±5000 A, ERPM to ±1e6) get a
     * window from zero (or the nearer limit) to [WINDOW_REACH] × the largest of |[value]| and |[stored]|, rounded up to
     * 1, 2, 2.5 or 5 × 10ⁿ and clipped to the limits; both signs when the limits allow both. A window covering half the
     * limits or more becomes the full range. The window is computed from the value when a drag starts and kept until
     * it ends, so letting go at an end makes the next one 2.5 times as wide.
     *
     * The step divides the track into about [TRACK_POSITIONS] round-numbered positions, never finer than [decimals];
     * integers keep to multiples of [definitionStep] (motor poles move in twos).
     */
    fun track(
        limitMin: Double,
        limitMax: Double,
        definitionStep: Double,
        decimals: Int,
        integer: Boolean,
        value: Double,
        stored: Double?,
    ): NumberTrack {
        val low = min(limitMin, limitMax)
        val high = max(limitMin, limitMax)
        val unit = if (integer) max(definitionStep, 1.0) else 10.0.pow(-decimals)
        val coarse = max(definitionStep, unit)
        var (from, to) = low to high
        if ((high - low) / coarse > FULL_RANGE_STEPS) {
            val reach = max(abs(value), abs(stored ?: 0.0))
            val radius = max(niceCeil(if (reach > 0) reach * WINDOW_REACH else max(definitionStep * 20, unit * 100)), unit * 10)
            val windowFrom = if (low >= 0) low else max(low, min(high, 0.0) - radius)
            val windowTo = if (high <= 0) high else min(high, max(low, 0.0) + radius)
            if (windowTo - windowFrom < (high - low) / 2) {
                from = windowFrom
                to = windowTo
            }
        }
        val fine = niceStep((to - from) / TRACK_POSITIONS)
        val step = if (integer) max(1L, (fine / coarse).roundToLong()) * coarse else max(unit, fine)
        return NumberTrack(from, to, step, low, high)
    }

    /** The smallest 1, 2, 2.5 or 5 × 10ⁿ at or above [x]. */
    fun niceCeil(x: Double): Double {
        if (x <= 0.0 || !x.isFinite()) return 1.0
        val magnitude = 10.0.pow(floor(log10(x)))
        val fraction = x / magnitude
        val nice = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { fraction <= it + 1e-9 }
        return nice * magnitude
    }
}

internal val ParamDefinition.isNumber: Boolean get() = type == ParamType.DOUBLE || type == ParamType.INT

/** A number setting's value in the units its editor shows. */
internal fun ParamDefinition.shown(value: ParamValue): Double = when (value) {
    is ParamValue.DoubleValue -> value.value * displayScale
    is ParamValue.IntValue -> value.value * displayScale
    is ParamValue.TextValue -> 0.0
}

/** The value a number shown as [shown] stores; integers round to the nearest whole number. */
internal fun ParamDefinition.fromShown(shown: Double): ParamValue =
    if (type == ParamType.INT) ParamValue.IntValue((shown / displayScale).roundToLong()) else ParamValue.DoubleValue(shown / displayScale)

/** The slider track for this number setting at [value], with the controller's [stored] value widening it. */
internal fun ParamDefinition.track(value: ParamValue, stored: ParamValue?): NumberTrack {
    val integer = type == ParamType.INT
    val percent = editAsPercentage && type == ParamType.DOUBLE
    val scale = displayScale
    val (low, high) = if (integer) minInt * scale to maxInt * scale else minDouble * scale to maxDouble * scale
    val step = when {
        percent -> 1.0
        integer -> stepInt * scale
        else -> stepDouble
    }
    return NumberTracks.track(low, high, step, if (integer) 0 else displayDecimals, integer, shown(value), stored?.let(::shown))
}

/**
 * The value a chart handle dragged to [raw] (in the setting's stored units) sets: clamped to the limits and put on
 * the grid of the setting's slider, so it reads the same as when dragged on its row.
 */
internal fun ParamDefinition.fromChart(raw: Double, track: NumberTrack): ParamValue = fromShown(track.snap(raw * displayScale))

internal fun ParamDefinition.fromChart(raw: Double, value: ParamValue, stored: ParamValue?): ParamValue = fromChart(raw, track(value, stored))

/** [value] one slider step up or down, as the row's −/+ would set it. */
internal fun ParamDefinition.nudged(value: ParamValue, stored: ParamValue?, up: Boolean): ParamValue =
    fromShown(track(value, stored).nudge(shown(value), up))
