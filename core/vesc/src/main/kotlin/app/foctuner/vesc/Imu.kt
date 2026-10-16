package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_GET_IMU_DATA

data class Axes(val x: Float, val y: Float, val z: Float)

/** Attitude as a unit quaternion; [w] is the firmware's q0. */
data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float)

/**
 * COMM_GET_IMU_DATA. Angles in radians, [accel] in g, [gyro] in °/s, [magnetometer] in µT (the units VESC Tool
 * labels them with). Fields left out of the request's mask are zero. A controller without an IMU still answers.
 */
data class ImuData(
    val roll: Float,
    val pitch: Float,
    val yaw: Float,
    val accel: Axes,
    val gyro: Axes,
    val magnetometer: Axes,
    val quaternion: Quaternion,
    /** Absent in replies from firmware older than the field. */
    val controllerId: Int?,
) {
    companion object {
        /** One bit per value: roll, pitch, yaw, accel x/y/z, gyro x/y/z, mag x/y/z, q0..q3. */
        const val ALL_FIELDS = 0xFFFF
        const val PITCH = 1 shl 1

        fun parse(r: ByteReader): ImuData {
            val mask = r.u16()
            val v = FloatArray(16) { bit -> if (mask and (1 shl bit) != 0) r.f32Auto() else 0f }
            return ImuData(
                roll = v[0],
                pitch = v[1],
                yaw = v[2],
                accel = Axes(v[3], v[4], v[5]),
                gyro = Axes(v[6], v[7], v[8]),
                magnetometer = Axes(v[9], v[10], v[11]),
                quaternion = Quaternion(v[12], v[13], v[14], v[15]),
                controllerId = if (r.remaining > 0) r.u8() else null,
            )
        }
    }
}

/** Reads the values in [mask] (bits as in [ImuData.ALL_FIELDS]). VESC Tool polls this at 50 Hz on its IMU page. */
suspend fun VescController.imu(mask: Int = ImuData.ALL_FIELDS): ImuData {
    val request = ByteWriter().u8(COMM_GET_IMU_DATA.id).u16(mask).toByteArray()
    return session.request(forwarded(request), COMM_GET_IMU_DATA) { ImuData.parse(it) }
}
