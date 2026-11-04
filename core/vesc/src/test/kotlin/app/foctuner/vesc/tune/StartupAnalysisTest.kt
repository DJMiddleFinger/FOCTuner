package app.foctuner.vesc.tune

import app.foctuner.vesc.AdcSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StartupAnalysisTest {
    private val motor = MotorConstants(resistance = 0.03, inductance = 28e-6, ldLqDiff = 4e-6, fluxLinkage = 0.0344, inverted = false)
    private val spec = TrialSpec(1, 10.0, 3000.0, 2.seconds, 4.milliseconds, 5.0, 60.0, 250.milliseconds, 80.0, 80.0, 10.0)

    /**
     * Samples every 4 ms of a motor at [erpm](t) with the controller's angle [delta](t) degrees ahead of the rotor and
     * [override](t) for the open-loop sequence (the FOC angle 45° behind the observer's). The rotor turns at
     * [rotorErpm](t), which differs from the frame speed only during open loop. Voltages follow the motor model.
     */
    private fun record(
        seconds: Double,
        erpm: (Double) -> Double,
        delta: (Double) -> Double = { 0.0 },
        override: (Double) -> Boolean = { false },
        rotorErpm: (Double) -> Double = erpm,
        lambda: Double = motor.fluxLinkage,
        outcome: TrialOutcome = TrialOutcome.REACHED,
    ): TrialRecord {
        var angle = 0.0
        val samples = (0 until (seconds / 0.004).toInt()).map { i ->
            val t = i * 0.004
            val frame = erpm(t) * 2 * PI / 60
            val rotor = rotorErpm(t) * 2 * PI / 60
            angle += frame * 0.004
            val d = Math.toRadians(delta(t))
            val current = spec.current
            val idR = -current * sin(d)
            val iqR = current * cos(d)
            val vdR = motor.resistance * idR - rotor * motor.lq * iqR
            val vqR = motor.resistance * iqR + rotor * motor.ld * idR + rotor * lambda
            val vd = cos(d) * vdR + sin(d) * vqR
            val vq = -sin(d) * vdR + cos(d) * vqR
            val observer = Math.toDegrees(angle).mod(360.0)
            ProbeSample(
                index = i, seconds = t, erpm = erpm(t), erpmFast = erpm(t), iq = current, id = 0.0, iqSet = current, vd = vd, vq = vq,
                phaseObserver = observer, phaseMotor = if (override(t)) (observer - 45).mod(360.0) else observer,
                duty = 0.2, lambdaEst = lambda, resistanceEst = motor.resistance,
            )
        }
        val reach = samples.firstOrNull { it.erpm > spec.targetErpm }?.let { (it.seconds * 1000).toInt() } ?: 0
        return TrialRecord(spec, TrialEnd(1, outcome, samples.size, reach, 0, 48.0, 30.0, 30.0, lambda, motor.resistance), samples)
    }

    @Test
    fun observerAngleErrorAndFluxLinkageComeFromTheVoltages() {
        val m = StartupAnalysis.analyze(record(1.0, { 1000 + 2500 * it }, delta = { 12.0 }, lambda = 0.0378), motor)
        assertEquals(12.0, m.angleErrorRms!!, 0.5)
        assertEquals(12.0, m.angleErrorMax!!, 0.5)
        assertTrue(m.angleSamples > 100)
        assertEquals(1.1, m.lambdaBemfRatio!!, 0.01)
        assertEquals(1.1, m.lambdaEstRatio!!, 0.01)
        assertEquals(0, m.openLoopEntries)
        assertNull(m.following, "no open loop, nothing to follow")
    }

    @Test
    fun aReversedMotorReadsTheSame() {
        val inverted = motor.copy(inverted = true)
        // With m_invert_direction the firmware reports speed and q current negated, the voltages as they are.
        val r = record(1.0, { 1000 + 2500 * it }, delta = { 12.0 })
        val flipped = r.copy(samples = r.samples.map { it.copy(erpm = -it.erpm, erpmFast = -it.erpmFast, iq = -it.iq, iqSet = -it.iqSet) }, spec = spec.copy(current = -10.0))
        val m = StartupAnalysis.analyze(flipped, inverted)
        assertEquals(12.0, m.angleErrorRms!!, 0.5)
    }

    @Test
    fun openLoopSequencesAreCountedFromTheAngleOverride() {
        val m = StartupAnalysis.analyze(
            record(0.8, { if (it < 0.4) 400.0 else 2000.0 }, override = { it in 0.1..0.3 || it in 0.5..0.6 }),
            motor,
        )
        assertEquals(2, m.openLoopEntries)
        assertEquals(0.3, m.openLoopSeconds, 0.02)
        val cost = StartupCost.breakdown(m, 0.5)
        assertEquals(1.0, cost.reentries)
    }

    @Test
    fun speedLostAfterTheHandoverAndBackwardTravelAreMeasured() {
        val speed = { t: Double ->
            when {
                t < 0.05 -> -200.0
                t < 0.2 -> 1000.0
                t < 0.25 -> 1000 - 8000 * (t - 0.2)
                else -> 600 + 4000 * (t - 0.25)
            }
        }
        val m = StartupAnalysis.analyze(record(1.0, speed, override = { it in 0.06..0.2 }), motor)
        assertEquals(200.0, m.reverseErpm, 1e-9)
        assertEquals(0.4, m.handoverDip, 0.05)
        val cost = StartupCost.breakdown(m, 1.0)
        assertEquals(0.8, cost.handover, 0.1)
        assertEquals(200.0 / 600.0, cost.reverse, 1e-6)
    }

    @Test
    fun theRotorLaggingTheOpenLoopShowsInTheBackEmf() {
        val m = StartupAnalysis.analyze(
            record(0.6, { 1000.0 }, override = { it < 0.5 }, rotorErpm = { 500.0 }, delta = { 0.0 }, outcome = TrialOutcome.TIMED_OUT),
            motor,
        )
        assertEquals(0.5, m.following!!, 0.03)
        assertEquals(2 * 0.4, StartupCost.breakdown(m, 1.0).following, 0.06)
    }

    @Test
    fun phaseCurrentsGivePeaksAndRipple() {
        fun adc(spike: Boolean) = List(200) { i ->
            val a = 2 * PI * i / 50
            val amplitude = if (spike && i == 100) 20.0 else 10.0
            AdcSample(
                index = i,
                current1 = (amplitude * cos(a)).toFloat(),
                current2 = (10 * cos(a - 2 * PI / 3)).toFloat(),
                current3 = (10 * cos(a + 2 * PI / 3)).toFloat(),
                voltage1 = 0f, voltage2 = 0f, voltage3 = 0f, voltageZero = 0f, currentTotal = 0f,
                switchingFrequency = 1000f, status = 0, phase = ((i * 250 / 50) % 250),
            )
        }
        val clean = assertNotNull(StartupAnalysis.phaseCurrents(adc(false), 10.0))
        assertEquals(1.0, clean.peakRatio, 0.01)
        assertTrue(clean.ripple < 0.01)
        assertEquals(1000.0, clean.sampleRate)
        assertEquals(0, clean.angleJumps)
        val spiky = assertNotNull(StartupAnalysis.phaseCurrents(adc(true), 10.0))
        assertEquals(2.0, spiky.peakRatio, 0.01)
        assertTrue(spiky.ripple > clean.ripple)
    }

    @Test
    fun observerAngleStepsAgainstTheDirectionAreReversals() {
        val angles = (0 until 30).map { i -> RotorAngle(i * 0.01, (i * 10.0 - if (i == 10 || i == 20) 30.0 else 0.0).mod(360.0)) }
        // Steps into 10 and 20 go back 20°, the steps out of them forward 40°.
        assertEquals(2, StartupAnalysis.observerReversals(angles, 1, 0.5))
        assertEquals(0, StartupAnalysis.observerReversals(angles.filter { it.seconds < 0.095 }, 1, 0.5))
        assertNull(StartupAnalysis.observerReversals(angles.take(3), 1, 0.5))
    }

    @Test
    fun failuresCostMoreThanAnySlowStart() {
        val slow = StartupAnalysis.analyze(record(1.5, { 2500 * it }), motor)
        assertEquals(TrialOutcome.REACHED, slow.outcome)
        val slowCost = StartupCost.of(slow, 0.5)!!
        val timedOut = StartupAnalysis.analyze(record(1.0, { 1500.0 }, outcome = TrialOutcome.TIMED_OUT), motor)
        val timeoutCost = StartupCost.of(timedOut, 0.5)!!
        assertEquals(15.0, timeoutCost, 1e-9)
        assertTrue(slowCost < timeoutCost)
        assertTrue(timeoutCost < StartupCost.FAULT)
        assertNull(StartupCost.of(timedOut.copy(outcome = TrialOutcome.STOPPED), 0.5))
    }

    @Test
    fun runsNeedTwoSamples() {
        assertEquals(listOf(1..2, 6..8), StartupAnalysis.runs(listOf(false, true, true, false, true, false, true, true, true)))
        assertEquals(emptyList(), StartupAnalysis.runs(listOf(true, false, true)))
        assertEquals(-170.0, StartupAnalysis.wrap(190.0), 1e-9)
        assertEquals(10.0, StartupAnalysis.wrap(-350.0), 1e-9)
    }
}
