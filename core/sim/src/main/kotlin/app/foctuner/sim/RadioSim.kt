package app.foctuner.sim

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_CAN_FWD_FRAME
import app.foctuner.protocol.CommPacketId.COMM_CAN_UPDATE_BAUD_ALL
import app.foctuner.protocol.CommPacketId.COMM_NRF_START_PAIRING
import app.foctuner.protocol.CommPacketId.COMM_SET_CAN_MODE
import app.foctuner.config.ConfigKind
import kotlin.time.Duration.Companion.milliseconds

/**
 * The board's nRF module and CAN bus as the firmware drives them:
 * - COMM_SET_BLE_NAME / PIN are stored ([bleName], [blePin]); the module restart isn't modelled.
 * - COMM_NRF_START_PAIRING replies "started", then "paired" after [REMOTE_DELAY] when the window is at least that
 *   long (a remote that asks to pair 3 s in), else "failed" when the window ends.
 * - COMM_CAN_UPDATE_BAUD_ALL replies whether the rate is valid and stores `can_baud_rate` on both motors.
 * - COMM_SET_CAN_MODE stores `can_mode`. In Comm Bridge mode the bus is relayed with COMM_CAN_FWD_FRAME: the second
 *   motor's CAN status 1 every 100 ms, and a device at standard id [ECHO_ID] that answers a frame sent to it from
 *   [ECHO_ID] + 1 with the same data.
 */
internal class RadioSim(private val vesc: SimulatedVesc, private val motors: List<MotorModel>, private val statusId: Int?) {
    var bleName: String? = null
        private set
    var blePin: String? = null
        private set
    private var bridging = false

    fun setBleName(r: ByteReader) {
        bleName = r.cString()
    }

    fun setBlePin(r: ByteReader) {
        blePin = r.cString()
    }

    fun startPairing(r: ByteReader) {
        val windowMs = r.i32()
        vesc.reply(byteArrayOf(COMM_NRF_START_PAIRING.id.toByte(), 0))
        val paired = windowMs >= REMOTE_DELAY_MS
        vesc.later((if (paired) REMOTE_DELAY_MS else windowMs.coerceAtLeast(0)).milliseconds) {
            vesc.reply(byteArrayOf(COMM_NRF_START_PAIRING.id.toByte(), if (paired) 1 else 2))
        }
    }

    fun updateBaudAll(r: ByteReader) {
        val baud = BAUD_BY_KBITS[r.i16()]
        if (baud != null) motors.forEach { vesc.updateConfig(it, ConfigKind.APPCONF) { c -> c.with("can_baud_rate", baud.toLong()) } }
        vesc.reply(byteArrayOf(COMM_CAN_UPDATE_BAUD_ALL.id.toByte(), if (baud != null) 1 else 0))
    }

    fun setCanMode(r: ByteReader, motor: MotorModel) {
        r.u8() // store: the sim keeps what it is given either way
        val ack = r.u8() != 0
        val mode = r.u8()
        vesc.updateConfig(motor, ConfigKind.APPCONF) { it.with("can_mode", mode.toLong()) }
        if (motor === motors.first()) {
            val bridge = mode == COMM_BRIDGE
            if (bridge && !bridging) {
                bridging = true
                heartbeat()
            }
            bridging = bridge
        }
        if (ack) vesc.reply(byteArrayOf(COMM_SET_CAN_MODE.id.toByte()))
    }

    fun forwardFrame(r: ByteReader) {
        val id = r.u32()
        val extended = r.u8() != 0
        val data = r.restBytes()
        if (bridging && !extended && id == ECHO_ID.toLong()) {
            vesc.later(2.milliseconds) { if (bridging) relay(ECHO_ID + 1L, extended = false, data) }
        }
    }

    private fun heartbeat() {
        if (!bridging) return
        val id = statusId
        val motor = motors.getOrNull(1)
        if (id != null && motor != null) {
            // CAN_PACKET_STATUS (9): ERPM i32, current i16 x10, duty i16 x1000, as comm_can.c sends it.
            val data = ByteWriter().i32(motor.erpm.toInt()).i16((motor.current * 10).toInt()).i16((motor.duty * 1000).toInt())
                .toByteArray()
            relay((CAN_PACKET_STATUS shl 8 or id).toLong(), extended = true, data)
        }
        vesc.later(HEARTBEAT) { heartbeat() }
    }

    private fun relay(id: Long, extended: Boolean, data: ByteArray) {
        vesc.reply(ByteWriter().u8(COMM_CAN_FWD_FRAME.id).u32(id).u8(if (extended) 1 else 0).bytes(data).toByteArray())
    }

    private companion object {
        const val REMOTE_DELAY_MS = 3000
        const val COMM_BRIDGE = 2
        const val ECHO_ID = 0x123
        const val CAN_PACKET_STATUS = 9
        val HEARTBEAT = 100.milliseconds
        val BAUD_BY_KBITS = mapOf(125 to 0, 250 to 1, 500 to 2, 1000 to 3, 10 to 4, 20 to 5, 50 to 6, 75 to 7, 100 to 8)
    }
}
