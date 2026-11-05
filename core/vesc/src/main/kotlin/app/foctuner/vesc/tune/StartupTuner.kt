package app.foctuner.vesc.tune

import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.config.ParamType
import app.foctuner.config.ParamValue
import app.foctuner.vesc.AdcSample
import app.foctuner.vesc.FirmwareInfo
import app.foctuner.vesc.LispException
import app.foctuner.vesc.PositionDisplay
import app.foctuner.vesc.RealtimeField
import app.foctuner.vesc.RealtimeValues
import app.foctuner.vesc.SampleMode
import app.foctuner.vesc.VescController
import app.foctuner.vesc.VescException
import app.foctuner.vesc.VescLinkException
import app.foctuner.vesc.VescTimeoutException
import app.foctuner.vesc.lispStats
import app.foctuner.vesc.rotorPositions
import app.foctuner.vesc.sampleAdc
import app.foctuner.vesc.setLispRunning
import app.foctuner.vesc.stopSampling
import app.foctuner.vesc.values
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How the tuner tests. [current] (A) drives every start; a start succeeds once the motor passes [targetErpm] within
 * [timeLimit]. With [currentSweep] the starts of every candidate cycle through half, once and one and a half times
 * [current], so the result has to work across the currents a rider starts with, not at one. With [bothDirections] the
 * starts alternate direction; turn it off where the drivetrain shouldn't turn backwards or freewheels in reverse
 * (geared hubs), where reverse starts would test a different load. Every candidate gets [trialsPerCandidate] starts;
 * [thorough] adds the observer type and the open-loop timing to the search. At least [verifyPairs] interleaved starts
 * of the starting and the best settings decide whether the best is a real improvement, a few more when it is close.
 * [period] is the probe's sample period.
 */
data class TuneSettings(
    val current: Double,
    val targetErpm: Double,
    val timeLimit: Duration = 3.seconds,
    val trialsPerCandidate: Int = 3,
    val thorough: Boolean = false,
    val capturePhaseCurrents: Boolean = true,
    val streamObserverAngle: Boolean = true,
    val measureResistance: Boolean = true,
    val verifyPairs: Int = 3,
    val period: Duration = 4.milliseconds,
    val currentSweep: Boolean = false,
    val bothDirections: Boolean = true,
) {
    init {
        require(current > 0.0) { "the start current must be positive" }
        require(targetErpm > 0.0 && targetErpm <= MAX_TARGET_ERPM) { "target ERPM out of range" }
        require(trialsPerCandidate in 1..5) { "1..5 starts per candidate" }
        require(verifyPairs in 1..8) { "1..8 verification pairs" }
        require(timeLimit in 0.5.seconds..20.seconds) { "time limit out of range" }
    }

    /** The start currents (A, positive) the starts of a candidate cycle through, in order. */
    val currents: List<Double> get() = if (currentSweep) SWEEP.map { it * current } else listOf(current)

    companion object {
        /** The probe's samples hold ±32767 ERPM. */
        const val MAX_TARGET_ERPM = 30_000.0

        /** The sweep's currents over [current]; the lowest comes first, so the phase current recording gets it. */
        internal val SWEEP = listOf(0.5, 1.0, 1.5)
    }
}

/** What a stage of the search varies. */
enum class TuneStage {
    BASELINE,
    RESISTANCE,
    FLUX_LINKAGE,
    OBSERVER_TYPE,
    OBSERVER_GAIN,
    OPENLOOP_ERPM,
    OPENLOOP_LOCK_TIME,
    OPENLOOP_RAMP_TIME,
    OPENLOOP_TIME,
    VERIFY,
}

/** Values to try (motor config units); [changed] is what this candidate changes from the stage's starting point. */
data class Candidate(val stage: TuneStage, val params: Map<ProbeParam, Double>, val changed: ProbeParam? = null)

/**
 * One start of a candidate: the probe's record, what the analysis made of it, its cost (null when it doesn't count)
 * and, from the realtime values polled alongside, the highest battery current and lowest input voltage. A fault
 * comes with the firmware's `faults` terminal output.
 */
data class TrialResult(
    val candidate: Candidate,
    val record: TrialRecord,
    val metrics: TrialMetrics,
    val cost: Double?,
    val breakdown: CostBreakdown?,
    val inputCurrentPeak: Double? = null,
    val voltageMin: Double? = null,
    val faultLog: String? = null,
)

data class CandidateResult(val candidate: Candidate, val trials: List<TrialResult>) {
    val score: Double? get() = trials.mapNotNull { it.cost }.takeIf { it.isNotEmpty() }?.average()
    val reached: Int get() = trials.count { it.metrics.outcome == TrialOutcome.REACHED }
    val failures: Int get() = trials.count { it.metrics.outcome == TrialOutcome.TIMED_OUT || it.metrics.outcome == TrialOutcome.FAULT }
    val meanReach: Double? get() = trials.mapNotNull { it.metrics.reachSeconds }.takeIf { it.isNotEmpty() }?.average()
}

/**
 * Interleaved starts of the starting settings ([baseline]) and the best candidate ([best]), in pairs: the n-th start of
 * each ran next to the other with the same current and direction. The scores are medians, so one odd start among a few
 * doesn't decide; failed starts are counted on their own.
 */
data class Verification(val baseline: List<TrialResult>, val best: List<TrialResult>) {
    val baselineScore: Double? get() = StartupAnalysis.median(baseline.mapNotNull { it.cost })
    val bestScore: Double? get() = StartupAnalysis.median(best.mapNotNull { it.cost })

    val pairs: Int get() = minOf(baseline.size, best.size)

    /** Pairs in which the best settings' start cost less than the starting settings' start next to it. */
    val wins: Int
        get() = baseline.zip(best).count { (a, b) ->
            val ca = a.cost
            val cb = b.cost
            ca != null && cb != null && cb < ca
        }

    val baselineFailures: Int get() = failures(baseline)
    val bestFailures: Int get() = failures(best)

    /**
     * At least 10 % lower median cost, better in most pairs and no more failed starts than the starting settings.
     * Without a score on either side nothing is accepted.
     */
    val accepted: Boolean
        get() {
            val a = baselineScore ?: return false
            val b = bestScore ?: return false
            return b < a * (1 - IMPROVEMENT) && 2 * wins > pairs && bestFailures <= baselineFailures
        }

    /** Not accepted, but at least 3 % better with at most one more failed start: more pairs could decide. */
    val undecided: Boolean
        get() {
            if (accepted) return false
            val a = baselineScore ?: return false
            val b = bestScore ?: return false
            return b < a * (1 - CLOSE) && bestFailures <= baselineFailures + 1
        }

    private companion object {
        const val IMPROVEMENT = 0.1
        const val CLOSE = 0.03

        fun failures(list: List<TrialResult>) = list.count { it.metrics.outcome == TrialOutcome.TIMED_OUT || it.metrics.outcome == TrialOutcome.FAULT }
    }
}

/** Something the run showed that the search can't fix by itself. */
sealed interface Finding {
    /**
     * The firmware's resistance measurement differs from the configured value. [measured] (Ω) is what it read with the
     * motor at [temperature] (°C, null without a motor temperature sensor); [atReference] is that value taken back to
     * [referenceTemperature] with copper's temperature coefficient, which is what was compared. Without phase filters
     * the measurement is rough.
     */
    data class ResistanceMismatch(
        val measured: Double,
        val configured: Double,
        val phaseFilters: Boolean,
        val temperature: Double? = null,
        val atReference: Double = measured,
        val referenceTemperature: Double = RESISTANCE_REFERENCE_TEMP,
    ) : Finding

    /** The flux linkage seen while running (Wb) differs from the configured value. */
    data class FluxLinkageMismatch(val estimated: Double, val configured: Double) : Finding

    /** Starts ended in these fault codes; [log] is the firmware's fault log, when it could be read. */
    data class Faults(val codes: List<Int>, val log: String?) : Finding

    /** The duty cycle came close to its limit before the target speed: the target is too high for this voltage. */
    data class DutyLimited(val peak: Double) : Finding

    /** With the starting settings the firmware had to fall back to open loop again during a start ([entries] on average). */
    data class ObserverLosesMotor(val entries: Double) : Finding

    /** With the starting settings the motor turned backwards first, by up to [erpm]. */
    data class StartsBackwards(val erpm: Double) : Finding

    /** During open loop the rotor ran at [ratio] of the open-loop speed: it slips. */
    data class OpenLoopSlips(val ratio: Double) : Finding

    /** Phase current peaks reached [ratio] times the start current. */
    data class CurrentPeaks(val ratio: Double) : Finding
}

data class TuneReport(
    val original: Map<ProbeParam, Double>,
    val baseline: CandidateResult,
    val best: CandidateResult,
    val candidates: List<CandidateResult>,
    val verification: Verification?,
    /** The settings to use, or null when nothing was reliably better than the starting ones. */
    val recommended: Map<ProbeParam, Double>?,
    val measuredResistance: Double?,
    val reachReference: Double,
    val findings: List<Finding>,
    /** How the starting values got back on the controller; [RestoreMethod.FAILED] means a reboot restores them. */
    val restore: RestoreMethod = RestoreMethod.FAILED,
) {
    /** Whether the controller is back on its starting values; if not, a reboot restores them. */
    val restored: Boolean get() = restore != RestoreMethod.FAILED

    /** What [recommended] changes: parameter, starting value, new value. */
    val changes: List<Triple<ProbeParam, Double, Double>>
        get() = recommended.orEmpty().mapNotNull { (param, value) ->
            val from = original[param] ?: return@mapNotNull null
            if (sameValue(from, value)) null else Triple(param, from, value)
        }
}

/** How the starting values were set back at the end of a run. */
enum class RestoreMethod {
    /** The probe set them with conf-set (RAM only), as they were. */
    PROBE,

    /** The app went quiet and the probe set them back by itself (its dead man's switch). */
    PROBE_ON_ITS_OWN,

    /**
     * The probe didn't answer, so the motor config as read before the run was written. That write also stores it, so
     * changes that were only in RAM before the run are stored now too.
     */
    CONFIG_WRITE,

    /** Neither worked; a reboot restores them, the run only changed RAM. */
    FAILED,
}

sealed interface TuneProgress {
    data class Stage(val stage: TuneStage, val candidates: Int, val plannedTrials: Int) : TuneProgress
    data class Trial(val result: TrialResult, val done: Int, val plannedTrials: Int) : TuneProgress
    data class CandidateDone(val result: CandidateResult, val best: CandidateResult) : TuneProgress
}

/** Why the tuner won't start on this controller. */
enum class TuneRefusal { FIRMWARE_TOO_OLD, NOT_FOC, NOT_SENSORLESS, NOT_DETECTED, FAULT_ACTIVE, KILL_SWITCH, MOTOR_TURNING, LOW_VOLTAGE, TOO_HOT, NO_PROBE, CONFIG_MISMATCH }

/** Why a run stopped early. */
enum class TuneAbort { TEMPERATURE, VOLTAGE, KILL_SWITCH, REPEATED_FAULTS, NOT_AT_REST, PROBE_LOST }

class TuneRefusedException(val reason: TuneRefusal, detail: String? = null) : VescException("Startup tuning refused: $reason${detail?.let { " ($it)" } ?: ""}")

class TuneAbortedException(val reason: TuneAbort) : VescException("Startup tuning stopped: $reason") {
    /** How the starting values were set back after the stop; set by [StartupTuner.run] before the exception leaves it. */
    @Volatile
    var restore: RestoreMethod = RestoreMethod.FAILED
        internal set
}

/**
 * The temperatures and voltage a run stays within: 5 °C below the derating starts, 0.5 V above the battery cutoff.
 * [motorSensor] tells whether the motor temperature is measured at all.
 */
data class TuneLimits(val maxFetTemp: Double, val maxMotorTemp: Double, val minVoltage: Double, val motorSensor: Boolean = true) {
    companion object {
        fun from(config: Config): TuneLimits {
            val sensor = config.definition.param("m_motor_temp_sens_type").enumNames.indexOf("Disabled")
            val noMotorSensor = sensor >= 0 && config.int("m_motor_temp_sens_type") == sensor.toLong()
            return TuneLimits(
                maxFetTemp = config.number("l_temp_fet_start") - 5,
                maxMotorTemp = if (noMotorSensor) 1000.0 else config.number("l_temp_motor_start") - 5,
                minVoltage = config.number("l_battery_cut_start") + 0.5,
                motorSensor = !noMotorSensor,
            )
        }
    }
}

/** Copper's temperature coefficient of resistance near room temperature, per °C. */
const val COPPER_TEMP_COEFFICIENT = 0.00393

/**
 * The winding temperature (°C) resistances are compared at when the firmware's temperature compensation is off: the
 * configured value comes from detection, which usually runs on a cool motor. With compensation on, its base
 * temperature is used instead, since that is where the firmware takes the configured value to hold.
 */
const val RESISTANCE_REFERENCE_TEMP = 25.0

/** [ohm] measured at [from] °C, taken to [to] °C. */
fun resistanceAt(ohm: Double, from: Double, to: Double): Double = ohm * (1 + COPPER_TEMP_COEFFICIENT * (to - RESISTANCE_REFERENCE_TEMP)) /
    (1 + COPPER_TEMP_COEFFICIENT * (from - RESISTANCE_REFERENCE_TEMP))

/** Defaults for a motor config and the pack voltage now. */
object TuneDefaults {
    /** A quarter of the motor current limit, 2..40 A, in half amps. */
    fun current(config: Config): Double = (config.number("l_current_max") * 0.25).coerceIn(2.0, 40.0).let { (it * 2).roundToInt() / 2.0 }

    /**
     * Twice the highest open-loop ERPM the search tries, at least 1500, but no more than 40 % of the no-load speed
     * at [voltage] (V / (√3·λ) rad/s), so the duty cycle stays far from its limit.
     */
    fun target(config: Config, voltage: Double): Double {
        val openloop = config.number("foc_openloop_rpm").takeIf { it > 0 } ?: 1500.0
        val lambda = config.number("foc_motor_flux_linkage")
        val noLoad = if (lambda > 0 && voltage > 0) voltage / (sqrt(3.0) * lambda) * 60 / (2 * PI) else Double.MAX_VALUE
        val wanted = max(1500.0, 2 * OPENLOOP_FACTORS.max() * openloop)
        return minOf(wanted, 0.4 * noLoad, TuneSettings.MAX_TARGET_ERPM).let { (it / 100).roundToInt() * 100.0 }
    }

    fun settings(config: Config, voltage: Double, thorough: Boolean = false): TuneSettings =
        TuneSettings(current = current(config), targetErpm = target(config, voltage), thorough = thorough, trialsPerCandidate = 3, verifyPairs = if (thorough) 4 else 3)

    internal val GAIN_FACTORS = listOf(0.5, 0.7, 1.4, 2.0)
    internal val OPENLOOP_FACTORS = listOf(0.6, 0.8, 1.25, 1.6)
}

/**
 * Low-speed sensorless start tuning on one controller.
 *
 * It streams a LispBM probe (see [StartupProbe]) that runs starts with conf-set values (RAM only) and samples the
 * firmware's speed estimates, dq currents and voltages, observer and FOC angles and its flux linkage and resistance
 * estimates every few ms. Alongside every start it polls the realtime values (temperatures, input voltage and
 * current, kill switch) to stop when a limit is near, streams the observer angle (COMM_ROTOR_POSITION) and, for the
 * first start of every candidate, records the phase currents at the switching rate (COMM_SAMPLE_PRINT, start
 * trigger). Before the search it measures the motor resistance with the firmware's measurement; after a fault it
 * reads the firmware's fault log.
 *
 * The search changes one parameter at a time from the best values so far (coordinate descent), then compares the
 * best with the starting values in interleaved starts. Whatever happens, the starting values are set back at the end.
 * [config] is the motor config as read from the controller just before; [motorIndex] is 2 for the second motor of a
 * dual board.
 */
class StartupTuner(
    private val controller: VescController,
    private val firmware: FirmwareInfo,
    private val config: Config,
    val settings: TuneSettings,
    private val motorIndex: Int = 1,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val limits = TuneLimits.from(config)
    private val configured = MotorConstants(
        resistance = config.number("foc_motor_r"),
        inductance = config.number("foc_motor_l"),
        ldLqDiff = config.number("foc_motor_ld_lq_diff"),
        fluxLinkage = config.number("foc_motor_flux_linkage"),
        inverted = config.bool("m_invert_direction"),
    )

    /** Where the configured resistance holds: the compensation's base temperature when it is on (see [RESISTANCE_REFERENCE_TEMP]). */
    private val referenceTemp: Double =
        if ("foc_temp_comp" in config.definition.params && config.bool("foc_temp_comp") && "foc_temp_comp_base_temp" in config.definition.params) {
            config.number("foc_temp_comp_base_temp")
        } else {
            RESISTANCE_REFERENCE_TEMP
        }

    /** The start currents, at most the motor current limit (the firmware would clamp them there anyway). */
    private val currents: List<Double> = settings.currents.map { it.coerceAtMost(config.number("l_current_max")) }.distinct()

    /** The parameters this firmware lets the probe change, minus those the search doesn't use without [TuneSettings.thorough]. */
    val parameters: List<ProbeParam> = ProbeParam.entries.filter {
        it.supportedOn(firmware.major, firmware.minor) && it.configName in config.definition.params
    }

    /**
     * How the starting values were set back once [run] has ended; null before that and when the run changed nothing. A
     * report or a [TuneAbortedException] says the same, but a cancelled or failed run ends with neither.
     */
    @Volatile
    var restoreResult: RestoreMethod? = null
        private set

    /** The search stages after the baseline, in order. */
    val stages: List<TuneStage> = buildList {
        add(TuneStage.RESISTANCE)
        add(TuneStage.FLUX_LINKAGE)
        if (settings.thorough && ProbeParam.OBSERVER_TYPE in parameters) add(TuneStage.OBSERVER_TYPE)
        add(TuneStage.OBSERVER_GAIN)
        add(TuneStage.OPENLOOP_ERPM)
        if (settings.thorough) {
            add(TuneStage.OPENLOOP_LOCK_TIME)
            add(TuneStage.OPENLOOP_RAMP_TIME)
            add(TuneStage.OPENLOOP_TIME)
        }
    }

    /** How many starts a run takes when every stage has its usual number of candidates. */
    val plannedTrials: Int get() = estimateTrials(stages.size)

    private fun estimateTrials(fromStage: Int): Int {
        val candidates = stages.drop(stages.size - fromStage).sumOf { typicalCandidates(it) }
        return (1 + candidates) * settings.trialsPerCandidate + 2 * settings.verifyPairs
    }

    private fun typicalCandidates(stage: TuneStage): Int = when (stage) {
        TuneStage.RESISTANCE, TuneStage.FLUX_LINKAGE -> 0
        TuneStage.OBSERVER_TYPE -> config.definition.param("foc_observer_type").enumNames.size - 1
        TuneStage.OBSERVER_GAIN -> TuneDefaults.GAIN_FACTORS.size
        TuneStage.OPENLOOP_ERPM -> TuneDefaults.OPENLOOP_FACTORS.size
        else -> 2
    }

    /** Runs the tuning. The starting values are back on the controller when this returns or throws. */
    suspend fun run(onProgress: (TuneProgress) -> Unit = {}): TuneReport = coroutineScope {
        refusal(config, firmware)?.let { throw TuneRefusedException(it) }
        preflight(controller.values(), limits)?.let { throw TuneRefusedException(it) }

        val lispWasRunning = try {
            controller.lispStats(allBindings = false)
            true
        } catch (e: VescTimeoutException) {
            false
        }
        val clock = timeSource.markNow()
        val log = SideLog()
        val abort = AtomicReference<TuneAbort?>(null)
        val sideJobs = mutableListOf<Job>()
        var keepAlive: Job? = null
        var probe: StartupProbe? = null
        var original: Map<ProbeParam, Double>? = null
        var restore: RestoreMethod? = null
        var thrown: Throwable? = null
        try {
            probe = try {
                StartupProbe.start(controller, motorIndex)
            } catch (e: LispException) {
                throw TuneRefusedException(TuneRefusal.NO_PROBE, e.message)
            } catch (e: VescTimeoutException) {
                throw TuneRefusedException(TuneRefusal.NO_PROBE, e.message)
            }
            val held = probe
            // Keeps the probe's dead man's switch off while this runs; if the app dies, the probe restores by itself.
            keepAlive = launch { held.keepAlive() }
            original = readOriginal(probe)
            if (settings.streamObserverAngle && motorIndex == 1) {
                sideJobs += launch {
                    try {
                        controller.rotorPositions(PositionDisplay.OBSERVER).collect { log.angle(clock.elapsedNow(), it.toDouble()) }
                    } catch (e: IOException) {
                        // The monitor or the probe reports a dead link.
                    }
                }
            }
            sideJobs += launch { monitor(probe, clock, log, abort) }
            val search = Search(probe, original, clock, log, abort, onProgress)
            val report = search.run()
            restore = restore(probe, original)
            report.copy(restore = restore)
        } catch (e: Throwable) {
            thrown = e
            throw e
        } finally {
            withContext(NonCancellable) {
                // The keep-alive ping stops last: until the probe has the starting values back it must not give up.
                sideJobs.forEach { it.cancel() }
                probe?.let { runCatching { it.stop() } }
                if ((restore == null || restore == RestoreMethod.FAILED) && original != null) restore = restore(probe, original)
                keepAlive?.cancel()
                probe?.let { runCatching { it.quit() } }
                runCatching { controller.stopSampling() }
                runCatching { controller.setLispRunning(lispWasRunning) }
                restoreResult = restore
                (thrown as? TuneAbortedException)?.restore = restore ?: RestoreMethod.FAILED
            }
        }
    }

    private suspend fun readOriginal(probe: StartupProbe): Map<ProbeParam, Double> {
        val read = probe.get(parameters)
        val values = LinkedHashMap<ProbeParam, Double>()
        for (param in parameters) {
            val value = read[param] ?: continue
            val stored = config.number(param.configName)
            // Both read the same RAM config; a difference means the units are wrong somewhere, so change nothing.
            if (!sameValue(value, stored, 0.02)) throw TuneRefusedException(TuneRefusal.CONFIG_MISMATCH, "${param.configName}: $value vs $stored")
            // The RAM value itself, not the config's copy, which its wire format may have rounded.
            values[param] = value
        }
        if (ProbeParam.OBSERVER_GAIN !in values || ProbeParam.OPENLOOP_ERPM !in values) throw TuneRefusedException(TuneRefusal.NO_PROBE, "conf-get failed")
        return values
    }

    /**
     * Sets [original] back with the probe; if the probe already did that by itself, leaves it; failing both, writes the
     * config as it was read, which also stores it.
     */
    private suspend fun restore(probe: StartupProbe?, original: Map<ProbeParam, Double>): RestoreMethod {
        if (probe != null) {
            if (probe.gaveUp) return RestoreMethod.PROBE_ON_ITS_OWN
            val back = try {
                probe.set(original)
            } catch (e: IOException) {
                null
            }
            if (back != null && original.all { (param, value) -> back[param]?.let { sameValue(it, value) } == true }) return RestoreMethod.PROBE
            if (probe.gaveUp) return RestoreMethod.PROBE_ON_ITS_OWN
        }
        return try {
            controller.writeConfig(ConfigKind.MCCONF, config)
            RestoreMethod.CONFIG_WRITE
        } catch (e: IOException) {
            RestoreMethod.FAILED
        }
    }

    /** Polls temperatures, voltage, current and the kill switch; stops the running start when a limit is near. */
    private suspend fun monitor(probe: StartupProbe, clock: TimeMark, log: SideLog, abort: AtomicReference<TuneAbort?>) {
        while (true) {
            delay(MONITOR_PERIOD)
            val v = try {
                controller.values(MONITOR_FIELDS)
            } catch (e: VescLinkException) {
                return
            } catch (e: IOException) {
                // No reply or a malformed one; the next poll tries again.
                continue
            }
            log.realtime(clock.elapsedNow(), v)
            val reason = when {
                v.killSwitchActive -> TuneAbort.KILL_SWITCH
                v.tempMosfet > limits.maxFetTemp || v.tempMotor > limits.maxMotorTemp -> TuneAbort.TEMPERATURE
                v.voltageInput > 1f && v.voltageInput < limits.minVoltage -> TuneAbort.VOLTAGE
                else -> null
            }
            if (reason != null) abort.compareAndSet(null, reason)
            // On every poll, not once: a start clears the script's stop flag, so a stop that lands between starts is lost.
            if (abort.get() != null) probe.stop()
        }
    }

    private inner class Search(
        private val probe: StartupProbe,
        private val original: Map<ProbeParam, Double>,
        private val clock: TimeMark,
        private val log: SideLog,
        private val abort: AtomicReference<TuneAbort?>,
        private val onProgress: (TuneProgress) -> Unit,
    ) {
        /** The starting settings' median time to the target (s) at each start current; the time limit until known. */
        private val references = HashMap<Double, Double>()
        private val defaultReference = settings.timeLimit.toDouble(DurationUnit.SECONDS)
        private var timeLimit = settings.timeLimit
        private var done = 0
        private var planned = plannedTrials
        private var direction = 1
        private var consecutiveFaults = 0
        /** Starts in a row that didn't count (lost reports, a motor that wouldn't come to rest). */
        private var lostStarts = 0
        private var measuredResistance: Double? = null

        /** The motor temperature (°C) during the resistance measurement; null without a sensor. */
        private var measuredAt: Double? = null

        /** The measured resistance at [referenceTemp] when the temperature is known, else as measured. */
        private var measuredAtReference: Double? = null
        private val results = mutableListOf<CandidateResult>()

        /**
         * The winding resistance at [referenceTemp] the analysis starts from: the measured one when the hardware measures
         * it well, else the configured one. With a motor sensor it follows each start's motor temperature ([modelAt]).
         */
        private var baseResistance = configured.resistance

        /** How far the resistance may differ before it is tried or reported: more when the winding temperature is unknown. */
        private val resistanceAllowance: Double get() = if (measuredAt == null) UNKNOWN_TEMP_ALLOWANCE else 0.0

        suspend fun run(): TuneReport {
            if (settings.measureResistance) {
                measuredAt = motorTemperature()
                measuredResistance = try {
                    probe.measureResistance(minOf(settings.current, config.number("l_current_max") * 0.5))
                } catch (e: ProbeException) {
                    null
                } catch (e: VescTimeoutException) {
                    if (probe.gaveUp) throw TuneAbortedException(TuneAbort.PROBE_LOST)
                    null
                }
                val r = measuredResistance
                val t = measuredAt
                measuredAtReference = r?.let { if (t != null) resistanceAt(it, t, referenceTemp) else it }
                val atReference = measuredAtReference
                if (atReference != null && firmware.hasPhaseFilters && atReference in configured.resistance * 0.3..configured.resistance * 3.0) {
                    baseResistance = atReference
                }
            }

            onProgress(TuneProgress.Stage(TuneStage.BASELINE, 1, planned))
            var baseline = evaluate(Candidate(TuneStage.BASELINE, original))
            val reaches = baseline.trials.mapNotNull { it.metrics.reachSeconds }
            for (current in currents) {
                val at = baseline.trials.filter { abs(it.record.spec.current) == current }.mapNotNull { it.metrics.reachSeconds }
                StartupAnalysis.median(at)?.let { references[current] = it }
            }
            if (reaches.isNotEmpty()) {
                // A current none of whose starts made it is judged against the others.
                val all = StartupAnalysis.median(reaches)!!
                for (current in currents) references.putIfAbsent(current, all)
                if (reaches.size == baseline.trials.size) {
                    val limit = (2.5 * reaches.max() + 0.3).coerceIn(1.0, settings.timeLimit.toDouble(DurationUnit.SECONDS))
                    timeLimit = (limit * 1000).roundToInt().milliseconds
                }
            }
            baseline = rescore(baseline)
            results += baseline
            var best = baseline
            onProgress(TuneProgress.CandidateDone(baseline, best))

            for ((index, stage) in stages.withIndex()) {
                val candidates = candidatesFor(stage, best.candidate.params, baseline)
                planned = done + candidates.size * settings.trialsPerCandidate + estimateTrials(stages.size - index - 1) -
                    settings.trialsPerCandidate
                if (candidates.isEmpty()) continue
                onProgress(TuneProgress.Stage(stage, candidates.size, planned))
                for (candidate in candidates) {
                    val result = evaluate(candidate)
                    results += result
                    if (better(result, best)) best = result
                    onProgress(TuneProgress.CandidateDone(result, best))
                }
            }

            val verification = if (best.candidate.params != baseline.candidate.params) verify(baseline.candidate, best.candidate) else null
            val recommended = if (verification?.accepted == true) best.candidate.params else null
            return TuneReport(
                original = original,
                baseline = baseline,
                best = best,
                candidates = results.toList(),
                verification = verification,
                recommended = recommended,
                measuredResistance = measuredResistance,
                reachReference = references[settings.current.coerceAtMost(config.number("l_current_max"))] ?: references.values.firstOrNull() ?: defaultReference,
                findings = findings(baseline),
            )
        }

        private fun reference(spec: TrialSpec): Double = references[abs(spec.current)] ?: defaultReference

        /** The motor temperature now (°C), or null without a sensor or a reply. */
        private suspend fun motorTemperature(): Double? {
            if (!limits.motorSensor) return null
            return try {
                controller.values(setOf(RealtimeField.TEMP_MOTOR)).tempMotor.toDouble().takeIf { it > -40.0 && it < 200.0 }
            } catch (e: IOException) {
                null
            }
        }

        /** The motor model for a start that ended with the motor at [motorTemp] °C. */
        private fun modelAt(motorTemp: Double): MotorConstants {
            val t = motorTemp.takeIf { limits.motorSensor && it > -40.0 && it < 200.0 } ?: return configured.copy(resistance = baseResistance)
            return configured.copy(resistance = resistanceAt(baseResistance, referenceTemp, t))
        }

        private fun better(a: CandidateResult, b: CandidateResult): Boolean {
            val sa = a.score ?: return false
            val sb = b.score ?: return true
            return sa < sb * (1 - STAGE_IMPROVEMENT)
        }

        private fun rescore(result: CandidateResult) = result.copy(
            trials = result.trials.map { t ->
                val reference = reference(t.record.spec)
                t.copy(
                    cost = StartupCost.of(t.metrics, reference),
                    breakdown = if (t.metrics.outcome == TrialOutcome.REACHED) StartupCost.breakdown(t.metrics, reference) else null,
                )
            },
        )

        private suspend fun apply(params: Map<ProbeParam, Double>): Map<ProbeParam, Double> {
            val back = try {
                probe.set(params)
            } catch (e: VescTimeoutException) {
                if (probe.gaveUp) throw TuneAbortedException(TuneAbort.PROBE_LOST)
                throw e
            }
            // The firmware clamps to the hardware's limits; keep what it actually uses.
            return params.mapValues { (param, value) -> back[param] ?: value }
        }

        private suspend fun evaluate(candidate: Candidate): CandidateResult {
            val actual = candidate.copy(params = apply(candidate.params))
            val trials = mutableListOf<TrialResult>()
            while (trials.size < settings.trialsPerCandidate) {
                val current = currents[trials.size % currents.size]
                trials += countedTrial(actual, direction, current, capture = settings.capturePhaseCurrents && trials.isEmpty())
                if (settings.bothDirections) direction = -direction
            }
            return CandidateResult(actual, trials)
        }

        /** One start that counts: starts that say nothing about the settings are repeated a few times. */
        private suspend fun countedTrial(candidate: Candidate, dir: Int, current: Double, capture: Boolean): TrialResult {
            while (true) {
                if (probe.gaveUp) throw TuneAbortedException(TuneAbort.PROBE_LOST)
                abort.get()?.let { throw TuneAbortedException(it) }
                val result = try {
                    trial(candidate, dir, current, capture)
                } catch (e: ProbeException) {
                    // Busy with the previous start (its release report got lost) or refused: wait and try again.
                    if (++lostStarts > MAX_LOST_STARTS) throw TuneAbortedException(TuneAbort.PROBE_LOST)
                    if (capture) controller.stopSampling()
                    delay(BUSY_WAIT)
                    continue
                }
                abort.get()?.let { throw TuneAbortedException(it) }
                when (result.metrics.outcome) {
                    TrialOutcome.TEMPERATURE -> throw TuneAbortedException(TuneAbort.TEMPERATURE)
                    TrialOutcome.VOLTAGE -> throw TuneAbortedException(TuneAbort.VOLTAGE)
                    TrialOutcome.FAULT -> if (++consecutiveFaults >= MAX_CONSECUTIVE_FAULTS) throw TuneAbortedException(TuneAbort.REPEATED_FAULTS)
                    TrialOutcome.REACHED, TrialOutcome.TIMED_OUT -> consecutiveFaults = 0
                    TrialOutcome.NOT_AT_REST -> {
                        if (++lostStarts > MAX_LOST_STARTS) throw TuneAbortedException(TuneAbort.NOT_AT_REST)
                        continue
                    }
                    else -> {
                        if (++lostStarts > MAX_LOST_STARTS) throw TuneAbortedException(TuneAbort.PROBE_LOST)
                        continue
                    }
                }
                lostStarts = 0
                done++
                if (done > planned) planned = done
                onProgress(TuneProgress.Trial(result, done, planned))
                return result
            }
        }

        private suspend fun trial(candidate: Candidate, dir: Int, current: Double, capture: Boolean): TrialResult = coroutineScope {
            val spec = TrialSpec(
                id = 0,
                current = dir * current,
                targetErpm = settings.targetErpm,
                timeout = timeLimit,
                period = settings.period,
                brakeCurrent = current * 0.5,
                restErpm = max(REST_ERPM, 0.02 * settings.targetErpm),
                rest = REST,
                maxFetTemp = limits.maxFetTemp,
                maxMotorTemp = limits.maxMotorTemp,
                minVoltage = limits.minVoltage,
            )
            val adc = if (capture) {
                async {
                    try {
                        controller.sampleAdc(SampleMode.Start, ADC_LENGTH, decimation(candidate), waitForFirst = ADC_FIRST, quiet = ADC_QUIET)
                    } catch (e: IOException) {
                        emptyList<AdcSample>()
                    }
                }
            } else {
                null
            }
            // The drive started where (arrival − sample time) is smallest: the sample that travelled fastest.
            var start: Duration? = null
            val record = probe.trial(spec) { s ->
                val at = clock.elapsedNow() - s.seconds.seconds
                if (start.let { it == null || at < it }) start = at
            }
            val phaseCurrents = adc?.let { job ->
                withTimeoutOrNull(ADC_WAIT) { job.await() } ?: run {
                    job.cancel()
                    null
                }
            }
            if (capture && (phaseCurrents.isNullOrEmpty())) controller.stopSampling()
            val driveStart = start
            val driveLength = (record.samples.lastOrNull()?.seconds ?: 0.0).seconds
            val angles = driveStart?.let { s0 -> log.anglesBetween(s0, s0 + driveLength) }
            val realtime = driveStart?.let { s0 -> log.realtimeBetween(s0, s0 + driveLength + MONITOR_PERIOD) }.orEmpty()
            val metrics = StartupAnalysis.analyze(record, modelAt(record.end.motorTemp), phaseCurrents?.takeIf { it.isNotEmpty() }, angles)
            val faultLog = if (metrics.outcome == TrialOutcome.FAULT) {
                try {
                    controller.terminal("faults")
                } catch (e: IOException) {
                    null
                }
            } else {
                null
            }
            TrialResult(
                candidate = candidate,
                record = record,
                metrics = metrics,
                cost = StartupCost.of(metrics, reference(record.spec)),
                breakdown = if (metrics.outcome == TrialOutcome.REACHED) StartupCost.breakdown(metrics, reference(record.spec)) else null,
                inputCurrentPeak = realtime.maxOfOrNull { abs(it.currentInput.toDouble()) },
                voltageMin = realtime.map { it.voltageInput.toDouble() }.filter { it > 1.0 }.minOrNull(),
                faultLog = faultLog,
            )
        }

        /** Decimation that spreads the recording over the open-loop sequence and the handover after it. */
        private fun decimation(candidate: Candidate): Int {
            val p = candidate.params
            val window = (config.number("foc_sl_openloop_hyst") + (p[ProbeParam.OPENLOOP_LOCK_TIME] ?: 0.0) +
                (p[ProbeParam.OPENLOOP_RAMP_TIME] ?: 0.0) + (p[ProbeParam.OPENLOOP_TIME] ?: 0.0) + 0.3).coerceIn(0.3, 1.5)
            val doubled = config.int("foc_control_sample_mode") != 0L
            val rate = config.number("foc_f_zv") * (if (doubled) 2 else 1)
            return ceil(window * rate / ADC_LENGTH).toInt().coerceIn(1, 255)
        }

        /**
         * Interleaved pairs of starts, the same current and direction in each pair. After [TuneSettings.verifyPairs]
         * pairs a result that is better but not clearly ([Verification.undecided]) gets up to [EXTRA_VERIFY_PAIRS] more:
         * the best was picked as the lowest of many noisy scores, so it tends to look better in the search than it is.
         */
        private suspend fun verify(baseline: Candidate, best: Candidate): Verification {
            planned = done + 2 * settings.verifyPairs
            onProgress(TuneProgress.Stage(TuneStage.VERIFY, 2, planned))
            val a = mutableListOf<TrialResult>()
            val b = mutableListOf<TrialResult>()
            var pair = 0
            while (true) {
                val dir = if (!settings.bothDirections || pair % 2 == 0) 1 else -1
                val current = currents[pair % currents.size]
                apply(baseline.params)
                a += countedTrial(baseline.copy(stage = TuneStage.VERIFY), dir, current, capture = false)
                apply(best.params)
                b += countedTrial(best.copy(stage = TuneStage.VERIFY), dir, current, capture = false)
                pair++
                if (pair < settings.verifyPairs) continue
                val verification = Verification(a.toList(), b.toList())
                if (!verification.undecided || pair >= settings.verifyPairs + EXTRA_VERIFY_PAIRS) return verification
                planned = maxOf(planned, done + 2)
            }
        }

        private fun candidatesFor(stage: TuneStage, from: Map<ProbeParam, Double>, baseline: CandidateResult): List<Candidate> {
            fun vary(param: ProbeParam, values: List<Double>): List<Candidate> {
                if (param !in from) return emptyList()
                val current = from.getValue(param)
                val definition = config.definition.param(param.configName)
                return values.map { it.coerceIn(definition.minDouble, definition.maxDouble) }
                    .distinct()
                    .filter { !sameValue(it, current) }
                    .map { Candidate(stage, from + (param to it), param) }
            }
            fun scaled(param: ProbeParam, factors: List<Double>, ifZero: List<Double>, range: ClosedFloatingPointRange<Double>): List<Candidate> {
                val current = from[param] ?: return emptyList()
                val values = if (current <= 0.0) ifZero else factors.map { (current * it).coerceIn(range) }
                return vary(param, values)
            }
            return when (stage) {
                TuneStage.RESISTANCE -> {
                    val r = measuredAtReference ?: return emptyList()
                    val ratio = r / configured.resistance
                    if (firmware.hasPhaseFilters && abs(ratio - 1) > MODEL_TOLERANCE + resistanceAllowance && ratio in 0.3..3.0) {
                        vary(ProbeParam.MOTOR_RESISTANCE, listOf(r))
                    } else {
                        emptyList()
                    }
                }
                TuneStage.FLUX_LINKAGE -> {
                    val ratio = fluxLinkageRatio(baseline) ?: return emptyList()
                    if (abs(ratio - 1) > MODEL_TOLERANCE && ratio in 0.5..2.0) vary(ProbeParam.FLUX_LINKAGE, listOf(configured.fluxLinkage * ratio)) else emptyList()
                }
                TuneStage.OBSERVER_TYPE -> {
                    val types = config.definition.param("foc_observer_type").enumNames.indices.map { it.toDouble() }
                    vary(ProbeParam.OBSERVER_TYPE, types)
                }
                TuneStage.OBSERVER_GAIN -> scaled(ProbeParam.OBSERVER_GAIN, TuneDefaults.GAIN_FACTORS, emptyList(), 0.0..Double.MAX_VALUE)
                TuneStage.OPENLOOP_ERPM -> scaled(
                    ProbeParam.OPENLOOP_ERPM, TuneDefaults.OPENLOOP_FACTORS, listOf(500.0, 1000.0, 1500.0),
                    200.0..max(200.0, settings.targetErpm * 0.5),
                )
                TuneStage.OPENLOOP_LOCK_TIME -> scaled(ProbeParam.OPENLOOP_LOCK_TIME, listOf(0.0, 0.5, 2.0), listOf(0.1, 0.25), 0.0..1.0)
                TuneStage.OPENLOOP_RAMP_TIME -> scaled(ProbeParam.OPENLOOP_RAMP_TIME, listOf(0.5, 2.0), listOf(0.05, 0.1, 0.2), 0.02..1.5)
                TuneStage.OPENLOOP_TIME -> scaled(ProbeParam.OPENLOOP_TIME, listOf(0.5, 2.0), listOf(0.05, 0.1), 0.02..1.5)
                TuneStage.BASELINE, TuneStage.VERIFY -> emptyList()
            }
        }

        /**
         * The flux linkage the starting settings' starts showed, over the configured one: from |back-EMF| / speed and,
         * where the observer tracks it, its own estimate. Null unless the starts agree within 5 % and both sources,
         * when there are two, within 5 % of each other.
         */
        private fun fluxLinkageRatio(baseline: CandidateResult): Double? {
            fun consistent(values: List<Double>): Double? {
                if (values.size < 2) return null
                val median = StartupAnalysis.median(values)!!
                return median.takeIf { (values.max() - values.min()) / median < 0.05 }
            }
            val bemf = consistent(baseline.trials.mapNotNull { it.metrics.lambdaBemfRatio })
            val observer = consistent(baseline.trials.mapNotNull { it.metrics.lambdaEstRatio }.filter { abs(it - 1) > 1e-3 })
            return when {
                bemf != null && observer != null -> if (abs(bemf - observer) / bemf < 0.05) (bemf + observer) / 2 else null
                else -> bemf ?: observer
            }
        }

        private fun findings(baseline: CandidateResult): List<Finding> = buildList {
            val r = measuredResistance
            val atReference = measuredAtReference
            if (r != null && atReference != null && abs(atReference / configured.resistance - 1) > FINDING_TOLERANCE + resistanceAllowance) {
                add(Finding.ResistanceMismatch(r, configured.resistance, firmware.hasPhaseFilters, measuredAt, atReference, referenceTemp))
            }
            fluxLinkageRatio(baseline)?.let { ratio ->
                if (abs(ratio - 1) > FINDING_TOLERANCE) add(Finding.FluxLinkageMismatch(configured.fluxLinkage * ratio, configured.fluxLinkage))
            }
            val all = results.flatMap { it.trials }
            val faults = all.filter { it.metrics.outcome == TrialOutcome.FAULT }
            if (faults.isNotEmpty()) add(Finding.Faults(faults.map { it.record.end.fault }.distinct(), faults.firstNotNullOfOrNull { it.faultLog }))
            val duty = baseline.trials.maxOfOrNull { it.metrics.dutyPeak } ?: 0.0
            if (duty > 0.9) add(Finding.DutyLimited(duty))
            val entries = baseline.trials.map { it.metrics.openLoopEntries.toDouble() }.average()
            if (entries > 1.2) add(Finding.ObserverLosesMotor(entries))
            val reverse = baseline.trials.maxOfOrNull { it.metrics.reverseErpm } ?: 0.0
            if (reverse > 0.05 * settings.targetErpm) add(Finding.StartsBackwards(reverse))
            StartupAnalysis.median(baseline.trials.mapNotNull { it.metrics.following })?.let { if (it < 0.6) add(Finding.OpenLoopSlips(it)) }
            baseline.trials.mapNotNull { it.metrics.phaseCurrents?.peakRatio }.maxOrNull()?.let { if (it > 2.0) add(Finding.CurrentPeaks(it)) }
        }
    }

    /** What the side channels collected, by time since the run started. Written by their coroutines, read by the search. */
    private class SideLog {
        private val angles = ArrayList<Pair<Duration, Double>>()
        private val values = ArrayList<Pair<Duration, RealtimeValues>>()

        @Synchronized
        fun angle(at: Duration, degrees: Double) {
            angles += at to degrees
            if (angles.size > MAX_ENTRIES) angles.subList(0, angles.size - MAX_ENTRIES).clear()
        }

        @Synchronized
        fun realtime(at: Duration, v: RealtimeValues) {
            values += at to v
            if (values.size > MAX_ENTRIES) values.subList(0, values.size - MAX_ENTRIES).clear()
        }

        @Synchronized
        fun anglesBetween(from: Duration, to: Duration): List<RotorAngle> =
            angles.filter { it.first in from..to }.map { RotorAngle((it.first - from).toDouble(DurationUnit.SECONDS), it.second) }

        @Synchronized
        fun realtimeBetween(from: Duration, to: Duration): List<RealtimeValues> = values.filter { it.first in from..to }.map { it.second }

        private companion object {
            const val MAX_ENTRIES = 20_000
        }
    }

    companion object {
        /** Why tuning can't run with [config] on [firmware], or null if it can. */
        fun refusal(config: Config, firmware: FirmwareInfo): TuneRefusal? = when {
            firmware.major * 100 + firmware.minor < 605 -> TuneRefusal.FIRMWARE_TOO_OLD
            config.int("motor_type") != FOC -> TuneRefusal.NOT_FOC
            config.int("foc_sensor_mode") != SENSORLESS -> TuneRefusal.NOT_SENSORLESS
            config.number("foc_motor_r") <= 0.0 || config.number("foc_motor_l") <= 0.0 || config.number("foc_motor_flux_linkage") <= 0.0 ->
                TuneRefusal.NOT_DETECTED
            else -> null
        }

        /** Why the controller isn't ready right now ([values] just read), or null. */
        fun preflight(values: RealtimeValues, limits: TuneLimits): TuneRefusal? = when {
            values.faultCode != 0 -> TuneRefusal.FAULT_ACTIVE
            values.killSwitchActive -> TuneRefusal.KILL_SWITCH
            abs(values.erpm) > 300f -> TuneRefusal.MOTOR_TURNING
            values.voltageInput < limits.minVoltage + 0.5 -> TuneRefusal.LOW_VOLTAGE
            values.tempMosfet > limits.maxFetTemp - 10 || values.tempMotor > limits.maxMotorTemp - 10 -> TuneRefusal.TOO_HOT
            else -> null
        }

        private const val FOC = 2L
        private const val SENSORLESS = 0L
        private const val STAGE_IMPROVEMENT = 0.03
        private const val MODEL_TOLERANCE = 0.08

        /** Extra resistance difference allowed without a motor temperature sensor: about 25 °C of copper. */
        private const val UNKNOWN_TEMP_ALLOWANCE = 0.1
        private const val EXTRA_VERIFY_PAIRS = 3
        private const val FINDING_TOLERANCE = 0.1
        private const val MAX_CONSECUTIVE_FAULTS = 3
        private const val MAX_LOST_STARTS = 4
        private const val REST_ERPM = 60.0
        private const val ADC_LENGTH = 500
        private val REST = 250.milliseconds
        private val ADC_FIRST = 7.seconds
        private val ADC_QUIET = 1.seconds
        private val ADC_WAIT = 6.seconds
        private val MONITOR_PERIOD = 200.milliseconds
        private val BUSY_WAIT = 1.seconds
        private val MONITOR_FIELDS = setOf(
            RealtimeField.TEMP_MOSFET, RealtimeField.TEMP_MOTOR, RealtimeField.CURRENT_INPUT, RealtimeField.VOLTAGE_INPUT,
            RealtimeField.FAULT, RealtimeField.STATUS,
        )
    }
}

/**
 * Sets [values] with conf-set through a short-lived probe: they take effect at once and are gone after a reboot, so a
 * result can be ridden before it is stored. LispBM is left running (stored code restarted) or paused as it was.
 * Returns what the controller reads back, null where conf-set failed.
 */
suspend fun applyUntilReboot(controller: VescController, motorIndex: Int, values: Map<ProbeParam, Double>): Map<ProbeParam, Double?> {
    val lispWasRunning = try {
        controller.lispStats(allBindings = false)
        true
    } catch (e: VescTimeoutException) {
        false
    }
    var probe: StartupProbe? = null
    try {
        probe = StartupProbe.start(controller, motorIndex)
        return probe.set(values)
    } finally {
        withContext(NonCancellable) {
            probe?.let { runCatching { it.quit() } }
            runCatching { controller.setLispRunning(lispWasRunning) }
        }
    }
}

/** This config with [values] (config units); integer parameters are rounded. Parameters the layout lacks are skipped. */
fun Config.withProbeValues(values: Map<ProbeParam, Double>): Config = values.entries.fold(this) { c, (param, value) ->
    if (param.configName !in c.definition.params) {
        c
    } else {
        when (c.definition.param(param.configName).type) {
            ParamType.DOUBLE -> c.with(param.configName, value)
            else -> c.with(param.configName, Math.round(value))
        }
    }
}

/** A number from the config whether the parameter is stored as a float or as an integer. */
internal fun Config.number(name: String): Double = when (val v = get(name)) {
    is ParamValue.DoubleValue -> v.value
    is ParamValue.IntValue -> v.value.toDouble()
    else -> error("$name is not a number")
}

/** Equal within [tolerance] relative (and 1e-9 absolute), as values that went through 32-bit floats compare. */
internal fun sameValue(a: Double, b: Double, tolerance: Double = 1e-3): Boolean = abs(a - b) <= max(1e-9, tolerance * max(abs(a), abs(b)))
