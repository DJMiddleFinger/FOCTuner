package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.CommPacketId.COMM_ROTOR_POSITION
import app.foctuner.protocol.CommPacketId.COMM_SET_DETECT
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the controller streams as COMM_ROTOR_POSITION (the firmware's `disp_pos_mode`; the ordinal is the wire value). */
enum class PositionDisplay {
    NONE,

    /** BLDC only: inductance-based position detection, which drives the motor. FOC firmware sends nothing. */
    INDUCTANCE,

    /** FOC only: the observer's electrical angle. */
    OBSERVER,
    ENCODER,

    /** The position PID's current position (VESC Tool: "PID Pos"). */
    PID_POSITION,

    /** Set point minus position of the position PID. */
    PID_POSITION_ERROR,

    /** FOC only: observer angle minus encoder angle. */
    ENCODER_OBSERVER_ERROR,

    /** FOC only: observer angle minus hall sensor angle. */
    HALL_OBSERVER_ERROR,
}

/**
 * Angles in degrees as the controller streams them (every 10 ms) while [mode] is selected, VESC Tool's
 * "Rotor Position" plot. Collecting selects [mode] with COMM_SET_DETECT and cancelling selects NONE again,
 * which on a BLDC motor also releases the motor. [PositionDisplay.INDUCTANCE] counts as a motor command
 * (keepalive until the flow is cancelled, then a release).
 *
 * Samples carry no sender, so only one controller on the bus should stream at a time.
 */
fun VescController.rotorPositions(mode: PositionDisplay): Flow<Float> = channelFlow {
    require(mode != PositionDisplay.NONE) { "select a mode to stream" }
    launch(start = CoroutineStart.UNDISPATCHED) {
        session.incoming.collect { packet ->
            if (packet[0].toInt() and 0xFF == COMM_ROTOR_POSITION.id) send(ByteReader(packet, 1).f32(1e5f))
        }
    }
    try {
        val select = byteArrayOf(COMM_SET_DETECT.id.toByte(), mode.ordinal.toByte())
        if (mode == PositionDisplay.INDUCTANCE) session.motorCommand(canId, select) else session.send(forwarded(select))
        awaitCancellation()
    } finally {
        withContext(NonCancellable) {
            try {
                session.send(forwarded(byteArrayOf(COMM_SET_DETECT.id.toByte(), PositionDisplay.NONE.ordinal.toByte())))
                if (mode == PositionDisplay.INDUCTANCE) motor.release()
            } catch (e: VescLinkException) {
                // The link is gone and the controller stops streaming to it.
            }
        }
    }
}
