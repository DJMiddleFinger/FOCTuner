package app.foctuner.vesc

import app.foctuner.config.Config
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_SET_MCCONF_TEMP_SETUP
import java.util.Properties
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/**
 * A set of riding limits that can be switched to without writing the whole motor config (VESC Tool mobile's
 * Profiles, `MCCONF_TEMP`). Speeds are in m/s, negative for reverse; current scales and duty are fractions.
 */
data class ConfigProfile(
    val name: String,
    val currentMinScale: Double = 1.0,
    val currentMaxScale: Double = 1.0,
    val speedMin: Double = -5.0,
    val speedMax: Double = 5.0,
    val dutyMin: Double = 0.05,
    val dutyMax: Double = 0.95,
    val wattMin: Double = -500.0,
    val wattMax: Double = 500.0,
) {
    /** VESC Tool's editor treats power limits beyond ±1.4 MW as "no power limit". */
    val limitsPower: Boolean get() = wattMax < POWER_LIMIT_OFF || wattMin > -POWER_LIMIT_OFF

    /** Whether [mcconf] has exactly these limits (VESC Tool highlights that profile as the one in use). */
    fun isInUse(mcconf: Config): Boolean {
        val f = mcconf.erpmPerMeterPerSecond()
        return almostEqual(currentMaxScale, mcconf.double("l_current_max_scale")) &&
            almostEqual(currentMinScale, mcconf.double("l_current_min_scale")) &&
            almostEqual(dutyMax, mcconf.double("l_max_duty")) &&
            almostEqual(dutyMin, mcconf.double("l_min_duty")) &&
            almostEqual(wattMax, mcconf.double("l_watt_max")) &&
            almostEqual(wattMin, mcconf.double("l_watt_min")) &&
            almostEqual(speedMax * f, mcconf.double("l_max_erpm")) &&
            almostEqual(speedMin * f, mcconf.double("l_min_erpm"))
    }

    companion object {
        /** What VESC Tool's power limit switch sets when turned off. */
        const val NO_POWER_LIMIT = 1_500_000.0
        private const val POWER_LIMIT_OFF = 1_400_000.0

        /** A profile with [mcconf]'s current limits ("Read current configuration"). */
        fun fromConfig(mcconf: Config, name: String): ConfigProfile {
            val f = mcconf.erpmPerMeterPerSecond()
            return ConfigProfile(
                name = name,
                currentMinScale = mcconf.double("l_current_min_scale"),
                currentMaxScale = mcconf.double("l_current_max_scale"),
                speedMin = if (f.isFinite() && f != 0.0) mcconf.double("l_min_erpm") / f else 0.0,
                speedMax = if (f.isFinite() && f != 0.0) mcconf.double("l_max_erpm") / f else 0.0,
                dutyMin = mcconf.double("l_min_duty"),
                dutyMax = mcconf.double("l_max_duty"),
                wattMin = mcconf.double("l_watt_min"),
                wattMax = mcconf.double("l_watt_max"),
            )
        }
    }
}

/** ERPM at 1 m/s from the motor poles, gear ratio and wheel diameter, as the firmware converts profile speeds. */
fun Config.erpmPerMeterPerSecond(): Double =
    (int("si_motor_poles") / 2.0) * 60.0 * double("si_gear_ratio") / (double("si_wheel_diameter") * PI)

/** [mcconf] as the controller has it after [profile] was applied (VESC Tool's `updateMcconfFromProfile`). */
fun Config.withProfile(profile: ConfigProfile): Config {
    val f = erpmPerMeterPerSecond()
    return with("l_current_min_scale", profile.currentMinScale)
        .with("l_current_max_scale", profile.currentMaxScale)
        .with("l_watt_min", profile.wattMin)
        .with("l_watt_max", profile.wattMax)
        .with("l_min_erpm", profile.speedMin * f)
        .with("l_max_erpm", profile.speedMax * f)
        .with("l_min_duty", profile.dutyMin)
        .with("l_max_duty", profile.dutyMax)
}

/**
 * COMM_SET_MCCONF_TEMP_SETUP, as VESC Tool's "Use until reboot" ([store] false) and "Use permanently" ([store] true)
 * send it: the controller converts the speeds to ERPM with its own wheel and gearing, clamps the scales and hardware
 * limits, and passes the result on to the controllers it hears on its CAN bus (COMM_SET_MCCONF_TEMP). The other
 * limits of the motor config stay as they are. Firmware 3.47 and later.
 */
suspend fun VescController.applyProfile(profile: ConfigProfile, store: Boolean) {
    val payload = ByteWriter().u8(COMM_SET_MCCONF_TEMP_SETUP.id)
        .u8(if (store) 1 else 0)
        .u8(1) // forward to CAN
        .u8(1) // acknowledge
        .u8(0) // don't divide the power limits by the number of controllers
        .f32Auto(profile.currentMinScale.toFloat()).f32Auto(profile.currentMaxScale.toFloat())
        .f32Auto(profile.speedMin.toFloat()).f32Auto(profile.speedMax.toFloat())
        .f32Auto(profile.dutyMin.toFloat()).f32Auto(profile.dutyMax.toFloat())
        .f32Auto(profile.wattMin.toFloat()).f32Auto(profile.wattMax.toFloat())
        .toByteArray()
    val timeout = if (store) session.options.configWriteTimeout else session.options.requestTimeout
    session.request(forwarded(payload), COMM_SET_MCCONF_TEMP_SETUP, timeout) { }
}

/** The profiles in the keys VESC Tool stores them under, `profile.<index>.<field>`. */
fun List<ConfigProfile>.toProperties(): Properties = Properties().also { p ->
    forEachIndexed { i, profile ->
        val fields = mapOf(
            "name" to profile.name,
            "current_min_scale" to profile.currentMinScale.toString(),
            "current_max_scale" to profile.currentMaxScale.toString(),
            "erpm_or_speed_min" to profile.speedMin.toString(),
            "erpm_or_speed_max" to profile.speedMax.toString(),
            "duty_min" to profile.dutyMin.toString(),
            "duty_max" to profile.dutyMax.toString(),
            "watt_min" to profile.wattMin.toString(),
            "watt_max" to profile.wattMax.toString(),
        )
        fields.forEach { (key, value) -> p.setProperty("profile.$i.$key", value) }
    }
}

fun profilesFrom(properties: Properties): List<ConfigProfile> = generateSequence(0) { it + 1 }
    .takeWhile { properties.getProperty("profile.$it.name") != null }
    .map { i ->
        fun d(key: String, default: Double) = properties.getProperty("profile.$i.$key")?.toDoubleOrNull() ?: default
        ConfigProfile(
            name = properties.getProperty("profile.$i.name"),
            currentMinScale = d("current_min_scale", 1.0),
            currentMaxScale = d("current_max_scale", 1.0),
            speedMin = d("erpm_or_speed_min", 0.0),
            speedMax = d("erpm_or_speed_max", 0.0),
            dutyMin = d("duty_min", 0.0),
            dutyMax = d("duty_max", 0.0),
            wattMin = d("watt_min", 0.0),
            wattMax = d("watt_max", 0.0),
        )
    }.toList()

/** VESC Tool's `Utility::almostEqual` with its 1e-4 tolerance. */
private fun almostEqual(a: Double, b: Double): Boolean = abs(a - b) <= 1e-4 * max(1.0, max(abs(a), abs(b)))
