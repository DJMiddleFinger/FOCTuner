package app.foctuner.vesc

/**
 * Drives the motor directly. Each call replaces the previous command. From the first command until
 * [release], the client sends COMM_ALIVE every [VescClientOptions.keepaliveInterval] so the firmware's
 * command timeout doesn't stop the motor. Nothing else keeps a command alive, and the client releases
 * every active motor when it is closed or its scope is cancelled.
 *
 * If the link drops the keepalive stops with it, and the controller releases the motor itself when its
 * command timeout (1 s by default; 0 disables it) runs out.
 */
class MotorControl internal constructor(
    private val session: VescSession,
    private val canId: Int?,
) {
    /** Torque-producing (q axis) current in amps; negative runs the motor backwards. */
    suspend fun setCurrent(amps: Float) = session.motorCommand(canId, Requests.current(amps))

    /** Braking current in amps; the controller applies it against the direction of rotation. */
    suspend fun setBrakeCurrent(amps: Float) = session.motorCommand(canId, Requests.brakeCurrent(amps))

    /** Duty cycle, -1..1. */
    suspend fun setDuty(duty: Float) = session.motorCommand(canId, Requests.duty(duty))

    /** Speed in electrical RPM (mechanical RPM times pole pairs). */
    suspend fun setRpm(erpm: Int) = session.motorCommand(canId, Requests.rpm(erpm))

    /**
     * Holds the rotor at [degrees] (0..360) with the position PID. Needs a position sensor (encoder) or a
     * motor that the observer can track at standstill; the angle includes `p_pid_offset`.
     */
    suspend fun setPosition(degrees: Float) = session.motorCommand(canId, Requests.position(degrees))

    /**
     * Holds the rotor still with [amps]: on FOC an open-loop current vector at a fixed angle, on BLDC and DC
     * an ordinary brake current.
     */
    suspend fun setHandbrake(amps: Float) = session.motorCommand(canId, Requests.handbrake(amps))

    /**
     * Current as a fraction (-1..1) of the configured limit: of the maximum current while the duty cycle is
     * near zero or has the same sign, of the maximum braking current otherwise.
     */
    suspend fun setCurrentRelative(fraction: Float) = session.motorCommand(canId, Requests.currentRelative(fraction))

    /** Stops driving the motor (zero current) and ends the keepalive for it. */
    suspend fun release() = session.releaseMotor(canId)
}
