package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_GET_STATS
import app.foctuner.protocol.CommPacketId.COMM_RESET_STATS

/**
 * COMM_GET_STATS: averages and peaks the firmware keeps since its stats were last reset (or since boot).
 * Speed is in m/s from the configured wheel and gearing, power in W, current in A, temperatures in °C,
 * [seconds] is how long the stats have been counting.
 */
data class RideStats(
    val speedAvg: Float,
    val speedMax: Float,
    val powerAvg: Float,
    val powerMax: Float,
    val currentAvg: Float,
    val currentMax: Float,
    val tempMosfetAvg: Float,
    val tempMosfetMax: Float,
    val tempMotorAvg: Float,
    val tempMotorMax: Float,
    val seconds: Float,
) {
    companion object {
        /** One bit per field, in field order; the request carries it as uint16, the reply echoes it as uint32. */
        const val ALL_FIELDS = 0x7FF

        fun parse(r: ByteReader): RideStats {
            val mask = r.u32()
            val v = FloatArray(11) { bit -> if (mask and (1L shl bit) != 0L) r.f32Auto() else 0f }
            return RideStats(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10])
        }
    }
}

suspend fun VescController.stats(): RideStats {
    val request = ByteWriter().u8(COMM_GET_STATS.id).u16(RideStats.ALL_FIELDS).toByteArray()
    return session.request(forwarded(request), COMM_GET_STATS) { RideStats.parse(it) }
}

/** Clears the stats. Asks for the acknowledgement the firmware sends when the request's flag byte is set. */
suspend fun VescController.resetStats() {
    session.request(forwarded(byteArrayOf(COMM_RESET_STATS.id.toByte(), 1)), COMM_RESET_STATS) { }
}
