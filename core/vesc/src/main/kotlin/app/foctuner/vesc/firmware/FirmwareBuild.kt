package app.foctuner.vesc.firmware

import app.foctuner.protocol.CommPacketId.COMM_FW_INFO
import app.foctuner.vesc.VescController

/** COMM_FW_INFO: which source the running firmware was built from. Firmware before 6.05 doesn't answer. */
data class FirmwareBuild(
    val major: Int,
    val minor: Int,
    /** Non-zero on beta builds. */
    val testVersion: Int,
    val gitHash: String,
    /** Set by people who build their own firmware. */
    val userGitHash: String,
)

suspend fun VescController.firmwareBuild(): FirmwareBuild =
    session.request(forwarded(byteArrayOf(COMM_FW_INFO.id.toByte())), COMM_FW_INFO) { r ->
        FirmwareBuild(r.u8(), r.u8(), r.u8(), r.cString(), if (r.remaining > 0) r.cString() else "")
    }
