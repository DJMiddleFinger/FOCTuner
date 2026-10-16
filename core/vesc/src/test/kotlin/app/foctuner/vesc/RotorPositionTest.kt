package app.foctuner.vesc

import app.foctuner.protocol.CommPacketId.COMM_SET_DETECT
import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RotorPositionTest {
    @Test
    fun streamsWhileCollectedAndSelectsNoneAfterwards() = runTest {
        val link = RecordingTransport(SimulatedVesc(testTimeSource))
        val client = VescClient(link, backgroundScope)
        client.motor.setCurrent(5f)

        val angles = client.rotorPositions(PositionDisplay.ENCODER).take(5).toList()

        assertEquals(5, angles.size)
        assertTrue(angles.all { it in 0f..360f })
        assertTrue(angles.toSet().size > 1, "$angles")
        val detect = link.sent(COMM_SET_DETECT.id)
        assertContentEquals(byteArrayOf(COMM_SET_DETECT.id.toByte(), 3), detect.first())
        assertContentEquals(byteArrayOf(COMM_SET_DETECT.id.toByte(), 0), detect.last())
        client.motor.release()
    }

    @Test
    fun pidErrorShowsHowFarThePositionIsFromItsTarget() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val client = VescClient(sim, backgroundScope)
        client.motor.setPosition(180f)

        val error = client.rotorPositions(PositionDisplay.PID_POSITION_ERROR).first()
        assertEquals(180f, kotlin.math.abs(error), 1f)

        delay(3.seconds)
        assertEquals(0f, client.rotorPositions(PositionDisplay.PID_POSITION_ERROR).first(), 5f)
        client.motor.release()
        assertFalse(sim.isDriven(0))
    }
}
