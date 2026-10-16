package app.foctuner.vesc

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_BMS_SET_BALANCE_OVERRIDE
import app.foctuner.protocol.CommPacketId.COMM_GET_EXT_HUM_TMP
import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BmsTest {
    @Test
    fun readsCellsTempsAndCounters() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        client.motor.setCurrent(30f)
        delay(10.seconds)

        val bms = client.bmsValues()
        assertEquals(12, bms.cells.size)
        assertEquals(12, bms.balancing.size)
        assertEquals(bms.voltageTotal, bms.cells.sum(), 0.05f)
        assertTrue((bms.cellMax ?: 0f) - (bms.cellMin ?: 0f) in 0.005f..0.02f)
        assertEquals(4, bms.temps.size)
        assertEquals(10, bms.canId)
        assertTrue(bms.soc in 0.5f..1f && bms.soh > 0.9f)
        assertTrue(bms.ampHours > 0f && bms.wattHours > bms.ampHours)
        assertEquals(101_300f, bms.pressure, 10f)
        assertEquals("", bms.status)
        client.motor.release()

        client.bmsResetCounters(ampHours = true, wattHours = false)
        val reset = client.bmsValues()
        assertEquals(0f, reset.ampHours, 0.001f)
        assertTrue(reset.wattHours > 0f)
    }

    @Test
    fun setCommandsChangeWhatTheBmsReports() = runTest {
        val link = RecordingTransport(SimulatedVesc(testTimeSource))
        val client = VescClient(link, backgroundScope)
        assertFalse(client.bmsValues().balancing.any { it })

        client.bmsForceBalance(true)
        val forced = client.bmsValues()
        assertTrue(forced.balancing.count { it } > 5)
        assertFalse(forced.balancing[forced.cells.indexOf(forced.cellMin)])

        client.bmsSetBalanceOverride(4, on = true)
        assertTrue(client.bmsValues().balancing[4])
        assertContentEquals(byteArrayOf(COMM_BMS_SET_BALANCE_OVERRIDE.id.toByte(), 4, 1), link.sent(COMM_BMS_SET_BALANCE_OVERRIDE.id).single())

        client.bmsSetChargeAllowed(false)
        assertEquals("Charging disabled", client.bmsValues().status)

        val before = client.bmsValues().current
        client.bmsZeroCurrentOffset()
        assertTrue(client.bmsValues().current < before)
    }

    @Test
    fun readsTheExternalHumiditySensorOfABms() = runTest {
        val bus = ScriptedTransport()
        val client = VescClient(bus, backgroundScope)
        val reading = async { client.can(10).externalHumidityTemperature() }
        runCurrent()
        assertContentEquals(byteArrayOf(34, 10, COMM_GET_EXT_HUM_TMP.id.toByte()), bus.packets.single())

        bus.inject(ByteWriter().u8(COMM_GET_EXT_HUM_TMP.id).f32(48.25f, 100f).f32(-3.5f, 100f).toByteArray())
        assertEquals(HumidityTemperature(48.25f, -3.5f), reading.await())
    }
}
