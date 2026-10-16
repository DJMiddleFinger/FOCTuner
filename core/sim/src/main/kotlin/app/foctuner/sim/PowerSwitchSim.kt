package app.foctuner.sim

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_PSW_GET_STATUS

/**
 * A VESC power switch at CAN id [ID] on the pack, as the controller relays its status. Off at start; switching
 * it on connects the output to the pack.
 */
internal class PowerSwitchSim {
    private var on = false

    /** Null when no switch matches, which the firmware answers with silence. */
    fun status(byId: Boolean, idOrIndex: Int, packVoltage: Float): ByteArray? {
        if (if (byId) idOrIndex != ID else idOrIndex != 0) return null
        return ByteWriter().u8(COMM_PSW_GET_STATUS.id).i16(ID).i16(1)
            .f32Auto(AGE_S).f32Auto(packVoltage).f32Auto(if (on) packVoltage - DROP else 0f).f32Auto(TEMP)
            .u8(if (on) 1 else 0).u8(0).u8(0)
            .toByteArray()
    }

    fun switch(id: Int, on: Boolean) {
        if (id == ID) this.on = on
    }

    private companion object {
        const val ID = 30
        const val AGE_S = 0.05f
        const val DROP = 0.08f
        const val TEMP = 28.5f
    }
}
