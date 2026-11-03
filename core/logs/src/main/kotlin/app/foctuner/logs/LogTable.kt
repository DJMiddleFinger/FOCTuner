package app.foctuner.logs

/** One column of a log, written as `key:name:unit:precision:relative:timestamp` in the header line like VESC Tool does. */
data class LogField(
    val key: String,
    val name: String,
    val unit: String = "",
    /** Digits after the decimal point when the value is written or shown. */
    val precision: Int = 2,
    /** Shown relative to the first sample. */
    val relativeToFirst: Boolean = false,
    /** Seconds since midnight UTC rather than a measurement. */
    val timestamp: Boolean = false,
)

/** A log as columns of equal length, one value per field and sample. */
class LogTable(val fields: List<LogField>, val columns: List<DoubleArray>) {
    init {
        require(fields.size == columns.size) { "${fields.size} fields but ${columns.size} columns" }
        require(columns.map { it.size }.distinct().size <= 1) { "columns differ in length" }
    }

    val rowCount: Int get() = columns.firstOrNull()?.size ?: 0

    fun indexOf(key: String): Int = fields.indexOfFirst { it.key == key }

    fun column(key: String): DoubleArray? = indexOf(key).takeIf { it >= 0 }?.let { columns[it] }

    /**
     * Time since the first sample for every row. Logs have the time of day in [LogKeys.TIME]; a sample that
     * is earlier than the one before it is past midnight. Without that column a row counts as one second.
     */
    val seconds: DoubleArray by lazy {
        val day = column(LogKeys.TIME)
        if (day == null || day.isEmpty()) {
            DoubleArray(rowCount) { it.toDouble() }
        } else {
            var offset = 0.0
            DoubleArray(day.size) { i ->
                if (i > 0 && day[i] < day[i - 1]) offset += SECONDS_PER_DAY
                day[i] + offset - day[0]
            }
        }
    }

    /** The row whose time is closest to [t] seconds after the start. */
    fun rowAt(t: Double): Int {
        val s = seconds
        if (s.isEmpty()) return 0
        var low = 0
        var high = s.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (s[mid] < t) low = mid + 1 else high = mid
        }
        return if (low > 0 && t - s[low - 1] < s[low] - t) low - 1 else low
    }

    val durationSeconds: Double get() = seconds.lastOrNull() ?: 0.0

    private companion object {
        const val SECONDS_PER_DAY = 86_400.0
    }
}

/** Keys of the columns VESC Tool's log analysis and this app give a meaning to. */
object LogKeys {
    const val TIME = "t_day"
    const val TIME_GNSS = "t_day_pos"
    const val SPEED_VESC = "kmh_vesc"
    const val SPEED_GNSS = "kmh_gnss"
    const val TRIP_TIME = "t_trip"
    const val TRIP_VESC = "trip_vesc"
    const val TRIP_VESC_ABS = "trip_vesc_abs"
    const val TRIP_GNSS = "trip_gnss"
    const val CURRENT_MOTOR = "setup_curr_motor"
    const val CURRENT_BATTERY = "setup_curr_battery"
    const val POWER = "setup_power"
    const val ERPM = "erpm"
    const val DUTY = "duty"
    const val FAULT = "fault"
    const val VOLTAGE = "v_in"
    const val BATTERY_LEVEL = "soc"
    const val TEMP_MOSFET = "t_mosfet"
    const val TEMP_MOTOR = "t_motor"
    const val AH_USED = "cnt_ah"
    const val AH_CHARGED = "cnt_ah_chg"
    const val WH_USED = "cnt_wh"
    const val WH_CHARGED = "cnt_wh_chg"
    const val ID = "id"
    const val IQ = "iq"
    const val VD = "vd"
    const val VQ = "vq"
    const val TEMP_MOSFET_1 = "t_mosfet_1"
    const val TEMP_MOSFET_2 = "t_mosfet_2"
    const val TEMP_MOSFET_3 = "t_mosfet_3"
    const val POSITION = "position"
    const val ROLL = "roll"
    const val PITCH = "pitch"
    const val YAW = "yaw"
    const val ACC_X = "acc_x"
    const val ACC_Y = "acc_y"
    const val ACC_Z = "acc_z"
    const val GYRO_X = "gyro_x"
    const val GYRO_Y = "gyro_y"
    const val GYRO_Z = "gyro_z"
    const val CONTROLLER_COUNT = "num_vesc"
    const val LATITUDE = "gnss_lat"
    const val LONGITUDE = "gnss_lon"
    const val ALTITUDE = "gnss_alt"
    const val VERTICAL_SPEED = "gnss_v_vel"
    const val HORIZONTAL_ACCURACY = "gnss_h_acc"
    const val VERTICAL_ACCURACY = "gnss_v_acc"
}
