package app.foctuner.sim

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_CUSTOM_APP_DATA
import kotlin.math.sin
import kotlin.time.Duration

/**
 * A Refloat package answering its INFO (version 2) and legacy realtime commands, for demo mode: a board standing
 * still with nobody on it (footpad off, "switch full" fault), leaning a little as if propped on its nose, with
 * LOCK and HANDTEST stored. Custom app data that doesn't start with Refloat's id 101 is echoed as before.
 */
internal class RefloatSim(private val motor: MotorModel) {
    private var locked = false
    private var handtest = false

    /** The reply to [data] (custom app data without the command id), or null if nothing is sent back. */
    fun handle(data: ByteArray, uptime: Duration): ByteArray? {
        if (data.size < 2 || data[0].toInt() != 101) return null
        val w = ByteWriter().u8(COMM_CUSTOM_APP_DATA.id).u8(101).u8(data[1].toInt())
        return when (data[1].toInt()) {
            0 -> w.u8(2).u8(data.getOrElse(3) { 0 }.toInt())
                .bytes(fixed("Refloat")).u8(1).u8(1).u8(0).bytes(fixed("demo")).u32(0x5EED).u32(10_000).u32(0).u8(0)
                .toByteArray()
            1 -> {
                val t = uptime.inWholeMilliseconds / 1000.0
                val pitch = (6.0 + 0.4 * sin(t)).toFloat()
                val state = if (locked) 15 else 9
                w.f32Auto(motor.current).f32Auto(pitch).f32Auto((1.5 * sin(t / 3)).toFloat())
                    .u8(state + (2 shl 4))
                    .u8(if (handtest) 0x8 else 0)
                    .f32Auto(0.4f).f32Auto(0.38f)
                    .f32Auto(0f).f32Auto(0f).f32Auto(0f).f32Auto(0f).f32Auto(0f).f32Auto(0f)
                    .f32Auto(pitch).f32Auto(motor.current).f32Auto(0f).f32Auto(0f).f32Auto(0f).f32Auto(0f)
                    .toByteArray()
            }
            12 -> {
                locked = data.getOrElse(2) { 0 }.toInt() != 0
                null
            }
            13 -> {
                handtest = data.getOrElse(2) { 0 }.toInt() != 0 && !locked
                null
            }
            else -> null
        }
    }

    private fun fixed(text: String) = text.toByteArray(Charsets.US_ASCII).copyOf(20)
}
