package app.foctuner.vesc

import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SetupValuesTest {
    @Test
    fun speedDistanceAndBatteryAgreeWithTheMotor() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        client.motor.setDuty(0.3f)
        delay(5.seconds)

        val setup = client.setupValues()
        val values = client.values()
        val expectedSpeed = values.erpm / 7f / 60f * PI.toFloat() * 0.083f
        assertEquals(expectedSpeed, setup.speed, 0.05f)
        assertTrue(setup.distanceAbs > 0f && setup.distanceAbs == setup.distance)
        assertTrue(setup.batteryLevel in 0.5f..1f)
        assertTrue(setup.batteryWattHours > 0f)
        assertEquals(1, setup.controllerCount)
        assertTrue(setup.uptimeMs in 4_950L..5_050L, "${setup.uptimeMs}")
        client.motor.release()
    }

    @Test
    fun odometerCanBeSetAndKeepsCounting() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        client.setOdometer(1_000_000L)
        assertEquals(1_000_000L, client.setupValues().odometer)

        client.motor.setDuty(0.5f)
        delay(10.seconds)
        client.motor.release()
        val setup = client.setupValues()
        assertTrue(setup.odometer - (1_000_000L + setup.distanceAbs.toLong()) in -1L..1L, "${setup.odometer}")
    }

    @Test
    fun selectiveRequestsOnlyCarryTheirFields() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        client.motor.setCurrent(10f)
        delay(2.seconds)

        val setup = client.setupValues(setOf(SetupField.SPEED, SetupField.ODOMETER))
        assertTrue(setup.speed > 0f)
        assertTrue(setup.odometer > 0L)
        assertEquals(0f, setup.voltageInput)
        assertEquals(0, setup.controllerCount)

        val values = client.values(setOf(RealtimeField.ERPM, RealtimeField.MOSFET_TEMPS, RealtimeField.STATUS))
        assertTrue(values.erpm > 0f)
        assertTrue(values.tempMosfet2 > 20f)
        assertEquals(0f, values.voltageInput)
        assertEquals(1, client.can(1).values(setOf(RealtimeField.CONTROLLER_ID)).controllerId)
        client.motor.release()
    }
}
