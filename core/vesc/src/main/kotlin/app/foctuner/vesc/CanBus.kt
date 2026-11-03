package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_CAN_FWD_FRAME
import app.foctuner.protocol.CommPacketId.COMM_CAN_UPDATE_BAUD_ALL
import app.foctuner.protocol.CommPacketId.COMM_SET_CAN_MODE
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** CAN bit rates the firmware accepts (`comm_can_kbits_to_baud`), in kbit/s, with their appconf `can_baud_rate` value. */
enum class CanBaud(val kbits: Int, val appconfValue: Int) {
    K10(10, 4), K20(20, 5), K50(50, 6), K75(75, 7), K100(100, 8), K125(125, 0), K250(250, 1), K500(500, 2), M1(1000, 3);

    companion object {
        fun fromAppconf(value: Int): CanBaud? = entries.firstOrNull { it.appconfValue == value }
        fun fromKbits(kbits: Int): CanBaud? = entries.firstOrNull { it.kbits == kbits }
    }
}

/** Appconf `can_mode` values. Anything but [Vesc] and [VescUavcan] stops CAN forwarding to and through the controller. */
enum class CanMode(val value: Int, val forwardsVescCommands: Boolean) {
    Vesc(0, true), Uavcan(1, false), CommBridge(2, false), Unused(3, false), VescUavcan(4, true);

    companion object {
        fun fromValue(value: Int): CanMode? = entries.firstOrNull { it.value == value }
    }
}

/**
 * COMM_CAN_UPDATE_BAUD_ALL (firmware 6.06+): the controller tells every VESC on the bus to switch to [baud]
 * after [switchDelay], switches itself and stores it in its app config. Returns whether it accepted the rate.
 * Devices that miss the broadcast keep the old rate and drop off the bus until reconfigured over USB.
 * The firmware handles it in its blocking thread and spends about half a second broadcasting.
 */
suspend fun VescController.updateCanBaudAll(baud: CanBaud, switchDelay: Duration = 1.seconds): Boolean = session.blocking {
    val payload = ByteWriter().u8(COMM_CAN_UPDATE_BAUD_ALL.id).i16(baud.kbits).i16(switchDelay.inWholeMilliseconds.toInt())
    session.request(forwarded(payload.toByteArray()), COMM_CAN_UPDATE_BAUD_ALL, BAUD_TIMEOUT) { it.u8() != 0 }
}

/**
 * COMM_SET_CAN_MODE: sets appconf `can_mode` without a full app config write, stored when [store] is set.
 * VESC Tool edits `can_mode` in the app config instead; this is for the CAN analyzer, which needs
 * [CanMode.CommBridge] while it runs. With [ack] the controller confirms; without it this returns at once.
 */
suspend fun VescController.setCanMode(mode: CanMode, store: Boolean, ack: Boolean = true) {
    val payload = ByteWriter().u8(COMM_SET_CAN_MODE.id).u8(if (store) 1 else 0).u8(if (ack) 1 else 0).u8(mode.value).toByteArray()
    if (ack) {
        session.request(forwarded(payload), COMM_SET_CAN_MODE) { }
    } else {
        session.send(forwarded(payload))
    }
}

/** A raw CAN frame: [id] is 11 bits unless [extended] (29 bits); [data] has at most 8 bytes. */
class CanFrame(val id: Long, val extended: Boolean, val data: ByteArray) {
    init {
        require(data.size <= 8) { "A CAN frame carries at most 8 bytes" }
        require(id in 0..(if (extended) 0x1FFF_FFFFL else 0x7FFL)) { "CAN id ${id.toString(16)} is out of range" }
    }

    override fun equals(other: Any?) =
        other is CanFrame && other.id == id && other.extended == extended && other.data.contentEquals(data)

    override fun hashCode() = (id * 31 + if (extended) 1 else 0).toInt() * 31 + data.contentHashCode()

    override fun toString() = "CanFrame(${if (extended) "%08X" else "%03X"}".format(id) + ", ${data.toHex()})"
}

private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

/** COMM_CAN_FWD_FRAME: the controller sends [frame] on its CAN bus. No reply. */
suspend fun VescController.sendCanFrame(frame: CanFrame) {
    val payload = ByteWriter().u8(COMM_CAN_FWD_FRAME.id).u32(frame.id).u8(if (frame.extended) 1 else 0).bytes(frame.data)
    session.send(forwarded(payload.toByteArray()))
}

/**
 * Frames the controller relays from its bus (COMM_CAN_FWD_FRAME), which it does only while its `can_mode` is
 * [CanMode.CommBridge]. They carry no sender.
 */
val VescController.canFrames: Flow<CanFrame>
    get() = session.incoming.mapNotNull { packet ->
        if ((packet[0].toInt() and 0xFF) != COMM_CAN_FWD_FRAME.id || packet.size < 6) return@mapNotNull null
        val r = ByteReader(packet, 1)
        val id = r.u32()
        val extended = r.u8() != 0
        val data = r.restBytes().let { if (it.size > 8) it.copyOf(8) else it }
        runCatching { CanFrame(if (extended) id and 0x1FFF_FFFFL else id and 0x7FFL, extended, data) }.getOrNull()
    }

private val BAUD_TIMEOUT = 4000.milliseconds
