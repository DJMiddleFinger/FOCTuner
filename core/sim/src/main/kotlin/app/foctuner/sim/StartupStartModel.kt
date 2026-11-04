package app.foctuner.sim

import app.foctuner.vesc.tune.ProbeSample
import app.foctuner.vesc.tune.TrialOutcome
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** What a simulated start runs with: the motor config's values (config units) and the probe's trial arguments. */
internal data class StartConditions(
    val current: Double,
    val targetErpm: Double,
    val timeout: Double,
    val period: Double,
    val voltage: Double,
    val currentMax: Double,
    val observerGain: Double,
    val observerType: Int,
    val openloopErpm: Double,
    val openloopLow: Double,
    val lockTime: Double,
    val rampTime: Double,
    val holdTime: Double,
    val hysteresis: Double,
    val boost: Double,
    val resistance: Double,
    val inductance: Double,
    val ldLqDiff: Double,
    val fluxLinkage: Double,
    val inverted: Boolean,
    val seed: Long,
    /** The real motor's resistance (Ω) and flux linkage (Wb), which the config values only approximate. */
    val trueResistance: Double = resistance * StartupStartModel.TRUE_RESISTANCE,
    val trueFluxLinkage: Double = fluxLinkage * StartupStartModel.TRUE_LAMBDA,
)

/**
 * A simulated start: the probe's samples, how it ended, and a fine trace (every [StartupStartModel.STEP] s) of the
 * FOC angle, the observer angle and the commanded current for the sampled-data and rotor-position simulations.
 */
internal class StartTrace(
    val samples: List<ProbeSample>,
    val outcome: TrialOutcome,
    val reachMs: Int,
    val endErpm: Double,
    val lambdaEst: Double,
    val resistanceEst: Double,
    val duration: Double,
    val focAngle: FloatArray,
    val observerAngle: FloatArray,
    val current: FloatArray,
) {
    /** Index of the fine trace at [seconds] after the drive started. */
    fun step(seconds: Double): Int = (seconds / StartupStartModel.STEP).toInt().coerceIn(0, focAngle.size - 1)
}

/**
 * A sensorless FOC start, simple but with the mechanisms that make starts good or bad:
 *
 * - The rotor: acceleration proportional to the torque-producing current (the current the controller applies,
 *   projected on the rotor's q axis through the controller's angle error), minus friction and a little cogging.
 * - The firmware's open-loop sequence as `mcpwm_foc.c` runs it: it starts once the speed estimate stayed below the
 *   open-loop ERPM (scaled with the current between `foc_openloop_rpm_low` and 1) for the hysteresis time, jumps the
 *   angle 60° ahead, holds it for the lock time, ramps the open-loop speed, holds it, and seeds the observer 45°
 *   ahead of the open-loop angle while it runs.
 * - The observer: it can only follow the rotor above a lock speed that falls with the square root of its gain
 *   (relative to the ideal 1e-3/λ², VESC Tool's formula, from the true flux linkage) and depends a little on its type;
 *   below it, it follows only part of the rotor's motion (none at standstill), so the rotor stalls against a current
 *   vector that no longer moves with it. Above it, its error decays at a rate proportional to the gain and the speed, its
 *   noise grows with the square root of the gain and falls with speed, and it carries a bias from a wrong flux
 *   linkage (mostly removed by the lambda-compensating types) and, at low speed, from a wrong resistance.
 *
 * The true motor differs from the config on purpose (by default flux linkage 10 % and resistance 15 % higher than the
 * config had when the probe started), so the tuner's model checks have something to find.
 */
internal object StartupStartModel {
    const val STEP = 0.0005
    const val TRUE_LAMBDA = 1.10
    const val TRUE_RESISTANCE = 1.15

    /** Rotor acceleration per amp of q current, ERPM/s, and friction: constant (ERPM/s) and per ERPM (1/s). */
    private const val ACCEL_PER_AMP = 400.0
    private const val COULOMB = 40.0
    private const val VISCOUS = 0.4
    private const val COGGING_AMPS = 0.3

    private const val LOCK_ERPM = 600.0
    private const val CONVERGENCE = 60.0
    private const val NOISE_DEGREES = 2.5
    private const val WANDER = 0.3
    private const val INITIAL_ERROR_DEGREES = 45.0
    private const val PLL_TAU = 0.005
    private const val FAST_TAU = 0.001

    private val LAMBDA_COMPENSATING = setOf(2, 3, 5, 6)

    fun run(c: StartConditions): StartTrace {
        val random = Random(c.seed)
        val dir = if (c.current < 0) -1.0 else 1.0
        val lambda = c.trueFluxLinkage
        val r = c.trueResistance
        val ld = c.inductance - c.ldLqDiff / 2
        val lq = c.inductance + c.ldLqDiff / 2
        val idealGain = 1e3 / (lambda * lambda)
        val gainRatio = (c.observerGain / idealGain).coerceAtLeast(1e-3)
        val typeFactor = when (c.observerType) {
            0, 2 -> 1.15
            1, 3 -> 0.9
            else -> 1.0
        }
        val lockErpm = LOCK_ERPM * sqrt(1 / gainRatio) * typeFactor
        val lambdaError = abs(c.fluxLinkage / lambda - 1) * 100
        val lambdaBias = Math.toRadians((if (c.observerType in LAMBDA_COMPENSATING) 0.15 else 1.0) * 2.5 * lambdaError)
        val resistanceError = abs(c.resistance / r - 1) * 100
        val vmax = c.voltage / sqrt(3.0) * 0.95
        val toRad = 2 * PI / 60

        var theta = random.nextDouble() * 2 * PI
        var omega = 0.0
        // The observer tracked the rotor down to low speed while the last start braked, so it starts near the rotor.
        var error = wrap(random.nextGaussian() * Math.toRadians(INITIAL_ERROR_DEGREES))
        var pll = 0.0
        var fast = 0.0
        var observerPrevious = theta + error
        var hystTimer = 0.0
        var olTimer = 0.0
        var olAngle = theta + error
        var lambdaEst = c.fluxLinkage
        var lockedTime = 0.0

        val steps = (c.timeout / STEP).toInt() + 1
        val focAngle = FloatArray(steps)
        val observerAngle = FloatArray(steps)
        val currentTrace = FloatArray(steps)
        val samples = ArrayList<ProbeSample>()
        var nextSample = 0.0
        var outcome = TrialOutcome.TIMED_OUT
        var reach = 0
        var t = 0.0
        var last = 0
        val total = c.lockTime + c.rampTime + c.holdTime
        val olCurrent = abs(c.current) + c.boost
        val olMaxErpm = min(c.openloopErpm, lerp(olCurrent / c.currentMax, c.openloopLow * c.openloopErpm, c.openloopErpm))

        for (step in 0 until steps) {
            t = step * STEP
            last = step
            val erpm = omega / toRad

            // The firmware's open-loop sequence, driven by its speed estimate.
            if (abs(pll / toRad) < olMaxErpm && hystTimer < c.hysteresis) hystTimer += STEP else if (hystTimer > 0) hystTimer -= STEP
            var startedNow = false
            if (hystTimer >= c.hysteresis && olTimer <= 1e-4) {
                olTimer = total
                startedNow = true
            }
            val override = olTimer > 0
            val observer: Double
            if (override) {
                val forward = total - olTimer
                val olErpm = when {
                    forward < c.lockTime -> 0.0
                    forward < c.lockTime + c.rampTime -> olMaxErpm * (forward - c.lockTime) / max(c.rampTime, 1e-6)
                    else -> olMaxErpm
                }
                if (startedNow) olAngle = theta + error + dir * PI / 3
                olAngle += dir * olErpm * toRad * STEP
                olTimer -= STEP
                hystTimer = 0.0
                // The observer is seeded 45° ahead of the open-loop angle while it runs.
                error = wrap(olAngle + dir * PI / 4 - theta)
                observer = theta + error
                // The deliberate 60° jump is not motion; keep it out of the speed estimates.
                if (startedNow) observerPrevious = observer
            } else {
                // The observer on its own.
                val speed = abs(erpm)
                if (speed >= lockErpm) {
                    val bias = lambdaBias + Math.toRadians(resistanceError * 600 / max(speed, 300.0))
                    val rate = CONVERGENCE * gainRatio * min(speed / 1500, 2.0)
                    error += -(error - bias * dir) * min(1.0, rate * STEP)
                    lockedTime += STEP
                    if (c.observerType in LAMBDA_COMPENSATING) lambdaEst = c.fluxLinkage + (lambda - c.fluxLinkage) * (1 - exp(-lockedTime / 0.25))
                } else {
                    // Too little back-EMF: the observer only partly follows the rotor, so its error grows with the
                    // rotor's motion (at standstill it stays where it was), plus a slow drift.
                    val follow = (speed / lockErpm).let { it * it }
                    error = wrap(error + (follow - 1) * omega * STEP + random.nextGaussian() * WANDER * sqrt(STEP))
                }
                observer = theta + error
                olAngle = observer
            }

            // Speed estimates from the observer angle (the noise is on the reading, not on the state).
            val observerRate = wrap(observer - observerPrevious) / STEP
            observerPrevious = observer
            pll += (observerRate - pll) * STEP / PLL_TAU
            fast += (observerRate - fast) * STEP / FAST_TAU

            // Torque from the current on the controller's q axis, through its angle error to the rotor.
            val frame = if (override) olAngle else observer
            val delta = wrap(frame - theta)
            val headroom = ((vmax - abs(omega) * lambda) / (r * abs(c.current) + 1e-3)).coerceIn(0.0, 1.0)
            val current = c.current * headroom
            val torqueAmps = current * cos(delta) + COGGING_AMPS * sin(6 * theta)
            val friction = COULOMB * sign(omega) + VISCOUS * erpm
            var nextErpm = erpm + (ACCEL_PER_AMP * torqueAmps - friction) * STEP
            if (nextErpm * erpm < 0 && abs(ACCEL_PER_AMP * torqueAmps) < COULOMB) nextErpm = 0.0
            omega = nextErpm * toRad
            theta += omega * STEP

            focAngle[step] = Math.toDegrees(norm(frame)).toFloat()
            observerAngle[step] = Math.toDegrees(norm(observer)).toFloat()
            currentTrace[step] = current.toFloat()

            if (t + 1e-9 >= nextSample) {
                nextSample += c.period
                val noise = Math.toRadians(NOISE_DEGREES * sqrt(gainRatio) * 1500 / max(abs(erpm), 300.0)) * random.nextGaussian()
                val observedDeg = Math.toDegrees(norm(observer + noise))
                val motorDeg = if (override) Math.toDegrees(norm(olAngle)) else observedDeg
                // Voltages: the motor's dq equations in the rotor frame, turned into the controller's frame.
                val idRotor = -current * sin(delta)
                val iqRotor = current * cos(delta)
                val vdRotor = r * idRotor - omega * lq * iqRotor
                val vqRotor = r * iqRotor + omega * ld * idRotor + omega * lambda
                val vd = cos(delta) * vdRotor + sin(delta) * vqRotor + 0.03 * random.nextGaussian()
                val vq = -sin(delta) * vdRotor + cos(delta) * vqRotor + 0.03 * random.nextGaussian()
                val user = if (c.inverted) -1.0 else 1.0
                samples += ProbeSample(
                    index = samples.size,
                    seconds = t,
                    erpm = user * pll / toRad,
                    erpmFast = fast / toRad + 20 * sqrt(gainRatio) * random.nextGaussian(),
                    iq = user * (current + 0.02 * abs(current) * random.nextGaussian()),
                    id = 0.02 * abs(current) * random.nextGaussian(),
                    iqSet = user * c.current,
                    vd = vd,
                    vq = vq,
                    phaseObserver = observedDeg,
                    phaseMotor = motorDeg,
                    duty = user * hypot(vd, vq) / (c.voltage / sqrt(3.0)) * sign(vq.takeIf { it != 0.0 } ?: 1.0),
                    lambdaEst = lambdaEst,
                    resistanceEst = c.resistance,
                )
                if (dir * pll / toRad > c.targetErpm) {
                    outcome = TrialOutcome.REACHED
                    reach = (t * 1000).toInt()
                    break
                }
            }
        }
        return StartTrace(
            samples = samples,
            outcome = outcome,
            reachMs = reach,
            endErpm = omega / toRad,
            lambdaEst = lambdaEst,
            resistanceEst = c.resistance,
            duration = t,
            focAngle = focAngle.copyOf(last + 1),
            observerAngle = observerAngle.copyOf(last + 1),
            current = currentTrace.copyOf(last + 1),
        )
    }

    /** How long braking with [brakeCurrent] (A) takes from [erpm] down to the probe's 150 ERPM, at most 3 s. */
    fun brakeSeconds(erpm: Double, brakeCurrent: Double): Double =
        if (abs(erpm) <= 150) 0.0 else min(3.0, (abs(erpm) - 150) / (ACCEL_PER_AMP * abs(brakeCurrent) + COULOMB))

    private fun lerp(fraction: Double, from: Double, to: Double) = from + (to - from) * fraction.coerceIn(0.0, 1.0)
    private fun wrap(a: Double): Double = ((a + PI) % (2 * PI) + 2 * PI) % (2 * PI) - PI
    private fun norm(a: Double): Double = ((a % (2 * PI)) + 2 * PI) % (2 * PI)
}
