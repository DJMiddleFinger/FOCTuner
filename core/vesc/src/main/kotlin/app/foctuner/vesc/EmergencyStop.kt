package app.foctuner.vesc

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_MOTOR_ESTOP
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How long VESC Tool's stop button makes the controller ignore motor commands (its `estop_ms` setting). */
val DEFAULT_ESTOP_DURATION: Duration = 5.seconds

/**
 * What VESC Tool's stop button does: releases the motor (zero current, which also ends this client's
 * keepalive for it), then sends COMM_MOTOR_ESTOP. The controller releases both motors of its board and ignores
 * every motor command for [duration] (up to 65.535 s); commands sent meanwhile are dropped, not delayed. The
 * command timeout and COMM_ALIVE keep working. Firmware older than 6.05 doesn't know ESTOP and only gets the
 * release.
 */
suspend fun VescController.emergencyStop(duration: Duration = DEFAULT_ESTOP_DURATION) {
    require(duration in 1.milliseconds..MAX_ESTOP) { "ESTOP duration must be within 1 ms..$MAX_ESTOP, was $duration" }
    motor.release()
    val ms = duration.inWholeMilliseconds.toInt()
    session.send(forwarded(ByteWriter().u8(COMM_MOTOR_ESTOP.id).u16(ms).toByteArray()))
}

private val MAX_ESTOP = 65_535.milliseconds
