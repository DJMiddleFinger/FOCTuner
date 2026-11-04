package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_SAMPLE_PRINT
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** `debug_sampling_mode`: when the controller records its ADC samples (VESC Tool's Sampled Data page). */
enum class SampleMode(val code: Int) {
    /** Record now, send when done. */
    Now(1),

    /** Record from the next motor start. */
    Start(2),

    /** Keep recording; on the next start send the samples around it. */
    TriggerStart(3),

    /** Keep recording; on the next fault send the samples before it. */
    TriggerFault(4),
    TriggerStartNoSend(5),
    TriggerFaultNoSend(6),

    /** Send the samples of the last recording again. */
    SendLast(7),
}

/**
 * One ADC sample: phase currents (A), phase voltages and the virtual zero (V), the filtered total current, the
 * switching frequency (Hz), the FOC/BLDC status byte and the commutation phase. With raw sampling the currents
 * and voltages are ADC counts.
 */
data class AdcSample(
    val index: Int,
    val current1: Float,
    val current2: Float,
    val current3: Float,
    val voltage1: Float,
    val voltage2: Float,
    val voltage3: Float,
    val voltageZero: Float,
    val currentTotal: Float,
    val switchingFrequency: Float,
    val status: Int,
    val phase: Int,
) {
    companion object {
        /** Firmware since 5.x sends the index first (packets of 44 bytes or more after the id). */
        fun parse(r: ByteReader, fallbackIndex: Int): AdcSample {
            val indexed = r.remaining >= 44
            val index = if (indexed) r.i16() else fallbackIndex
            return AdcSample(
                index = index,
                current1 = r.f32Auto(), current2 = r.f32Auto(), current3 = r.f32Auto(),
                voltage1 = r.f32Auto(), voltage2 = r.f32Auto(), voltage3 = r.f32Auto(),
                voltageZero = r.f32Auto(), currentTotal = r.f32Auto(),
                switchingFrequency = r.f32Auto(), status = r.u8(), phase = r.u8(),
            )
        }
    }
}

/**
 * COMM_SAMPLE_PRINT: asks for [length] samples (the firmware caps it at 1000) recorded every [decimation]th PWM
 * cycle and collects them as they stream in. The trigger modes wait for their event first: [waitForFirst] bounds
 * that. Collection ends once [length] samples arrived, or [quiet] passed without one; samples lost on the way are
 * asked for one by one (`DEBUG_SAMPLING_SEND_SINGLE_SAMPLE`), as VESC Tool does. Returns them in index order; an
 * empty list means nothing came.
 */
suspend fun VescController.sampleAdc(
    mode: SampleMode,
    length: Int,
    decimation: Int = 1,
    raw: Boolean = false,
    waitForFirst: Duration = 5.seconds,
    quiet: Duration = 500.milliseconds,
    onProgress: (received: Int) -> Unit = {},
): List<AdcSample> = coroutineScope {
    require(length in 1..MAX_SAMPLES) { "Between 1 and $MAX_SAMPLES samples" }
    require(decimation in 1..255) { "Decimation must be within 1..255" }
    val got = HashMap<Int, AdcSample>()
    val arrivals = Channel<AdcSample>(Channel.UNLIMITED)
    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
        var count = 0
        session.incoming.collect { packet ->
            if ((packet[0].toInt() and 0xFF) != COMM_SAMPLE_PRINT.id) return@collect
            try {
                arrivals.send(AdcSample.parse(ByteReader(packet, 1), count++))
            } catch (e: IndexOutOfBoundsException) {
                // A cut-off packet; the index is asked for again below.
            }
        }
    }
    try {
        session.send(forwarded(request(mode.code, length, decimation, raw)))
        val expected = if (mode == SampleMode.TriggerStart || mode == SampleMode.TriggerFault) MAX_SAMPLES else length
        var wait = waitForFirst
        while (got.size < expected) {
            val sample = withTimeoutOrNull(wait) { arrivals.receive() } ?: break
            got[sample.index] = sample
            onProgress(got.size)
            wait = quiet
        }
        if (got.isNotEmpty()) {
            val missing = (0..got.keys.max()).filter { it !in got }
            for (index in missing.take(MAX_RESENDS)) {
                session.send(forwarded(request(SINGLE_SAMPLE, index, 1, raw)))
                val sample = withTimeoutOrNull(quiet) {
                    var s: AdcSample
                    do s = arrivals.receive() while (s.index != index)
                    s
                } ?: continue
                got[sample.index] = sample
                onProgress(got.size)
            }
        }
    } finally {
        collector.cancel()
    }
    got.values.sortedBy { it.index }
}

/**
 * Disarms a recording that is still waiting for its trigger (`DEBUG_SAMPLING_OFF`), so a start trigger nobody waits
 * for any more doesn't fire on a later start. No reply.
 */
suspend fun VescController.stopSampling() {
    session.send(forwarded(request(SAMPLING_OFF, 1, 1, false)))
}

private fun request(mode: Int, length: Int, decimation: Int, raw: Boolean) =
    ByteWriter().u8(COMM_SAMPLE_PRINT.id).u8(mode).u16(length).u8(decimation).u8(if (raw) 1 else 0).toByteArray()

/** `ADC_SAMPLE_MAX_LEN` in the firmware. */
const val MAX_SAMPLES = 1000
private const val SINGLE_SAMPLE = 8
private const val SAMPLING_OFF = 0
private const val MAX_RESENDS = 200
