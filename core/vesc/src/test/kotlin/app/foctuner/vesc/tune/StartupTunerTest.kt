package app.foctuner.vesc.tune

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.sim.SimulatedVesc
import app.foctuner.vesc.LispCode
import app.foctuner.vesc.VescClient
import app.foctuner.vesc.customAppData
import app.foctuner.vesc.lispStats
import app.foctuner.vesc.setLispRunning
import app.foctuner.vesc.uploadLisp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class StartupTunerTest {
    private val base = Config.defaults(BundledDefinitions.load("7.01", ConfigKind.MCCONF))

    private class Bench(val sim: SimulatedVesc, val client: VescClient, val time: TimeSource)

    private fun TestScope.bench(): Bench {
        val sim = SimulatedVesc(testTimeSource)
        return Bench(sim, VescClient(sim, backgroundScope), testTimeSource)
    }

    private suspend fun Bench.tuner(thorough: Boolean = false): StartupTuner {
        val config = client.readConfig(ConfigKind.MCCONF, base)
        val settings = TuneDefaults.settings(config, client.values().voltageInput.toDouble(), thorough)
        return StartupTuner(client, client.firmwareInfo(), config, settings, timeSource = time)
    }

    private suspend fun Bench.tuned(): Map<ProbeParam, Double> {
        val config = client.readConfig(ConfigKind.MCCONF, base)
        return ProbeParam.entries.associateWith { config.number(it.configName) }
    }

    @Test
    fun findsBetterStartSettingsAndPutsTheOriginalsBack() = runTest(timeout = 5.minutes) {
        val bench = bench()
        // Half the observer gain the simulated motor wants (as after a flux linkage measured too high): the observer
        // only follows above about 850 ERPM, far above the open-loop speed at this current.
        val halved = bench.client.readConfig(ConfigKind.MCCONF, base).let { it.with("foc_observer_gain", it.double("foc_observer_gain") * 0.5) }
        bench.client.writeConfig(ConfigKind.MCCONF, halved)
        val before = bench.tuned()
        val tuner = bench.tuner()
        val progress = mutableListOf<TuneProgress>()
        val report = tuner.run { progress += it }

        assertTrue(report.restored, "restored")
        assertEquals(before, bench.tuned(), "the controller is back on its own values")
        assertFalse(bench.sim.isDriven(0))

        // The observer loses the motor after the handover and the firmware starts over.
        assertTrue(report.findings.any { it is Finding.ObserverLosesMotor }, report.findings.toString())
        val resistance = assertNotNull(report.findings.filterIsInstance<Finding.ResistanceMismatch>().singleOrNull())
        assertEquals(1.15, resistance.measured / resistance.configured, 1e-3)

        val verification = assertNotNull(report.verification)
        assertTrue(verification.accepted, "best ${verification.bestScore} vs starting ${verification.baselineScore}")
        val recommended = assertNotNull(report.recommended)
        val gain = recommended.getValue(ProbeParam.OBSERVER_GAIN) / before.getValue(ProbeParam.OBSERVER_GAIN)
        assertTrue(gain > 1.3, "the gain goes back up: $recommended")
        assertTrue(report.best.score!! < report.baseline.score!!, "best ${report.best.score} baseline ${report.baseline.score}")
        assertTrue(report.changes.isNotEmpty(), "changes")

        // Every channel contributed: the probe's samples, the phase current recording and the observer angle stream.
        val first = report.baseline.trials.first()
        assertTrue(first.record.samples.size > 50, "samples ${first.record.samples.size}")
        assertNotNull(first.metrics.phaseCurrents)
        assertTrue(report.candidates.flatMap { it.trials }.any { it.metrics.observerReversals != null }, "rotor stream")
        val unpolled = report.candidates.flatMap { it.trials }.filter { it.inputCurrentPeak == null }
        assertTrue(unpolled.isEmpty(), "realtime values missing for ${unpolled.map { it.metrics.outcome to it.record.samples.size }}")

        assertEquals(TuneStage.BASELINE, (progress.first() as TuneProgress.Stage).stage)
        val trials = progress.filterIsInstance<TuneProgress.Trial>()
        assertEquals(report.candidates.sumOf { it.trials.size } + 2 * verification.pairs, trials.size)
        assertTrue(verification.pairs >= tuner.settings.verifyPairs)
        assertEquals(trials.size, trials.last().done)
    }

    @Test
    fun stoppingMidwayPutsTheOriginalsBackAndLetsTheMotorGo() = runTest(timeout = 5.minutes) {
        val bench = bench()
        val before = bench.tuned()
        val tuner = bench.tuner()
        val job = launch { tuner.run() }
        advanceTimeBy(40_000)
        assertFalse(job.isCompleted, "still searching")
        job.cancelAndJoin()
        assertEquals(before, bench.tuned())
        assertFalse(bench.sim.isDriven(0))
        assertEquals(RestoreMethod.PROBE, tuner.restoreResult, "a cancelled run has no report, so it says so here")
    }

    @Test
    fun anActiveKillSwitchIsRefusedBeforeAnythingChanges() = runTest {
        val bench = bench()
        val tuner = bench.tuner()
        bench.sim.killSwitch = true
        val refused = assertFailsWith<TuneRefusedException> { tuner.run() }
        assertEquals(TuneRefusal.KILL_SWITCH, refused.reason)
        assertNull(tuner.restoreResult, "nothing to set back")
    }

    @Test
    fun aKillSwitchBetweenStartsKeepsTheNextStartFromRunning() = runTest(timeout = 5.minutes) {
        val bench = bench()
        val tuner = bench.tuner()
        var starts = 0
        // On right after the probe answers: the monitor sees it during the resistance measurement, while no start runs.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            bench.client.customAppData.collect {
                when (ProbeCodec.parse(it)) {
                    is ProbeMessage.Hello -> bench.sim.killSwitch = true
                    is ProbeMessage.End -> starts++
                    else -> Unit
                }
            }
        }
        val aborted = assertFailsWith<TuneAbortedException> { tuner.run() }
        watcher.cancel()
        assertEquals(TuneAbort.KILL_SWITCH, aborted.reason)
        assertEquals(0, starts, "no start once the switch is on")
        assertFalse(bench.sim.isDriven(0))
    }

    @Test
    fun aScriptThatWasRunningRunsAgainAfterwards() = runTest(timeout = 5.minutes) {
        val bench = bench()
        bench.client.uploadLisp(LispCode.pack("(def vt-user 42)\n"))
        assertTrue(bench.client.setLispRunning(true))
        val tuner = bench.tuner()
        val job = launch { tuner.run() }
        advanceTimeBy(20_000)
        job.cancelAndJoin()
        val stats = bench.client.lispStats(allBindings = false)
        assertEquals(42f, stats.bindings.single { it.name == "vt-user" }.value)
    }

    @Test
    fun aResultCanBeUsedUntilTheNextReboot() = runTest {
        val bench = bench()
        val back = applyUntilReboot(bench.client, 1, mapOf(ProbeParam.OBSERVER_GAIN to 8e7, ProbeParam.OPENLOOP_ERPM to 2000.0))
        assertEquals(8e7, back.getValue(ProbeParam.OBSERVER_GAIN)!!, 100.0)
        // The simulator's stored config stands for the firmware's RAM copy, which conf-set changes.
        assertEquals(8e7, bench.tuned().getValue(ProbeParam.OBSERVER_GAIN), 100.0)
        assertEquals(2000.0, bench.tuned().getValue(ProbeParam.OPENLOOP_ERPM), 1e-3)

        val config = base.withProbeValues(mapOf(ProbeParam.OBSERVER_TYPE to 1.6, ProbeParam.OPENLOOP_TIME to 0.2))
        assertEquals(2L, config.int("foc_observer_type"))
        assertEquals(0.2, config.double("foc_sl_openloop_time"))
    }

    @Test
    fun refusesWhatItCantTune() = runTest {
        val bench = bench()
        val hall = bench.client.readConfig(ConfigKind.MCCONF, base).with("foc_sensor_mode", 2L)
        bench.client.writeConfig(ConfigKind.MCCONF, hall)
        val refused = assertFailsWith<TuneRefusedException> { bench.tuner().run() }
        assertEquals(TuneRefusal.NOT_SENSORLESS, refused.reason)

        val firmware = bench.client.firmwareInfo()
        assertEquals(TuneRefusal.FIRMWARE_TOO_OLD, StartupTuner.refusal(base, firmware.copy(major = 6, minor = 2)))
        assertEquals(TuneRefusal.NOT_DETECTED, StartupTuner.refusal(base.with("foc_motor_flux_linkage", 0.0), firmware))
        assertEquals(TuneRefusal.NOT_FOC, StartupTuner.refusal(base.with("motor_type", 0L), firmware))
    }

    @Test
    fun defaultsFollowTheMotorAndThePack() {
        assertEquals(15.0, TuneDefaults.current(base))
        // Open-loop 1500 ERPM: twice the highest candidate (1.6x) is 4800, well below 40 % of the no-load speed.
        assertEquals(4800.0, TuneDefaults.target(base, 48.0))
        // A slow motor on a low voltage: 40 % of 12 V / (√3 · 34.4 mWb) in ERPM.
        assertEquals(800.0, TuneDefaults.target(base.with("foc_motor_flux_linkage", 0.0344), 12.0))
        assertEquals(listOf(ProbeParam.OBSERVER_TYPE), ProbeParam.entries.filterNot { it.supportedOn(6, 5) })
    }
}
