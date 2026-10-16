package app.foctuner.sim

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId
import kotlin.math.PI
import kotlin.time.Duration

/**
 * COMM_GET_VALUES_SETUP(_SELECTIVE) and COMM_SET_ODOMETER for a vehicle with a 7 pole-pair motor on an 83 mm
 * wheel and a 12S 10 Ah pack (the `si_*` settings the firmware uses). The simulated bus has no other controllers,
 * so the totals are this motor's own and the count is 1. The odometer is one per board and advances with the
 * first motor's distance, as the firmware's backup value does.
 */
internal class SetupValuesSim(private val battery: Battery, private val board: MotorModel) {
    private var odometerSet = INITIAL_ODOMETER_M
    private var distanceAtSet = 0.0

    fun setOdometer(meters: Long) {
        odometerSet = meters
        distanceAtSet = board.tachometerAbs * METERS_PER_STEP
    }

    /** The reply for [id] (plain or selective); a selective reply echoes [mask] first. */
    fun reply(id: CommPacketId, mask: Long, motor: MotorModel, voltage: Float, controllerId: Int, uptime: Duration): ByteArray {
        val w = ByteWriter().u8(id.id)
        if (id == CommPacketId.COMM_GET_VALUES_SETUP_SELECTIVE) w.u32(mask)
        fun field(bit: Int, write: ByteWriter.() -> Unit) {
            if (mask and (1L shl bit) != 0L) w.write()
        }
        val speed = motor.erpm / POLE_PAIRS / 60f * PI.toFloat() * WHEEL_DIAMETER_M
        field(0) { f16(motor.tempFet, 10f) }
        field(1) { f16(motor.tempMotor, 10f) }
        field(2) { f32(motor.current, 100f) }
        field(3) { f32(motor.inputCurrent, 100f) }
        field(4) { f16(motor.duty, 1000f) }
        field(5) { f32(motor.erpm, 1f) }
        field(6) { f32(speed, 1000f) }
        field(7) { f16(voltage, 10f) }
        field(8) { f16(battery.charge, 1000f) }
        field(9) { f32(motor.ampHours.toFloat(), 1e4f) }
        field(10) { f32(motor.ampHoursCharged.toFloat(), 1e4f) }
        field(11) { f32(motor.wattHours.toFloat(), 1e4f) }
        field(12) { f32(motor.wattHoursCharged.toFloat(), 1e4f) }
        field(13) { f32((motor.tachometer * METERS_PER_STEP).toFloat(), 1000f) }
        field(14) { f32((motor.tachometerAbs * METERS_PER_STEP).toFloat(), 1000f) }
        field(15) { f32(motor.angle, 1e6f) }
        field(16) { u8(0) }
        field(17) { u8(controllerId) }
        field(18) { u8(1) }
        field(19) { f32(battery.charge * PACK_WH, 1000f) }
        field(20) { u32(odometerSet + (board.tachometerAbs * METERS_PER_STEP - distanceAtSet).toLong()) }
        field(21) { u32(uptime.inWholeMilliseconds) }
        return w.toByteArray()
    }

    private companion object {
        const val POLE_PAIRS = 7f
        const val WHEEL_DIAMETER_M = 0.083f

        /** The firmware's tacho scale: wheel circumference / (3 x motor poles), 6 steps per electrical revolution. */
        const val METERS_PER_STEP = PI * WHEEL_DIAMETER_M / (3 * 14)
        const val PACK_WH = 10f * 12f * 3.7f
        const val INITIAL_ODOMETER_M = 412_300L
    }
}
