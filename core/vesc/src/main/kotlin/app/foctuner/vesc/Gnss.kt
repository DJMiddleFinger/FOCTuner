package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_GET_GNSS

/**
 * COMM_GET_GNSS: the fix of a GNSS receiver the controller reads (over UART or from a VESC Express). Latitude and
 * longitude in degrees, [height] in m, [speed] in m/s, time of day in UTC. [ageSeconds] is the time since the
 * receiver last updated; a controller without one answers with zeros and a large age.
 */
data class GnssFix(
    val latitude: Double,
    val longitude: Double,
    val height: Float,
    val speed: Float,
    val hdop: Float,
    val msToday: Int,
    val year: Int,
    val month: Int,
    val day: Int,
    val ageSeconds: Float,
) {
    companion object {
        const val ALL_FIELDS = 0x3FF

        fun parse(r: ByteReader): GnssFix {
            val mask = r.u32()
            fun has(bit: Int) = mask and (1L shl bit) != 0L
            return GnssFix(
                latitude = if (has(0)) r.i64() / 1e16 else 0.0,
                longitude = if (has(1)) r.i64() / 1e16 else 0.0,
                height = if (has(2)) r.f32Auto() else 0f,
                speed = if (has(3)) r.f32Auto() else 0f,
                hdop = if (has(4)) r.f32Auto() else 0f,
                msToday = if (has(5)) r.i32() else 0,
                year = if (has(6)) r.i16() else 0,
                month = if (has(7)) r.u8() else 0,
                day = if (has(8)) r.u8() else 0,
                ageSeconds = if (has(9)) r.f32Auto() else 0f,
            )
        }
    }
}

suspend fun VescController.gnss(): GnssFix {
    val request = ByteWriter().u8(COMM_GET_GNSS.id).u16(GnssFix.ALL_FIELDS).toByteArray()
    return session.request(forwarded(request), COMM_GET_GNSS) { GnssFix.parse(it) }
}
