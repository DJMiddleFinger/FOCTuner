package app.foctuner.sim

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_ROTOR_POSITION
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * COMM_SET_DETECT and the COMM_ROTOR_POSITION stream from `main.c`. The firmware sends a sample every 10 ms
 * while a mode is selected; the simulation has no clock of its own, so it sends one sample with each write
 * that arrives at least 10 ms after the previous sample (the client's polling and keepalive set the pace).
 * The mode is one setting for the whole board, as in the firmware; samples describe the motor that selected it.
 * The simulated motors are FOC, so inductance mode sends nothing.
 */
internal class RotorPositionSim(private val vesc: SimulatedVesc) {
    private var mode = NONE
    private var motor: MotorModel? = null
    private var lastSample = Duration.ZERO - INTERVAL

    fun select(mode: Int, motor: MotorModel) {
        this.mode = mode
        this.motor = motor
    }

    fun sendDue(now: Duration) {
        val m = motor ?: return
        if (now - lastSample < INTERVAL) return
        val t = now.toDouble(DurationUnit.SECONDS)
        val degrees = when (mode) {
            OBSERVER -> m.angle * POLE_PAIRS % 360f
            ENCODER, PID_POSITION -> m.angle
            PID_POSITION_ERROR -> (m.drive as? Drive.Position)?.let { m.angleError(it.degrees) } ?: 0f
            ENCODER_OBSERVER_ERROR -> (0.6 * sin(t * 7.0)).toFloat()
            HALL_OBSERVER_ERROR -> (4.0 * sin(t * 3.0)).toFloat()
            else -> return
        }
        lastSample = now
        vesc.reply(ByteWriter().u8(COMM_ROTOR_POSITION.id).f32(degrees, 1e5f).toByteArray())
    }

    private companion object {
        const val NONE = 0
        const val OBSERVER = 2
        const val ENCODER = 3
        const val PID_POSITION = 4
        const val PID_POSITION_ERROR = 5
        const val ENCODER_OBSERVER_ERROR = 6
        const val HALL_OBSERVER_ERROR = 7
        const val POLE_PAIRS = 7
        val INTERVAL = 10.milliseconds
    }
}
