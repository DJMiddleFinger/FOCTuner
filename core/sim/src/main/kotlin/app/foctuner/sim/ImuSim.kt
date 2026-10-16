package app.foctuner.sim

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_GET_IMU_DATA
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * The board's IMU on a small vehicle driven by [motor]: it pitches back when accelerating and forward when
 * braking, sways a little in roll, and turns gently (yaw follows the distance covered). Accelerometer, gyro,
 * magnetometer and quaternion are derived from that attitude, so they agree with each other. Advanced lazily
 * at each COMM_GET_IMU_DATA, like the rest of the simulation.
 */
internal class ImuSim(private val motor: MotorModel) {
    private var last = Duration.ZERO
    private var lastSpeed = 0f
    private var accel = 0f
    private var yaw = 0f
    private var rpy = FloatArray(3)
    private var rates = FloatArray(3)

    fun reply(now: Duration, mask: Int, controllerId: Int): ByteArray {
        advance(now)
        val (roll, pitch, yawNow) = rpy
        val gx = -sin(pitch) + accel / G * cos(pitch)
        val gy = sin(roll) * cos(pitch)
        val gz = cos(roll) * cos(pitch)
        val q = quaternion(roll, pitch, yawNow)
        val values = floatArrayOf(
            roll, pitch, yawNow,
            gx, gy, gz,
            rates[0], rates[1], rates[2],
            EARTH_HORIZONTAL * cos(yawNow), -EARTH_HORIZONTAL * sin(yawNow), EARTH_VERTICAL,
            q[0], q[1], q[2], q[3],
        )
        val w = ByteWriter().u8(COMM_GET_IMU_DATA.id).u16(mask)
        values.forEachIndexed { bit, v -> if (mask and (1 shl bit) != 0) w.f32Auto(v) }
        return w.u8(controllerId).toByteArray()
    }

    private fun advance(now: Duration) {
        val dt = (now - last).toDouble(DurationUnit.SECONDS).toFloat()
        if (dt <= 0f) return
        val t = now.toDouble(DurationUnit.SECONDS).toFloat()
        val speed = motor.erpm / POLE_PAIRS / 60f * PI.toFloat() * WHEEL_DIAMETER_M
        accel += ((speed - lastSpeed) / dt - accel) * (1f - exp(-dt / ACCEL_TAU))
        yaw = wrap(yaw + speed * dt * TURN_PER_METER)
        val next = floatArrayOf(
            ROLL_SWAY * sin(t * 0.7f),
            -atan(accel / G) * PITCH_PER_ACCEL + PITCH_SWAY * sin(t * 1.3f),
            yaw,
        )
        for (i in 0..2) rates[i] = (wrap(next[i] - rpy[i]) / dt) * DEG_PER_RAD
        rpy = next
        lastSpeed = speed
        last = now
    }

    /** ZYX (yaw, pitch, roll) Euler angles to a unit quaternion w, x, y, z. */
    private fun quaternion(roll: Float, pitch: Float, yaw: Float): FloatArray {
        val cr = cos(roll / 2)
        val sr = sin(roll / 2)
        val cp = cos(pitch / 2)
        val sp = sin(pitch / 2)
        val cy = cos(yaw / 2)
        val sy = sin(yaw / 2)
        return floatArrayOf(
            cr * cp * cy + sr * sp * sy,
            sr * cp * cy - cr * sp * sy,
            cr * sp * cy + sr * cp * sy,
            cr * cp * sy - sr * sp * cy,
        )
    }

    private fun wrap(angle: Float): Float {
        val twoPi = 2 * PI.toFloat()
        return ((angle + PI.toFloat()) % twoPi + twoPi) % twoPi - PI.toFloat()
    }

    private companion object {
        const val G = 9.81f
        const val POLE_PAIRS = 7f
        const val WHEEL_DIAMETER_M = 0.083f
        const val ACCEL_TAU = 0.2f
        const val TURN_PER_METER = 0.05f
        const val PITCH_PER_ACCEL = 0.6f
        const val ROLL_SWAY = 0.03f
        const val PITCH_SWAY = 0.01f
        const val DEG_PER_RAD = 57.29578f
        const val EARTH_HORIZONTAL = 20f
        const val EARTH_VERTICAL = 45f
    }
}
