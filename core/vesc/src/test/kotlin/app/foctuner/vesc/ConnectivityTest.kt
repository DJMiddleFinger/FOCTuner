package app.foctuner.vesc

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.protocol.CommPacketId
import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ConnectivityTest {
    private val appBase = Config.defaults(BundledDefinitions.load("7.01", ConfigKind.APPCONF))

    @Test
    fun pinAndNameFollowVescTool() {
        assertEquals("001234", blePin("1234"))
        assertEquals("", blePin(""))
        assertEquals("123456", blePin("123456"))
        assertFailsWith<IllegalArgumentException> { blePin("12a4") }
        assertFailsWith<IllegalArgumentException> { blePin("1234567") }
        assertEquals("x".repeat(25), bleNameOrDefault("x".repeat(30)))
        assertEquals(listOf(3, 4, 5), (0..10).filter(::uartPortServed))
    }

    @Test
    fun setsBleNameAndPin() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val client = VescClient(sim, backgroundScope)
        assertTrue(client.firmwareInfo().bleModuleConfigurable)
        client.setBleName("Board")
        client.setBlePin("42")
        client.firmwareInfo() // a round trip so both sends were handled
        assertEquals("Board", sim.bleName)
        assertEquals("000042", sim.blePin)
    }

    @Test
    fun pairsARemoteOrTimesOut() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        var started = false
        assertEquals(NrfPairingResult.Paired, client.pairNrfRemote(10.seconds) { started = true })
        assertTrue(started)
        assertEquals(NrfPairingResult.TimedOut, client.pairNrfRemote(2.seconds))
    }

    @Test
    fun pairingWithoutAnAnswerIsReported() = runTest {
        val transport = ScriptedTransport()
        val client = VescClient(transport, backgroundScope)
        assertEquals(NrfPairingResult.NotSupported, client.pairNrfRemote(5.seconds))

        val pending = async { client.pairNrfRemote(5.seconds) }
        yield()
        transport.inject(byteArrayOf(CommPacketId.COMM_NRF_START_PAIRING.id.toByte(), 0))
        assertEquals(NrfPairingResult.NoAnswer, pending.await())
    }

    @Test
    fun changesCanBaudOnTheWholeBus() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        assertEquals(CanBaud.K500, CanBaud.fromAppconf(client.readConfig(ConfigKind.APPCONF, appBase).intValue("can_baud_rate")))
        assertTrue(client.updateCanBaudAll(CanBaud.M1))
        assertEquals(CanBaud.M1, CanBaud.fromAppconf(client.readConfig(ConfigKind.APPCONF, appBase).intValue("can_baud_rate")))
        assertEquals(CanBaud.M1, CanBaud.fromAppconf(client.can(1).readConfig(ConfigKind.APPCONF, appBase).intValue("can_baud_rate")))
    }

    @Test
    fun canModeAndTheFrameAnalyzer() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        client.setCanMode(CanMode.CommBridge, store = false)
        assertEquals(CanMode.CommBridge.value, client.readConfig(ConfigKind.APPCONF, appBase).intValue("can_mode"))

        val status = client.canFrames.first { it.extended }
        assertEquals((9 shl 8 or 1).toLong(), status.id)
        assertEquals(8, status.data.size)

        val echo = async { client.canFrames.first { !it.extended } }
        yield()
        client.sendCanFrame(CanFrame(0x123, extended = false, byteArrayOf(1, 2, 3)))
        val answer = echo.await()
        assertEquals(0x124L, answer.id)
        assertContentEquals(byteArrayOf(1, 2, 3), answer.data)

        client.setCanMode(CanMode.Vesc, store = false)
        assertNull(withTimeoutOrNull(1.seconds) { client.canFrames.first() })
        assertFailsWith<IllegalArgumentException> { CanFrame(0x800, extended = false, byteArrayOf()) }
        assertFalse(CanMode.CommBridge.forwardsVescCommands)
    }

    @Test
    fun releaseAllHasNoLockout() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val client = VescClient(sim, backgroundScope)
        client.motor.setCurrent(5f)
        client.can(1).motor.setCurrent(5f)
        assertTrue(sim.isDriven(0) && sim.isDriven(1))
        client.releaseAll()
        client.firmwareInfo()
        assertFalse(sim.isDriven(0) || sim.isDriven(1))
        client.motor.setCurrent(2f)
        client.firmwareInfo()
        assertTrue(sim.isDriven(0))
        client.releaseAll()
    }

    private fun Config.intValue(name: String) = (this[name] as app.foctuner.config.ParamValue.IntValue).value.toInt()
}
