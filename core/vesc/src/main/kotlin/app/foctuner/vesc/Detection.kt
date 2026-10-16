package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId
import app.foctuner.protocol.CommPacketId.COMM_APP_DISABLE_OUTPUT
import app.foctuner.protocol.CommPacketId.COMM_DETECT_APPLY_ALL_FOC
import app.foctuner.protocol.CommPacketId.COMM_DETECT_ENCODER
import app.foctuner.protocol.CommPacketId.COMM_DETECT_HALL_FOC
import app.foctuner.protocol.CommPacketId.COMM_DETECT_MOTOR_FLUX_LINKAGE_OPENLOOP
import app.foctuner.protocol.CommPacketId.COMM_DETECT_MOTOR_PARAM
import app.foctuner.protocol.CommPacketId.COMM_DETECT_MOTOR_R_L
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/*
 * Motor detection as the firmware's commands.c runs it: every command here goes to its blocking thread, which
 * drops a command that arrives while another one runs, and answers only when the measurement is over. The
 * timeouts are the longest each measurement can take in mcpwm_foc.c / conf_general.c, with some margin.
 */

/** Resistance in Ω and inductances in H, as the motor config stores them. All zero means the measurement failed. */
data class ResistanceInductance(val resistance: Float, val inductance: Float, val ldLqDiff: Float) {
    /** VESC Tool's test: a failed measurement reports zero for both. */
    val ok: Boolean get() = resistance >= 1e-9f || inductance >= 1e-9f
}

/** Encoder offset in degrees, ratio (electrical per mechanical turn) and direction, as the motor config stores them. */
data class EncoderParams(val offset: Float, val ratio: Float, val inverted: Boolean)

/** Flux linkage in Wb (0 when the measurement failed) and, when an encoder was read along the way, its parameters. */
data class FluxLinkage(val linkage: Float, val encoder: EncoderParams?) {
    val ok: Boolean get() = linkage >= 1e-9f
}

/** FOC hall table: one angle per hall state in 1/200 turn, 255 for the two states a working sensor never shows. */
data class FocHallTable(val table: List<Int>, val ok: Boolean)

enum class HallSensors { Detected, Failed, Ws2811Enabled, EncoderEnabled, Unknown }

/** BLDC sensorless parameters and the commutation table read from the hall sensors while spinning. */
data class BldcParams(val cycleIntLimit: Float, val bemfCouplingK: Float, val hallTable: List<Int>, val halls: HallSensors) {
    /** VESC Tool's test: a run that never got the motor spinning reports zero for both. */
    val ok: Boolean get() = cycleIntLimit >= 0.01f || bemfCouplingK >= 0.01f
}

enum class DetectedSensors { Sensorless, Hall, Encoder }

enum class FocDetectionFailure { PersistentFault, FluxLinkage, CanTimeout, CanFailed, Fault, Unknown }

/**
 * Result code of COMM_DETECT_APPLY_ALL_FOC: 0..2 for success with the sensors that were found, negative for a
 * failure, −100 + the fault code when a fault stopped it.
 */
data class FocDetectionResult(val code: Int) {
    val ok: Boolean get() = code >= 0

    val sensors: DetectedSensors? get() = when (code) {
        0 -> DetectedSensors.Sensorless
        1 -> DetectedSensors.Hall
        2 -> DetectedSensors.Encoder
        else -> null
    }

    val failure: FocDetectionFailure? get() = when {
        code >= 0 -> null
        code == -1 -> FocDetectionFailure.PersistentFault
        code == -10 -> FocDetectionFailure.FluxLinkage
        code == -50 -> FocDetectionFailure.CanTimeout
        code == -51 -> FocDetectionFailure.CanFailed
        fault != null -> FocDetectionFailure.Fault
        else -> FocDetectionFailure.Unknown
    }

    /** The fault that stopped the detection; [FaultCode.NONE] means it failed for no reason the firmware knows. */
    val fault: FaultCode? get() = if (code < -51) FaultCode.fromCode(code + 100) else null
}

/**
 * Detects everything FOC needs on this controller (both motors of a dual board) and, with [detectCan], on every
 * VESC on its CAN bus, then stores it: current limits from [maxPowerLoss] (W), R, L, flux linkage, current
 * controller gains, observer gain, temperature compensation and the sensors found. [minCurrentIn],
 * [maxCurrentIn] (A, battery), [openloopErpm] and [sensorlessErpm] are stored too; 0 keeps the firmware default.
 * The firmware may also renumber this controller's CAN id when another one on the bus uses it.
 *
 * Like VESC Tool, app output (remote, throttle) is disabled on the whole bus while it runs. Read the configs
 * afterwards to see what was applied.
 */
suspend fun VescController.detectApplyAllFoc(
    detectCan: Boolean,
    maxPowerLoss: Float,
    minCurrentIn: Float = 0f,
    maxCurrentIn: Float = 0f,
    openloopErpm: Float = 0f,
    sensorlessErpm: Float = 0f,
): FocDetectionResult {
    val payload = ByteWriter().u8(COMM_DETECT_APPLY_ALL_FOC.id).u8(if (detectCan) 1 else 0)
        .f32(maxPowerLoss, 1e3f).f32(minCurrentIn, 1e3f).f32(maxCurrentIn, 1e3f)
        .f32(openloopErpm, 1e3f).f32(sensorlessErpm, 1e3f)
        .toByteArray()
    return session.blocking {
        disableAppOutput(APPLY_ALL_FOC_TIMEOUT, forwardCan = true)
        try {
            delay(APP_OUTPUT_SETTLE)
            session.request(forwarded(payload), COMM_DETECT_APPLY_ALL_FOC, APPLY_ALL_FOC_TIMEOUT) { FocDetectionResult(it.i16()) }
        } finally {
            withContext(NonCancellable) { quietly { enableAppOutput(forwardCan = true) } }
        }
    }
}

/** Measures phase resistance and inductance. The motor makes noise but doesn't turn. */
suspend fun VescController.measureResistanceInductance(): ResistanceInductance =
    blockingRequest(byteArrayOf(COMM_DETECT_MOTOR_R_L.id.toByte()), COMM_DETECT_MOTOR_R_L, RL_TIMEOUT) { r ->
        val resistance = r.f32(1e6f)
        val inductance = r.f32(1e3f) * 1e-6f
        val ldLqDiff = if (r.remaining >= 4) r.f32(1e3f) * 1e-6f else 0f
        ResistanceInductance(resistance, inductance, ldLqDiff)
    }

/**
 * Measures the flux linkage by spinning the motor open loop with [current] (A), accelerating by [erpmPerSecond]
 * until it reaches [duty], then coasting. [resistance] (Ω) and [inductance] (H) come from
 * [measureResistanceInductance]. With [current] 0 nothing is driven and the motor must be spun by hand or another
 * motor (passive measurement).
 */
suspend fun VescController.measureFluxLinkage(
    current: Float,
    erpmPerSecond: Float,
    duty: Float,
    resistance: Float,
    inductance: Float,
): FluxLinkage {
    val payload = ByteWriter().u8(COMM_DETECT_MOTOR_FLUX_LINKAGE_OPENLOOP.id)
        .f32(current, 1e3f).f32(erpmPerSecond, 1e3f).f32(duty, 1e3f).f32(resistance, 1e6f).f32(inductance, 1e8f)
        .toByteArray()
    return blockingRequest(payload, COMM_DETECT_MOTOR_FLUX_LINKAGE_OPENLOOP, FLUX_LINKAGE_TIMEOUT) { r ->
        val linkage = r.f32(1e7f)
        val encoder = if (r.remaining >= 9) {
            EncoderParams(r.f32(1e6f), r.f32(1e6f), r.u8() != 0).takeIf { it.offset >= 0f && it.ratio > 0f }
        } else {
            null
        }
        FluxLinkage(linkage, encoder)
    }
}

/**
 * Turns the motor slowly with [current] (A) and records the angle of each hall state. The firmware only runs it
 * when the sensor port is set to hall sensors; otherwise its reply is malformed and this times out, so check
 * `m_sensor_port_mode` first.
 */
suspend fun VescController.detectFocHalls(current: Float): FocHallTable {
    val payload = ByteWriter().u8(COMM_DETECT_HALL_FOC.id).f32(current, 1e3f).toByteArray()
    return blockingRequest(payload, COMM_DETECT_HALL_FOC, HALL_TIMEOUT) { r ->
        FocHallTable(List(8) { r.u8() }, ok = r.u8() == 0)
    }
}

/** Turns the motor with [current] (A) to find the encoder's offset, ratio and direction; null if no encoder is configured. */
suspend fun VescController.detectEncoder(current: Float): EncoderParams? {
    val payload = ByteWriter().u8(COMM_DETECT_ENCODER.id).f32(current, 1e3f).toByteArray()
    return blockingRequest(payload, COMM_DETECT_ENCODER, ENCODER_TIMEOUT) { r ->
        EncoderParams(r.f32(1e6f), r.f32(1e6f), r.u8() != 0).takeIf { it.offset <= NO_ENCODER_OFFSET }
    }
}

/**
 * BLDC detection: spins the motor up sensorless with [current] (A) from [minErpm], then lets it slow down to
 * [lowDuty] to find the integrator limit and BEMF coupling, reading the hall sensors on the way.
 */
suspend fun VescController.detectBldc(current: Float, minErpm: Float, lowDuty: Float): BldcParams {
    val payload = ByteWriter().u8(COMM_DETECT_MOTOR_PARAM.id)
        .f32(current, 1e3f).f32(minErpm, 1e3f).f32(lowDuty, 1e3f).toByteArray()
    return blockingRequest(payload, COMM_DETECT_MOTOR_PARAM, BLDC_TIMEOUT) { r ->
        val cycleIntLimit = r.f32(1e3f)
        val coupling = r.f32(1e3f)
        val table = List(8) { r.i8() }
        val halls = when (r.i8()) {
            0 -> HallSensors.Detected
            -1 -> HallSensors.Failed
            -2 -> HallSensors.Ws2811Enabled
            -3 -> HallSensors.EncoderEnabled
            else -> HallSensors.Unknown
        }
        BldcParams(cycleIntLimit, coupling, table, halls)
    }
}

/**
 * Makes the firmware's apps (remote, throttle, balance) ignore their input for [duration] so they can't fight a
 * test or detection; with [forwardCan] every controller on the bus does the same. No reply.
 */
suspend fun VescController.disableAppOutput(duration: Duration, forwardCan: Boolean) {
    sendAppOutput(duration.inWholeMilliseconds.toInt().coerceAtLeast(1), forwardCan)
}

suspend fun VescController.enableAppOutput(forwardCan: Boolean) = sendAppOutput(0, forwardCan)

private suspend fun VescController.sendAppOutput(ms: Int, forwardCan: Boolean) {
    session.send(forwarded(ByteWriter().u8(COMM_APP_DISABLE_OUTPUT.id).u8(if (forwardCan) 1 else 0).i32(ms).toByteArray()))
}

internal suspend fun <T> VescController.blockingRequest(
    payload: ByteArray,
    replyId: CommPacketId,
    timeout: Duration,
    parse: (ByteReader) -> T,
): T = session.blocking { session.request(forwarded(payload), replyId, timeout, parse) }

private suspend fun quietly(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: IOException) {
        // The link is gone; the firmware's own timer re-enables app output.
    }
}

/** The firmware waits up to 180 s for CAN controllers to report; VESC Tool waits as long. */
private val APPLY_ALL_FOC_TIMEOUT = 180.seconds

/** VESC Tool's pause between disabling app output and starting the detection. */
private val APP_OUTPUT_SETTLE = 100.milliseconds

/** About 3 to 4 s in practice; the sample loops can stretch it on high-inductance motors. VESC Tool waits 8 s. */
private val RL_TIMEOUT = 10.seconds

/** 5 s fault wait, 2.2 s lock-in, up to 15 s ramp, then 2.5 s of sampling and settling. VESC Tool waits 12 s. */
private val FLUX_LINKAGE_TIMEOUT = 30.seconds

/** 1 s lock-in plus three turns each way at 5 ms per degree, about 12 s. VESC Tool waits 25 s. */
private val HALL_TIMEOUT = 25.seconds

/** Index search up to 15 s, then the ratio and offset sweeps, which grow with the pole count. VESC Tool waits 50 s. */
private val ENCODER_TIMEOUT = 90.seconds

/** 1 s settle, up to three 5 s spin-up attempts, then sampling while it coasts down. */
private val BLDC_TIMEOUT = 30.seconds

/** The firmware answers 1001° when no encoder is configured. */
private const val NO_ENCODER_OFFSET = 1000f
