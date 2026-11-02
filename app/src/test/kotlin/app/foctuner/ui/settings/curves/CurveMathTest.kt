package app.foctuner.ui.settings.curves

import kotlin.math.E
import kotlin.test.Test
import kotlin.test.assertEquals

class CurveMathTest {
    private fun near(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-9)

    @Test
    fun throttleCurveModesMatchTheFirmware() {
        // Exponential: 1 − (1 − x)^(1 + c) above zero curve, x^(1 − c) below.
        near(0.75, CurveMath.throttle(0.5, 1.0, 0.0, 0))
        near(0.25, CurveMath.throttle(0.5, -1.0, 0.0, 0))
        // Natural.
        near(E / (E + 1), CurveMath.throttle(0.5, 2.0, 0.0, 1))
        near(1 / (E + 1), CurveMath.throttle(0.5, -2.0, 0.0, 1))
        near(0.5, CurveMath.throttle(0.5, 0.0, 0.0, 1))
        // Polynomial.
        near(2.0 / 3, CurveMath.throttle(0.5, 1.0, 0.0, 2))
        near(1.0 / 3, CurveMath.throttle(0.5, -1.0, 0.0, 2))
        // Any other mode is linear.
        near(0.3, CurveMath.throttle(0.3, 4.0, 4.0, 7))
    }

    @Test
    fun throttleCurveUsesTheBrakeCurveBelowZeroAndKeepsEndpoints() {
        near(-0.25, CurveMath.throttle(-0.5, 3.0, -1.0, 0))
        for (mode in 0..2) {
            for (curve in listOf(-3.0, -0.5, 0.0, 0.5, 3.0)) {
                near(0.0, CurveMath.throttle(0.0, curve, curve, mode))
                near(1.0, CurveMath.throttle(1.0, curve, curve, mode))
                near(-1.0, CurveMath.throttle(-1.0, curve, curve, mode))
            }
        }
        near(1.0, CurveMath.throttle(1.4, 0.0, 0.0, 2))
    }

    @Test
    fun deadbandZeroesTheMiddleAndRescalesTheRest() {
        near(0.0, CurveMath.deadband(0.1, 0.15))
        near(0.0, CurveMath.deadband(0.15, 0.15))
        near(0.5, CurveMath.deadband(0.575, 0.15))
        near(-0.5, CurveMath.deadband(-0.575, 0.15))
        near(1.0, CurveMath.deadband(1.0, 0.15))
        near(0.4, CurveMath.deadband(0.4, 0.0))
    }

    @Test
    fun thermalLimitsCutDriveEarlierThanBraking() {
        // 85..100 °C with a 15 % acceleration decrease moves the drive range to 76..88.75 °C.
        assertEquals(60.0 to -60.0, CurveMath.thermal(50.0, 85.0, 100.0, 0.15, 60.0, -60.0))
        val (drive, brake) = CurveMath.thermal(80.0, 85.0, 100.0, 0.15, 60.0, -60.0)
        near(60.0 * (1 - 4.0 / 12.75), drive)
        near(-60.0, brake)
        val (hotDrive, hotBrake) = CurveMath.thermal(92.5, 85.0, 100.0, 0.15, 60.0, -60.0)
        near(0.0, hotDrive)
        near(-30.0, hotBrake)
        assertEquals(0.0 to 0.0, CurveMath.thermal(101.0, 85.0, 100.0, 0.15, 60.0, -60.0))
    }

    @Test
    fun thermalLimitIsSharedFromTheLargerCurrent() {
        // A smaller braking current is only reduced once the shared limit falls below it.
        near(-30.0, CurveMath.thermal(92.5, 85.0, 100.0, 0.0, 60.0, -30.0).second)
        near(-15.0, CurveMath.thermal(96.25, 85.0, 100.0, 0.0, 60.0, -30.0).second)
        near(30.0, CurveMath.thermal(92.5, 85.0, 100.0, 0.0, 60.0, -30.0).first)
    }

    @Test
    fun erpmLimitReducesDriveInBothDirections() {
        near(60.0, CurveMath.erpmDrive(50_000.0, 100_000.0, -100_000.0, 0.8, 60.0))
        near(30.0, CurveMath.erpmDrive(90_000.0, 100_000.0, -100_000.0, 0.8, 60.0))
        near(0.0, CurveMath.erpmDrive(100_001.0, 100_000.0, -100_000.0, 0.8, 60.0))
        near(30.0, CurveMath.erpmDrive(-90_000.0, 100_000.0, -100_000.0, 0.8, 60.0))
    }

    @Test
    fun batteryCutoffsReduceDrawAndRegen() {
        near(50.0, CurveMath.batteryDrive(43.0, 42.0, 40.0, 50.0))
        near(25.0, CurveMath.batteryDrive(41.0, 42.0, 40.0, 50.0))
        near(0.0, CurveMath.batteryDrive(39.0, 42.0, 40.0, 50.0))
        near(-20.0, CurveMath.batteryRegen(49.0, 50.0, 52.0, -20.0))
        near(-10.0, CurveMath.batteryRegen(51.0, 50.0, 52.0, -20.0))
        near(0.0, CurveMath.batteryRegen(53.0, 50.0, 52.0, -20.0))
    }

    @Test
    fun fieldWeakeningRampsUpToMaxDuty() {
        near(0.0, CurveMath.fieldWeakening(0.5, 0.9, 0.95, 30.0))
        near(0.0, CurveMath.fieldWeakening(0.855, 0.9, 0.95, 30.0))
        near(15.0, CurveMath.fieldWeakening(0.9025, 0.9, 0.95, 30.0))
        near(30.0, CurveMath.fieldWeakening(0.95, 0.9, 0.95, 30.0))
        near(30.0, CurveMath.fieldWeakening(1.0, 0.9, 0.95, 30.0))
    }

    @Test
    fun dutyLimitIsOffAtFullStart() {
        near(60.0, CurveMath.dutyDrive(0.94, 1.0, 0.95, 60.0, 0.05))
        near(60.0, CurveMath.dutyDrive(0.5, 0.8, 0.95, 60.0, 0.05))
        near(0.25, CurveMath.dutyDrive(0.95, 0.8, 0.95, 60.0, 0.05))
    }

    @Test
    fun ppmMapsPulsesAroundTheCenterOrOverTheWholeRange() {
        near(0.0, CurveMath.ppm(1.5, 1.0, 2.0, 1.5, 0.0, centered = true))
        near(0.5, CurveMath.ppm(1.75, 1.0, 2.0, 1.5, 0.0, centered = true))
        near(-1.0, CurveMath.ppm(0.9, 1.0, 2.0, 1.5, 0.0, centered = true))
        near(0.0, CurveMath.ppm(1.55, 1.0, 2.0, 1.5, 0.15, centered = true))
        near(0.5, CurveMath.ppm(1.5, 1.0, 2.0, 1.5, 0.0, centered = false))
        near(1.0, CurveMath.ppm(2.1, 1.0, 2.0, 1.5, 0.0, centered = false))
    }

    @Test
    fun rampRisesAndFallsAtFullScalePerRampTime() {
        near(0.5, CurveMath.ramp(0.2, 0.4, 0.2, 0.5))
        near(1.0, CurveMath.ramp(0.5, 0.4, 0.2, 0.5))
        near(0.5, CurveMath.ramp(0.6, 0.4, 0.2, 0.5))
        near(0.0, CurveMath.ramp(0.7, 0.4, 0.2, 0.5))
        // Ramp times under 10 ms mean no ramping.
        near(1.0, CurveMath.ramp(0.001, 0.0, 0.0, 0.5))
        near(0.0, CurveMath.ramp(0.501, 0.0, 0.0, 0.5))
    }

    @Test
    fun ticksAreRoundNumbersInsideTheRange() {
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), niceTicks(0.0, 100.0, 5))
        assertEquals(listOf(-50.0, 0.0, 50.0), niceTicks(-69.0, 69.0, 4))
        assertEquals(listOf(1.0, 1.5, 2.0), niceTicks(0.85, 2.15, 3))
    }
}
