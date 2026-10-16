package app.foctuner.vesc

import app.foctuner.config.Config
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_GET_IMU_CALIBRATION
import kotlin.time.Duration.Companion.seconds

/** Mounting rotation in degrees and sensor offsets (accelerometer in g, gyro in °/s), as the app config stores them. */
data class ImuCalibration(
    val roll: Float,
    val pitch: Float,
    val yaw: Float,
    val accelOffsets: List<Float>,
    val gyroOffsets: List<Float>,
)

/**
 * Lets the firmware work out the IMU's mounting rotation and gyro offsets. The board must sit level and still for
 * the few seconds it samples; [yaw] (degrees) is how the board is turned and can't be measured. Nothing is stored:
 * apply the result with [withImuCalibration] and write the app config.
 */
suspend fun VescController.calibrateImu(yaw: Float): ImuCalibration {
    val payload = ByteWriter().u8(COMM_GET_IMU_CALIBRATION.id).f32(yaw, 1e3f).toByteArray()
    // Gyro sampling, two 1.5 s settling waits and the roll and pitch samples take about 4.5 s.
    return blockingRequest(payload, COMM_GET_IMU_CALIBRATION, 10.seconds) { r ->
        val v = List(9) { r.f32(1e6f) }
        ImuCalibration(v[0], v[1], v[2], v.subList(3, 6), v.subList(6, 9))
    }
}

fun Config.withImuCalibration(cal: ImuCalibration): Config {
    var c = with("imu_conf.rot_roll", cal.roll.toDouble())
        .with("imu_conf.rot_pitch", cal.pitch.toDouble())
        .with("imu_conf.rot_yaw", cal.yaw.toDouble())
    for (i in 0..2) {
        c = c.with("imu_conf.accel_offsets__$i", cal.accelOffsets[i].toDouble())
            .with("imu_conf.gyro_offsets__$i", cal.gyroOffsets[i].toDouble())
    }
    return c
}
