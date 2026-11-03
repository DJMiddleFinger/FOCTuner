package app.foctuner.vesc

import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SampledDataTest {
    @Test
    fun recordsNowAndRecoversLostSamples() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val client = VescClient(sim, backgroundScope)
        client.motor.setCurrent(10f)
        delay(2.seconds)
        sim.dropSamples(3, 57)
        val samples = client.sampleAdc(SampleMode.Now, length = 200, decimation = 2)
        client.motor.release()
        assertEquals((0 until 200).toList(), samples.map { it.index })
        assertEquals(30_000f, samples.first().switchingFrequency)
        // Three balanced phases sum to about zero, and the current swings near the commanded 10 A.
        assertTrue(samples.all { abs(it.current1 + it.current2 + it.current3) < 0.5f })
        assertTrue(samples.maxOf { it.current1 } in 8f..11f, "${samples.maxOf { it.current1 }}")
    }

    @Test
    fun startTriggerWaitsForTheMotor() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val pending = async { client.sampleAdc(SampleMode.Start, length = 100, waitForFirst = 10.seconds) }
        delay(1.seconds)
        client.motor.setDuty(0.2f)
        val samples = pending.await()
        client.motor.release()
        assertEquals(100, samples.size)

        assertEquals(100, client.sampleAdc(SampleMode.SendLast, length = 100).size)
        assertEquals(emptyList(), client.sampleAdc(SampleMode.TriggerFault, length = 100, waitForFirst = 1.seconds))
        assertFailsWith<IllegalArgumentException> { client.sampleAdc(SampleMode.Now, length = 1001) }
    }
}
