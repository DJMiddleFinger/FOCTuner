package app.foctuner.vesc

import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AccessoriesTest {
    @Test
    fun gnssFixMovesWithTheVehicle() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val start = client.gnss()
        assertEquals(57.70887, start.latitude, 1e-6)
        assertEquals(2026, start.year)
        assertTrue(start.hdop in 0.1f..2f)

        client.motor.setDuty(0.5f)
        delay(10.seconds)
        val moved = client.gnss()
        assertTrue(moved.latitude > start.latitude && moved.longitude > start.longitude)
        assertTrue(moved.speed > 1f)
        assertTrue(moved.msToday - start.msToday in 9_950..10_050, "${moved.msToday - start.msToday}")
        client.motor.release()
    }

    @Test
    fun listsAndSwitchesPowerSwitches() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val switches = client.powerSwitches()
        assertEquals(1, switches.size)
        val psw = switches.single()
        assertEquals(30, psw.id)
        assertFalse(psw.outputOn)
        assertEquals(0f, psw.voltageOut)

        client.setPowerSwitch(30, on = true)
        val on = client.powerSwitch(30)
        assertTrue(on.outputOn)
        assertEquals(on.voltageIn, on.voltageOut, 0.2f)

        assertFailsWith<VescTimeoutException> { client.powerSwitch(31) }
    }

    @Test
    fun readsAndDrivesTheIoBoard() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val idle = client.ioBoard(40)
        assertEquals(40, idle.id)
        assertEquals(4, idle.adc1to4?.size)
        assertNotNull(idle.adc1to4Age)
        assertEquals(64, idle.digital?.size)
        assertTrue(idle.digital?.get(10) == true)
        assertFalse(idle.digital?.get(3) == true)

        client.setIoBoardDigital(40, 3, on = true)
        client.setIoBoardPwm(40, 1, 0.5f)
        val driven = client.ioBoard(40)
        assertTrue(driven.digital?.get(3) == true)
        assertEquals(1.65f, driven.adc5to8?.get(1) ?: 0f, 0.02f)

        assertFailsWith<VescTimeoutException> { client.ioBoard(41) }
    }
}
