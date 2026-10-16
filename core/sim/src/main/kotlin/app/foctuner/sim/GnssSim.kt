package app.foctuner.sim

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_GET_GNSS
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration

/** A GNSS receiver with a good fix that moves north-east with the distance [motor] covers. */
internal class GnssSim(private val motor: MotorModel) {
    fun reply(now: Duration, mask: Int): ByteArray {
        val meters = motor.tachometer * METERS_PER_STEP
        val lat = START_LAT + meters * cos(HEADING) / METERS_PER_DEGREE
        val lon = START_LON + meters * sin(HEADING) / (METERS_PER_DEGREE * cos(START_LAT * PI / 180))
        val speed = abs(motor.erpm) / 7f / 60f * PI.toFloat() * 0.083f
        val w = ByteWriter().u8(COMM_GET_GNSS.id).u32(mask.toLong())
        fun field(bit: Int, write: ByteWriter.() -> Unit) {
            if (mask and (1 shl bit) != 0) w.write()
        }
        field(0) { i64((lat * 1e16).toLong()) }
        field(1) { i64((lon * 1e16).toLong()) }
        field(2) { f32Auto(HEIGHT) }
        field(3) { f32Auto(speed) }
        field(4) { f32Auto(HDOP) }
        field(5) { i32(((START_MS_TODAY + now.inWholeMilliseconds) % DAY_MS).toInt()) }
        field(6) { i16(2026) }
        field(7) { u8(10) }
        field(8) { u8(16) }
        field(9) { f32Auto(AGE_S) }
        return w.toByteArray()
    }

    private companion object {
        const val START_LAT = 57.70887
        const val START_LON = 11.97456
        const val HEADING = PI / 6
        const val METERS_PER_DEGREE = 111_320.0
        const val METERS_PER_STEP = PI * 0.083 / 42
        const val HEIGHT = 12.5f
        const val HDOP = 0.8f
        const val AGE_S = 0.1f
        const val START_MS_TODAY = 9L * 3_600_000L
        const val DAY_MS = 86_400_000L
    }
}
