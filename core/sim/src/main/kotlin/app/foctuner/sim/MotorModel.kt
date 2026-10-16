package app.foctuner.sim

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/** What the controller was last told to do with a motor. */
internal sealed interface Drive {
    data object Released : Drive
    data class Current(val amps: Float) : Drive
    data class Brake(val amps: Float) : Drive
    data class Duty(val duty: Float) : Drive
    data class Speed(val erpm: Float) : Drive
    data class Position(val degrees: Float) : Drive
    data class Handbrake(val amps: Float) : Drive
}

/** A 12S pack: open-circuit voltage follows the charge, and the terminal voltage sags with current drawn. */
internal class Battery(private val capacityAh: Float = 10f, var charge: Float = 0.85f) {
    fun voltage(currentDrawn: Float): Float = 40.8f + 9.6f * charge - INTERNAL_RESISTANCE * currentDrawn

    fun draw(ampHours: Float) {
        charge = (charge - ampHours / capacityAh).coerceIn(0f, 1f)
    }

    private companion object {
        const val INTERNAL_RESISTANCE = 0.06f
    }
}

/**
 * A toy motor behind a FOC controller: a first-order current loop, a rotor with drag, and first-order
 * heating. Good enough to look alive on a dashboard, not a physical model.
 */
internal class MotorModel {
    var drive: Drive = Drive.Released

    var erpm = 0f
        private set
    var current = 0f
        private set
    var duty = 0f
        private set
    var inputCurrent = 0f
        private set
    var tempFet = AMBIENT
        private set
    var tempMotor = AMBIENT
        private set
    var ampHours = 0.0
        private set
    var ampHoursCharged = 0.0
        private set
    var wattHours = 0.0
        private set
    var wattHoursCharged = 0.0
        private set
    var tachometer = 0.0
        private set
    var tachometerAbs = 0.0
        private set

    /** Mechanical rotor angle in degrees, 0..360, as the position PID sees it. */
    var angle = 0f
        private set

    val stats = RideStatsModel()

    /** Advances by [dt] seconds with the battery at [voltage]. */
    fun step(dt: Float, voltage: Float) {
        val noLoadErpm = voltage * ERPM_PER_VOLT
        val target = when (val d = drive) {
            Drive.Released -> 0f
            is Drive.Current -> d.amps
            is Drive.Brake -> if (abs(erpm) < MIN_BRAKE_ERPM) 0f else -sign(erpm) * abs(d.amps)
            is Drive.Duty -> (d.duty * noLoadErpm - erpm) * SPEED_GAIN
            is Drive.Speed -> (d.erpm - erpm) * SPEED_GAIN
            is Drive.Position -> {
                val target = (angleError(d.degrees) * POSITION_GAIN).coerceIn(-POSITION_MAX_ERPM, POSITION_MAX_ERPM)
                ((target - erpm) * SPEED_GAIN).coerceIn(-POSITION_CURRENT_LIMIT, POSITION_CURRENT_LIMIT)
            }
            is Drive.Handbrake -> (-erpm * HANDBRAKE_GAIN).coerceIn(-abs(d.amps), abs(d.amps))
        }.coerceIn(-CURRENT_LIMIT, CURRENT_LIMIT)
        current += (target - current) * (1f - exp(-dt / CURRENT_TAU))

        val friction = DRAG * erpm + COULOMB * sign(erpm)
        var next = erpm + (current * ACCEL_PER_AMP - friction) * dt
        if (next * erpm < 0f && abs(current * ACCEL_PER_AMP) < abs(friction)) next = 0f
        erpm = next.coerceIn(-MAX_SPEED_FRACTION * noLoadErpm, MAX_SPEED_FRACTION * noLoadErpm)

        duty = when (val d = drive) {
            Drive.Released -> 0f
            is Drive.Duty -> d.duty
            else -> erpm / noLoadErpm + current * PHASE_RESISTANCE / voltage
        }.coerceIn(-1f, 1f)
        inputCurrent = duty * current

        val hours = dt / 3600.0
        if (inputCurrent >= 0f) {
            ampHours += inputCurrent * hours
            wattHours += inputCurrent * voltage * hours
        } else {
            ampHoursCharged -= inputCurrent * hours
            wattHoursCharged -= inputCurrent * voltage * hours
        }
        val steps = erpm * STEPS_PER_ELECTRICAL_REV / 60.0 * dt
        angle = ((angle + erpm / POLE_PAIRS / 60f * 360f * dt) % 360f + 360f) % 360f
        tachometer += steps
        tachometerAbs += abs(steps)

        stats.add(dt, erpm, voltage * inputCurrent, current, tempFet, tempMotor)
        tempFet += (AMBIENT + FET_HEATING * current * current - tempFet) * (1f - exp(-dt / FET_TAU))
        tempMotor += (AMBIENT + MOTOR_HEATING * current * current - tempMotor) * (1f - exp(-dt / MOTOR_TAU))
    }

    /** Shortest way from the rotor angle to [target], -180..180 degrees. */
    fun angleError(target: Float): Float = ((target - angle) % 360f + 540f) % 360f - 180f

    private companion object {
        const val AMBIENT = 25f
        const val CURRENT_LIMIT = 60f
        const val CURRENT_TAU = 0.03f
        const val ERPM_PER_VOLT = 160f
        const val MAX_SPEED_FRACTION = 0.97f
        const val ACCEL_PER_AMP = 150f
        const val DRAG = 0.1f
        const val COULOMB = 60f
        const val SPEED_GAIN = 0.05f
        const val MIN_BRAKE_ERPM = 50f
        const val PHASE_RESISTANCE = 0.03f
        const val STEPS_PER_ELECTRICAL_REV = 6
        const val POLE_PAIRS = 7f
        const val POSITION_GAIN = 2f
        const val POSITION_MAX_ERPM = 2000f
        const val POSITION_CURRENT_LIMIT = 20f
        const val HANDBRAKE_GAIN = 0.2f
        const val FET_HEATING = 0.010f
        const val MOTOR_HEATING = 0.020f
        const val FET_TAU = 30f
        const val MOTOR_TAU = 60f
    }
}
