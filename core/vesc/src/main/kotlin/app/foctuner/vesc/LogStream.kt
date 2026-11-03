package app.foctuner.vesc

import app.foctuner.logs.LogCapture
import app.foctuner.logs.LogEvent
import app.foctuner.logs.LogField
import app.foctuner.logs.LogTable
import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_LOG_CONFIG_FIELD
import app.foctuner.protocol.CommPacketId.COMM_LOG_DATA_F32
import app.foctuner.protocol.CommPacketId.COMM_LOG_DATA_F64
import app.foctuner.protocol.CommPacketId.COMM_LOG_START
import app.foctuner.protocol.CommPacketId.COMM_LOG_STOP
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/*
 * COMM_LOG_*, as the VESC Express (main/log_comm.c, main/log.c) and VESC Tool (commands.cpp) use them. They carry a
 * log from whoever produces the values to whoever writes it down, with no reply. A LispBM script on a VESC or Express
 * produces them (log-start, log-config-field, log-send-f32); an Express with an SD card writes them to
 * log_can/log_NNN_YY-MM-DD_hh-mm-ss.csv; VESC Tool, and this app, collect them into a log on the phone. The STM32
 * firmware itself does not handle them. The Express keeps writing rows, with empty cells for values that did not
 * change, until it receives COMM_LOG_STOP, and ignores a start while a log runs.
 */

/** The longest key, name and unit the Express stores (its buffers minus the terminator), in bytes. */
private const val MAX_KEY = 24
private const val MAX_NAME = 29
private const val MAX_UNIT = 9

/** The Express keeps this many fields. */
const val LOG_MAX_FIELDS = 120

/** Starts a log of [fieldCount] fields at [rateHz]; the controller adds its own time and, with a receiver, GNSS columns on request. */
suspend fun VescController.logStart(
    fieldCount: Int,
    rateHz: Float,
    appendTime: Boolean = false,
    appendGnss: Boolean = false,
    appendGnssTime: Boolean = false,
) {
    require(fieldCount in 1..LOG_MAX_FIELDS) { "a log has 1..$LOG_MAX_FIELDS fields, not $fieldCount" }
    require(rateHz > 0f) { "the rate must be positive" }
    val payload = ByteWriter().u8(COMM_LOG_START.id).i16(fieldCount).f32Auto(rateHz)
        .u8(if (appendTime) 1 else 0).u8(if (appendGnss) 1 else 0).u8(if (appendGnssTime) 1 else 0).toByteArray()
    session.send(forwarded(payload))
}

suspend fun VescController.logStop() = session.send(forwarded(byteArrayOf(COMM_LOG_STOP.id.toByte())))

/** Names field [index]. Send these before [logStart]; the header is written when the log opens. */
suspend fun VescController.logConfigField(index: Int, field: LogField) {
    require(index in 0 until LOG_MAX_FIELDS) { "field index $index is out of range" }
    require(field.key.encodeToByteArray().size <= MAX_KEY) { "key '${field.key}' is longer than $MAX_KEY bytes" }
    require(field.name.encodeToByteArray().size <= MAX_NAME) { "name '${field.name}' is longer than $MAX_NAME bytes" }
    require(field.unit.encodeToByteArray().size <= MAX_UNIT) { "unit '${field.unit}' is longer than $MAX_UNIT bytes" }
    val payload = ByteWriter().u8(COMM_LOG_CONFIG_FIELD.id).i16(index)
        .cString(field.key).cString(field.name).cString(field.unit)
        .u8(field.precision).u8(if (field.relativeToFirst) 1 else 0).u8(if (field.timestamp) 1 else 0).toByteArray()
    session.send(forwarded(payload))
}

/** New values for the fields from [first] on, as 32-bit floats. */
suspend fun VescController.logSendF32(first: Int, values: FloatArray) {
    require(values.size <= LOG_MAX_FIELDS) { "too many values" }
    val w = ByteWriter().u8(COMM_LOG_DATA_F32.id).i16(first)
    for (v in values) w.f32Auto(v)
    session.send(forwarded(w.toByteArray()))
}

/** Like [logSendF32] with 64-bit floats, for values a 32-bit float rounds (coordinates). */
suspend fun VescController.logSendF64(first: Int, values: DoubleArray) {
    require(values.size <= MAX_F64_VALUES) { "at most $MAX_F64_VALUES 64-bit values fit one packet" }
    val w = ByteWriter().u8(COMM_LOG_DATA_F64.id).i16(first)
    for (v in values) w.f64Auto(v)
    session.send(forwarded(w.toByteArray()))
}

private const val MAX_F64_VALUES = 60

/** The log events that reach this connection, from any controller on it. Subscribe before the log starts. */
fun VescController.logEvents(): Flow<LogEvent> = session.incoming.mapNotNull { parseLogEvent(it) }

/**
 * The logs a script on the controller streams to us, each emitted when it stops (if it has at least [minRows] rows).
 * Rows are cut at the stream's rate from the latest values, stamped with [timeOfDay], like VESC Tool's live analysis.
 */
fun VescController.streamedLogs(minRows: Int = 5, timeOfDay: () -> Double): Flow<LogTable> = channelFlow {
    val capture = LogCapture()
    var ticker: Job? = null
    logEvents().collect { event ->
        capture.accept(event)
        when (event) {
            is LogEvent.Start -> if (capture.running) {
                ticker?.cancel()
                val period = (1000.0 / capture.rateHz).toLong().coerceAtLeast(1)
                ticker = launch {
                    while (true) {
                        delay(period)
                        capture.tick(timeOfDay())
                    }
                }
            }
            LogEvent.Stop -> {
                ticker?.cancel()
                ticker = null
                if (capture.rowCount >= minRows) send(capture.table())
            }
            else -> Unit
        }
    }
}

internal fun parseLogEvent(packet: ByteArray): LogEvent? {
    if (packet.isEmpty()) return null
    val r = ByteReader(packet, 1)
    return try {
        when (packet[0].toInt() and 0xFF) {
            COMM_LOG_START.id -> LogEvent.Start(r.i16(), r.f32Auto().toDouble(), r.u8() != 0, r.u8() != 0, r.u8() != 0)
            COMM_LOG_STOP.id -> LogEvent.Stop
            COMM_LOG_CONFIG_FIELD.id -> {
                val index = r.i16()
                val key = r.cString()
                val name = r.cString()
                val unit = r.cString()
                LogEvent.Field(index, LogField(key, name, unit, r.u8(), r.u8() != 0, r.u8() != 0))
            }
            COMM_LOG_DATA_F32.id -> LogEvent.Samples(r.i16(), buildList { while (r.remaining >= 4) add(r.f32Auto().toDouble()) })
            COMM_LOG_DATA_F64.id -> LogEvent.Samples(r.i16(), buildList { while (r.remaining >= 8) add(r.f64Auto()) })
            else -> null
        }
    } catch (e: IndexOutOfBoundsException) {
        null
    }
}

/** Seconds since midnight UTC, the unit of the `t_day` column. */
fun timeOfDaySeconds(epochMs: Long): Double = Math.floorMod(epochMs, 86_400_000L) / 1000.0
