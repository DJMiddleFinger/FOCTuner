package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_IO_BOARD_GET_ALL
import app.foctuner.protocol.CommPacketId.COMM_IO_BOARD_SET_DIGITAL
import app.foctuner.protocol.CommPacketId.COMM_IO_BOARD_SET_PWM

/**
 * What the controller last heard from the VESC IO board with CAN id [id] (COMM_IO_BOARD_GET_ALL). Each group is
 * null when the board hasn't sent it. ADC readings in V; ages in seconds since the board's last message.
 * Firmware reports the ADC 1-4 age for the other groups too.
 */
data class IoBoardValues(
    val id: Int,
    val adc1to4: List<Float>?,
    val adc1to4Age: Float?,
    val adc5to8: List<Float>?,
    val adc5to8Age: Float?,
    /** 64 digital inputs, index = input number. */
    val digital: List<Boolean>?,
    val digitalAge: Float?,
) {
    companion object {
        fun parse(r: ByteReader): IoBoardValues {
            val id = r.i16()
            var adcLow: Pair<Float, List<Float>>? = null
            var adcHigh: Pair<Float, List<Float>>? = null
            var digital: Pair<Float, List<Boolean>>? = null
            while (r.remaining > 0) {
                when (r.u8()) {
                    1 -> adcLow = r.f32Auto() to List(4) { r.f16(100f) }
                    2 -> adcHigh = r.f32Auto() to List(4) { r.f16(100f) }
                    3 -> {
                        val age = r.f32Auto()
                        val bits = (r.u32() shl 32) or r.u32()
                        digital = age to List(64) { bits ushr it and 1L == 1L }
                    }
                    else -> break
                }
            }
            return IoBoardValues(id, adcLow?.second, adcLow?.first, adcHigh?.second, adcHigh?.first, digital?.second, digital?.first)
        }
    }
}

/** Times out ([VescTimeoutException]) when the controller has heard nothing from that board. */
suspend fun VescController.ioBoard(canId: Int): IoBoardValues {
    val request = ByteWriter().u8(COMM_IO_BOARD_GET_ALL.id).i16(canId).toByteArray()
    return session.request(forwarded(request), COMM_IO_BOARD_GET_ALL) { IoBoardValues.parse(it) }
}

/** Sets PWM output [channel] of board [canId] to [duty], 0..1. No reply. */
suspend fun VescController.setIoBoardPwm(canId: Int, channel: Int, duty: Float) {
    require(duty in 0f..1f) { "duty must be within 0..1, was $duty" }
    session.send(forwarded(ByteWriter().u8(COMM_IO_BOARD_SET_PWM.id).i16(canId).i16(channel).f32Auto(duty).toByteArray()))
}

/** Switches digital output [channel] of board [canId]. No reply. */
suspend fun VescController.setIoBoardDigital(canId: Int, channel: Int, on: Boolean) {
    session.send(forwarded(ByteWriter().u8(COMM_IO_BOARD_SET_DIGITAL.id).i16(canId).i16(channel).u8(if (on) 1 else 0).toByteArray()))
}
