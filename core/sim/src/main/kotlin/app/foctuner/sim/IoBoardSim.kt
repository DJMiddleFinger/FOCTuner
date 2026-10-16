package app.foctuner.sim

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_IO_BOARD_GET_ALL
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * A VESC IO board at CAN id [ID]. Its outputs are wired back to its inputs so the simulation shows them: digital
 * outputs 0-7 drive digital inputs 0-7, and PWM outputs 0-3 reach ADC 5-8 through RC filters (duty × 3.3 V).
 * ADC 1 carries a slowly moving signal, input 10 is tied high.
 */
internal class IoBoardSim {
    private val pwm = FloatArray(4)
    private var outputs = 0L

    /** Null for any other id, which the firmware answers with silence. */
    fun all(id: Int, now: Duration): ByteArray? {
        if (id != ID) return null
        val t = now.toDouble(DurationUnit.SECONDS)
        val w = ByteWriter().u8(COMM_IO_BOARD_GET_ALL.id).i16(ID)
        w.u8(1).f32Auto(AGE_S)
        floatArrayOf(1.65f + 0.4f * sin(t * 0.5).toFloat(), 1.2f, 0f, 4.98f).forEach { w.f16(it, 100f) }
        w.u8(2).f32Auto(AGE_S)
        pwm.forEach { w.f16(it * 3.3f, 100f) }
        val inputs = (outputs and 0xFF) or (1L shl 10)
        return w.u8(3).f32Auto(AGE_S).u32(inputs ushr 32).u32(inputs and 0xFFFFFFFFL).toByteArray()
    }

    fun setPwm(id: Int, channel: Int, duty: Float) {
        if (id == ID && channel in pwm.indices) pwm[channel] = duty.coerceIn(0f, 1f)
    }

    fun setDigital(id: Int, channel: Int, on: Boolean) {
        if (id != ID || channel !in 0..63) return
        outputs = if (on) outputs or (1L shl channel) else outputs and (1L shl channel).inv()
    }

    private companion object {
        const val ID = 40
        const val AGE_S = 0.02f
    }
}
