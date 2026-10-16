package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_GET_VALUES_SETUP
import app.foctuner.protocol.CommPacketId.COMM_GET_VALUES_SETUP_SELECTIVE
import app.foctuner.protocol.CommPacketId.COMM_SET_ODOMETER

/** The fields of COMM_GET_VALUES_SETUP in wire order; the ordinal is the bit in the selective mask. */
enum class SetupField {
    TEMP_MOSFET, TEMP_MOTOR, CURRENT_MOTOR, CURRENT_INPUT, DUTY, ERPM, SPEED, VOLTAGE_INPUT, BATTERY_LEVEL,
    AMP_HOURS, AMP_HOURS_CHARGED, WATT_HOURS, WATT_HOURS_CHARGED, DISTANCE, DISTANCE_ABS, PID_POSITION, FAULT,
    CONTROLLER_ID, CONTROLLER_COUNT, BATTERY_WATT_HOURS, ODOMETER, UPTIME,
}

/**
 * COMM_GET_VALUES_SETUP: the vehicle as a whole. Currents, Ah and Wh are summed over this controller and every
 * controller it heard on CAN in the last 0.1 s ([controllerCount] of them). Speed (m/s), distances (m) and the
 * battery come from the motor config's `si_*` settings (wheel, gearing, poles, battery type, cells and Ah).
 * Fields a selective request left out are zero.
 */
data class SetupValues(
    val tempMosfet: Float,
    val tempMotor: Float,
    val currentMotor: Float,
    val currentInput: Float,
    val dutyCycle: Float,
    val erpm: Float,
    val speed: Float,
    val voltageInput: Float,
    /** 0..1, estimated from the pack voltage. */
    val batteryLevel: Float,
    val ampHours: Float,
    val ampHoursCharged: Float,
    val wattHours: Float,
    val wattHoursCharged: Float,
    /** Since boot, signed. */
    val distance: Float,
    /** Since boot, both directions counted. */
    val distanceAbs: Float,
    val pidPosition: Float,
    val faultCode: Int,
    val controllerId: Int,
    val controllerCount: Int,
    /** Energy left in the pack, estimated from the voltage. */
    val batteryWattHours: Float,
    /** Meters, stored by the controller across reboots. */
    val odometer: Long,
    val uptimeMs: Long,
) {
    val fault: FaultCode? get() = FaultCode.fromCode(faultCode)

    companion object {
        /** Reads the fields in [mask]; a field missing at the end of the reply (older firmware) reads as zero. */
        fun parse(r: ByteReader, mask: Long): SetupValues {
            fun has(field: SetupField, size: Int) = mask and (1L shl field.ordinal) != 0L && r.remaining >= size
            fun f16(field: SetupField, scale: Float) = if (has(field, 2)) r.f16(scale) else 0f
            fun f32(field: SetupField, scale: Float) = if (has(field, 4)) r.f32(scale) else 0f
            fun u8(field: SetupField) = if (has(field, 1)) r.u8() else 0
            fun u32(field: SetupField) = if (has(field, 4)) r.u32() else 0L
            return SetupValues(
                tempMosfet = f16(SetupField.TEMP_MOSFET, 10f),
                tempMotor = f16(SetupField.TEMP_MOTOR, 10f),
                currentMotor = f32(SetupField.CURRENT_MOTOR, 100f),
                currentInput = f32(SetupField.CURRENT_INPUT, 100f),
                dutyCycle = f16(SetupField.DUTY, 1000f),
                erpm = f32(SetupField.ERPM, 1f),
                speed = f32(SetupField.SPEED, 1000f),
                voltageInput = f16(SetupField.VOLTAGE_INPUT, 10f),
                batteryLevel = f16(SetupField.BATTERY_LEVEL, 1000f),
                ampHours = f32(SetupField.AMP_HOURS, 1e4f),
                ampHoursCharged = f32(SetupField.AMP_HOURS_CHARGED, 1e4f),
                wattHours = f32(SetupField.WATT_HOURS, 1e4f),
                wattHoursCharged = f32(SetupField.WATT_HOURS_CHARGED, 1e4f),
                distance = f32(SetupField.DISTANCE, 1000f),
                distanceAbs = f32(SetupField.DISTANCE_ABS, 1000f),
                pidPosition = f32(SetupField.PID_POSITION, 1e6f),
                faultCode = u8(SetupField.FAULT),
                controllerId = u8(SetupField.CONTROLLER_ID),
                controllerCount = u8(SetupField.CONTROLLER_COUNT),
                batteryWattHours = f32(SetupField.BATTERY_WATT_HOURS, 1000f),
                odometer = u32(SetupField.ODOMETER),
                uptimeMs = u32(SetupField.UPTIME),
            )
        }
    }
}

suspend fun VescController.setupValues(): SetupValues =
    session.request(forwarded(byteArrayOf(COMM_GET_VALUES_SETUP.id.toByte())), COMM_GET_VALUES_SETUP) {
        SetupValues.parse(it, ALL_SETUP_FIELDS)
    }

/** COMM_GET_VALUES_SETUP_SELECTIVE: only [fields] travel, the rest read as zero. */
suspend fun VescController.setupValues(fields: Set<SetupField>): SetupValues {
    val mask = fields.fold(0L) { m, f -> m or (1L shl f.ordinal) }
    val request = ByteWriter().u8(COMM_GET_VALUES_SETUP_SELECTIVE.id).u32(mask).toByteArray()
    return session.request(forwarded(request), COMM_GET_VALUES_SETUP_SELECTIVE) { r -> SetupValues.parse(r, r.u32()) }
}

/** COMM_SET_ODOMETER: the controller stores [meters] as its odometer. There is no reply. */
suspend fun VescController.setOdometer(meters: Long) {
    require(meters in 0..0xFFFFFFFFL) { "odometer must fit 32 bits of meters, was $meters" }
    session.send(forwarded(ByteWriter().u8(COMM_SET_ODOMETER.id).u32(meters).toByteArray()))
}

private const val ALL_SETUP_FIELDS = 0xFFFFFFFFL
