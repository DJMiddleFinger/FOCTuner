package app.foctuner.vesc

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_ALIVE
import app.foctuner.protocol.CommPacketId.COMM_GET_VALUES
import app.foctuner.protocol.CommPacketId.COMM_MOTOR_ESTOP
import app.foctuner.protocol.CommPacketId.COMM_PING_CAN
import app.foctuner.protocol.CommPacketId.COMM_PRINT
import app.foctuner.protocol.CommPacketId.COMM_SET_CURRENT
import app.foctuner.protocol.PacketCodec
import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VescClientTest {
    private val releaseCommand = byteArrayOf(COMM_SET_CURRENT.id.toByte(), 0, 0, 0, 0)

    @Test
    fun readsFirmwareInfo() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val info = client.firmwareInfo()
        assertEquals("7.01", info.version)
        assertEquals("60_MK6", info.hardwareName)
        assertEquals("53494d554c41544544565343", info.uuid)
    }

    @Test
    fun readsValuesAndPollsThem() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val v = client.values()
        assertEquals(0f, v.erpm)
        assertTrue(v.voltageInput in 45f..52f)

        val polled = client.realtime(100.milliseconds).take(3).toList()
        assertEquals(3, polled.size)
        // The period is measured with the wall clock, so a slow test run shaves a millisecond off a wait.
        assertTrue(currentTime in 198..200, "$currentTime")
    }

    @Test
    fun requestsForTheSameReplyAreSentOneAtATime() = runTest {
        val link = ScriptedTransport()
        val client = VescClient(link, backgroundScope)
        val calls = List(2) { async { client.values() } }
        runCurrent()
        assertEquals(1, link.packets.count { it[0].toInt() == COMM_GET_VALUES.id })

        link.inject(valuesPayload(erpm = 100f))
        runCurrent()
        assertEquals(2, link.packets.count { it[0].toInt() == COMM_GET_VALUES.id })

        link.inject(valuesPayload(erpm = 200f))
        assertEquals(listOf(100f, 200f), calls.awaitAll().map { it.erpm })
    }

    @Test
    fun runsTerminalCommandsAndStripsTheEcho() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val printed = async(start = CoroutineStart.UNDISPATCHED) { client.prints.take(2).toList() }

        val out = client.terminal("uptime")

        assertTrue(Regex("Uptime: \\d+\\.\\d\\d s").matches(out), out)
        assertEquals("-> uptime \n", printed.await().first())
        assertEquals(
            "Git Branch: release_7_01\nGit Hash  : simulated\nCompiler  : arm-none-eabi-gcc (simulated)",
            client.terminal("fw_info"),
        )
        assertEquals("Invalid command: bogus\ntype help to list all available commands", client.terminal("bogus"))
    }

    @Test
    fun terminalOutputIsWaitedForUntilItGoesQuiet() = runTest {
        val link = ScriptedTransport()
        val client = VescClient(link, backgroundScope)
        val result = async { client.terminal("foc_dc_cal") }
        runCurrent()

        link.inject(print("-> foc_dc_cal \n"))
        link.inject(print("Performing DC offset calibration..."))
        delay(200.milliseconds)
        link.inject(print("Done!\n"))

        assertEquals("Performing DC offset calibration...\nDone!", result.await())
        assertEquals(200 + 250, currentTime)
    }

    @Test
    fun passesUnsolicitedPrintsThrough() = runTest {
        val link = ScriptedTransport()
        val client = VescClient(link, backgroundScope)
        val printed = async(start = CoroutineStart.UNDISPATCHED) { client.prints.take(1).toList() }
        link.inject(print("Warning: something happened\n"))
        assertEquals(listOf("Warning: something happened\n"), printed.await())
    }

    @Test
    fun listsCanDevicesAndTalksToThemThroughTheAttachedController() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        assertEquals(listOf(1), client.pingCan())

        val main = client.firmwareInfo()
        val second = client.can(1).firmwareInfo()
        assertEquals(main.uuid.dropLast(2), second.uuid.dropLast(2))
        assertEquals(main.uuid.takeLast(2).toInt(16) + 1, second.uuid.takeLast(2).toInt(16))

        assertEquals(1, client.can(1).values().controllerId)
        assertEquals(0, client.values().controllerId)
        assertTrue(client.can(1).terminal("hw_status").contains("Hardware: 60_MK6"))
    }

    @Test
    fun requestToAMissingCanDeviceTimesOut() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        assertFailsWith<VescTimeoutException> { client.can(9).values() }
        assertEquals(1000, currentTime)
        assertFailsWith<VescTimeoutException> { client.can(9).terminal("uptime") }

        assertEquals(0, client.values().controllerId)
    }

    @Test
    fun keepsAMotorCommandAliveUntilReleased() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val link = RecordingTransport(sim)
        val client = VescClient(link, backgroundScope)

        client.motor.setDuty(0.1f)
        delay(1050.milliseconds)
        assertEquals(10, link.sent(COMM_ALIVE.id).size)

        delay(5.seconds)
        assertTrue(sim.isDriven(0))
        assertTrue(client.values().erpm > 500f)

        client.motor.release()
        val keepalives = link.sent(COMM_ALIVE.id).size
        delay(1.seconds)
        assertEquals(keepalives, link.sent(COMM_ALIVE.id).size)
        assertContentEquals(releaseCommand, link.sent(COMM_SET_CURRENT.id).last())
        assertFalse(sim.isDriven(0))
    }

    @Test
    fun keepaliveReachesCanControllersToo() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val client = VescClient(sim, backgroundScope)

        client.can(1).motor.setCurrent(15f)
        delay(5.seconds)
        assertTrue(sim.isDriven(1))
        assertFalse(sim.isDriven(0))
        assertEquals(15f, client.can(1).values().currentMotor, 0.5f)

        client.can(1).motor.release()
        assertFalse(sim.isDriven(1))
    }

    @Test
    fun releasesTheMotorWhenTheScopeIsCancelled() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val link = RecordingTransport(sim)
        val clientScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val client = VescClient(link, clientScope)
        client.motor.setCurrent(10f)
        client.can(1).motor.setCurrent(-5f)
        runCurrent()
        assertTrue(sim.isDriven(0) && sim.isDriven(1))

        clientScope.cancel()
        runCurrent()

        assertFalse(sim.isDriven(0) || sim.isDriven(1))
        assertContentEquals(releaseCommand, link.sent(COMM_SET_CURRENT.id).last())
        assertTrue(link.closed)
    }

    @Test
    fun releasesTheMotorAndClosesTheLinkOnClose() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val link = RecordingTransport(sim)
        val client = VescClient(link, backgroundScope)
        client.motor.setRpm(3000)

        client.close()

        assertFalse(sim.isDriven(0))
        assertTrue(link.closed)
        assertFalse(client.connected.value)
        assertFailsWith<VescLinkException> { client.values() }
        assertFailsWith<VescLinkException> { client.motor.setDuty(0.1f) }
    }

    @Test
    fun emergencyStopReleasesEveryMotorAndBlocksCommandsForItsDuration() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val link = RecordingTransport(sim)
        val client = VescClient(link, backgroundScope)
        client.motor.setCurrent(10f)
        client.can(1).motor.setDuty(0.2f)
        runCurrent()
        assertTrue(sim.isDriven(0) && sim.isDriven(1))

        client.emergencyStop(3.seconds)

        assertFalse(sim.isDriven(0) || sim.isDriven(1))
        assertContentEquals(
            byteArrayOf(COMM_MOTOR_ESTOP.id.toByte(), 0x0B, 0xB8.toByte()),
            link.sent(COMM_MOTOR_ESTOP.id).single(),
        )
        assertFailsWith<VescException> { client.motor.setCurrent(5f) }
        val keepalives = link.sent(COMM_ALIVE.id).size
        delay(2.seconds)
        assertEquals(keepalives, link.sent(COMM_ALIVE.id).size)
        assertFailsWith<VescException> { client.can(1).motor.setDuty(0.1f) }

        delay(1.seconds + 1.milliseconds)
        client.motor.setCurrent(5f)
        assertTrue(sim.isDriven(0))
    }

    @Test
    fun aLeasedCommandIsReleasedOnceItIsNotRenewed() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val link = RecordingTransport(sim)
        val client = VescClient(link, backgroundScope)

        repeat(10) {
            client.motor.setCurrent(10f, holdFor = 300.milliseconds)
            delay(100.milliseconds)
        }
        delay(150.milliseconds)
        assertTrue(sim.isDriven(0))

        delay(100.milliseconds)
        assertFalse(sim.isDriven(0))
        assertContentEquals(releaseCommand, link.sent(COMM_SET_CURRENT.id).last())
        val keepalives = link.sent(COMM_ALIVE.id).size
        delay(1.seconds)
        assertEquals(keepalives, link.sent(COMM_ALIVE.id).size)
    }

    @Test
    fun aCommandWithoutALeaseCancelsThePreviousLease() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val client = VescClient(sim, backgroundScope)
        client.motor.setCurrent(10f, holdFor = 200.milliseconds)
        client.motor.setCurrent(8f)
        delay(3.seconds)
        assertTrue(sim.isDriven(0))
        client.motor.release()
    }

    @Test
    fun outstandingRequestsFailWhenTheLinkDrops() = runTest {
        val link = ScriptedTransport()
        val client = VescClient(link, backgroundScope)
        val call = async { runCatching { client.values() } }
        runCurrent()

        link.incoming.close(IOException("boom"))

        val failure = assertIs<VescLinkException>(call.await().exceptionOrNull())
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.message == "boom" })
        assertFalse(client.connected.value)
        assertEquals(0, currentTime)
    }

    @Test
    fun rejectsMalformedReplies() = runTest {
        val link = ScriptedTransport()
        val client = VescClient(link, backgroundScope)
        val call = async { runCatching { client.values() } }
        runCurrent()

        link.inject(byteArrayOf(COMM_GET_VALUES.id.toByte(), 1, 2, 3))

        assertIs<VescException>(call.await().exceptionOrNull())
    }

    @Test
    fun worksOverSmallBlePackets() = runTest {
        val link = RecordingTransport(SimulatedVesc(testTimeSource, chunkSize = 20))
        val client = VescClient(link, backgroundScope)

        assertEquals("7.01", client.firmwareInfo().version)
        assertEquals(listOf(1), client.pingCan())
        assertTrue(client.can(1).terminal("fw_info with a long list of arguments").contains("Git Hash"))
        assertEquals(0, client.values().controllerId)

        assertTrue(link.writes.all { it.size <= 20 })
        assertTrue(link.writes.size > link.packets.size)
    }

    @Test
    fun dropsAHalfReceivedFrameThatSitsIdle() = runTest {
        val link = ScriptedTransport()
        val client = VescClient(link, backgroundScope)
        val call = async { client.pingCan() }
        runCurrent()

        link.incoming.trySend(halfAValuesFrame())
        delay(300.milliseconds)
        link.inject(byteArrayOf(COMM_PING_CAN.id.toByte(), 5))

        assertEquals(listOf(5), call.await())
    }

    @Test
    fun aStalePartialFrameWouldOtherwiseSwallowTheNextShortReply() = runTest {
        val link = ScriptedTransport()
        val client = VescClient(link, backgroundScope, VescClientOptions(frameIdleTimeout = 10.minutes))
        val call = async { runCatching { client.pingCan() } }
        runCurrent()

        link.incoming.trySend(halfAValuesFrame())
        delay(300.milliseconds)
        link.inject(byteArrayOf(COMM_PING_CAN.id.toByte(), 5))

        assertIs<VescTimeoutException>(call.await().exceptionOrNull())
    }

    private fun halfAValuesFrame() = PacketCodec.encode(valuesPayload(erpm = 321f)).copyOf(20)

    private fun print(text: String) = ByteWriter().u8(COMM_PRINT.id).bytes(text.toByteArray()).toByteArray()

    private fun valuesPayload(erpm: Float) = ByteWriter().u8(COMM_GET_VALUES.id)
        .f16(25f, 10f).f16(25f, 10f)
        .f32(0f, 100f).f32(0f, 100f).f32(0f, 100f).f32(0f, 100f)
        .f16(0f, 1000f).f32(erpm, 1f).f16(50f, 10f)
        .f32(0f, 10000f).f32(0f, 10000f).f32(0f, 10000f).f32(0f, 10000f)
        .i32(0).i32(0).u8(0)
        .toByteArray()
}
