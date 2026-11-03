package app.foctuner.vesc

import app.foctuner.logs.GeoFix
import app.foctuner.logs.LogField
import app.foctuner.logs.LogKeys

/** One look at the vehicle: the controller's realtime values, the vehicle-wide ones, the IMU, when it has one. */
class RideSample(val values: RealtimeValues, val setup: SetupValues?, val imu: ImuData?)

/** What the recorder knows beyond the controller when it writes a row. */
class RideRow(
    val sample: RideSample,
    /** Seconds since midnight UTC when the row was taken. */
    val timeOfDay: Double,
    /** The phone's fix when it is new since the previous row. */
    val fix: GeoFix?,
    /** Metres along the GNSS track so far. */
    val gnssDistance: Double,
)

/** One column of a ride log: how it is named in the file and where its value comes from. */
class RideColumn(val field: LogField, val read: (RideRow) -> Double)

/**
 * The columns of VESC Tool's log analysis, with its keys, names, units and precisions, so a ride log from the phone
 * opens there like VESC Tool's own. Values the controller didn't give are NaN and written as empty cells.
 */
object RideColumns {
    private fun col(key: String, name: String, unit: String = "", precision: Int = 2, relative: Boolean = false, read: (RideRow) -> Double) =
        RideColumn(LogField(key, name, unit, precision, relative), read)

    private fun time(key: String, name: String, read: (RideRow) -> Double) =
        RideColumn(LogField(key, name, "s", 3, relativeToFirst = key == LogKeys.TRIP_TIME, timestamp = true), read)

    private fun RideRow.setup(read: (SetupValues) -> Float): Double = sample.setup?.let { read(it).toDouble() } ?: Double.NaN
    private fun RideRow.imu(read: (ImuData) -> Float): Double = sample.imu?.let { read(it).toDouble() } ?: Double.NaN

    /** The columns that come from the controller alone; a log the controller writes itself can have any of these. */
    val controller: List<RideColumn> = listOf(
        col(LogKeys.SPEED_VESC, "Speed VESC", "km/h") { it.setup { s -> s.speed * 3.6f } },
        col(LogKeys.TRIP_VESC, "Trip VESC", "m", 3, relative = true) { it.setup { s -> s.distance } },
        col(LogKeys.TRIP_VESC_ABS, "Trip VESC ABS", "m", 3, relative = true) { it.setup { s -> s.distanceAbs } },
        col(LogKeys.CURRENT_MOTOR, "Current Motors", "A") { it.setup { s -> s.currentMotor } },
        col(LogKeys.CURRENT_BATTERY, "Current Battery", "A") { it.setup { s -> s.currentInput } },
        col(LogKeys.POWER, "Power", "W", 0) { it.setup { s -> s.currentInput * s.voltageInput } },
        col(LogKeys.ERPM, "ERPM", "", 0) { it.sample.values.erpm.toDouble() },
        col(LogKeys.DUTY, "Duty", "%", 1) { it.sample.values.dutyCycle * 100.0 },
        col(LogKeys.FAULT, "Fault Code", "", 0) { it.sample.values.faultCode.toDouble() },
        col(LogKeys.VOLTAGE, "Input Voltage", "V") { it.sample.values.voltageInput.toDouble() },
        col(LogKeys.BATTERY_LEVEL, "Battery Level", "%", 1) { it.setup { s -> s.batteryLevel * 100f } },
        col(LogKeys.TEMP_MOSFET, "Temp MOSFET", "°C", 1) { it.sample.values.tempMosfet.toDouble() },
        col(LogKeys.TEMP_MOTOR, "Temp Motor", "°C", 1) { it.sample.values.tempMotor.toDouble() },
        col(LogKeys.AH_USED, "Ah Used", "Ah", 3) { it.setup { s -> s.ampHours } },
        col(LogKeys.AH_CHARGED, "Ah Charged", "Ah", 3) { it.setup { s -> s.ampHoursCharged } },
        col(LogKeys.WH_USED, "Wh Used", "Wh", 3) { it.setup { s -> s.wattHours } },
        col(LogKeys.WH_CHARGED, "Wh Charged", "Wh", 3) { it.setup { s -> s.wattHoursCharged } },
        col(LogKeys.ID, "id", "A") { it.sample.values.currentD.toDouble() },
        col(LogKeys.IQ, "iq", "A") { it.sample.values.currentQ.toDouble() },
        col(LogKeys.VD, "vd", "V") { it.sample.values.voltageD.toDouble() },
        col(LogKeys.VQ, "vq", "V") { it.sample.values.voltageQ.toDouble() },
        col(LogKeys.TEMP_MOSFET_1, "Temp MOSFET 1", "°C", 1) { it.sample.values.tempMosfet1.toDouble() },
        col(LogKeys.TEMP_MOSFET_2, "Temp MOSFET 2", "°C", 1) { it.sample.values.tempMosfet2.toDouble() },
        col(LogKeys.TEMP_MOSFET_3, "Temp MOSFET 3", "°C", 1) { it.sample.values.tempMosfet3.toDouble() },
        col(LogKeys.POSITION, "Motor Pos", "°", 1) { it.sample.values.pidPosition.toDouble() },
        col(LogKeys.ROLL, "Roll", "rad", 3) { it.imu { i -> i.roll } },
        col(LogKeys.PITCH, "Pitch", "rad", 3) { it.imu { i -> i.pitch } },
        col(LogKeys.YAW, "Yaw", "rad", 3) { it.imu { i -> i.yaw } },
        col(LogKeys.ACC_X, "Accel X", "G") { it.imu { i -> i.accel.x } },
        col(LogKeys.ACC_Y, "Accel Y", "G") { it.imu { i -> i.accel.y } },
        col(LogKeys.ACC_Z, "Accel Z", "G") { it.imu { i -> i.accel.z } },
        col(LogKeys.GYRO_X, "Gyro X", "°/s", 1) { it.imu { i -> i.gyro.x } },
        col(LogKeys.GYRO_Y, "Gyro Y", "°/s", 1) { it.imu { i -> i.gyro.y } },
        col(LogKeys.GYRO_Z, "Gyro Z", "°/s", 1) { it.imu { i -> i.gyro.z } },
        col("v1_curr_motor", "V1 Current", "A") { it.sample.values.currentMotor.toDouble() },
        col("v1_curr_battery", "V1 Current Battery", "A") { it.sample.values.currentInput.toDouble() },
        col("v1_cnt_ah", "V1 Ah Used", "Ah", 3) { it.sample.values.ampHours.toDouble() },
        col("v1_cnt_ah_chg", "V1 Ah Charged", "Ah", 3) { it.sample.values.ampHoursCharged.toDouble() },
        col("v1_cnt_wh", "V1 Wh Used", "Wh", 3) { it.sample.values.wattHours.toDouble() },
        col("v1_cnt_wh_chg", "V1 Wh Charged", "Wh", 3) { it.sample.values.wattHoursCharged.toDouble() },
        col(LogKeys.CONTROLLER_COUNT, "VESC num", "", 0) { it.setup { s -> s.controllerCount.toFloat() } },
    )

    /** A ride log from the phone: [controller] columns plus time and the phone's position, in VESC Tool's order. */
    val ride: List<RideColumn> = buildList {
        add(controller.first())
        add(col(LogKeys.SPEED_GNSS, "Speed GNSS", "km/h") { r -> r.fix?.speed?.let { it * 3.6 } ?: Double.NaN })
        add(time(LogKeys.TIME, "Time") { it.timeOfDay })
        add(time(LogKeys.TIME_GNSS, "Time GNSS") { r -> r.fix?.let { timeOfDaySeconds(it.timeMs) } ?: Double.NaN })
        add(time(LogKeys.TRIP_TIME, "Time of trip") { it.timeOfDay })
        addAll(controller.subList(1, 3))
        add(col(LogKeys.TRIP_GNSS, "Trip GNSS", "m", 3, relative = true) { it.gnssDistance })
        addAll(controller.drop(3))
        add(col(LogKeys.LATITUDE, "Latitude", "°", 7) { r -> r.fix?.latitude ?: Double.NaN })
        add(col(LogKeys.LONGITUDE, "Longitude", "°", 7) { r -> r.fix?.longitude ?: Double.NaN })
        add(col(LogKeys.ALTITUDE, "Altitude", "m") { r -> r.fix?.altitude ?: Double.NaN })
        add(col(LogKeys.HORIZONTAL_ACCURACY, "H. Accuracy GNSS", "m") { r -> r.fix?.let { (it.accuracy ?: DEFAULT_ACCURACY).toDouble() } ?: Double.NaN })
        add(col(LogKeys.VERTICAL_ACCURACY, "V. Accuracy GNSS", "m") { r -> r.fix?.verticalAccuracy?.toDouble() ?: Double.NaN })
    }

    /** Written when the phone's fix has no accuracy; the analysis drops fixes without one. */
    const val DEFAULT_ACCURACY = 10f
}
