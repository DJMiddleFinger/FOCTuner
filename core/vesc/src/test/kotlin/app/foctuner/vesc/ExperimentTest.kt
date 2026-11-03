package app.foctuner.vesc

import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ExperimentTest {
    @Test
    fun planStepsIncludeTheEndAndRunDownwardsToo() {
        val up = ExperimentPlan(ExperimentKind.Current, 0.0, 10.0, 2.5, 1.seconds)
        assertEquals(5, up.steps)
        assertEquals(5.seconds, up.duration)
        assertEquals(listOf(0.0, 2.5, 5.0, 7.5, 10.0), (0 until 5).map { up.valueAt((it * 1000 + 500).milliseconds) })
        assertNull(up.valueAt(5.seconds))
        val down = ExperimentPlan(ExperimentKind.Duty, 0.5, 0.1, 0.2, 1.seconds)
        assertEquals(listOf(0.5, 0.3, 0.1), (0 until 3).map { down.valueAt((it * 1000).milliseconds)!!.let { v -> Math.round(v * 10) / 10.0 } })
        assertFailsWith<IllegalArgumentException> { ExperimentPlan(ExperimentKind.Rpm, 0.0, 1.0, 0.0, 1.seconds) }
    }

    @Test
    fun runsADutySweepAndReleases() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val client = VescClient(sim, backgroundScope)
        val samples = mutableListOf<ExperimentSample>()
        client.runExperiment(ExperimentPlan(ExperimentKind.Duty, 0.1, 0.3, 0.1, 2.seconds), timeSource = testTimeSource) { samples += it }
        assertTrue(samples.size in 50..70, "${samples.size}")
        assertEquals(listOf(0.1, 0.2, 0.3), samples.map { Math.round(it.commanded * 10) / 10.0 }.distinct())
        assertTrue(samples.last().erpm > samples.first().erpm)
        assertFalse(sim.isDriven(0))
    }
}
