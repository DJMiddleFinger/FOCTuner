package app.foctuner.vesc

import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ImuTest {
    @Test
    fun attitudeFollowsTheMotor() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val still = client.imu()
        assertEquals(0, still.controllerId)
        assertEquals(1f, still.accel.z, 0.05f)
        val q = still.quaternion
        assertEquals(1f, sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z), 1e-4f)

        client.motor.setCurrent(40f)
        delay(300.milliseconds)
        val accelerating = client.imu()
        assertTrue(abs(accelerating.pitch) > abs(still.pitch) + 0.02f, "${still.pitch} -> ${accelerating.pitch}")

        delay(3.seconds)
        client.imu()
        delay(1.seconds)
        val turned = client.imu()
        assertTrue(abs(turned.yaw - still.yaw) > 0.1f, "${still.yaw} -> ${turned.yaw}")
        assertTrue(abs(turned.gyro.z) > 1f, "${turned.gyro}")
        client.motor.release()
    }

    @Test
    fun aMaskedRequestOnlyCarriesThoseValues() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val pitchOnly = client.imu(ImuData.PITCH)
        assertEquals(0f, pitchOnly.accel.z)
        assertEquals(0f, pitchOnly.quaternion.w)
        assertEquals(1, client.can(1).imu(ImuData.PITCH).controllerId)
    }
}
