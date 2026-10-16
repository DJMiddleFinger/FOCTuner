package app.foctuner.vesc

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_SET_SERVO_POS

/**
 * COMM_SET_SERVO_POS: sets the servo output (the PPM pin driven as a servo, when the app config enables it)
 * to [position], 0..1. Unlike motor commands it is not subject to the command timeout, so it stays where it
 * was set without a keepalive.
 */
suspend fun VescController.setServoPosition(position: Float) {
    require(position in 0f..1f) { "servo position must be within 0..1, was $position" }
    session.send(forwarded(ByteWriter().u8(COMM_SET_SERVO_POS.id).f16(position, 1e3f).toByteArray()))
}
