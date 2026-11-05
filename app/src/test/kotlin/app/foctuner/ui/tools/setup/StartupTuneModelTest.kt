package app.foctuner.ui.tools.setup

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.sim.SimulatedVesc
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.VescClient
import app.foctuner.vesc.tune.Candidate
import app.foctuner.vesc.tune.CandidateResult
import app.foctuner.vesc.tune.ProbeParam
import app.foctuner.vesc.tune.RestoreMethod
import app.foctuner.vesc.tune.TuneReport
import app.foctuner.vesc.tune.TuneStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The tool's model against the simulator in real time, so a test run is kept to its first starts. */
class StartupTuneModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sim = SimulatedVesc()
    private val client = VescClient(sim, scope)
    private val base = Config.defaults(BundledDefinitions.load("7.01", ConfigKind.MCCONF))

    @After
    fun tearDown() = scope.cancel()

    private suspend fun model(): StartupTuneModel {
        val model = StartupTuneModel(ToolContext(client, client, client.firmwareInfo(), scope, simulator = sim))
        model.refresh()
        return model
    }

    private suspend fun gain(): Double = client.readConfig(ConfigKind.MCCONF, base).double("foc_observer_gain")

    private fun StartupTuneModel.shownGain(): Double = state.value.config!!.double("foc_observer_gain")

    private suspend fun StartupTuneModel.settle() = withTimeout(20.seconds) { while (runner.busy) delay(20) }

    /** A finished run that recommends [to] instead of [from]. */
    private fun report(from: Double, to: Double): TuneReport {
        val starting = CandidateResult(Candidate(TuneStage.BASELINE, mapOf(ProbeParam.OBSERVER_GAIN to from)), emptyList())
        return TuneReport(
            original = mapOf(ProbeParam.OBSERVER_GAIN to from),
            baseline = starting,
            best = starting,
            candidates = emptyList(),
            verification = null,
            recommended = mapOf(ProbeParam.OBSERVER_GAIN to to),
            measuredResistance = null,
            reachReference = 1.0,
            findings = emptyList(),
        )
    }

    @Test
    fun applyingShowsTheValuesThatWereWritten() = runBlocking {
        val model = model()
        val before = model.shownGain()
        model.edit { it.copy(report = report(before, before * 2)) }
        model.apply()
        model.settle()
        assertEquals(before * 2, gain(), before * 1e-3, "written")
        assertEquals(before * 2, model.shownGain(), before * 1e-3, "and shown as the current setting")
    }

    @Test
    fun usingUntilRebootShowsTheValuesInUse() = runBlocking {
        val model = model()
        val before = model.shownGain()
        model.edit { it.copy(report = report(before, before * 2)) }
        model.useUntilReboot()
        model.settle()
        assertEquals(before * 2, gain(), before * 1e-3, "in use")
        assertEquals(before * 2, model.shownGain(), before * 1e-3, "and shown as the current setting")
    }

    @Test
    fun stoppingARunReportsHowTheValuesCameBack() = runBlocking {
        val model = model()
        val before = gain()
        model.start()
        withTimeout(30.seconds) { model.state.first { it.done >= 1 } }
        model.runner.stop()
        assertTrue(model.runner.busy, "busy until the starting values are back, so a new run can't read test values")
        val restore = withTimeout(15.seconds) { model.state.mapNotNull { it.abortedRestore }.first() }
        assertEquals(RestoreMethod.PROBE, restore)
        model.settle()
        assertEquals(before, gain(), before * 1e-3)
    }
}
