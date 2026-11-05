package app.foctuner.vesc.tune

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.vesc.LispCode
import app.foctuner.vesc.LispStreamMode
import app.foctuner.vesc.VescController
import app.foctuner.vesc.VescException
import app.foctuner.vesc.VescLinkException
import app.foctuner.vesc.VescTimeoutException
import app.foctuner.vesc.customAppData
import app.foctuner.vesc.sendCustomAppData
import app.foctuner.vesc.streamLisp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/*
 * The LispBM probe the startup tuner streams to the controller (resources/.../tune/startup_probe.lisp) and the
 * COMM_CUSTOM_APP_DATA protocol it speaks. The script's header comment is the reference for the layout; this file
 * and the script have to change together (StartupProbeScriptTest checks the sample layout against the script).
 */

/**
 * What the probe reads and changes with conf-get / conf-set, in the order of the script's `ft-params`. Values in
 * this app are in motor config units (what [app.foctuner.config.Config] holds); [toLisp] converts to the units
 * conf-set uses. [since] is the first firmware whose conf-set knows the parameter.
 */
enum class ProbeParam(val configName: String, val toLisp: Double, val since: Int) {
    OBSERVER_GAIN("foc_observer_gain", 1e-6, 605),
    OPENLOOP_ERPM("foc_openloop_rpm", 1.0, 605),
    OPENLOOP_ERPM_LOW("foc_openloop_rpm_low", 1.0, 605),
    OPENLOOP_LOCK_TIME("foc_sl_openloop_time_lock", 1.0, 605),
    OPENLOOP_RAMP_TIME("foc_sl_openloop_time_ramp", 1.0, 605),
    OPENLOOP_TIME("foc_sl_openloop_time", 1.0, 605),
    OBSERVER_TYPE("foc_observer_type", 1.0, 606),
    MOTOR_RESISTANCE("foc_motor_r", 1e3, 605),
    FLUX_LINKAGE("foc_motor_flux_linkage", 1e3, 605),
    ;

    /** Whether conf-set and conf-get on firmware [major].[minor] know this parameter. */
    fun supportedOn(major: Int, minor: Int): Boolean = major * 100 + minor >= since
}

/**
 * One sample the probe took during a start. [erpm], [iq], [iqSet] and [duty] are in the rider's direction (they
 * follow `m_invert_direction` like the motor commands); [erpmFast], [id], [vd], [vq] and the phases are the
 * firmware's internal values. Phases in degrees, [lambdaEst] in Wb, [resistanceEst] in Ω.
 */
data class ProbeSample(
    val index: Int,
    val seconds: Double,
    val erpm: Double,
    val erpmFast: Double,
    val iq: Double,
    val id: Double,
    val iqSet: Double,
    val vd: Double,
    val vq: Double,
    val phaseObserver: Double,
    val phaseMotor: Double,
    val duty: Double,
    val lambdaEst: Double,
    val resistanceEst: Double,
)

/** One start: [current] (A) is signed and sets the direction; the probe stops at [targetErpm] in that direction. */
data class TrialSpec(
    val id: Int,
    val current: Double,
    val targetErpm: Double,
    val timeout: Duration,
    val period: Duration,
    val brakeCurrent: Double,
    val restErpm: Double,
    val rest: Duration,
    val maxFetTemp: Double,
    val maxMotorTemp: Double,
    val minVoltage: Double,
) {
    init {
        require(id in 0..0xFFFF) { "trial id must fit 16 bits" }
        require(timeout.inWholeMilliseconds in 1..60_000) { "timeout out of range" }
        require(period.inWholeMilliseconds in 1..255) { "period out of range" }
        require(rest.inWholeMilliseconds in 0..60_000) { "rest out of range" }
    }

    val direction: Int get() = if (current < 0) -1 else 1
}

/** How a start ended, by the probe's outcome code; [LOST] is the app's: no end report arrived. */
enum class TrialOutcome(val code: Int) {
    REACHED(0),
    TIMED_OUT(1),
    FAULT(2),
    STOPPED(3),
    NOT_AT_REST(4),
    TEMPERATURE(5),
    VOLTAGE(6),
    SCRIPT_ERROR(7),
    LOST(-1),
    ;

    companion object {
        fun fromCode(code: Int): TrialOutcome = entries.firstOrNull { it.code == code } ?: SCRIPT_ERROR
    }
}

/** The probe's end report: values read when the drive phase ended, before braking. */
data class TrialEnd(
    val trial: Int,
    val outcome: TrialOutcome,
    val samples: Int,
    val reachMs: Int,
    val fault: Int,
    val voltage: Double,
    val fetTemp: Double,
    val motorTemp: Double,
    val lambdaEst: Double,
    val resistanceEst: Double,
)

/** A finished start: what was asked, how it ended and every sample that arrived (lost packets leave gaps). */
data class TrialRecord(val spec: TrialSpec, val end: TrialEnd, val samples: List<ProbeSample>) {
    val outcome: TrialOutcome get() = end.outcome
}

/** What the probe sends. */
sealed interface ProbeMessage {
    data class Hello(val version: Int, val motor: Int) : ProbeMessage
    data class Params(val values: Map<ProbeParam, Double?>) : ProbeMessage
    data class Data(val trial: Int, val first: Int, val samples: List<ProbeSample>) : ProbeMessage
    data class End(val end: TrialEnd) : ProbeMessage
    data class Error(val code: Int) : ProbeMessage
    data object Bye : ProbeMessage
    data class Resistance(val ohm: Double?) : ProbeMessage
    data class Released(val trial: Int) : ProbeMessage

    /** The app went quiet: the probe set the values it found at the first hello back and stopped answering. */
    data object GaveUp : ProbeMessage
}

/** The probe refused a command or stopped answering. */
class ProbeException(message: String) : VescException(message)

/** Encoding of the commands and decoding of the probe's packets. */
object ProbeCodec {
    const val VERSION = 2
    private const val MAGIC_F = 70
    private const val MAGIC_T = 84

    /** Samples per data packet and bytes per sample, as the script's ft-pkt holds them. */
    const val SAMPLES_PER_PACKET = 8
    const val SAMPLE_SIZE = 26
    const val DATA_HEADER = 9

    const val HELLO = 1
    const val SET = 2
    const val GET = 3
    const val TRIAL = 4
    const val STOP = 5
    const val QUIT = 6
    const val MEASURE_RESISTANCE = 7
    const val PING = 8

    const val HELLO_REPLY = 129
    const val PARAMS_REPLY = 130
    const val DATA = 132
    const val END = 133
    const val ERROR = 134
    const val BYE = 135
    const val RESISTANCE = 136
    const val RELEASED = 137
    const val GAVE_UP = 138

    /** Seconds without a command (and nothing running) after which the probe restores and stops, as `ft-quiet`. */
    const val QUIET_SECONDS = 5.0

    /** The script's error code for a command that needs the probe idle. */
    const val BUSY = 1

    private fun head(type: Int) = ByteWriter().u8(MAGIC_F).u8(MAGIC_T).u8(VERSION).u8(type)

    fun hello(motor: Int): ByteArray = head(HELLO).u8(motor).toByteArray()

    fun set(values: Map<ProbeParam, Double>): ByteArray {
        require(values.size in 1..ProbeParam.entries.size) { "1..${ProbeParam.entries.size} values" }
        val w = head(SET).u8(values.size)
        for ((param, value) in values) w.u8(param.ordinal).ieee(value * param.toLisp)
        return w.toByteArray()
    }

    fun get(params: Collection<ProbeParam>): ByteArray {
        require(params.size in 1..ProbeParam.entries.size) { "1..${ProbeParam.entries.size} parameters" }
        val w = head(GET).u8(params.size)
        for (param in params) w.u8(param.ordinal)
        return w.toByteArray()
    }

    fun trial(spec: TrialSpec): ByteArray = head(TRIAL)
        .u16(spec.id)
        .ieee(spec.current)
        .ieee(spec.targetErpm)
        .u16(spec.timeout.inWholeMilliseconds.toInt())
        .u8(spec.period.inWholeMilliseconds.toInt())
        .ieee(spec.brakeCurrent)
        .ieee(spec.restErpm)
        .u16(spec.rest.inWholeMilliseconds.toInt())
        .ieee(spec.maxFetTemp)
        .ieee(spec.maxMotorTemp)
        .ieee(spec.minVoltage)
        .toByteArray()

    fun stop(): ByteArray = head(STOP).toByteArray()

    fun quit(): ByteArray = head(QUIT).toByteArray()

    fun ping(): ByteArray = head(PING).toByteArray()

    fun measureResistance(current: Double): ByteArray = head(MEASURE_RESISTANCE).ieee(current).toByteArray()

    /** The command a [payload] from the app carries, or null if it isn't one; for the simulator. */
    fun commandOf(payload: ByteArray): Int? =
        if (payload.size >= 4 && payload.u(0) == MAGIC_F && payload.u(1) == MAGIC_T && payload.u(2) == VERSION) payload.u(3) else null

    /** A packet from the probe (custom app data without the command id), or null if it isn't one. */
    fun parse(payload: ByteArray): ProbeMessage? {
        if (payload.size < 4 || payload.u(0) != MAGIC_F || payload.u(1) != MAGIC_T || payload.u(2) != VERSION) return null
        val r = ByteReader(payload, 4)
        return try {
            when (payload.u(3)) {
                HELLO_REPLY -> ProbeMessage.Hello(r.u8(), r.u8())
                PARAMS_REPLY -> {
                    val n = r.u8()
                    val values = LinkedHashMap<ProbeParam, Double?>()
                    repeat(n) {
                        val param = ProbeParam.entries.getOrNull(r.u8())
                        val ok = r.u8() != 0
                        val value = r.ieee()
                        if (param != null) values[param] = if (ok && value.isFinite()) value / param.toLisp else null
                    }
                    ProbeMessage.Params(values)
                }
                DATA -> {
                    val trial = r.u16()
                    val first = r.u16()
                    val count = r.u8().coerceAtMost(SAMPLES_PER_PACKET)
                    ProbeMessage.Data(trial, first, List(count) { sample(r, first + it) })
                }
                END -> ProbeMessage.End(
                    TrialEnd(
                        trial = r.u16(),
                        outcome = TrialOutcome.fromCode(r.u8()),
                        samples = r.u16(),
                        reachMs = r.u16(),
                        fault = r.u8(),
                        voltage = r.ieee(),
                        fetTemp = r.ieee(),
                        motorTemp = r.ieee(),
                        lambdaEst = r.ieee(),
                        resistanceEst = r.ieee(),
                    ),
                )
                ERROR -> ProbeMessage.Error(r.u8())
                BYE -> ProbeMessage.Bye
                RESISTANCE -> {
                    val ok = r.u8() != 0
                    val ohm = r.ieee()
                    ProbeMessage.Resistance(ohm.takeIf { ok && it.isFinite() && it > 0.0 })
                }
                RELEASED -> ProbeMessage.Released(r.u16())
                GAVE_UP -> ProbeMessage.GaveUp
                else -> null
            }
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    /** The sample layout of `ft-sample`, in order. */
    internal enum class Field(val offset: Int, val signed: Boolean, val lisp: String, val scale: Double) {
        TIME(0, false, "(* 1000.0 (secs-since ft-t0))", 1000.0),
        ERPM(2, true, "(get-rpm)", 1.0),
        ERPM_FAST(4, true, "(get-rpm-faster)", 1.0),
        IQ(6, true, "(* 50 (get-iq))", 50.0),
        ID(8, true, "(* 50 (get-id))", 50.0),
        IQ_SET(10, true, "(* 50 (get-iq-set))", 50.0),
        VD(12, true, "(* 100 (get-vd))", 100.0),
        VQ(14, true, "(* 100 (get-vq))", 100.0),
        PHASE_OBSERVER(16, false, "(* 100 (phase-observer))", 100.0),
        PHASE_MOTOR(18, false, "(* 100 (phase-motor))", 100.0),
        DUTY(20, true, "(* 10000 (get-duty))", 10000.0),
        LAMBDA(22, false, "(* 100000 (get-est-lambda))", 100000.0),
        RESISTANCE(24, false, "(* 10000 (get-est-res))", 10000.0),
    }

    private fun sample(r: ByteReader, index: Int): ProbeSample {
        val raw = DoubleArray(Field.entries.size)
        for (f in Field.entries) raw[f.ordinal] = (if (f.signed) r.i16() else r.u16()) / f.scale
        return ProbeSample(
            index = index,
            seconds = raw[Field.TIME.ordinal],
            erpm = raw[Field.ERPM.ordinal],
            erpmFast = raw[Field.ERPM_FAST.ordinal],
            iq = raw[Field.IQ.ordinal],
            id = raw[Field.ID.ordinal],
            iqSet = raw[Field.IQ_SET.ordinal],
            vd = raw[Field.VD.ordinal],
            vq = raw[Field.VQ.ordinal],
            phaseObserver = raw[Field.PHASE_OBSERVER.ordinal],
            phaseMotor = raw[Field.PHASE_MOTOR.ordinal],
            duty = raw[Field.DUTY.ordinal],
            lambdaEst = raw[Field.LAMBDA.ordinal],
            resistanceEst = raw[Field.RESISTANCE.ordinal],
        )
    }

    // Probe-side encoders, for the simulator and tests.

    fun helloReply(motor: Int): ByteArray = head(HELLO_REPLY).u8(VERSION).u8(motor).toByteArray()

    fun paramsReply(values: List<Pair<ProbeParam, Double?>>): ByteArray {
        val w = head(PARAMS_REPLY).u8(values.size)
        for ((param, value) in values) w.u8(param.ordinal).u8(if (value != null) 1 else 0).ieee((value ?: 0.0) * param.toLisp)
        return w.toByteArray()
    }

    /** A full-size data packet as the script sends it: [samples] (at most 8) first, the rest zero. */
    fun data(trial: Int, first: Int, samples: List<ProbeSample>): ByteArray {
        require(samples.size <= SAMPLES_PER_PACKET) { "at most $SAMPLES_PER_PACKET samples" }
        val w = head(DATA).u16(trial).u16(first).u8(samples.size)
        for (s in samples) {
            val values = doubleArrayOf(
                s.seconds, s.erpm, s.erpmFast, s.iq, s.id, s.iqSet, s.vd, s.vq,
                s.phaseObserver, s.phaseMotor, s.duty, s.lambdaEst, s.resistanceEst,
            )
            for (f in Field.entries) {
                val v = values[f.ordinal] * f.scale
                // Like the script: clamp, then truncate toward zero as the firmware's int conversion does.
                if (f.signed) w.i16(v.coerceIn(-32767.0, 32767.0).toInt()) else w.u16(v.coerceIn(0.0, 65535.0).toInt())
            }
        }
        repeat(SAMPLES_PER_PACKET - samples.size) { repeat(SAMPLE_SIZE) { w.u8(0) } }
        return w.toByteArray()
    }

    fun end(end: TrialEnd): ByteArray = head(END)
        .u16(end.trial).u8(end.outcome.code.coerceAtLeast(0)).u16(end.samples).u16(end.reachMs.coerceIn(0, 65535)).u8(end.fault)
        .ieee(end.voltage).ieee(end.fetTemp).ieee(end.motorTemp).ieee(end.lambdaEst).ieee(end.resistanceEst)
        .toByteArray()

    fun error(code: Int): ByteArray = head(ERROR).u8(code).toByteArray()

    fun bye(): ByteArray = head(BYE).toByteArray()

    fun resistanceReply(ohm: Double?): ByteArray = head(RESISTANCE).u8(if (ohm != null) 1 else 0).ieee(ohm ?: 0.0).toByteArray()

    fun released(trial: Int): ByteArray = head(RELEASED).u16(trial).toByteArray()

    fun gaveUp(): ByteArray = head(GAVE_UP).toByteArray()

    /** Decodes a TRIAL command, for the simulator. */
    fun parseTrial(payload: ByteArray): TrialSpec? {
        if (commandOf(payload) != TRIAL || payload.size < 39) return null
        val r = ByteReader(payload, 4)
        val id = r.u16()
        val current = r.ieee()
        val target = r.ieee()
        val timeout = r.u16()
        val period = r.u8()
        val brake = r.ieee()
        val restErpm = r.ieee()
        val rest = r.u16()
        return TrialSpec(
            id, current, target, timeout.coerceAtLeast(1).milliseconds, period.coerceAtLeast(1).milliseconds, brake, restErpm,
            rest.milliseconds, r.ieee(), r.ieee(), r.ieee(),
        )
    }

    /** Decodes SET (values in config units) or GET (values null), for the simulator. */
    fun parseParams(payload: ByteArray): List<Pair<ProbeParam?, Double?>> {
        val command = commandOf(payload) ?: return emptyList()
        val r = ByteReader(payload, 4)
        return try {
            List(r.u8()) {
                val param = ProbeParam.entries.getOrNull(r.u8())
                if (command == SET) param to r.ieee().let { v -> param?.let { v / it.toLisp } } else param to null
            }
        } catch (e: IndexOutOfBoundsException) {
            emptyList()
        }
    }

    fun parseMeasureResistance(payload: ByteArray): Double? =
        if (commandOf(payload) == MEASURE_RESISTANCE && payload.size >= 8) ByteReader(payload, 4).ieee() else null

    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF
    private fun ByteWriter.ieee(v: Double) = u32(v.toFloat().toRawBits().toLong() and 0xFFFFFFFFL)
    private fun ByteReader.ieee(): Double = Float.fromBits(i32()).toDouble()
}

/**
 * The probe running on [controller]: start it with [start], then send it commands one at a time. Replies carry no
 * request id, so a [Mutex] keeps exchanges apart; a trial holds it until the motor has been released.
 */
class StartupProbe private constructor(private val controller: VescController) {
    private val lock = Mutex()
    private var nextTrial = 1

    /**
     * Whether the probe reported that the app went quiet: it set the values it found at the first hello back by itself
     * and answers nothing more. Only seen while [keepAlive] runs.
     */
    @Volatile
    var gaveUp: Boolean = false
        private set

    private val messages: Flow<ProbeMessage> get() = controller.customAppData.mapNotNull(ProbeCodec::parse)

    /** Asks the probe who it is; returns the motor it controls. */
    suspend fun hello(motor: Int): Int = lock.withLock {
        repeat(HELLO_TRIES) {
            exchange(ProbeCodec.hello(motor), REPLY_TIMEOUT) { it as? ProbeMessage.Hello }?.let { return@withLock it.motor }
        }
        throw noReply("hello")
    }

    /** Sets [values] with conf-set (RAM only) and returns what conf-get reads back, null where it failed. */
    suspend fun set(values: Map<ProbeParam, Double>): Map<ProbeParam, Double?> = lock.withLock {
        exchange(ProbeCodec.set(values), REPLY_TIMEOUT) { (it as? ProbeMessage.Params)?.values } ?: throw noReply("set")
    }

    suspend fun get(params: Collection<ProbeParam>): Map<ProbeParam, Double?> = lock.withLock {
        exchange(ProbeCodec.get(params), REPLY_TIMEOUT) { (it as? ProbeMessage.Params)?.values } ?: throw noReply("get")
    }

    /** The firmware's resistance measurement at [current] (A), in Ω; null if it failed. The rotor doesn't turn. */
    suspend fun measureResistance(current: Double): Double? = lock.withLock {
        val reply = exchange(ProbeCodec.measureResistance(current), RESISTANCE_TIMEOUT) {
            it as? ProbeMessage.Resistance ?: (it as? ProbeMessage.Error)
        } ?: throw noReply("resistance measurement")
        if (reply is ProbeMessage.Error) throw ProbeException("The probe is busy")
        (reply as ProbeMessage.Resistance).ohm
    }

    /**
     * Runs one start and returns it once the motor has been released. [spec]'s id is replaced by the next trial
     * number. [onSample] sees samples as they arrive. If the probe goes quiet while the motor is driven, this
     * sends stop and a motor release, then reports [TrialOutcome.LOST].
     */
    suspend fun trial(spec: TrialSpec, onSample: (ProbeSample) -> Unit = {}): TrialRecord = lock.withLock {
        val asked = spec.copy(id = nextTrial++ and 0xFFFF)
        coroutineScope {
            val inbox = Channel<ProbeMessage>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { messages.collect { inbox.send(it) } }
            val samples = sortedMapOf<Int, ProbeSample>()
            var end: TrialEnd? = null
            var released = false
            try {
                controller.sendCustomAppData(ProbeCodec.trial(asked))
                // Before the drive starts the probe waits up to 4 s for the rotor to be still.
                var wait = REST_LIMIT + REPLY_TIMEOUT
                val gap = maxOf(DATA_GAP, asked.period * 3 * ProbeCodec.SAMPLES_PER_PACKET)
                while (!released) {
                    val message = withTimeoutOrNull(wait) { inbox.receive() } ?: break
                    when (message) {
                        is ProbeMessage.Data -> if (message.trial == asked.id && end == null) {
                            for (s in message.samples) {
                                samples[s.index] = s
                                onSample(s)
                            }
                            wait = gap
                        }
                        is ProbeMessage.End -> if (message.end.trial == asked.id && end == null) {
                            end = message.end
                            wait = BRAKE_LIMIT + REPLY_TIMEOUT
                        }
                        is ProbeMessage.Released -> if (message.trial == asked.id) released = true
                        is ProbeMessage.Error -> if (end == null && samples.isEmpty()) {
                            throw ProbeException(if (message.code == ProbeCodec.BUSY) "The probe is busy" else "The probe refused the trial (${message.code})")
                        }
                        else -> Unit
                    }
                }
                if (!released) {
                    // Quiet probe: make sure nothing keeps driving the motor.
                    controller.sendCustomAppData(ProbeCodec.stop())
                    controller.motor.release()
                    if (!controller.session.connected.value) throw VescLinkException("Link closed during a trial")
                }
            } finally {
                collector.cancel()
            }
            val ended = end ?: TrialEnd(asked.id, TrialOutcome.LOST, samples.size, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0)
            TrialRecord(asked, ended, samples.values.toList())
        }
    }

    /**
     * Pings the probe every second so its dead man's switch ([ProbeCodec.QUIET_SECONDS] without a command) stays off
     * while the app holds it, and watches for [ProbeMessage.GaveUp]. Runs until cancelled; a failed ping is retried
     * on the next tick. With [ping] false it only listens (for tests of the switch).
     */
    suspend fun keepAlive(ping: Boolean = true): Unit = coroutineScope {
        launch(start = CoroutineStart.UNDISPATCHED) { messages.collect { if (it is ProbeMessage.GaveUp) gaveUp = true } }
        while (isActive && ping) {
            try {
                controller.sendCustomAppData(ProbeCodec.ping())
            } catch (e: IOException) {
                // The link reports itself; the probe restores on its own if it stays down.
            }
            delay(PING_INTERVAL)
        }
    }

    /** Ends a running trial early; the motor is let go without braking. */
    suspend fun stop() = controller.sendCustomAppData(ProbeCodec.stop())

    /** Stops the probe's event loop; it answers nothing afterwards. */
    suspend fun quit() {
        lock.withLock { exchange(ProbeCodec.quit(), REPLY_TIMEOUT) { it as? ProbeMessage.Bye } }
    }

    private suspend fun <T : Any> exchange(payload: ByteArray, timeout: Duration, accept: (ProbeMessage) -> T?): T? = coroutineScope {
        val reply = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(timeout) { messages.mapNotNull(accept).first() }
        }
        controller.sendCustomAppData(payload)
        reply.await()
    }

    private fun noReply(what: String): VescException =
        if (controller.session.connected.value) VescTimeoutException("The startup probe didn't answer $what") else VescLinkException("Link closed")

    companion object {
        private const val RESOURCE = "startup_probe.lisp"
        private const val HELLO_TRIES = 4
        private val REPLY_TIMEOUT = 1.seconds
        private val RESISTANCE_TIMEOUT = 8.seconds
        private val REST_LIMIT = 4.seconds
        private val BRAKE_LIMIT = 3.seconds
        private val DATA_GAP = 600.milliseconds
        private val PING_INTERVAL = 1.seconds

        /** The probe's source, as stored in the app. */
        val script: String by lazy {
            StartupProbe::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
                ?: error("$RESOURCE is missing from the app")
        }

        /**
         * Streams the probe (restarting LispBM empty, so scripts and packages that were running stop until LispBM
         * is started again) and waits for it to answer. [motor] is 1, or 2 for the second motor of a dual board.
         */
        suspend fun start(controller: VescController, motor: Int): StartupProbe {
            controller.streamLisp(LispCode.reduce(script).encodeToByteArray(), LispStreamMode.ResetAndLoad)
            val probe = StartupProbe(controller)
            val answered = probe.hello(motor)
            if (answered != motor) throw ProbeException("The probe controls motor $answered, not $motor")
            return probe
        }

        /** Rounds [value] the way the probe's 16-bit sample fields do, for tests. */
        internal fun quantize(value: Double, scale: Double): Double = (value * scale).roundToInt() / scale
    }
}
