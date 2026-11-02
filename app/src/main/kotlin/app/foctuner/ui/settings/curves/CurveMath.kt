package app.foctuner.ui.settings.curves

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/*
 * What the firmware does with the settings the charts show, ported from VESC firmware `utils_math.c`
 * (utils_map, utils_deadband, utils_throttle_curve, utils_min_abs), `mc_interface.c` (update_override_limits),
 * `mcpwm_foc.c` (field weakening), `servo_dec.c` and `app_ppm.c` (pulse mapping) and the apps' ramping.
 * Currents are in A, duty cycles and throttle as fractions (0..1 or −1..1).
 */
internal object CurveMath {
    /** utils_map; a zero-width input range maps everything to [outMin] instead of dividing by zero. */
    fun map(x: Double, inMin: Double, inMax: Double, outMin: Double, outMax: Double): Double =
        if (inMax == inMin) outMin else (x - inMin) * (outMax - outMin) / (inMax - inMin) + outMin

    /** utils_min_abs: whichever of [a] and [b] has the smaller magnitude, sign kept. */
    fun minAbs(a: Double, b: Double): Double = if (abs(a) < abs(b)) a else b

    /** utils_deadband with a maximum of 1: zero inside ±[band], rescaled so the rest still reaches ±1. */
    fun deadband(x: Double, band: Double): Double {
        if (abs(x) < band) return 0.0
        if (band >= 1.0) return 0.0
        val k = 1.0 / (1.0 - band)
        return if (x > 0) k * x + (1.0 - k) else -(k * -x + (1.0 - k))
    }

    /**
     * utils_throttle_curve: [x] in −1..1; [accel] shapes the positive half and [brake] the negative one.
     * [mode] 0 is exponential, 1 natural, 2 polynomial, anything else linear.
     */
    fun throttle(x: Double, accel: Double, brake: Double, mode: Int): Double {
        val v = x.coerceIn(-1.0, 1.0)
        val a = abs(v)
        val c = if (v >= 0) accel else brake
        val y = when (mode) {
            0 -> if (c >= 0) 1.0 - (1.0 - a).pow(1.0 + c) else a.pow(1.0 - c)
            1 -> when {
                abs(c) < 1e-10 -> a
                c >= 0 -> 1.0 - ((exp(c * (1.0 - a)) - 1.0) / (exp(c) - 1.0))
                else -> (exp(-c * a) - 1.0) / (exp(-c) - 1.0)
            }
            2 -> if (c >= 0) 1.0 - ((1.0 - a) / (1.0 + c * a)) else a / (1.0 - c * (1.0 - a))
            else -> a
        }
        return if (v < 0) -y else y
    }

    /**
     * Motor current limits at temperature [t] of one sensor (MOSFET or motor), as `update_override_limits`
     * derates them: both directions share a limit that falls linearly from the larger configured current at
     * [start] to zero at [end]; drive is also cut on a range moved towards 25 °C by [accelDec] (0..1), so some
     * braking is left when drive is gone. Returns (drive, brake) in A; brake has the sign of [currentMin].
     */
    fun thermal(t: Double, start: Double, end: Double, accelDec: Double, currentMax: Double, currentMin: Double): Pair<Double, Double> {
        var drive = currentMax
        var brake = currentMin
        if (t < start) {
            // Full current.
        } else if (t > end) {
            drive = 0.0
            brake = 0.0
        } else {
            val shared = map(t, start, end, max(abs(currentMax), abs(currentMin)), 0.0)
            if (abs(currentMin) > shared) brake = sign(currentMin) * shared
            if (abs(currentMax) > shared) drive = sign(currentMax) * shared
        }
        val accelStart = map(accelDec, 0.0, 1.0, start, 25.0)
        val accelEnd = map(accelDec, 0.0, 1.0, end, 25.0)
        val accel = when {
            t < accelStart -> currentMax
            t > accelEnd -> 0.0
            else -> map(t, accelStart, accelEnd, currentMax, 0.0)
        }
        return minAbs(drive, accel) to brake
    }

    /** Drive current allowed at [erpm]: reduced from [start] × the limit to the limit, in both directions. */
    fun erpmDrive(erpm: Double, maxErpm: Double, minErpm: Double, start: Double, currentMax: Double): Double {
        val forward = when {
            erpm < maxErpm * start -> currentMax
            erpm > maxErpm -> 0.0
            else -> map(erpm, maxErpm * start, maxErpm, currentMax, 0.0)
        }
        val reverse = when {
            erpm > minErpm * start -> currentMax
            erpm < minErpm -> 0.0
            else -> map(erpm, minErpm * start, minErpm, currentMax, 0.0)
        }
        return minAbs(minAbs(currentMax, forward), reverse)
    }

    /** Drive current allowed at [duty]: reduced from [dutyStart] × [maxDuty] to [maxDuty]; off when [dutyStart] > 0.99. */
    fun dutyDrive(duty: Double, dutyStart: Double, maxDuty: Double, currentMax: Double, ccMinCurrent: Double): Double {
        if (duty < dutyStart * maxDuty || dutyStart > 0.99) return currentMax
        return minAbs(currentMax, map(min(duty, maxDuty), dutyStart * maxDuty, maxDuty, currentMax, ccMinCurrent * 5.0))
    }

    /** Field weakening current at [duty]: zero below [dutyStart] × [maxDuty], rising to [currentMax] at [maxDuty]. */
    fun fieldWeakening(duty: Double, dutyStart: Double, maxDuty: Double, currentMax: Double): Double {
        if (dutyStart >= 0.99 || duty <= dutyStart * maxDuty) return 0.0
        return map(duty, dutyStart * maxDuty, maxDuty, 0.0, currentMax).coerceIn(0.0, max(currentMax, 0.0))
    }

    /** Battery current allowed at [volts] while driving: reduced from [cutStart] down to [cutEnd]. */
    fun batteryDrive(volts: Double, cutStart: Double, cutEnd: Double, inCurrentMax: Double): Double = when {
        volts > cutStart -> inCurrentMax
        volts < cutEnd -> 0.0
        else -> map(volts, cutStart, cutEnd, inCurrentMax, 0.0)
    }

    /** Regen current allowed at [volts]: reduced from [cutStart] up to [cutEnd], so a full battery isn't overcharged. */
    fun batteryRegen(volts: Double, cutStart: Double, cutEnd: Double, inCurrentMin: Double): Double = when {
        volts < cutStart -> inCurrentMin
        volts > cutEnd -> 0.0
        else -> map(volts, cutStart, cutEnd, inCurrentMin, 0.0)
    }

    /**
     * Throttle for a receiver pulse of [ms]: the decoder maps [start]..[end] to −1..1 (clamped); centered control
     * types then split at [center], the others use the whole range as 0..1; then the deadband [band] applies.
     */
    fun ppm(ms: Double, start: Double, end: Double, center: Double, band: Double, centered: Boolean): Double {
        val servo = map(ms, start, end, -1.0, 1.0).coerceIn(-1.0, 1.0)
        val value = if (centered) {
            val clamped = map(servo, -1.0, 1.0, start, end)
            if (clamped < center) map(clamped, start, center, -1.0, 0.0) else map(clamped, center, end, 0.0, 1.0)
        } else {
            (servo + 1.0) / 2.0
        }
        return deadband(value, band)
    }

    /**
     * Ramped output at [t] s for an input that jumps to full at 0 s and back to zero at [release] s. A full-scale
     * change takes [rise] s going up and [fall] s coming down; ramp times under 10 ms mean no ramping.
     */
    fun ramp(t: Double, rise: Double, fall: Double, release: Double): Double {
        if (t <= 0.0) return 0.0
        fun up(time: Double) = if (rise > 0.01) min(1.0, time / rise) else 1.0
        if (t <= release) return up(t)
        val peak = up(release)
        return if (fall > 0.01) max(0.0, peak - (t - release) / fall) else 0.0
    }
}
