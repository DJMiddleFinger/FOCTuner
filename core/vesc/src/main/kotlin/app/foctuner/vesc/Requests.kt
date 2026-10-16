package app.foctuner.vesc

import app.foctuner.config.ConfigKind
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId
import app.foctuner.protocol.CommPacketId.COMM_ALIVE
import app.foctuner.protocol.CommPacketId.COMM_FORWARD_CAN
import app.foctuner.protocol.CommPacketId.COMM_FW_VERSION
import app.foctuner.protocol.CommPacketId.COMM_GET_APPCONF
import app.foctuner.protocol.CommPacketId.COMM_GET_APPCONF_DEFAULT
import app.foctuner.protocol.CommPacketId.COMM_GET_MCCONF
import app.foctuner.protocol.CommPacketId.COMM_GET_MCCONF_DEFAULT
import app.foctuner.protocol.CommPacketId.COMM_GET_VALUES
import app.foctuner.protocol.CommPacketId.COMM_MOTOR_ESTOP
import app.foctuner.protocol.CommPacketId.COMM_PING_CAN
import app.foctuner.protocol.CommPacketId.COMM_SET_APPCONF
import app.foctuner.protocol.CommPacketId.COMM_SET_MCCONF
import app.foctuner.protocol.CommPacketId.COMM_SET_CURRENT
import app.foctuner.protocol.CommPacketId.COMM_SET_CURRENT_BRAKE
import app.foctuner.protocol.CommPacketId.COMM_SET_DUTY
import app.foctuner.protocol.CommPacketId.COMM_SET_RPM
import app.foctuner.protocol.CommPacketId.COMM_TERMINAL_CMD
import kotlin.time.Duration

/*
 * Request payloads, with the scales used by the firmware's commands.c (and VESC Tool's commands.cpp):
 * duty is sent as duty * 1e5, currents as amps * 1e3, speed as plain ERPM, all as int32.
 */

internal object Requests {
    val firmwareInfo = bare(COMM_FW_VERSION)
    val values = bare(COMM_GET_VALUES)
    val pingCan = bare(COMM_PING_CAN)
    val alive = bare(COMM_ALIVE)
    val release = current(0f)

    /** The firmware reads the command up to the end of the packet, so there is no terminator. */
    fun terminal(command: String) = ByteWriter().u8(COMM_TERMINAL_CMD.id)
        .bytes(command.toByteArray(Charsets.ISO_8859_1)).toByteArray()

    fun duty(duty: Float): ByteArray {
        require(duty in -1f..1f) { "duty must be within -1..1, was $duty" }
        return ByteWriter().u8(COMM_SET_DUTY.id).f32(duty, 1e5f).toByteArray()
    }

    fun current(amps: Float): ByteArray {
        require(amps.isFinite()) { "current must be finite, was $amps" }
        return ByteWriter().u8(COMM_SET_CURRENT.id).f32(amps, 1e3f).toByteArray()
    }

    fun brakeCurrent(amps: Float): ByteArray {
        require(amps.isFinite()) { "brake current must be finite, was $amps" }
        return ByteWriter().u8(COMM_SET_CURRENT_BRAKE.id).f32(amps, 1e3f).toByteArray()
    }

    /** [erpm] is electrical RPM: mechanical RPM times the motor's pole pairs. */
    fun rpm(erpm: Int) = ByteWriter().u8(COMM_SET_RPM.id).i32(erpm).toByteArray()

    /** The firmware takes the time to ignore motor input as a uint16 in milliseconds. */
    fun emergencyStop(duration: Duration) = ByteWriter().u8(COMM_MOTOR_ESTOP.id)
        .u16(duration.inWholeMilliseconds.coerceIn(1, 65535).toInt()).toByteArray()

    /** The command that reads [kind] (or its firmware defaults); the reply has the same id. */
    fun readConfigId(kind: ConfigKind, defaults: Boolean): CommPacketId = when (kind) {
        ConfigKind.MCCONF -> if (defaults) COMM_GET_MCCONF_DEFAULT else COMM_GET_MCCONF
        ConfigKind.APPCONF -> if (defaults) COMM_GET_APPCONF_DEFAULT else COMM_GET_APPCONF
    }

    /** The command that stores [kind]; the firmware acknowledges with an empty packet of the same id. */
    fun writeConfigId(kind: ConfigKind): CommPacketId = when (kind) {
        ConfigKind.MCCONF -> COMM_SET_MCCONF
        ConfigKind.APPCONF -> COMM_SET_APPCONF
    }

    /** Sends [payload] to CAN controller [canId] instead of the one we are connected to. */
    fun forwarded(canId: Int?, payload: ByteArray): ByteArray =
        if (canId == null) payload else ByteWriter().u8(COMM_FORWARD_CAN.id).u8(canId).bytes(payload).toByteArray()

    fun bare(id: CommPacketId) = byteArrayOf(id.id.toByte())
}
