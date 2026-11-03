package app.foctuner.sim

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Rides as an Express would have logged them on its card: a 10 Hz log of the vehicle with no GNSS columns, in the
 * format of main/log.c (the fields in order, then the time of day it appends). The ride is a smooth pull-away, a
 * cruise with some variation and a stop, with power from mass, drag and rolling resistance on a 12S pack.
 */
internal object StoredRides {
    private const val RATE_HZ = 10
    private const val MASS_KG = 110.0
    private const val WHEEL_M = 0.083
    private const val POLE_PAIRS = 7.0

    private val header = listOf(
        "kmh_vesc:Speed VESC:km/h:2",
        "setup_curr_motor:Current Motors:A:2",
        "setup_curr_battery:Current Battery:A:2",
        "setup_power:Power:W:0",
        "erpm:ERPM::0",
        "duty:Duty:%:1",
        "v_in:Input Voltage:V:2",
        "soc:Battery Level:%:1",
        "t_mosfet:Temp MOSFET:°C:1",
        "t_motor:Temp Motor:°C:1",
        "cnt_wh:Wh Used:Wh:3",
        "cnt_wh_chg:Wh Charged:Wh:3",
    ).joinToString(";") { "$it:0:0" } + ";t_day:Time:s:3:0:1\n"

    /** A [seconds] long ride starting at [startOfDay] seconds UTC, [variation] seeding its cruise speed. */
    fun csv(seconds: Int, startOfDay: Double, variation: Double, startCharge: Double): ByteArray {
        val out = StringBuilder(header)
        val dt = 1.0 / RATE_HZ
        var wh = 0.0
        var whCharged = 0.0
        var tempFet = 24.0
        var tempMotor = 23.0
        var previous = 0.0
        for (i in 0 until seconds * RATE_HZ) {
            val t = i * dt
            val v = speed(t, seconds.toDouble(), variation)
            val a = (v - previous) / dt
            previous = v
            val power = (MASS_KG * a * v + 0.5 * 1.2 * 0.5 * v * v * v + 0.012 * MASS_KG * 9.81 * v).let { if (it < 0) it * 0.6 else it / 0.88 }
            val charge = startCharge - (wh - whCharged) / PACK_WH
            val voltage = 36.0 + 14.4 * charge.coerceIn(0.0, 1.0) - power * 0.0012
            val battery = power / voltage
            val duty = (v / MAX_SPEED).coerceIn(0.0, 0.95)
            val motor = if (duty < 0.05) battery else (battery / duty).coerceIn(-45.0, 45.0)
            if (power > 0) wh += power * dt / 3600 else whCharged -= power * dt / 3600
            tempFet += (0.9 * abs(motor) * 0.01 + 0.0 - (tempFet - 22.0) * 0.004) * dt * 4
            tempMotor += (abs(motor) * 0.012 - (tempMotor - 21.0) * 0.003) * dt * 4
            out.append(f(v * 3.6, 2)).append(';').append(f(motor, 2)).append(';').append(f(battery, 2)).append(';')
                .append(f(power, 0)).append(';').append(f(v / (PI * WHEEL_M) * 60 * POLE_PAIRS, 0)).append(';')
                .append(f(duty * 100, 1)).append(';').append(f(voltage, 2)).append(';').append(f(charge * 100, 1)).append(';')
                .append(f(tempFet, 1)).append(';').append(f(tempMotor, 1)).append(';')
                .append(f(wh, 3)).append(';').append(f(whCharged, 3)).append(';').append(f(startOfDay + t, 3)).append('\n')
        }
        return out.toString().encodeToByteArray()
    }

    /** m/s: pull away over 8 s, cruise at a speed that wanders with [variation], stop over the last 10 s. */
    private fun speed(t: Double, total: Double, variation: Double): Double {
        val up = smooth(t / 8.0)
        val down = smooth((total - t) / 10.0)
        val cruise = 6.2 + 1.2 * sin(t / 9.0 + variation) + 0.6 * sin(t / 3.1 + 2 * variation)
        return max(0.0, cruise * min(up, down))
    }

    private fun smooth(x: Double): Double {
        val c = x.coerceIn(0.0, 1.0)
        return c * c * (3 - 2 * c)
    }

    private fun f(v: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", v)

    private const val MAX_SPEED = 8.6
    private const val PACK_WH = 420.0
}
