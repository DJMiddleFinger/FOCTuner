package app.foctuner.vesc.tune

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.sim.SimulatedVesc
import app.foctuner.vesc.VescClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The probe's dead man's switch, the verification's statistics, the current sweep and one-direction runs. */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupSafeguardsTest {
    private val base = Config.defaults(BundledDefinitions.load("7.01", ConfigKind.MCCONF))

    private fun TestScope.client(): VescClient = VescClient(SimulatedVesc(testTimeSource), backgroundScope)

    private suspend fun VescClient.gain(): Double = readConfig(ConfigKind.MCCONF, base).double("foc_observer_gain")

    @Test
    fun aProbeThatHearsNothingSetsItsValuesBackAndStops() = runTest {
        val client = client()
        val before = client.gain()
        val probe = StartupProbe.start(client, 1)
        probe.set(mapOf(ProbeParam.OBSERVER_GAIN to before * 0.5))
        assertEquals(before * 0.5, client.gain(), before * 1e-3)

        // The app dies here: no ping, no quit.
        advanceTimeBy((ProbeCodec.QUIET_SECONDS + 1).seconds)
        assertEquals(before, client.gain(), before * 1e-3, "back to what the probe found at hello")
        // And it doesn't take commands any more, so nothing can change the values behind its back.
        val late = runCatching { probe.set(mapOf(ProbeParam.OBSERVER_GAIN to before * 2)) }
        assertTrue(late.isFailure, "a probe that gave up answers nothing")
        assertEquals(before, client.gain(), before * 1e-3)
    }

    @Test
    fun theKeepAliveHoldsTheProbeAndSeesItGiveUp() = runTest {
        val client = client()
        val before = client.gain()
        val probe = StartupProbe.start(client, 1)
        probe.set(mapOf(ProbeParam.OBSERVER_GAIN to before * 0.5))
        val pinging = launch { probe.keepAlive() }
        advanceTimeBy(30.seconds)
        assertEquals(before * 0.5, client.gain(), before * 1e-3, "held while the app pings")
        assertFalse(probe.gaveUp)

        // Pings stop (a frozen app) while the keep-alive still listens: the probe restores and says so.
        pinging.cancelAndJoin()
        val listening = launch { probe.keepAlive(ping = false) }
        advanceTimeBy((ProbeCodec.QUIET_SECONDS + 1).seconds)
        assertEquals(before, client.gain(), before * 1e-3)
        assertTrue(probe.gaveUp)
        listening.cancelAndJoin()
    }

    @Test
    fun quittingLeavesTheValuesAsSet() = runTest {
        // Use until reboot: hello, set, quit. The switch must not undo it afterwards.
        val client = client()
        applyUntilReboot(client, 1, mapOf(ProbeParam.OBSERVER_GAIN to 8e7))
        advanceTimeBy(20.seconds)
        assertEquals(8e7, client.gain(), 100.0)
    }

    private fun result(cost: Double?, outcome: TrialOutcome = TrialOutcome.REACHED): TrialResult {
        val spec = TrialSpec(0, 10.0, 3000.0, 3.seconds, 4.milliseconds, 5.0, 60.0, 250.milliseconds, 80.0, 80.0, 30.0)
        val end = TrialEnd(0, outcome, 0, 0, 0, 48.0, 30.0, 30.0, 0.0, 0.0)
        val metrics = TrialMetrics(
            outcome, 3000.0, if (outcome == TrialOutcome.REACHED) 0.5 else null, 1.0, 0.0, 1, 0.1, 0.0, null, null, null, null, 0,
            null, null, 0.3, null, null, null, null, null, 0,
        )
        return TrialResult(Candidate(TuneStage.VERIFY, emptyMap()), TrialRecord(spec, end, emptyList()), metrics, cost, null)
    }

    @Test
    fun verificationNeedsAClearMedianAndMostPairs() {
        // One lucky start doesn't carry it: the best wins one pair of three by a lot.
        val lucky = Verification(listOf(result(2.0), result(2.0), result(2.0)), listOf(result(0.5), result(2.1), result(2.2)))
        assertFalse(lucky.accepted, "median ${lucky.bestScore} vs ${lucky.baselineScore}, wins ${lucky.wins}")

        val clear = Verification(listOf(result(2.0), result(2.1), result(1.9)), listOf(result(1.4), result(1.5), result(1.6)))
        assertTrue(clear.accepted)
        assertEquals(3, clear.wins)

        // A fault among the starting settings' starts moves its median, not the decision on its own.
        val oneFault = Verification(listOf(result(30.0, TrialOutcome.FAULT), result(2.0), result(2.0)), listOf(result(1.95), result(1.98), result(1.97)))
        assertFalse(oneFault.accepted, "2 % better is no improvement")

        // More failed starts than the starting settings: never accepted, however good the rest looks.
        val failing = Verification(listOf(result(2.0), result(2.0), result(2.0)), listOf(result(1.0), result(1.0), result(15.0, TrialOutcome.TIMED_OUT)))
        assertFalse(failing.accepted)

        // Nothing to compare with is not a win.
        assertFalse(Verification(listOf(result(null), result(null)), listOf(result(1.0), result(1.0))).accepted)

        // Close: better, not clearly. Worth more pairs.
        val close = Verification(listOf(result(2.0), result(2.0), result(2.0)), listOf(result(1.85), result(1.9), result(2.1)))
        assertFalse(close.accepted)
        assertTrue(close.undecided)
        assertFalse(clear.undecided)
        assertFalse(Verification(listOf(result(2.0)), listOf(result(2.5))).undecided)
    }

    @Test
    fun everyStartGoesIntoTheCsv() {
        val baseline = Candidate(TuneStage.BASELINE, mapOf(ProbeParam.OBSERVER_GAIN to 100000.0))
        val candidates = listOf(CandidateResult(baseline, listOf(result(1.0), result(15.0, TrialOutcome.TIMED_OUT)).map { it.copy(candidate = baseline) }))
        val csv = StartupCsv.of(candidates, Verification(listOf(result(1.2)), listOf(result(0.9))))
        val lines = csv.trimEnd().lines()
        assertEquals(StartupCsv.header, lines.first())
        assertEquals(5, lines.size, csv)
        val columns = StartupCsv.header.split(';')
        assertTrue(lines.all { it.split(';').size == columns.size }, "every row has every column")
        val first = columns.zip(lines[1].split(';')).toMap()
        assertEquals("reached", first["outcome"])
        assertEquals("1", first["cost"])
        assertEquals("100000", first["foc_observer_gain"], "large round numbers keep their zeros")
        assertEquals("timed_out", columns.zip(lines[2].split(';')).toMap()["outcome"])
        assertEquals(listOf("verify_baseline", "verify_best"), lines.drop(3).map { columns.zip(it.split(';')).toMap()["stage"] })
    }

    @Test
    fun resistanceIsComparedAtTheReferenceTemperature() {
        // A winding measured warm reads high: 60 °C is about 14 % above 25 °C for copper.
        val r25 = 0.050
        val r60 = resistanceAt(r25, RESISTANCE_REFERENCE_TEMP, 60.0)
        assertEquals(1.0 + COPPER_TEMP_COEFFICIENT * 35, r60 / r25, 1e-9)
        assertEquals(r25, resistanceAt(r60, 60.0, RESISTANCE_REFERENCE_TEMP), 1e-12)
        assertEquals(r60, resistanceAt(r60, 60.0, 60.0), 1e-12)
    }

    @Test
    fun theSweepCyclesCurrentsAndOneDirectionStaysForward() = runTest(timeout = 5.minutes) {
        val client = client()
        val config = client.readConfig(ConfigKind.MCCONF, base)
        val defaults = TuneDefaults.settings(config, client.values().voltageInput.toDouble())
        val settings = defaults.copy(currentSweep = true, bothDirections = false)
        val report = StartupTuner(client, client.firmwareInfo(), config, settings, timeSource = testTimeSource).run()

        assertTrue(report.restored)
        val trials = report.candidates.flatMap { it.trials } + report.verification?.let { it.baseline + it.best }.orEmpty()
        assertTrue(trials.all { it.record.spec.current > 0 }, "forward only")
        val currents = trials.map { abs(it.record.spec.current) }.toSet()
        assertEquals(TuneSettings.SWEEP.map { it * settings.current }.toSet(), currents)
        // Each candidate's starts run at each current once, lowest first.
        for (candidate in report.candidates) {
            assertEquals(TuneSettings.SWEEP.map { it * settings.current }, candidate.trials.map { it.record.spec.current })
        }
        // Each start is timed against the starting settings at its own current: a slow low-current start isn't penalized.
        val baseline = report.baseline.trials.filter { it.metrics.outcome == TrialOutcome.REACHED }
        assertTrue(baseline.all { (it.breakdown?.time ?: 0.0) in 0.99..1.01 }, baseline.map { it.breakdown?.time }.toString())
    }
}
