package app.foctuner.sim

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_BMS_GET_VALUES
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * A 12S VESC BMS at CAN id [CAN_ID] on the same pack as the motors, as the controller relays it for
 * COMM_BMS_GET_VALUES. Cells follow the pack's charge with a fixed spread and sag under load; cells above the
 * lowest one balance when forced to or when they pass 4.1 V; counters integrate the pack current between
 * requests. Takes the BMS set commands the controller would forward.
 */
internal class BmsSim(private val battery: Battery, private val motors: List<MotorModel>) {
    var chargeAllowed = true
    var forceBalance = false
    private val overrides = HashMap<Int, Boolean>()
    private var currentOffset = INITIAL_OFFSET
    private var ampHours = 0.0
    private var wattHours = 0.0
    private var ampHoursTotal = 152.4
    private var wattHoursTotal = 6_510.0
    private var last = Duration.ZERO

    fun override(cell: Int, on: Boolean) {
        overrides[cell] = on
    }

    fun resetCounters(ah: Boolean, wh: Boolean) {
        if (ah) ampHours = 0.0
        if (wh) wattHours = 0.0
    }

    fun zeroCurrentOffset() {
        currentOffset = 0f
    }

    fun values(now: Duration, packVoltage: Float): ByteArray {
        val current = motors.sumOf { it.inputCurrent.toDouble() }.toFloat()
        val hours = (now - last).toDouble(DurationUnit.HOURS)
        last = now
        ampHours += current * hours
        wattHours += current * packVoltage * hours
        ampHoursTotal += current * hours
        wattHoursTotal += current * packVoltage * hours

        val cellBase = packVoltage / CELLS
        val cells = FloatArray(CELLS) { cellBase + SPREAD_MV[it] / 1000f }
        val lowest = cells.min()
        val balancing = BooleanArray(CELLS) { i ->
            overrides[i] ?: (cells[i] > lowest + BALANCE_THRESHOLD && (forceBalance || cells[i] > AUTO_BALANCE_VOLTAGE))
        }
        val heat = current * current * HEATING
        val temps = floatArrayOf(24.5f + heat, 25.1f + heat, 24.8f + heat * 1.2f, 23.9f + heat)

        val w = ByteWriter().u8(COMM_BMS_GET_VALUES.id)
            .f32(packVoltage, 1e6f).f32(0f, 1e6f)
            .f32(current + currentOffset, 1e6f).f32(current + currentOffset * 2, 1e6f)
            .f32(ampHours.toFloat(), 1e3f).f32(wattHours.toFloat(), 1e3f)
            .u8(CELLS)
        cells.forEach { w.f16(it, 1e3f) }
        balancing.forEach { w.u8(if (it) 1 else 0) }
        w.u8(temps.size)
        temps.forEach { w.f16(it, 1e2f) }
        return w.f16(31.2f, 1e2f).f16(27.4f, 1e2f).f16(41.5f, 1e2f).f16(temps.max(), 1e2f)
            .f16(battery.charge, 1e3f).f16(SOH, 1e3f)
            .u8(CAN_ID)
            .f32Auto(CHARGED_AH_TOTAL).f32Auto(CHARGED_WH_TOTAL).f32Auto(ampHoursTotal.toFloat()).f32Auto(wattHoursTotal.toFloat())
            .f16(PRESSURE_PA, 1e-1f)
            .u8(0)
            .cString(if (chargeAllowed) "" else "Charging disabled")
            .toByteArray()
    }

    private companion object {
        const val CELLS = 12
        const val CAN_ID = 10
        const val INITIAL_OFFSET = 0.04f
        const val BALANCE_THRESHOLD = 0.004f
        const val AUTO_BALANCE_VOLTAGE = 4.1f
        const val HEATING = 0.002f
        const val SOH = 0.97f
        const val CHARGED_AH_TOTAL = 160.2f
        const val CHARGED_WH_TOTAL = 6_840f
        const val PRESSURE_PA = 101_300f
        val SPREAD_MV = intArrayOf(3, -2, 5, 0, -4, 1, 2, -1, 4, -3, 0, 2)
    }
}
