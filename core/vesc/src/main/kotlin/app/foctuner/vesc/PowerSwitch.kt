package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_PSW_GET_STATUS
import app.foctuner.protocol.CommPacketId.COMM_PSW_SWITCH

/**
 * A VESC power switch on the CAN bus as the controller last heard it (COMM_PSW_GET_STATUS). Voltages in V,
 * [temp] in °C, [ageSeconds] since its last status message. [count] is how many switches the controller knows.
 */
data class PowerSwitchStatus(
    val id: Int,
    val count: Int,
    val ageSeconds: Float,
    val voltageIn: Float,
    val voltageOut: Float,
    val temp: Float,
    val outputOn: Boolean,
    val prechargeOn: Boolean,
    val dischargeOn: Boolean,
) {
    companion object {
        fun parse(r: ByteReader) = PowerSwitchStatus(
            id = r.i16(),
            count = r.i16(),
            ageSeconds = r.f32Auto(),
            voltageIn = r.f32Auto(),
            voltageOut = r.f32Auto(),
            temp = r.f32Auto(),
            outputOn = r.u8() != 0,
            prechargeOn = r.u8() != 0,
            dischargeOn = r.u8() != 0,
        )
    }
}

/**
 * Status of the switch with CAN id [canId]. The controller doesn't answer for a switch it hasn't heard from, so
 * that times out ([VescTimeoutException]).
 */
suspend fun VescController.powerSwitch(canId: Int): PowerSwitchStatus = powerSwitchStatus(byId = true, canId)

/** Every power switch the controller has heard from, in the order it stored them; empty if none. */
suspend fun VescController.powerSwitches(): List<PowerSwitchStatus> {
    val first = try {
        powerSwitchStatus(byId = false, 0)
    } catch (e: VescTimeoutException) {
        return emptyList()
    }
    return listOf(first) + (1 until first.count).map { powerSwitchStatus(byId = false, it) }
}

/**
 * COMM_PSW_SWITCH: turns the output of switch [canId] on (through its precharge) or off. With [plotPrecharge]
 * the switch reports its precharge curve. There is no reply; read the status to see the result.
 */
suspend fun VescController.setPowerSwitch(canId: Int, on: Boolean, plotPrecharge: Boolean = false) {
    val payload = ByteWriter().u8(COMM_PSW_SWITCH.id).i16(canId).u8(if (on) 1 else 0).u8(if (plotPrecharge) 1 else 0)
    session.send(forwarded(payload.toByteArray()))
}

private suspend fun VescController.powerSwitchStatus(byId: Boolean, idOrIndex: Int): PowerSwitchStatus {
    val request = ByteWriter().u8(COMM_PSW_GET_STATUS.id).u8(if (byId) 1 else 0).i16(idOrIndex).toByteArray()
    return session.request(forwarded(request), COMM_PSW_GET_STATUS) { PowerSwitchStatus.parse(it) }
}
