package app.foctuner.sim

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * COMM_MOTOR_ESTOP as `mc_interface_ignore_input_both` and `release_motor_override_both` handle it: both motors
 * of the board are released and motor commands are ignored for the given time. The command handlers still reset
 * the command timeout, as in the firmware.
 */
internal class EstopSim(private val motors: List<MotorModel>) {
    private var ignoreUntil = Duration.ZERO

    fun stop(now: Duration, ms: Int) {
        ignoreUntil = now + ms.milliseconds
        motors.forEach { it.drive = Drive.Released }
    }

    fun ignoresInput(now: Duration): Boolean = now < ignoreUntil
}

/**
 * COMM_SET_CURRENT_REL: the firmware scales by `l_current_max` when the duty cycle is near zero or has the same
 * sign, by `|l_current_min|` otherwise; the simulated motor uses ±[CURRENT_LIMIT] for both.
 */
internal fun relativeCurrent(fraction: Float): Float = fraction * CURRENT_LIMIT

private const val CURRENT_LIMIT = 60f
