package app.foctuner.vesc.tune

import app.foctuner.vesc.AdcSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The motor model the analysis inverts, from the motor config: R (Ω), L and Lq - Ld (H), λ (Wb). The firmware's
 * current controller uses the same model (mcpwm_foc.c): vd = R·id − ω·Lq·iq, vq = R·iq + ω·Ld·id + ω·λ, with
 * Lq = L + ΔL/2 and Ld = L − ΔL/2 (foc_math.c).
 */
data class MotorConstants(
    val resistance: Double,
    val inductance: Double,
    val ldLqDiff: Double,
    val fluxLinkage: Double,
    val inverted: Boolean,
) {
    val ld: Double get() = inductance - ldLqDiff / 2
    val lq: Double get() = inductance + ldLqDiff / 2
}

/** An observer angle from the COMM_ROTOR_POSITION stream, [seconds] after the drive started (by the app's clock). */
data class RotorAngle(val seconds: Double, val degrees: Double)

/**
 * From the phase currents the firmware recorded at the start (COMM_SAMPLE_PRINT): the highest phase current over the
 * commanded current, the spread of the current vector's length over the commanded current, how often the FOC angle
 * jumped by more than 45° between samples, and the recording's sample rate.
 */
data class PhaseCurrentMetrics(val peakRatio: Double, val ripple: Double, val angleJumps: Int, val samples: Int, val sampleRate: Double)

/**
 * What one start showed. Speeds are ERPM in the commanded direction; times in seconds.
 *
 * - [openLoopEntries], [openLoopSeconds]: how often and how long the firmware ran its open-loop start sequence (the
 *   FOC angle differs from the observer's while it does).
 * - [handoverDip]: the largest fraction of speed lost within 100 ms after the observer took over from open loop.
 * - [handoverTorque]: the acceleration in the 150 ms after the last handover (its 20th percentile) over the start's
 *   usual acceleration later on. The current is constant, so this is the torque the observer's angle still produced:
 *   cos(angle error). Near 0 the handover almost failed even when the start succeeded.
 * - [following]: during open loop, the rotor's speed (from the back-EMF in vd/vq) over the open-loop speed.
 * - [angleErrorRms], [angleErrorMax]: the observer's angle error (degrees) estimated from vd/vq with the motor
 *   model, where the back-EMF is large enough to tell ([angleSamples] samples).
 * - [roughness]: RMS of the fast speed estimate around its local mean after the observer took over, over the target.
 * - [trackingError]: RMS of q current minus its set point over the start current.
 * - [lambdaEstRatio]: the observer's flux linkage estimate over the configured one; [lambdaBemfRatio] the same from
 *   |back-EMF| / speed; [resistanceEstRatio] the firmware's resistance estimate over the configured R.
 */
data class TrialMetrics(
    val outcome: TrialOutcome,
    val targetErpm: Double,
    val reachSeconds: Double?,
    val progress: Double,
    val reverseErpm: Double,
    val openLoopEntries: Int,
    val openLoopSeconds: Double,
    val handoverDip: Double,
    val handoverTorque: Double?,
    val following: Double?,
    val angleErrorRms: Double?,
    val angleErrorMax: Double?,
    val angleSamples: Int,
    val roughness: Double?,
    val trackingError: Double?,
    val dutyPeak: Double,
    val lambdaEstRatio: Double?,
    val lambdaBemfRatio: Double?,
    val resistanceEstRatio: Double?,
    val phaseCurrents: PhaseCurrentMetrics?,
    val observerReversals: Int?,
    val samples: Int,
)

object StartupAnalysis {
    /** The FOC angle and the observer's differ by more than this while the open-loop sequence overrides it. */
    const val OVERRIDE_DEGREES = 8.0

    /** Back-EMF below this (V) is too close to dead-time and offset errors to read an angle from. */
    const val MIN_BEMF_VOLTS = 0.5

    /** Above this ERPM the 100 Hz rotor position stream can't tell forward from backward steps. */
    const val ROTOR_STREAM_MAX_ERPM = 1500.0

    private const val HANDOVER_WINDOW = 0.1
    private const val TORQUE_WINDOW = 0.15
    private const val SETTLE = 0.02
    private const val CURRENT_RISE = 0.03
    private const val SMOOTHING = 3

    fun analyze(record: TrialRecord, motor: MotorConstants, adc: List<AdcSample>? = null, rotor: List<RotorAngle>? = null): TrialMetrics {
        val spec = record.spec
        val dir = spec.direction
        val internal = if (motor.inverted) -1 else 1
        val target = spec.targetErpm
        val samples = record.samples.sortedBy { it.index }
        val speed = samples.map { it.erpm * dir }
        val time = samples.map { it.seconds }
        val period = medianStep(time) ?: spec.period.inWholeMilliseconds / 1000.0

        val override = samples.map { abs(wrap(it.phaseMotor - it.phaseObserver)) > OVERRIDE_DEGREES }
        val runs = runs(override)
        val lastHandover = runs.lastOrNull()?.let { time[it.last] }

        // Back-EMF in the controller's frame: e = v − R·i − jω·L·i, from the firmware's own dq model.
        val lambda = motor.fluxLinkage
        val electrical = samples.map { s ->
            val omega = internal * s.erpm * 2 * PI / 60
            val iq = internal * s.iq
            val ed = s.vd - motor.resistance * s.id + omega * motor.lq * iq
            val eq = s.vq - motor.resistance * iq - omega * motor.ld * s.id
            val readable = abs(omega) * lambda >= max(MIN_BEMF_VOLTS, 2 * motor.resistance * abs(iq))
            val sg = sign(omega)
            Electrical(omega, hypot(ed, eq), Math.toDegrees(atan2(ed * sg, eq * sg)), readable)
        }

        val handoverDip = runs.maxOfOrNull { run ->
            val handover = run.last + 1
            if (handover >= samples.size) return@maxOfOrNull 0.0
            val v0 = speed[handover]
            val end = time[run.last] + HANDOVER_WINDOW
            val low = (handover until samples.size).takeWhile { time[it] <= end }.minOfOrNull { speed[it] } ?: v0
            max(0.0, (v0 - low) / max(v0, 0.05 * target))
        } ?: 0.0

        val handoverTorque = lastHandover?.let { handoverTorque(time, samples.map { internal * it.erpmFast * dir }, it, target) }

        val following = median(
            runs.flatMap { run ->
                val half = (time[run.first] + time[run.last]) / 2
                run.filter { time[it] >= half && electrical[it].readable }
                    .map { (electrical[it].bemf / (abs(electrical[it].omega) * lambda)).coerceIn(0.0, 2.0) }
            },
        )

        val observerFrom = (lastHandover?.plus(SETTLE)) ?: 0.05
        val tracked = samples.indices.filter { !override[it] && time[it] >= observerFrom }
        val angles = tracked.filter { electrical[it].readable }.map { electrical[it].delta }
        val angleRms = if (angles.isEmpty()) null else sqrt(angles.sumOf { it * it } / angles.size)

        val smoothFrom = lastHandover?.plus(SETTLE) ?: 0.1
        val fast = samples.indices.filter { time[it] >= smoothFrom }.map { internal * samples[it].erpmFast * dir }
        val roughness = if (fast.size < 3 * SMOOTHING) {
            null
        } else {
            val residuals = (SMOOTHING until fast.size - SMOOTHING).map { i ->
                fast[i] - (i - SMOOTHING..i + SMOOTHING).sumOf { fast[it] } / (2 * SMOOTHING + 1)
            }
            sqrt(residuals.sumOf { it * it } / residuals.size) / target
        }

        val current = abs(spec.current)
        val trackingSamples = samples.indices.filter { !override[it] && time[it] >= CURRENT_RISE && abs(samples[it].duty) < 0.9 }
        val trackingError = if (trackingSamples.size < 3 || current <= 0.0) {
            null
        } else {
            sqrt(trackingSamples.sumOf { val e = samples[it].iq - samples[it].iqSet; e * e } / trackingSamples.size) / current
        }

        val estimable = tracked.filter { electrical[it].readable }
        val lambdaEst = median(estimable.map { samples[it].lambdaEst }.filter { it > 0.0 })
        val lambdaBemf = median(estimable.map { electrical[it].bemf / abs(electrical[it].omega) })

        // The rotor stream is only unambiguous while the motor is slow; stop where the probe saw it get faster.
        val slowUntil = samples.indices.firstOrNull { speed[it] > ROTOR_STREAM_MAX_ERPM }?.let { time[it] } ?: time.lastOrNull() ?: 0.0
        val reversals = rotor?.let { observerReversals(it, dir * internal, minOf(0.5, slowUntil)) }

        val reached = record.outcome == TrialOutcome.REACHED
        return TrialMetrics(
            outcome = record.outcome,
            targetErpm = target,
            reachSeconds = if (reached) record.end.reachMs / 1000.0 else null,
            progress = (speed.maxOrNull() ?: 0.0) / target,
            reverseErpm = max(0.0, -(speed.minOrNull() ?: 0.0)),
            openLoopEntries = runs.size,
            openLoopSeconds = runs.sumOf { time[it.last] - time[it.first] + period },
            handoverDip = handoverDip,
            handoverTorque = handoverTorque,
            following = following,
            angleErrorRms = angleRms,
            angleErrorMax = angles.maxOfOrNull { abs(it) },
            angleSamples = angles.size,
            roughness = roughness,
            trackingError = trackingError,
            dutyPeak = samples.maxOfOrNull { abs(it.duty) } ?: 0.0,
            lambdaEstRatio = lambdaEst?.let { if (lambda > 0) it / lambda else null },
            lambdaBemfRatio = lambdaBemf?.let { if (lambda > 0) it / lambda else null },
            resistanceEstRatio = record.end.resistanceEst.takeIf { it > 0.0 && motor.resistance > 0.0 }?.let { it / motor.resistance },
            phaseCurrents = adc?.let { phaseCurrents(it, current) },
            observerReversals = reversals,
            samples = samples.size,
        )
    }

    /**
     * Peak phase current, spread of the current vector length (amplitude-invariant Clarke transform, as the firmware
     * uses) and FOC angle jumps from a start recording; null for fewer than 20 samples.
     */
    fun phaseCurrents(adc: List<AdcSample>, current: Double): PhaseCurrentMetrics? {
        if (adc.size < 20 || current <= 0.0) return null
        val magnitudes = adc.map { a ->
            val alpha = (2.0 * a.current1 - a.current2 - a.current3) / 3.0
            val beta = (a.current2 - a.current3) / sqrt(3.0)
            hypot(alpha, beta)
        }
        val peak = adc.maxOf { maxOf(abs(it.current1), abs(it.current2), abs(it.current3)).toDouble() }
        val tail = magnitudes.drop(adc.size / 10)
        val mean = tail.average()
        val spread = sqrt(tail.sumOf { (it - mean) * (it - mean) } / tail.size)
        val angles = adc.map { it.phase * 360.0 / 250.0 }
        val jumps = angles.zipWithNext().count { (a, b) -> abs(wrap(b - a)) > 45.0 }
        return PhaseCurrentMetrics(
            peakRatio = peak / current,
            ripple = spread / current,
            angleJumps = jumps,
            samples = adc.size,
            sampleRate = median(adc.map { it.switchingFrequency.toDouble() }) ?: 0.0,
        )
    }

    /**
     * Acceleration right after the handover at [handover] (s) relative to the acceleration later in the start, from the
     * fast speed estimate [speed] (ERPM, positive forward) smoothed over ±3 samples; null when either part is too short.
     */
    internal fun handoverTorque(time: List<Double>, speed: List<Double>, handover: Double, target: Double): Double? {
        val span = SMOOTHING
        val accel = (span until speed.size - span).map { i ->
            val dt = time[i + span] - time[i - span]
            Triple(time[i], speed[i], if (dt > 0) (speed[i + span] - speed[i - span]) / dt else 0.0)
        }
        val window = accel.filter { it.first > handover && it.first <= handover + TORQUE_WINDOW }.map { it.third }
        val later = accel.filter { it.first > handover + TORQUE_WINDOW && it.second < 0.8 * target }.map { it.third }
        if (window.size < 5 || later.size < 5) return null
        val reference = median(later)!!
        if (reference <= 0.0) return null
        val low = window.sorted()[window.size / 5]
        return (low / reference).coerceIn(-1.0, 1.5)
    }

    /** Steps of the observer angle against [direction] (+1 or −1, internal) of more than 15° in the first [window] s. */
    fun observerReversals(angles: List<RotorAngle>, direction: Int, window: Double): Int? {
        val inWindow = angles.filter { it.seconds in 0.0..window }.sortedBy { it.seconds }
        if (inWindow.size < 5) return null
        return inWindow.zipWithNext().count { (a, b) ->
            val step = wrap(b.degrees - a.degrees)
            abs(step) < 90.0 && step * direction < -15.0
        }
    }

    /** Index runs of [flags] that are true for at least two samples in a row. */
    internal fun runs(flags: List<Boolean>): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (i in 0..flags.size) {
            val on = i < flags.size && flags[i]
            if (on && start < 0) start = i
            if (!on && start >= 0) {
                if (i - start >= 2) runs += start until i
                start = -1
            }
        }
        return runs
    }

    /** [degrees] folded into −180..180. */
    fun wrap(degrees: Double): Double = ((degrees % 360.0) + 540.0) % 360.0 - 180.0

    internal fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun medianStep(time: List<Double>): Double? = median(time.zipWithNext { a, b -> b - a }.filter { it > 0 })

    private class Electrical(val omega: Double, val bemf: Double, val delta: Double, val readable: Boolean)
}

/**
 * The terms of a start's cost, each scaled so that about 1 means "noticeably bad": as bad as taking twice the
 * reference time to reach the target speed.
 *
 * - [time]: time to target over the reference (the median of the starting configuration's starts).
 * - [reentries]: 1 per extra open-loop sequence; a second one means the observer lost the motor at low speed.
 * - [handover]: 2 × the speed lost after the observer took over.
 * - [handoverTorque]: up to 1.5 as the torque right after the handover falls from 80 % of normal to nothing, more if
 *   it reversed: a start that nearly failed.
 * - [reverse]: backward speed over 20 % of the target (an initial twitch depends on where the rotor stood, so it is noisy).
 * - [roughness]: speed ripple over 3 % of the target.
 * - [angle]: RMS observer angle error over 30°.
 * - [following]: 2 × how far the rotor fell short of 90 % of the open-loop speed.
 * - [phaseCurrent]: peak phase current beyond 1.5 × the command, plus half the vector-length spread over 15 %.
 * - [observerJitter]: 0.25 per backward step of the streamed observer angle at low speed.
 */
data class CostBreakdown(
    val time: Double,
    val reentries: Double,
    val handover: Double,
    val handoverTorque: Double,
    val reverse: Double,
    val roughness: Double,
    val angle: Double,
    val following: Double,
    val phaseCurrent: Double,
    val observerJitter: Double,
) {
    val total: Double get() = time + reentries + handover + handoverTorque + reverse + roughness + angle + following + phaseCurrent + observerJitter
}

object StartupCost {
    /** A start that faulted. */
    const val FAULT = 30.0

    /** A start that never reached the target costs this plus up to as much again for how far it got. */
    const val TIMEOUT = 10.0

    /**
     * The cost of a start, lower is better; null for outcomes that say nothing about the settings (stopped, never at
     * rest, lost, limits, script errors). [reachReference] is in seconds.
     */
    fun of(m: TrialMetrics, reachReference: Double): Double? = when (m.outcome) {
        TrialOutcome.FAULT -> FAULT
        TrialOutcome.TIMED_OUT -> TIMEOUT + TIMEOUT * (1.0 - m.progress.coerceIn(0.0, 1.0))
        TrialOutcome.REACHED -> breakdown(m, reachReference).total
        else -> null
    }

    fun breakdown(m: TrialMetrics, reachReference: Double): CostBreakdown {
        val reference = max(reachReference, 0.01)
        val phase = m.phaseCurrents?.let { max(0.0, it.peakRatio - 1.5) + 0.5 * it.ripple / 0.15 } ?: 0.0
        return CostBreakdown(
            time = (m.reachSeconds ?: reference) / reference,
            reentries = max(0, m.openLoopEntries - 1).toDouble(),
            handover = 2.0 * m.handoverDip,
            handoverTorque = m.handoverTorque?.let { (1.5 * (0.8 - it) / 0.8).coerceIn(0.0, 2.5) } ?: 0.0,
            reverse = m.reverseErpm / (0.2 * m.targetErpm),
            roughness = (m.roughness ?: 0.0) / 0.03,
            angle = (m.angleErrorRms ?: 0.0) / 30.0,
            following = m.following?.let { 2.0 * max(0.0, 0.9 - it) } ?: 0.0,
            phaseCurrent = phase,
            observerJitter = 0.25 * (m.observerReversals ?: 0),
        )
    }
}
