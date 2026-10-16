package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.CommPacketId.COMM_ALIVE
import app.foctuner.protocol.CommPacketId.COMM_FORWARD_CAN
import app.foctuner.protocol.CommPacketId.COMM_MOTOR_ESTOP
import app.foctuner.protocol.CommPacketId.COMM_SET_CURRENT
import app.foctuner.protocol.CommPacketId.COMM_SET_CURRENT_REL
import app.foctuner.protocol.CommPacketId.COMM_SET_HANDBRAKE
import app.foctuner.protocol.CommPacketId.COMM_SET_POS
import app.foctuner.protocol.CommPacketId.COMM_SET_SERVO_POS
import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MotorCommandsTest {
    @Test
    fun sendsTheFirmwareScales() = runTest {
        val link = RecordingTransport(SimulatedVesc(testTimeSource))
        val client = VescClient(link, backgroundScope)

        client.motor.setPosition(90f)
        client.motor.setHandbrake(3f)
        client.motor.setCurrentRelative(-0.25f)
        client.setServoPosition(0.5f)
        client.can(1).motor.setPosition(1.5f)

        assertEquals(90_000_000, ByteReader(link.sent(COMM_SET_POS.id).single(), 1).i32())
        assertEquals(3_000, ByteReader(link.sent(COMM_SET_HANDBRAKE.id).single(), 1).i32())
        assertEquals(-25_000, ByteReader(link.sent(COMM_SET_CURRENT_REL.id).single(), 1).i32())
        val servo = link.sent(COMM_SET_SERVO_POS.id).single()
        assertEquals(3, servo.size)
        assertEquals(500, ByteReader(servo, 1).i16())
        val forwarded = ByteReader(link.sent(COMM_FORWARD_CAN.id).last(), 1)
        assertEquals(1, forwarded.u8())
        assertEquals(COMM_SET_POS.id, forwarded.u8())
        assertEquals(1_500_000, forwarded.i32())
    }

    @Test
    fun rejectsValuesOutsideTheirRange() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        assertFailsWith<IllegalArgumentException> { client.motor.setPosition(361f) }
        assertFailsWith<IllegalArgumentException> { client.motor.setCurrentRelative(1.5f) }
        assertFailsWith<IllegalArgumentException> { client.motor.setHandbrake(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { client.setServoPosition(-0.1f) }
        assertFailsWith<IllegalArgumentException> { client.emergencyStop(70.seconds) }
    }

    @Test
    fun holdsAPositionPastTheCommandTimeoutUntilReleased() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val client = VescClient(sim, backgroundScope)

        client.motor.setPosition(90f)
        delay(3.seconds)
        assertTrue(sim.isDriven(0))
        assertEquals(90f, client.values().pidPosition, 5f)

        client.motor.release()
        assertFalse(sim.isDriven(0))
    }

    @Test
    fun handbrakeStopsTheRotorAndIsReleasedWhenTheScopeIsCancelled() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val clientScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val client = VescClient(sim, clientScope)
        client.motor.setCurrent(20f)
        delay(1.seconds)
        assertTrue(client.values().erpm > 1000f)

        client.motor.setHandbrake(10f)
        delay(2.seconds)
        assertTrue(sim.isDriven(0))
        assertTrue(abs(client.values().erpm) < 50f)

        clientScope.cancel()
        runCurrent()
        assertFalse(sim.isDriven(0))
    }

    @Test
    fun relativeCurrentIsKeptAliveAndReleasedOnClose() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val client = VescClient(sim, backgroundScope)

        client.motor.setCurrentRelative(0.25f)
        delay(2.seconds)
        assertTrue(sim.isDriven(0))
        assertEquals(15f, client.values().currentMotor, 0.5f)

        client.close()
        assertFalse(sim.isDriven(0))
    }

    @Test
    fun emergencyStopReleasesBothMotorsAndBlocksCommandsForItsDuration() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val link = RecordingTransport(sim)
        val client = VescClient(link, backgroundScope)
        client.motor.setDuty(0.3f)
        client.can(1).motor.setCurrent(10f)
        delay(1.seconds)

        client.emergencyStop()
        assertFalse(sim.isDriven(0) || sim.isDriven(1))
        val estop = link.packets.last()
        assertEquals(COMM_MOTOR_ESTOP.id, estop[0].toInt() and 0xFF)
        assertEquals(5_000, ByteReader(estop, 1).u16())
        assertEquals(0, ByteReader(link.sent(COMM_SET_CURRENT.id).last(), 1).i32())

        delay(2.seconds)
        client.motor.setDuty(0.3f)
        delay(100.milliseconds)
        assertFalse(sim.isDriven(0))

        delay(3.seconds)
        client.motor.setDuty(0.3f)
        delay(100.milliseconds)
        assertTrue(sim.isDriven(0))
    }

    @Test
    fun emergencyStopEndsTheKeepalive() = runTest {
        val link = RecordingTransport(SimulatedVesc(testTimeSource))
        val client = VescClient(link, backgroundScope)
        client.motor.setCurrent(5f)
        delay(500.milliseconds)

        client.emergencyStop(1.seconds)
        val keepalives = link.sent(COMM_ALIVE.id).size
        delay(2.seconds)
        assertEquals(keepalives, link.sent(COMM_ALIVE.id).size)
    }
}
