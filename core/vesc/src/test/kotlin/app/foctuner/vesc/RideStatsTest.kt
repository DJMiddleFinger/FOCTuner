package app.foctuner.vesc

import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RideStatsTest {
    @Test
    fun countsWhileRidingAndResets() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        client.motor.setCurrent(30f)
        delay(4.seconds)
        client.motor.release()

        val stats = client.stats()
        assertTrue(stats.seconds in 3.9f..4.1f, "${stats.seconds}")
        assertTrue(stats.currentMax in 29f..30.5f, "${stats.currentMax}")
        assertTrue(stats.speedMax > stats.speedAvg && stats.speedAvg > 0f)
        assertTrue(stats.tempMotorMax > 25f)

        client.resetStats()
        assertEquals(0f, client.stats().seconds)
        assertEquals(0f, client.can(1).stats().currentMax)
    }
}
