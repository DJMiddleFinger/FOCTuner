package app.foctuner.vesc

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** What an experiment steps through (VESC Tool's Experiments page). */
enum class ExperimentKind { Duty, Current, Rpm }

/**
 * Steps from [from] to [to] in [step] increments, holding each for [stepTime]. Unlike VESC Tool, which stops just
 * before the last step, [to] itself is held too. [step] is a magnitude; the direction follows from and to.
 */
data class ExperimentPlan(val kind: ExperimentKind, val from: Double, val to: Double, val step: Double, val stepTime: Duration) {
    init {
        require(step > 0) { "The step must be positive" }
        require(stepTime.isPositive()) { "Each step needs time" }
        require(steps <= MAX_STEPS) { "At most $MAX_STEPS steps" }
    }

    val steps: Int get() = (abs(to - from) / step).roundToInt() + 1
    val duration: Duration get() = stepTime * steps

    /** The value commanded [elapsed] into the run, or null once it is over. */
    fun valueAt(elapsed: Duration): Double? {
        val index = floor(elapsed / stepTime).toInt()
        if (index >= steps || index < 0) return null
        val direction = if (to >= from) 1 else -1
        val value = from + direction * step * index
        return if (direction > 0) value.coerceAtMost(to) else value.coerceAtLeast(to)
    }

    private companion object {
        const val MAX_STEPS = 10_000
    }
}

/** One row of an experiment: seconds since the start, the commanded value and what the controller reported. */
data class ExperimentSample(
    val seconds: Double,
    val commanded: Double,
    val currentIn: Float,
    val currentMotor: Float,
    val power: Float,
    val voltage: Float,
    val erpm: Float,
    val duty: Float,
    val tempMosfet: Float,
    val tempMotor: Float,
)

/**
 * Runs [plan] on this controller's motor: commands each step, reads the realtime values every [sampleInterval] and
 * reports them through [onSample]. The motor is released when the plan ends, on cancellation and on failure. Every
 * command carries a lease, so a dropped app releases the motor too.
 */
suspend fun VescController.runExperiment(
    plan: ExperimentPlan,
    sampleInterval: Duration = 100.milliseconds,
    timeSource: TimeSource = TimeSource.Monotonic,
    onSample: (ExperimentSample) -> Unit,
) {
    val start = timeSource.markNow()
    val lease = sampleInterval * 3
    try {
        while (true) {
            val elapsed = start.elapsedNow()
            val value = plan.valueAt(elapsed) ?: break
            when (plan.kind) {
                ExperimentKind.Duty -> motor.setDuty(value.toFloat(), lease)
                ExperimentKind.Current -> motor.setCurrent(value.toFloat(), lease)
                ExperimentKind.Rpm -> motor.setRpm(value.roundToInt(), lease)
            }
            val v = values()
            onSample(
                ExperimentSample(
                    seconds = elapsed.inWholeMilliseconds / 1000.0,
                    commanded = value,
                    currentIn = v.currentInput,
                    currentMotor = v.currentMotor,
                    power = v.currentInput * v.voltageInput,
                    voltage = v.voltageInput,
                    erpm = v.erpm,
                    duty = v.dutyCycle,
                    tempMosfet = v.tempMosfet,
                    tempMotor = v.tempMotor,
                ),
            )
            val next = sampleInterval - (start.elapsedNow() - elapsed)
            if (next.isPositive()) delay(next)
        }
    } finally {
        withContext(NonCancellable) { runCatching { motor.release() } }
    }
}
