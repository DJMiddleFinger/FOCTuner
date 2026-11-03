package app.foctuner.vesc

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotorComparisonTest {
    // A 14-pole outrunner: 30 mΩ, 20 µH, 10 mWb, no saliency, no core losses.
    private val motor = MotorConstants(0.03, 20e-6, 0.0, 0.01, 0.0, 14, 0.1)

    @Test
    fun standstillTorqueIsAllCopperLoss() {
        val p = MotorCalculator(motor).at(rpm = 0.0, torque = 1.05)
        // T = 3/2 · pp · λ · iq  ->  iq = 1.05 / (1.5 · 7 · 0.01) = 10 A
        assertEquals(10.0, p.iq, 1e-6)
        assertEquals(1.5 * 100 * 0.03, p.lossCopper, 1e-9)
        assertEquals(0.0, p.powerOut, 1e-9)
    }

    @Test
    fun runningPointBalancesPower() {
        val p = MotorCalculator(motor).at(rpm = 3000.0, torque = 1.05)
        val mech = 3000 * 2 * PI / 60 * 1.05
        assertEquals(mech, p.powerOut, 1e-6)
        assertEquals(p.powerOut + p.lossCopper, p.powerIn, 1e-6)
        assertTrue(p.efficiency in 0.9..1.0)
        assertEquals(21_000.0, p.erpm, 1e-6)
        // Back-EMF dominates vq: ωe · λ = 21000/60·2π · 0.01 ≈ 22 V
        assertEquals(21_000 / 60.0 * 2 * PI * 0.01 + 0.03 * 10, p.vq, 1e-6)
    }

    @Test
    fun gearingAndMotorsShareTheLoad() {
        val geared = MotorCalculator(motor, DriveSetup(gearing = 3.0, motors = 2.0)).at(1000.0, 6.3)
        assertEquals(3000.0, geared.rpmMotor, 1e-9)
        assertEquals(1.05, geared.torqueMotor, 1e-6)
        assertEquals(10.0, geared.iq, 1e-6)
    }

    @Test
    fun vbusSolveFindsTheSpeedThatTheVoltageAllows() {
        val calc = MotorCalculator(motor)
        val p = calc.atTorqueAndVbus(1.05, vbus = 36.0)
        assertEquals(36.0, p.vbusMin, 0.05)
        assertTrue(calc.at(p.rpmOut * 1.05, 1.05).vbusMin > 36.0)
    }

    @Test
    fun sweepsAndConfigConstants() {
        val calc = MotorCalculator(motor, DriveSetup(maxRpm = 4000.0))
        val rpm = calc.sweep(MotorCalculator.Sweep.Rpm, torque = 1.0, rpm = 10_000.0, points = 100)
        assertTrue(rpm.last().second.rpmMotor >= 4000.0 && rpm.size < 100)
        assertEquals(99, calc.sweep(MotorCalculator.Sweep.Torque, torque = 2.0, rpm = 1000.0, points = 100).size)

        val config = Config.defaults(BundledDefinitions.load("7.01", ConfigKind.MCCONF))
        val constants = MotorConstants.from(config)
        assertEquals(config.double("foc_motor_r"), constants.resistance)
        assertEquals(config.int("si_motor_poles").toInt(), constants.poles)
    }
}
