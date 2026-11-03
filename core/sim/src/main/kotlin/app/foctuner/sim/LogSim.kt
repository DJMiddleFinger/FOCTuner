package app.foctuner.sim

import app.foctuner.protocol.ByteReader
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The logger of a VESC Express (main/log.c) as COMM_LOG_* drives it: a start with a field count and rate opens
 * log_can/log_NNN_YY-MM-DD_hh-mm-ss.csv with one header cell per configured field, then a row every 1/rate seconds
 * with the values updated since the previous row (other cells empty) and the time of day when asked for, until a stop.
 * A start while a log runs is ignored. The simulated Express has no GNSS receiver for the log, so those flags add
 * nothing. Rows are written when the next request arrives, as the rest of the simulator advances.
 */
internal class LogSim(private val files: FileSystemSim) {
    private class Header(var key: String, var name: String, var unit: String, var precision: Int, var relative: Boolean, var timestamp: Boolean)

    private val headers = Array(MAX_FIELDS) { Header("key_h$it", "name_h$it", "", 2, false, false) }
    private val values = DoubleArray(MAX_FIELDS)
    private val updated = BooleanArray(MAX_FIELDS)
    private var fieldCount = 0
    private var period = 100.milliseconds
    private var appendTime = false
    private var path: String? = null
    private var nextRow = Duration.ZERO

    fun start(r: ByteReader, now: Duration) {
        if (fieldCount > 0) return
        val count = r.i16().coerceAtMost(MAX_FIELDS)
        var rate = r.f32Auto()
        if (rate < 0.1f) rate = 10f
        appendTime = r.u8() != 0
        if (count <= 0) return
        files.ensureFolder(FOLDER)
        fieldCount = count
        period = (1000.0 / rate).milliseconds
        val at = START_OF_DAY_S + now.inWholeSeconds
        val name = "log_%03d_26-10-16_%02d-%02d-%02d.csv".format(files.highestLogIndex(FOLDER) + 1, at / 3600 % 24, at / 60 % 60, at % 60)
        path = "$FOLDER/$name".also { files.create(it) }
        val cells = headers.take(count).map { "${it.key}:${it.name}:${it.unit}:${it.precision}:${if (it.relative) 1 else 0}:${if (it.timestamp) 1 else 0}" }
        val time = if (appendTime) ";t_day:Time:s:3:0:1" else ""
        files.append(path.orEmpty(), cells.joinToString(";") + time + "\n")
        nextRow = now
    }

    fun stop() {
        fieldCount = 0
        path = null
    }

    fun configure(r: ByteReader) {
        val i = r.i16()
        if (i !in 0 until MAX_FIELDS) return
        headers[i].apply {
            key = r.cString().take(24)
            name = r.cString().take(29)
            unit = r.cString().take(9)
            precision = r.u8()
            relative = r.u8() != 0
            timestamp = r.u8() != 0
        }
    }

    fun data(r: ByteReader, wide: Boolean) {
        var i = r.i16()
        if (i < 0) return
        while (i < MAX_FIELDS && r.remaining >= if (wide) 8 else 4) {
            values[i] = if (wide) r.f64Auto() else r.f32Auto().toDouble()
            updated[i] = true
            i++
        }
    }

    /** Writes the rows that came due before [now]. */
    fun step(now: Duration) {
        val file = path ?: return
        if (now - nextRow > MAX_CATCH_UP) nextRow = now - MAX_CATCH_UP
        val rows = StringBuilder()
        while (nextRow <= now) {
            for (i in 0 until fieldCount) {
                if (updated[i]) {
                    rows.append(String.format(Locale.ROOT, "%.${headers[i].precision}f", values[i]))
                    updated[i] = false
                }
                if (i < fieldCount - 1) rows.append(';')
            }
            if (appendTime) rows.append(';').append(String.format(Locale.ROOT, "%.3f", START_OF_DAY_S + nextRow.inWholeMilliseconds / 1000.0))
            rows.append('\n')
            nextRow += period
        }
        files.append(file, rows.toString())
    }

    companion object {
        const val FOLDER = "/log_can"
        private const val MAX_FIELDS = 120
        private const val START_OF_DAY_S = 9L * 3600
        private val MAX_CATCH_UP = 60.seconds
    }
}
