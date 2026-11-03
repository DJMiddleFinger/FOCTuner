package app.foctuner.vesc

import app.foctuner.logs.GeoFix
import app.foctuner.logs.LogCsv
import app.foctuner.logs.metersBetween
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Writer
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

data class RecordingStatus(
    val rows: Int = 0,
    val seconds: Double = 0.0,
    /** The phone gave a position within the last few seconds. */
    val hasFix: Boolean = false,
    /** False while the controller doesn't answer. */
    val replying: Boolean = true,
    /** Metres along the GNSS track. */
    val gnssDistance: Double = 0.0,
)

/**
 * Writes a ride log in VESC Tool's format: one row per [RideSampler] sample at about [rateHz] (the link sets the real
 * rate), with the phone's position in the GNSS columns on the rows where it has a new fix, like the Express logger.
 * [clock] is epoch milliseconds.
 */
class RideRecorder(
    private val controller: VescController,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow(RecordingStatus())
    val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    /**
     * Records into [out] until cancelled or the link fails, flushing every couple of seconds so little is lost if the
     * process dies. [fix] gives the phone's latest position, if any.
     */
    suspend fun record(out: Writer, rateHz: Float, fix: () -> GeoFix?) {
        require(rateHz > 0f) { "the rate must be positive" }
        val columns = RideColumns.ride
        val fields = columns.map { it.field }
        val sampler = RideSampler(controller)
        val values = DoubleArray(columns.size)
        val started = clock()
        val period = (1000f / rateHz).toLong().milliseconds
        var lastFixMs = Long.MIN_VALUE
        var lastAccepted: GeoFix? = null
        var gnssDistance = 0.0
        var rows = 0
        var lastFlush = TimeSource.Monotonic.markNow()
        out.write(LogCsv.header(fields) + "\n")
        try {
            while (true) {
                val tickStart = TimeSource.Monotonic.markNow()
                val sample = sampler.sample()
                val now = clock()
                if (sample == null) {
                    _status.value = _status.value.copy(replying = false, seconds = (now - started) / 1000.0)
                } else {
                    val latest = fix()
                    val recent = latest != null && now - latest.timeMs <= FIX_MAX_AGE_MS
                    val fresh = latest.takeIf { recent && it?.timeMs != lastFixMs }
                    if (fresh != null) {
                        lastFixMs = fresh.timeMs
                        val previous = lastAccepted
                        if ((fresh.accuracy ?: RideColumns.DEFAULT_ACCURACY) <= MAX_TRACK_ACCURACY) {
                            if (previous != null) {
                                gnssDistance += metersBetween(previous.latitude, previous.longitude, fresh.latitude, fresh.longitude)
                            }
                            lastAccepted = fresh
                        }
                    }
                    val row = RideRow(sample, timeOfDaySeconds(now), fresh, gnssDistance)
                    for (i in columns.indices) values[i] = columns[i].read(row)
                    out.write(LogCsv.row(fields, values) + "\n")
                    rows++
                    if (lastFlush.elapsedNow() >= FLUSH_EVERY) {
                        out.flush()
                        lastFlush = TimeSource.Monotonic.markNow()
                    }
                    _status.value = RecordingStatus(rows, (now - started) / 1000.0, recent, replying = true, gnssDistance)
                }
                delay(period - tickStart.elapsedNow())
            }
        } finally {
            out.flush()
        }
    }

    private companion object {
        const val FIX_MAX_AGE_MS = 3_000L
        const val MAX_TRACK_ACCURACY = 30f
        val FLUSH_EVERY = 2_000.milliseconds
    }
}
