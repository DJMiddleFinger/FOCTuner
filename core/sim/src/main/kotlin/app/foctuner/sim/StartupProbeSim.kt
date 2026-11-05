package app.foctuner.sim

import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.config.ParamType
import app.foctuner.protocol.CommPacketId.COMM_CUSTOM_APP_DATA
import app.foctuner.vesc.tune.ProbeCodec
import app.foctuner.vesc.tune.ProbeParam
import app.foctuner.vesc.tune.TrialEnd
import app.foctuner.vesc.tune.TrialOutcome
import app.foctuner.vesc.tune.TrialSpec
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The startup tuner's LispBM probe (startup_probe.lisp) once it has been streamed: [LispSim] can't run the script, so
 * this answers its protocol the way the script does, with starts from [StartupStartModel]. conf-set changes the stored
 * motor config, which in the simulator stands for the firmware's RAM copy. While a start is driven the motor model gets
 * the same current (as the script's set-current, so an emergency stop drops it), so the realtime values, the sampled
 * data and the rotor position follow along; the latter two read the start's own angles and currents.
 */
internal class StartupProbeSim(private val vesc: SimulatedVesc, private val motors: List<MotorModel>, private val voltage: () -> Float) {
    /** A start in progress: the simulated trace, when its drive phase starts (uptime) and the motor it drives. */
    class Running(val spec: TrialSpec, val trace: StartTrace, val driveStart: Duration, val motor: MotorModel, val token: Int) {
        val driveEnd: Duration get() = driveStart + trace.duration.seconds
    }

    private var generation = -1
    private var alive = false
    private var motorIndex = 1
    private var busy = false
    private var token = 0
    private var trials = 0L
    private var sent = 0
    private var running: Running? = null

    /** What conf-get read at the first hello, for the dead man's switch; null until then. */
    private var saved: Map<ProbeParam, Double?>? = null

    /** When the last command arrived (uptime). */
    private var seen = Duration.ZERO

    /** The real motor behind each motor model (R in Ω, λ in Wb), fixed from its config when the probe first sees it. */
    private val truth = HashMap<MotorModel, Pair<Double, Double>>()

    private val motor: MotorModel get() = motors.getOrElse(motorIndex - 1) { motors.first() }

    /** The start whose drive phase is under way at [now], if any. */
    fun driving(now: Duration): Running? = running?.takeIf { now >= it.driveStart && now <= it.driveEnd }

    /** The observer angle (degrees) of the start being driven at [now]. */
    fun observerAngle(now: Duration): Float? = driving(now)?.let { r ->
        r.trace.observerAngle[r.trace.step((now - r.driveStart).inWholeMicroseconds / 1e6)]
    }

    /** Handles custom app data for the probe; [generation] changes whenever LispBM restarts, which ends the old probe. */
    fun handle(data: ByteArray, generation: Int) {
        if (generation != this.generation) {
            this.generation = generation
            alive = true
            busy = false
            running = null
            token++
            motorIndex = 1
            truth.clear()
            saved = null
        }
        if (!alive) return
        val command = ProbeCodec.commandOf(data) ?: return
        seen = vesc.uptime
        watch()
        when (command) {
            ProbeCodec.HELLO -> {
                val wanted = data.getOrElse(4) { 1 }.toInt()
                if (wanted == 1 || (wanted == 2 && motors.size > 1)) motorIndex = wanted
                if (saved == null) saved = ProbeParam.entries.associateWith { get(it) }
                send(ProbeCodec.helloReply(motorIndex))
            }
            ProbeCodec.SET -> {
                val changes = ProbeCodec.parseParams(data)
                for ((param, value) in changes) if (param != null && value != null) set(param, value)
                send(ProbeCodec.paramsReply(changes.mapNotNull { (param, _) -> param?.let { it to get(it) } }))
            }
            ProbeCodec.GET -> send(ProbeCodec.paramsReply(ProbeCodec.parseParams(data).mapNotNull { (param, _) -> param?.let { it to get(it) } }))
            ProbeCodec.TRIAL -> ProbeCodec.parseTrial(data)?.let(::start)
            ProbeCodec.STOP -> stop()
            ProbeCodec.QUIT -> {
                stop()
                alive = false
                send(ProbeCodec.bye())
            }
            ProbeCodec.MEASURE_RESISTANCE -> measure()
            else -> Unit
        }
    }

    /** The script's ft-watch: checks once the quiet time after the latest command or release has passed. */
    private fun watch() {
        val myGeneration = generation
        vesc.later(QUIET + WATCH_TICK) {
            val values = saved
            if (generation != myGeneration || !alive || busy || values == null || vesc.uptime - seen < QUIET) return@later
            alive = false
            for ((param, value) in values) if (value != null) set(param, value)
            send(ProbeCodec.gaveUp())
        }
    }

    private fun get(param: ProbeParam): Double? {
        val config = vesc.config(motor, ConfigKind.MCCONF)
        if (param.configName !in config.definition.params) return null
        return config.number(param.configName)
    }

    private fun set(param: ProbeParam, value: Double) {
        vesc.updateConfig(motor, ConfigKind.MCCONF) { c ->
            if (param.configName !in c.definition.params) return@updateConfig c
            val definition = c.definition.param(param.configName)
            when (definition.type) {
                ParamType.DOUBLE -> c.with(param.configName, value.coerceIn(definition.minDouble, definition.maxDouble))
                else -> c.with(param.configName, Math.round(value).coerceIn(definition.minInt, definition.maxInt))
            }
        }
    }

    private fun start(spec: TrialSpec) {
        if (busy) return send(ProbeCodec.error(ProbeCodec.BUSY))
        busy = true
        val myToken = ++token
        val driven = motor
        val c = vesc.config(driven, ConfigKind.MCCONF)
        val (trueResistance, trueLambda) = truthOf(driven, c)
        val trace = StartupStartModel.run(
            StartConditions(
                current = spec.current,
                targetErpm = spec.targetErpm,
                timeout = spec.timeout.inWholeMilliseconds / 1000.0,
                period = spec.period.inWholeMilliseconds / 1000.0,
                voltage = voltage().toDouble(),
                currentMax = c.number("l_current_max"),
                observerGain = c.number("foc_observer_gain"),
                observerType = c.number("foc_observer_type").toInt(),
                openloopErpm = c.number("foc_openloop_rpm"),
                openloopLow = c.number("foc_openloop_rpm_low"),
                lockTime = c.number("foc_sl_openloop_time_lock"),
                rampTime = c.number("foc_sl_openloop_time_ramp"),
                holdTime = c.number("foc_sl_openloop_time"),
                hysteresis = c.number("foc_sl_openloop_hyst"),
                boost = c.number("foc_sl_openloop_boost_q"),
                resistance = c.number("foc_motor_r"),
                inductance = c.number("foc_motor_l"),
                ldLqDiff = c.number("foc_motor_ld_lq_diff"),
                fluxLinkage = c.number("foc_motor_flux_linkage"),
                inverted = c.bool("m_invert_direction"),
                seed = ++trials * 1_000_003L + generation,
                trueResistance = trueResistance,
                trueFluxLinkage = trueLambda,
            ),
        )
        // The motor has been still since the last start ended, so the probe's rest wait is just its minimum.
        val running = Running(spec, trace, vesc.uptime + spec.rest, driven, myToken)
        this.running = running
        sent = 0
        val samples = trace.samples
        for (first in samples.indices step ProbeCodec.SAMPLES_PER_PACKET) {
            val chunk = samples.subList(first, min(first + ProbeCodec.SAMPLES_PER_PACKET, samples.size)).toList()
            vesc.later(spec.rest + chunk.last().seconds.seconds) {
                if (token == myToken) {
                    send(ProbeCodec.data(spec.id, first, chunk))
                    sent = first + chunk.size
                }
            }
        }
        var t = Duration.ZERO
        while (t < trace.duration.seconds) {
            val at = t
            vesc.later(spec.rest + at) {
                if (token == myToken) {
                    vesc.lispCommand(driven, Drive.Current(trace.current[trace.step(at.inWholeMicroseconds / 1e6)].takeIf { it != 0f } ?: spec.current.toFloat()))
                    vesc.streamTick()
                }
            }
            t += COMMAND_INTERVAL
        }
        vesc.later(spec.rest + trace.duration.seconds) {
            if (token != myToken) return@later
            finish(running, trace.outcome, samples.size)
            val brake = StartupStartModel.brakeSeconds(trace.endErpm, spec.brakeCurrent).seconds
            vesc.lispCommand(driven, Drive.Brake(spec.brakeCurrent.toFloat()))
            vesc.later(brake) {
                if (token != myToken) return@later
                release(running)
            }
        }
    }

    private fun stop() {
        val r = running ?: return
        token++
        finish(r, TrialOutcome.STOPPED, sent)
        release(r)
    }

    private fun finish(r: Running, outcome: TrialOutcome, samples: Int) {
        send(
            ProbeCodec.end(
                TrialEnd(
                    trial = r.spec.id,
                    outcome = outcome,
                    samples = samples,
                    reachMs = if (outcome == TrialOutcome.REACHED) r.trace.reachMs else 0,
                    fault = 0,
                    voltage = voltage().toDouble(),
                    fetTemp = r.motor.tempFet.toDouble(),
                    motorTemp = r.motor.tempMotor.toDouble(),
                    lambdaEst = r.trace.lambdaEst,
                    resistanceEst = r.trace.resistanceEst,
                ),
            ),
        )
    }

    private fun release(r: Running) {
        vesc.lispCommand(r.motor, Drive.Released)
        running = null
        busy = false
        seen = vesc.uptime
        watch()
        send(ProbeCodec.released(r.spec.id))
    }

    private fun measure() {
        if (busy) return send(ProbeCodec.error(ProbeCodec.BUSY))
        busy = true
        val measured = truthOf(motor, vesc.config(motor, ConfigKind.MCCONF)).first
        val myGeneration = generation
        vesc.later(MEASURE_TIME) {
            if (generation != myGeneration) return@later
            busy = false
            seen = vesc.uptime
            watch()
            send(ProbeCodec.resistanceReply(measured))
        }
    }

    private fun truthOf(motor: MotorModel, config: Config): Pair<Double, Double> = truth.getOrPut(motor) {
        config.number("foc_motor_r") * StartupStartModel.TRUE_RESISTANCE to config.number("foc_motor_flux_linkage") * StartupStartModel.TRUE_LAMBDA
    }

    private fun send(payload: ByteArray) = vesc.reply(byteArrayOf(COMM_CUSTOM_APP_DATA.id.toByte()) + payload)

    private fun Config.number(name: String): Double = when (definition.param(name).type) {
        ParamType.DOUBLE -> double(name)
        else -> int(name).toDouble()
    }

    private companion object {
        val COMMAND_INTERVAL = 10.milliseconds
        val MEASURE_TIME = 400.milliseconds
        val QUIET = ProbeCodec.QUIET_SECONDS.seconds
        val WATCH_TICK = 250.milliseconds
    }
}
