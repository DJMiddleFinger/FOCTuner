package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_GET_VALUES_SELECTIVE

/**
 * The fields of COMM_GET_VALUES in wire order; the ordinal is the bit in the selective mask. [MOSFET_TEMPS]
 * carries all three per-MOSFET temperatures.
 */
enum class RealtimeField {
    TEMP_MOSFET, TEMP_MOTOR, CURRENT_MOTOR, CURRENT_INPUT, CURRENT_D, CURRENT_Q, DUTY, ERPM, VOLTAGE_INPUT,
    AMP_HOURS, AMP_HOURS_CHARGED, WATT_HOURS, WATT_HOURS_CHARGED, TACHOMETER, TACHOMETER_ABS, FAULT,
    PID_POSITION, CONTROLLER_ID, MOSFET_TEMPS, VOLTAGE_D, VOLTAGE_Q, STATUS,
}

/**
 * COMM_GET_VALUES_SELECTIVE: only [fields] travel, which keeps fast polling cheap over BLE. Fields left out
 * read as zero (false for the status flags).
 */
suspend fun VescController.values(fields: Set<RealtimeField>): RealtimeValues {
    val mask = fields.fold(0L) { m, f -> m or (1L shl f.ordinal) }
    val request = ByteWriter().u8(COMM_GET_VALUES_SELECTIVE.id).u32(mask).toByteArray()
    return session.request(forwarded(request), COMM_GET_VALUES_SELECTIVE) { r -> parseSelective(r, r.u32()) }
}

private fun parseSelective(r: ByteReader, mask: Long): RealtimeValues {
    fun has(field: RealtimeField) = mask and (1L shl field.ordinal) != 0L
    fun f16(field: RealtimeField, scale: Float) = if (has(field)) r.f16(scale) else 0f
    fun f32(field: RealtimeField, scale: Float) = if (has(field)) r.f32(scale) else 0f
    fun i32(field: RealtimeField) = if (has(field)) r.i32() else 0
    fun u8(field: RealtimeField) = if (has(field)) r.u8() else 0
    val tempMosfet = f16(RealtimeField.TEMP_MOSFET, 10f)
    val tempMotor = f16(RealtimeField.TEMP_MOTOR, 10f)
    val currentMotor = f32(RealtimeField.CURRENT_MOTOR, 100f)
    val currentInput = f32(RealtimeField.CURRENT_INPUT, 100f)
    val currentD = f32(RealtimeField.CURRENT_D, 100f)
    val currentQ = f32(RealtimeField.CURRENT_Q, 100f)
    val duty = f16(RealtimeField.DUTY, 1000f)
    val erpm = f32(RealtimeField.ERPM, 1f)
    val voltage = f16(RealtimeField.VOLTAGE_INPUT, 10f)
    val ah = f32(RealtimeField.AMP_HOURS, 1e4f)
    val ahCharged = f32(RealtimeField.AMP_HOURS_CHARGED, 1e4f)
    val wh = f32(RealtimeField.WATT_HOURS, 1e4f)
    val whCharged = f32(RealtimeField.WATT_HOURS_CHARGED, 1e4f)
    val tacho = i32(RealtimeField.TACHOMETER)
    val tachoAbs = i32(RealtimeField.TACHOMETER_ABS)
    val fault = u8(RealtimeField.FAULT)
    val pidPos = f32(RealtimeField.PID_POSITION, 1e6f)
    val controllerId = u8(RealtimeField.CONTROLLER_ID)
    val t1 = f16(RealtimeField.MOSFET_TEMPS, 10f)
    val t2 = f16(RealtimeField.MOSFET_TEMPS, 10f)
    val t3 = f16(RealtimeField.MOSFET_TEMPS, 10f)
    val vd = f32(RealtimeField.VOLTAGE_D, 1000f)
    val vq = f32(RealtimeField.VOLTAGE_Q, 1000f)
    val status = u8(RealtimeField.STATUS)
    return RealtimeValues(
        tempMosfet, tempMotor, currentMotor, currentInput, currentD, currentQ, duty, erpm, voltage,
        ah, ahCharged, wh, whCharged, tacho, tachoAbs, fault, pidPos, controllerId, t1, t2, t3, vd, vq,
        timedOut = status and 1 != 0,
        killSwitchActive = status and 2 != 0,
    )
}
