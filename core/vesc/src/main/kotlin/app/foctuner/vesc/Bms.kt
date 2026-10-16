package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.CommPacketId
import app.foctuner.protocol.CommPacketId.COMM_BMS_FORCE_BALANCE
import app.foctuner.protocol.CommPacketId.COMM_BMS_GET_VALUES
import app.foctuner.protocol.CommPacketId.COMM_BMS_RESET_COUNTERS
import app.foctuner.protocol.CommPacketId.COMM_BMS_SET_BALANCE_OVERRIDE
import app.foctuner.protocol.CommPacketId.COMM_BMS_SET_CHARGE_ALLOWED
import app.foctuner.protocol.CommPacketId.COMM_BMS_ZERO_CURRENT_OFFSET
import app.foctuner.protocol.CommPacketId.COMM_GET_EXT_HUM_TMP

/**
 * COMM_BMS_GET_VALUES. A VESC BMS answers with its own values; a motor controller answers with what it last
 * heard from the BMS on CAN (all zero if none). Voltages in V, currents in A, temperatures in °C, [soc] and
 * [soh] 0..1, [pressure] in Pa. Fields after [tempCellsMax] are optional and keep their defaults when the
 * firmware doesn't send them.
 */
data class BmsValues(
    val voltageTotal: Float,
    val voltageCharge: Float,
    val current: Float,
    /** Current measured by the cell monitor IC. */
    val currentIc: Float,
    val ampHours: Float,
    val wattHours: Float,
    val cells: List<Float>,
    val balancing: List<Boolean>,
    /** A value of [NO_SENSOR] or below means the sensor is missing. */
    val temps: List<Float>,
    val tempIc: Float,
    val tempHumiditySensor: Float,
    val humidity: Float,
    val tempCellsMax: Float,
    val soc: Float = 0f,
    val soh: Float = 0f,
    val canId: Int? = null,
    val ampHoursChargedTotal: Float = 0f,
    val wattHoursChargedTotal: Float = 0f,
    val ampHoursDischargedTotal: Float = 0f,
    val wattHoursDischargedTotal: Float = 0f,
    val pressure: Float = 0f,
    /** 1: the first five temps are IC, cell min, cell max, MOSFET and ambient. */
    val dataVersion: Int = 0,
    val status: String = "",
) {
    val cellMin: Float? get() = cells.minOrNull()
    val cellMax: Float? get() = cells.maxOrNull()

    companion object {
        const val NO_SENSOR = -280f

        fun parse(r: ByteReader): BmsValues {
            val vTot = r.f32(1e6f)
            val vCharge = r.f32(1e6f)
            val current = r.f32(1e6f)
            val currentIc = r.f32(1e6f)
            val ah = r.f32(1e3f)
            val wh = r.f32(1e3f)
            val cellCount = r.u8()
            val cells = List(cellCount) { r.f16(1e3f) }
            val balancing = List(cellCount) { r.u8() != 0 }
            val sensorCount = r.u8()
            val temps = List(sensorCount) { r.f16(1e2f) }
            val tempIc = r.f16(1e2f)
            val tempHum = r.f16(1e2f)
            val humidity = r.f16(1e2f)
            val tempCellsMax = r.f16(1e2f)
            val soc = if (r.remaining >= 2) r.f16(1e3f) else 0f
            val soh = if (r.remaining >= 2) r.f16(1e3f) else 0f
            val canId = if (r.remaining >= 1) r.u8() else null
            val totals = if (r.remaining >= 16) FloatArray(4) { r.f32Auto() } else FloatArray(4)
            val pressure = if (r.remaining >= 2) r.f16(1e-1f) else 0f
            val dataVersion = if (r.remaining >= 1) r.u8() else 0
            val status = if (r.remaining >= 1) r.cString() else ""
            return BmsValues(
                vTot, vCharge, current, currentIc, ah, wh, cells, balancing, temps, tempIc, tempHum, humidity,
                tempCellsMax, soc, soh, canId, totals[0], totals[1], totals[2], totals[3], pressure, dataVersion, status,
            )
        }
    }
}

suspend fun VescController.bmsValues(): BmsValues =
    session.request(forwarded(Requests.bare(COMM_BMS_GET_VALUES)), COMM_BMS_GET_VALUES) { BmsValues.parse(it) }

/*
 * The BMS set commands get no reply. A motor controller passes them on over CAN only when its BMS type is VESC
 * and it heard from the BMS recently; otherwise they are dropped silently.
 */

/** Allows or blocks charging (the BMS opens its charge switch). */
suspend fun VescController.bmsSetChargeAllowed(allowed: Boolean) = bmsCommand(COMM_BMS_SET_CHARGE_ALLOWED, allowed.byte())

/** Turns balancing of cell [cell] (0-based) on or off regardless of the BMS's own balancing logic. */
suspend fun VescController.bmsSetBalanceOverride(cell: Int, on: Boolean) {
    require(cell in 0..255) { "cell must be within 0..255, was $cell" }
    bmsCommand(COMM_BMS_SET_BALANCE_OVERRIDE, cell.toByte(), on.byte())
}

suspend fun VescController.bmsResetCounters(ampHours: Boolean, wattHours: Boolean) =
    bmsCommand(COMM_BMS_RESET_COUNTERS, ampHours.byte(), wattHours.byte())

/** Forces balancing on, or hands it back to the BMS's own logic. */
suspend fun VescController.bmsForceBalance(on: Boolean) = bmsCommand(COMM_BMS_FORCE_BALANCE, on.byte())

/** Takes the present current reading as zero. Only meaningful with no current flowing. */
suspend fun VescController.bmsZeroCurrentOffset() = bmsCommand(COMM_BMS_ZERO_CURRENT_OFFSET)

/**
 * COMM_GET_EXT_HUM_TMP, answered by VESC BMS hardware only (reach it with [VescClient.can] or connect to it):
 * the external humidity sensor's relative humidity in % and temperature in °C. Motor controllers don't answer.
 */
suspend fun VescController.externalHumidityTemperature(): HumidityTemperature =
    session.request(forwarded(Requests.bare(COMM_GET_EXT_HUM_TMP)), COMM_GET_EXT_HUM_TMP) { r ->
        HumidityTemperature(humidity = r.f32(100f), temperature = r.f32(100f))
    }

data class HumidityTemperature(val humidity: Float, val temperature: Float)

private suspend fun VescController.bmsCommand(id: CommPacketId, vararg args: Byte) =
    session.send(forwarded(byteArrayOf(id.id.toByte(), *args)))

private fun Boolean.byte(): Byte = if (this) 1 else 0
