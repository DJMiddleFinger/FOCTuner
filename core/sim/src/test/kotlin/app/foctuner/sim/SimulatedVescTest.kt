package app.foctuner.sim

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_ALIVE
import app.foctuner.protocol.CommPacketId.COMM_FORWARD_CAN
import app.foctuner.protocol.CommPacketId.COMM_FW_VERSION
import app.foctuner.protocol.CommPacketId.COMM_GET_DECODED_PPM
import app.foctuner.protocol.CommPacketId.COMM_GET_VALUES
import app.foctuner.protocol.CommPacketId.COMM_MOTOR_ESTOP
import app.foctuner.protocol.CommPacketId.COMM_PING_CAN
import app.foctuner.protocol.CommPacketId.COMM_PRINT
import app.foctuner.protocol.CommPacketId.COMM_SET_CURRENT
import app.foctuner.protocol.CommPacketId.COMM_SET_CURRENT_BRAKE
import app.foctuner.protocol.CommPacketId.COMM_SET_DUTY
import app.foctuner.protocol.CommPacketId.COMM_TERMINAL_CMD
import app.foctuner.protocol.PacketCodec
import app.foctuner.protocol.PacketDecoder
import app.foctuner.vesc.FirmwareInfo
import app.foctuner.vesc.RealtimeValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedVescTest {
    /** Talks to the simulator like a host would and keeps what it sent back. */
    private class Host(val sim: SimulatedVesc, scope: CoroutineScope) {
        val chunks = mutableListOf<ByteArray>()
        val replies = mutableListOf<ByteArray>()
        private val decoder = PacketDecoder()

        init {
            scope.launch {
                sim.received.collect {
                    chunks += it
                    replies += decoder.feed(it)
                }
            }
        }

        suspend fun send(payload: ByteArray) = sim.write(PacketCodec.encode(payload))

        suspend fun send(vararg bytes: Int) = send(ByteArray(bytes.size) { bytes[it].toByte() })

        suspend fun ask(vararg bytes: Int): List<ByteArray> {
            replies.clear()
            send(*bytes)
            return replies.toList()
        }

        suspend fun values(): RealtimeValues = RealtimeValues.parse(ByteReader(ask(COMM_GET_VALUES.id).single(), 1))

        suspend fun firmware(vararg forwardTo: Int): FirmwareInfo =
            FirmwareInfo.parse(ByteReader(ask(*forwardPrefix(forwardTo), COMM_FW_VERSION.id).single(), 1))

        suspend fun setCurrent(amps: Float) = send(ByteWriter().u8(COMM_SET_CURRENT.id).f32(amps, 1e3f).toByteArray())

        suspend fun setBrake(amps: Float) = send(ByteWriter().u8(COMM_SET_CURRENT_BRAKE.id).f32(amps, 1e3f).toByteArray())

        suspend fun setDuty(duty: Float) = send(ByteWriter().u8(COMM_SET_DUTY.id).f32(duty, 1e5f).toByteArray())

        /** Lets [time] pass while sending keepalives, as a connected app would. */
        suspend fun runFor(time: Duration) {
            repeat((time / 500.milliseconds).toInt()) {
                delay(500.milliseconds)
                send(COMM_ALIVE.id)
            }
        }

        private fun forwardPrefix(ids: IntArray) = if (ids.isEmpty()) intArrayOf() else intArrayOf(COMM_FORWARD_CAN.id, ids[0])
    }

    private fun TestScope.host(sim: SimulatedVesc = SimulatedVesc(testTimeSource)) = Host(sim, backgroundScope)

    @Test
    fun reportsFirmware701() = runTest(UnconfinedTestDispatcher()) {
        val info = host().firmware()
        assertEquals("7.01", info.version)
        assertEquals("60_MK6", info.hardwareName)
        assertEquals("SIMULATEDVSC", info.uuid.chunked(2).map { it.toInt(16).toChar() }.joinToString(""))
        assertEquals("", info.firmwareName)
    }

    @Test
    fun idleControllerSitsAtAmbientOnAFullishPack() = runTest(UnconfinedTestDispatcher()) {
        val host = host()
        delay(10.seconds)
        val v = host.values()
        assertEquals(0f, v.erpm)
        assertEquals(0f, v.currentMotor)
        assertEquals(25f, v.tempMosfet)
        assertEquals(49f, v.voltageInput, 0.2f)
        assertEquals(0, v.controllerId)
    }

    @Test
    fun currentCommandSpinsTheMotorAndLoadsThePack() = runTest(UnconfinedTestDispatcher()) {
        val host = host()
        val idle = host.values()

        host.setCurrent(40f)
        host.runFor(5.seconds)
        val v = host.values()

        assertTrue(v.erpm > 2000f, "erpm ${v.erpm}")
        assertEquals(40f, v.currentMotor, 0.5f)
        assertEquals(40f, v.currentQ, 0.5f)
        assertTrue(v.currentInput > 5f)
        assertTrue(v.dutyCycle > 0.1f)
        assertTrue(v.voltageInput < idle.voltageInput - 0.3f, "sag ${idle.voltageInput} -> ${v.voltageInput}")
        assertTrue(v.tempMotor > idle.tempMotor + 1.5f)
        assertTrue(v.tempMosfet > idle.tempMosfet + 0.5f)
        assertTrue(v.ampHours > 0f && v.wattHours > 0f)
        assertTrue(v.tachometer > 1000 && v.tachometerAbs >= v.tachometer)
        assertFalse(v.timedOut)
    }

    @Test
    fun dutyCommandSettlesNearTheRequestedSpeed() = runTest(UnconfinedTestDispatcher()) {
        val host = host()
        host.setDuty(0.5f)
        host.runFor(5.seconds)
        val v = host.values()
        assertEquals(0.5f, v.dutyCycle, 0.001f)
        assertEquals(0.5f * 49f * 160f, v.erpm, 300f)
    }

    @Test
    fun brakingSlowsTheMotorAndChargesThePack() = runTest(UnconfinedTestDispatcher()) {
        val host = host()
        host.setDuty(0.4f)
        host.runFor(5.seconds)
        val fast = host.values()

        host.setBrake(30f)
        host.runFor(500.milliseconds)
        val slower = host.values()

        assertTrue(slower.erpm < fast.erpm / 2, "erpm ${fast.erpm} -> ${slower.erpm}")
        assertTrue(slower.currentMotor < 0f)
        assertTrue(slower.currentInput < 0f)
        assertTrue(slower.ampHoursCharged > 0f)
    }

    @Test
    fun releasingCoastsDownSlowly() = runTest(UnconfinedTestDispatcher()) {
        val host = host()
        host.setDuty(0.4f)
        host.runFor(5.seconds)
        val fast = host.values()

        host.setCurrent(0f)
        host.runFor(2.seconds)
        val coasting = host.values()

        assertFalse(host.sim.isDriven(0))
        assertEquals(0f, coasting.currentMotor, 0.1f)
        assertEquals(0f, coasting.dutyCycle)
        assertTrue(coasting.erpm in fast.erpm * 0.5f..fast.erpm * 0.95f, "erpm ${fast.erpm} -> ${coasting.erpm}")
    }

    @Test
    fun motorsAreReleasedWhenNoCommandOrKeepaliveArrives() = runTest(UnconfinedTestDispatcher()) {
        val host = host()
        host.setCurrent(20f)
        host.runFor(2.seconds)
        assertTrue(host.sim.isDriven(0))
        assertFalse(host.values().timedOut)

        delay(900.milliseconds)
        assertTrue(host.sim.isDriven(0))
        delay(200.milliseconds)
        assertFalse(host.sim.isDriven(0))

        val v = host.values()
        assertTrue(v.timedOut)
        assertEquals(0f, v.currentMotor, 1f)
        delay(500.milliseconds)
        assertTrue(host.values().erpm < v.erpm)

        host.setCurrent(10f)
        assertTrue(host.sim.isDriven(0))
        assertFalse(host.values().timedOut)
    }

    @Test
    fun emergencyStopReleasesTheBoardAndIgnoresCommandsForItsDuration() = runTest(UnconfinedTestDispatcher()) {
        val host = host()
        host.setCurrent(20f)
        host.send(COMM_FORWARD_CAN.id, 1, COMM_SET_CURRENT.id, 0, 0, 0x27, 0x10)
        assertTrue(host.sim.isDriven(0) && host.sim.isDriven(1))

        host.send(COMM_MOTOR_ESTOP.id, 0x01, 0xF4)
        assertFalse(host.sim.isDriven(0) || host.sim.isDriven(1))

        host.setCurrent(20f)
        assertFalse(host.sim.isDriven(0))
        delay(400.milliseconds)
        host.setCurrent(20f)
        assertFalse(host.sim.isDriven(0))
        delay(100.milliseconds)
        host.setCurrent(20f)
        assertTrue(host.sim.isDriven(0))
    }

    @Test
    fun theTimeoutAppliesWhereItExpiredNotWhenItIsNoticed() = runTest(UnconfinedTestDispatcher()) {
        val host = host()
        host.setCurrent(20f)
        delay(10.seconds)
        val v = host.values()
        assertEquals(0f, v.currentMotor, 0.1f)
        assertTrue(v.erpm in 300f..1500f, "erpm ${v.erpm}")
    }

    @Test
    fun aZeroTimeoutDisablesIt() = runTest(UnconfinedTestDispatcher()) {
        val host = host(SimulatedVesc(testTimeSource, commandTimeout = Duration.ZERO))
        host.setCurrent(20f)
        delay(10.seconds)
        assertTrue(host.sim.isDriven(0))
    }

    @Test
    fun theSecondMotorAnswersThroughForwardCan() = runTest(UnconfinedTestDispatcher()) {
        val host = host()
        val main = host.firmware()
        val second = host.firmware(1)
        assertEquals(main.uuid.dropLast(2), second.uuid.dropLast(2))
        assertEquals(main.uuid.takeLast(2).toInt(16) + 1, second.uuid.takeLast(2).toInt(16))

        val reply = host.ask(COMM_FORWARD_CAN.id, 1, COMM_GET_VALUES.id).single()
        assertEquals(1, RealtimeValues.parse(ByteReader(reply, 1)).controllerId)

        assertContentEquals(byteArrayOf(COMM_PING_CAN.id.toByte(), 1), host.ask(COMM_PING_CAN.id).single())
    }

    @Test
    fun forwardedMotorCommandsDriveOnlyTheTargetMotor() = runTest(UnconfinedTestDispatcher()) {
        val host = host()
        host.send(ByteWriter().u8(COMM_FORWARD_CAN.id).u8(1).u8(COMM_SET_CURRENT.id).f32(15f, 1e3f).toByteArray())
        host.runFor(2.seconds)

        assertTrue(host.sim.isDriven(1))
        assertFalse(host.sim.isDriven(0))
        assertEquals(15f, RealtimeValues.parse(ByteReader(host.ask(COMM_FORWARD_CAN.id, 1, COMM_GET_VALUES.id).single(), 1)).currentMotor, 0.5f)
        assertEquals(0f, host.values().currentMotor)
    }

    @Test
    fun unknownCanIdsAndUnsupportedCommandsGetNoReply() = runTest(UnconfinedTestDispatcher()) {
        val host = host()
        assertTrue(host.ask(COMM_FORWARD_CAN.id, 9, COMM_GET_VALUES.id).isEmpty())
        assertTrue(host.ask(COMM_FORWARD_CAN.id).isEmpty())
        assertTrue(host.ask(COMM_GET_DECODED_PPM.id).isEmpty())
        assertTrue(host.ask(250).isEmpty())
    }

    @Test
    fun aSingleMotorBoardHasNoSecondController() = runTest(UnconfinedTestDispatcher()) {
        val host = host(SimulatedVesc(testTimeSource, dualMotor = false))
        assertContentEquals(byteArrayOf(COMM_PING_CAN.id.toByte()), host.ask(COMM_PING_CAN.id).single())
        assertTrue(host.ask(COMM_FORWARD_CAN.id, 1, COMM_GET_VALUES.id).isEmpty())
    }

    @Test
    fun terminalRepliesAreOnePrintPerLineStartingWithTheEcho() = runTest(UnconfinedTestDispatcher()) {
        val host = host()
        delay(12_340.milliseconds)
        val text = { replies: List<ByteArray> ->
            replies.map { assertEquals(COMM_PRINT.id, it[0].toInt()); it.decodeToString(1) }
        }

        val uptime = text(host.ask(COMM_TERMINAL_CMD.id, *"uptime".map { it.code }.toIntArray()))
        assertEquals(listOf("-> uptime \n", "Uptime: 12.34 s\n"), uptime)

        val status = text(host.ask(COMM_TERMINAL_CMD.id, *"HW_STATUS".map { it.code }.toIntArray()))
        assertEquals("-> HW_STATUS \n", status.first())
        assertTrue("Hardware: 60_MK6" in status)
        assertTrue("UUID: 53 49 4D 55 4C 41 54 45 44 56 53 43" in status)

        val bogus = text(host.ask(COMM_TERMINAL_CMD.id, *"frobnicate now".map { it.code }.toIntArray()))
        assertEquals(listOf("-> frobnicate now \n", "Invalid command: frobnicate\ntype help to list all available commands\n"), bogus)
    }

    @Test
    fun splitsRepliesAndAcceptsWritesInBlePackets() = runTest(UnconfinedTestDispatcher()) {
        val sim = SimulatedVesc(testTimeSource, chunkSize = 20)
        val host = host(sim)
        assertEquals(20, sim.maxWriteSize)

        val request = PacketCodec.encode(ByteWriter().u8(COMM_FORWARD_CAN.id).u8(1).u8(COMM_GET_VALUES.id).toByteArray())
        request.toList().chunked(3).forEach { sim.write(it.toByteArray()) }

        assertEquals(1, host.replies.size)
        assertTrue(host.chunks.size > 3)
        assertTrue(host.chunks.all { it.size <= 20 })
        assertEquals(1, RealtimeValues.parse(ByteReader(host.replies.single(), 1)).controllerId)
    }

    @Test
    fun closingEndsTheReceivedFlowAndRejectsWrites() = runTest(UnconfinedTestDispatcher()) {
        val sim = SimulatedVesc(testTimeSource)
        var completed = false
        backgroundScope.launch {
            sim.received.collect { }
            completed = true
        }

        sim.close()

        assertTrue(completed)
        assertFailsWith<IOException> { sim.write(PacketCodec.encode(byteArrayOf(COMM_GET_VALUES.id.toByte()))) }
    }
}
