package app.foctuner.sim

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_SAMPLE_PRINT
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * COMM_SAMPLE_PRINT like `mc_interface_sample_print_data`: three phase currents and voltages from the motor's
 * current, speed and duty at a 30 kHz switching frequency. Now records at once; Start and Trigger start wait for the
 * motor to be driven; Trigger fault never fires (the sim has no faults); the no-send variants keep the samples for
 * Send last. Single-sample requests resend one sample of the last recording. Indices in [drop] are lost once on
 * the way, for tests of the resend.
 */
internal class SamplingSim(
    private val vesc: SimulatedVesc,
    private val motor: MotorModel,
    private val voltage: () -> Float,
    /** The startup probe's start being driven at a given uptime; a start trigger records that start's currents. */
    private val probeStart: (Duration) -> StartupProbeSim.Running? = { null },
) {
    private var last: List<ByteArray> = emptyList()
    val drop = HashSet<Int>()
    private var waiting = 0

    fun request(r: ByteReader) {
        val mode = r.u8()
        val length = r.u16().coerceAtMost(MAX)
        val decimation = r.u8().coerceAtLeast(1)
        val raw = r.remaining > 0 && r.u8() != 0
        when (mode) {
            1 -> record(length, decimation, raw, send = true)
            2, 3, 5 -> {
                val ticket = ++waiting
                awaitStart(ticket, length, decimation, raw, send = mode != 5)
            }
            7 -> last.forEach(vesc::reply)
            8 -> last.getOrNull(length)?.let(vesc::reply)
            else -> waiting++ // fault triggers: nothing in the sim faults; also cancels a pending start trigger
        }
    }

    private fun awaitStart(ticket: Int, length: Int, decimation: Int, raw: Boolean, send: Boolean) {
        if (ticket != waiting) return
        val start = probeStart(vesc.uptime)
        when {
            start != null -> recordStart(start, ticket, length, decimation, raw, send)
            motor.drive != Drive.Released -> record(length, decimation, raw, send)
            else -> vesc.later(10.milliseconds) { awaitStart(ticket, length, decimation, raw, send) }
        }
    }

    /**
     * Records a probe start from its own trace: the q current at the FOC angle, split into the three phases
     * (amplitude-invariant Clarke), the angle as the firmware's 0..250 byte, and the sample rate after decimation.
     * Like the firmware, the samples go out once the recording is complete.
     */
    private fun recordStart(start: StartupProbeSim.Running, ticket: Int, length: Int, decimation: Int, raw: Boolean, send: Boolean) {
        val vbus = voltage()
        val trace = start.trace
        val offset = (vesc.uptime - start.driveStart).inWholeMicroseconds / 1e6
        val scale = if (raw) COUNTS_PER_UNIT else 1f
        last = List(length) { i ->
            val k = trace.step(offset + i * decimation / F_SW)
            val angle = Math.toRadians(trace.focAngle[k].toDouble())
            val current = trace.current[k].toDouble()
            val alpha = -current * sin(angle)
            val beta = current * cos(angle)
            val currents = listOf(alpha, -alpha / 2 + SQRT3_2 * beta, -alpha / 2 - SQRT3_2 * beta).map { it.toFloat() }
            val volts = listOf(0.0, -2 * PI / 3, 2 * PI / 3).map { (vbus / 2 + 0.1 * vbus * sin(angle + PI / 2 + it)).toFloat() }
            ByteWriter().u8(COMM_SAMPLE_PRINT.id).i16(i)
                .f32Auto(currents[0] * scale).f32Auto(currents[1] * scale).f32Auto(currents[2] * scale)
                .f32Auto(volts[0] * scale).f32Auto(volts[1] * scale).f32Auto(volts[2] * scale)
                .f32Auto(vbus * scale).f32Auto(currents[0] * scale)
                .f32Auto((F_SW / decimation).toFloat()).u8(0).u8((trace.focAngle[k] / 360f * 250f).toInt().coerceIn(0, 250)).i32(i)
                .toByteArray()
        }
        if (!send) return
        val duration = (length * decimation / F_SW).seconds
        vesc.later(duration) {
            if (ticket == waiting) last.forEachIndexed { i, packet -> if (!drop.remove(i)) vesc.reply(packet) }
        }
    }

    private fun record(length: Int, decimation: Int, raw: Boolean, send: Boolean) {
        val vbus = voltage()
        val amplitude = motor.current
        val electricalHz = motor.erpm / 60.0
        last = List(length) { i ->
            val t = i * decimation / F_SW
            val angle = 2 * PI * electricalHz * t
            fun phase(shift: Double) = sin(angle + shift)
            val currents = listOf(0.0, -2 * PI / 3, 2 * PI / 3).map { (amplitude * phase(it)).toFloat() }
            val volts = listOf(0.0, -2 * PI / 3, 2 * PI / 3).map { (vbus / 2 + motor.duty * vbus / 2 * phase(it + PI / 2)).toFloat() }
            val scale = if (raw) COUNTS_PER_UNIT else 1f
            ByteWriter().u8(COMM_SAMPLE_PRINT.id).i16(i)
                .f32Auto(currents[0] * scale).f32Auto(currents[1] * scale).f32Auto(currents[2] * scale)
                .f32Auto(volts[0] * scale).f32Auto(volts[1] * scale).f32Auto(volts[2] * scale)
                .f32Auto(vbus * scale).f32Auto(currents[0] * scale)
                .f32Auto(F_SW.toFloat()).u8(0).u8(((angle / (PI / 3)).toInt() % 6 + 6) % 6 + 1).i32(i)
                .toByteArray()
        }
        if (send) {
            last.forEachIndexed { i, packet -> if (!drop.remove(i)) vesc.reply(packet) }
        }
    }

    private companion object {
        const val MAX = 1000
        const val F_SW = 30_000.0
        const val COUNTS_PER_UNIT = 40f
        val SQRT3_2 = kotlin.math.sqrt(3.0) / 2
    }
}
