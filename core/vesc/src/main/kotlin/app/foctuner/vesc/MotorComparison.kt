package app.foctuner.vesc

import app.foctuner.config.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/** The motor constants the model uses, as the motor config stores them (SI units). */
data class MotorConstants(
    val resistance: Double,
    val inductance: Double,
    /** Lq - Ld. */
    val ldLqDiff: Double,
    val fluxLinkage: Double,
    /** No-load current (`si_motor_nl_current`), which stands in for core losses. */
    val noLoadCurrent: Double,
    val poles: Int,
    val wheelDiameter: Double,
) {
    companion object {
        /** From a motor config (`foc_motor_*`, `si_*`). Parameters a firmware lacks count as 0. */
        fun from(config: Config): MotorConstants {
            fun d(name: String) = if (name in config.definition.params) config.double(name) else 0.0
            fun i(name: String) = if (name in config.definition.params) config.int(name).toInt() else 0
            return MotorConstants(d("foc_motor_r"), d("foc_motor_l"), d("foc_motor_ld_lq_diff"), d("foc_motor_flux_linkage"), d("si_motor_nl_current"), i("si_motor_poles"), d("si_wheel_diameter"))
        }
    }
}

/** How the motor is used: VESC Tool's `MotorDataParams`. */
data class DriveSetup(
    /** Motor turns per output turn. */
    val gearing: Double = 1.0,
    val maxRpm: Double = 50_000.0,
    val gearingEfficiency: Double = 1.0,
    /** Field weakening current, A. */
    val fieldWeakening: Double = 0.0,
    /** Motors sharing the load. */
    val motors: Double = 1.0,
    /** Winding temperature rise, °C (copper resistance +0.386 %/°C). */
    val temperatureRise: Double = 0.0,
    val mtpa: Boolean = false,
)

/** One operating point (VESC Tool's `MotorData` after `update`). Powers in W, torques in Nm, currents in A. */
data class OperatingPoint(
    val torqueOut: Double,
    val torqueMotor: Double,
    val rpmOut: Double,
    val rpmMotor: Double,
    val erpm: Double,
    val iq: Double,
    val id: Double,
    val current: Double,
    val lossCopper: Double,
    val lossOther: Double,
    val lossMotor: Double,
    val lossGearing: Double,
    val lossTotal: Double,
    val powerOut: Double,
    val powerIn: Double,
    val efficiency: Double,
    val vq: Double,
    val vd: Double,
    /** Lowest bus voltage that can drive this point (sine modulation at 95 %). */
    val vbusMin: Double,
    val powerFactor: Double,
    val kmh: Double,
    val whPerKm: Double,
)

/**
 * The permanent-magnet motor model of VESC Tool's Motor Comparison page (`pagemotorcomparison.h`, after
 * MathWorks' PMSM equations): currents, voltages and losses for an output speed and torque.
 */
class MotorCalculator(private val motor: MotorConstants, private val setup: DriveSetup = DriveSetup()) {
    /** The operating point at output [rpm] and output [torque]. */
    fun at(rpm: Double, torque: Double, fieldWeakening: Double = setup.fieldWeakening): OperatingPoint {
        val r = motor.resistance * (1 + 0.00386 * setup.temperatureRise)
        val lq = motor.inductance + motor.ldLqDiff / 2
        val ld = motor.inductance - motor.ldLqDiff / 2
        val lambda = motor.fluxLinkage
        val polePairs = motor.poles / 2.0
        val rpmMotor = rpm * setup.gearing
        var torqueMotor = torque / (setup.gearing * setup.motors * setup.gearingEfficiency)
        val rpsMotor = rpm * 2 * PI / 60 * setup.gearing
        val eRps = rpsMotor * polePairs
        val tNl = sign(rpm) * 1.5 * motor.noLoadCurrent * lambda * polePairs

        var iq = (torqueMotor + tNl) * (2.0 / 3.0) / (lambda * polePairs)
        var id = -fieldWeakening
        var updated = torqueMotor
        var iqAdj = 0.0
        repeat(30) {
            iq -= 0.2 * iq * (updated - torqueMotor) / (sign(updated) * max(abs(updated), 1.0))
            iq += iqAdj
            if (setup.mtpa && abs(motor.ldLqDiff) > 1e-9) {
                id = (lambda - sqrt(lambda * lambda + 8 * motor.ldLqDiff * motor.ldLqDiff * iq * iq)) / (4 * motor.ldLqDiff)
                iqAdj = iq - sign(iq) * sqrt(iq * iq - id * id)
                iq = sign(iq) * sqrt(iq * iq - id * id)
                id = if (abs(id) > abs(fieldWeakening)) id else -fieldWeakening
            }
            updated = 1.5 * polePairs * (iq * lambda + iq * id * (ld - lq)) - tNl
        }
        torqueMotor = updated
        val torqueOut = torqueMotor * setup.gearing * setup.motors * setup.gearingEfficiency
        val current = sqrt(iq * iq + id * id)
        val lossCopper = current * current * r * 1.5 * setup.motors
        val lossOther = rpsMotor * tNl * setup.motors
        val lossMotor = lossCopper + lossOther
        val lossGearing = torqueMotor * (1 - setup.gearingEfficiency) * rpsMotor
        val powerOut = rpsMotor * torqueMotor * setup.motors * setup.gearingEfficiency
        val powerIn = rpsMotor * torqueMotor * setup.motors + lossMotor
        val vq = r * iq + eRps * (lambda + id * ld)
        val vd = r * id - eRps * lq * iq
        val vbusMin = 1.5 * sqrt(vq * vq + vd * vd) / (sqrt(3.0) / 2) / 0.95
        val pReal = 1.5 * (vq * iq + vd * id)
        val pReactive = 1.5 * (vq * id - vd * iq)
        val den = sqrt(pReal * pReal + pReactive * pReactive)
        val kmh = 3.6 * PI * motor.wheelDiameter * rpm / 60
        return OperatingPoint(
            torqueOut = torqueOut, torqueMotor = torqueMotor, rpmOut = rpm, rpmMotor = rpmMotor, erpm = rpmMotor * polePairs,
            iq = iq, id = id, current = current,
            lossCopper = lossCopper, lossOther = lossOther, lossMotor = lossMotor, lossGearing = lossGearing, lossTotal = lossMotor + lossGearing,
            powerOut = powerOut, powerIn = powerIn,
            efficiency = if (max(abs(powerOut), abs(powerIn)) > 0) min(abs(powerOut), abs(powerIn)) / max(abs(powerOut), abs(powerIn)) else 0.0,
            vq = vq, vd = vd, vbusMin = vbusMin,
            powerFactor = if (den > 0.01) pReal / den else -1.0,
            kmh = kmh, whPerKm = if (kmh != 0.0) powerIn / kmh else 0.0,
        )
    }

    /** The point at output [torque] where the bus voltage just suffices: the top speed at that torque. */
    fun atTorqueAndVbus(torque: Double, vbus: Double, rpmGuess: Double = 1000.0): OperatingPoint {
        var rpm = rpmGuess
        var point = at(rpm, torque)
        repeat(20) {
            if (point.vbusMin <= 0) return point
            rpm *= vbus / point.vbusMin
            point = at(rpm, torque)
        }
        return point
    }

    enum class Sweep { Torque, Rpm, Power, Vbus }

    /**
     * VESC Tool's test sweeps over [points] steps: torque from 0 (or -[torque] with [negative]) at [rpm]; speed up to
     * [rpm] at [torque]; speed from [rpmStart] at constant [power]; or torque at bus voltage [vbus]. Stops once the
     * motor shaft passes [DriveSetup.maxRpm]. Returns the x value (torque or rpm) with each point.
     */
    fun sweep(kind: Sweep, torque: Double, rpm: Double, points: Int = 100, negative: Boolean = false, power: Double = 0.0, rpmStart: Double = 0.0, vbus: Double = 0.0): List<Pair<Double, OperatingPoint>> {
        require(points in 2..10_000) { "2 to 10000 points" }
        val out = ArrayList<Pair<Double, OperatingPoint>>()
        fun add(x: Double, p: OperatingPoint): Boolean {
            out += x to p
            return p.rpmMotor < setup.maxRpm
        }
        when (kind) {
            Sweep.Torque, Sweep.Vbus -> {
                val t = abs(torque)
                val step = t / points
                var x = if (negative) -t else step
                while (x < t) {
                    if (!add(x, if (kind == Sweep.Torque) at(rpm, x) else atTorqueAndVbus(x, vbus))) break
                    x += step
                }
            }
            Sweep.Rpm -> {
                val step = rpm / points
                var x = if (negative) -rpm else step
                while (x < rpm) {
                    if (!add(x, at(x, torque))) break
                    x += step
                }
            }
            Sweep.Power -> {
                val step = rpm / points
                var x = max(rpmStart, step)
                while (x < rpm) {
                    if (!add(x, at(x, power / (x * 2 * PI / 60)))) break
                    x += step
                }
            }
        }
        return out
    }
}
