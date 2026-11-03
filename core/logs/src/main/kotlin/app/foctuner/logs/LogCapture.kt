package app.foctuner.logs

/** What a controller sends over COMM_LOG_* to a logger (this app, VESC Tool, or a VESC Express's SD card). */
sealed interface LogEvent {
    data class Start(
        val fieldCount: Int,
        val rateHz: Double,
        val appendTime: Boolean,
        val appendGnss: Boolean,
        val appendGnssTime: Boolean,
    ) : LogEvent

    data object Stop : LogEvent

    data class Field(val index: Int, val field: LogField) : LogEvent

    /** New values for the fields from [first] on. */
    data class Samples(val first: Int, val values: List<Double>) : LogEvent
}

/**
 * Turns a controller's log stream into a log the way VESC Tool's analysis page does for a live script: values arrive
 * whenever the script sends them, and a row is cut from the latest ones at the stream's own rate with [tick]. With
 * `appendTime` the first column is the time of day and the script's fields follow.
 */
class LogCapture {
    private var fields = emptyList<LogField>()
    private var latest = DoubleArray(0)
    private var shift = 0
    private val rows = ArrayList<DoubleArray>()

    @get:Synchronized
    var running = false
        private set

    @get:Synchronized
    var rateHz = 1.0
        private set

    @get:Synchronized
    val rowCount: Int get() = rows.size

    @Synchronized
    fun accept(event: LogEvent) {
        when (event) {
            is LogEvent.Start -> {
                if (event.fieldCount <= 0) return
                shift = if (event.appendTime) 1 else 0
                fields = buildList {
                    if (event.appendTime) add(LogField(LogKeys.TIME, "Time", "s", 3, timestamp = true))
                    repeat(event.fieldCount) { add(LogField("field_$it", "Field $it")) }
                }
                latest = DoubleArray(fields.size)
                rows.clear()
                rateHz = event.rateHz.coerceIn(MIN_RATE, MAX_RATE)
                running = true
            }
            LogEvent.Stop -> running = false
            is LogEvent.Field -> {
                val i = event.index + shift
                if (i in fields.indices) fields = fields.toMutableList().also { it[i] = event.field }
            }
            is LogEvent.Samples -> event.values.forEachIndexed { n, v ->
                val i = event.first + shift + n
                if (i in latest.indices) latest[i] = v
            }
        }
    }

    /** Cuts a row from the latest values, stamped [timeOfDay] seconds UTC when the stream asked for time. */
    @Synchronized
    fun tick(timeOfDay: Double) {
        if (!running) return
        if (shift == 1) latest[0] = timeOfDay
        rows += latest.copyOf()
    }

    @Synchronized
    fun table(): LogTable = LogTable(fields, fields.indices.map { c -> DoubleArray(rows.size) { rows[it][c] } })

    private companion object {
        const val MIN_RATE = 0.1
        const val MAX_RATE = 1000.0
    }
}

/** A position from the phone's receiver. [timeMs] is when the fix was taken, UTC. */
data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    /** m/s over ground. */
    val speed: Float?,
    val accuracy: Float?,
    val verticalAccuracy: Float?,
    val timeMs: Long,
)
