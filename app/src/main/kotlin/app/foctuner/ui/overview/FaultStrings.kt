package app.foctuner.ui.overview

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.foctuner.R
import app.foctuner.vesc.FaultCode

internal data class FaultStrings(val title: String, val detail: String)

/** Plain-language title and what to check; null when there is no fault. */
@Composable
internal fun faultStrings(code: Int): FaultStrings? {
    val fault = FaultCode.fromCode(code)
        ?: return FaultStrings(stringResource(R.string.fault_unknown_title, code), stringResource(R.string.fault_unknown_detail))
    val (title, detail) = when (fault) {
        FaultCode.NONE -> return null
        FaultCode.OVER_VOLTAGE -> R.string.fault_over_voltage_title to R.string.fault_over_voltage_detail
        FaultCode.UNDER_VOLTAGE -> R.string.fault_under_voltage_title to R.string.fault_under_voltage_detail
        FaultCode.DRV -> R.string.fault_drv_title to R.string.fault_drv_detail
        FaultCode.ABS_OVER_CURRENT -> R.string.fault_abs_over_current_title to R.string.fault_abs_over_current_detail
        FaultCode.OVER_TEMP_FET -> R.string.fault_over_temp_fet_title to R.string.fault_over_temp_fet_detail
        FaultCode.OVER_TEMP_MOTOR -> R.string.fault_over_temp_motor_title to R.string.fault_over_temp_motor_detail
        FaultCode.GATE_DRIVER_OVER_VOLTAGE -> R.string.fault_gate_driver_over_voltage_title to R.string.fault_gate_driver_over_voltage_detail
        FaultCode.GATE_DRIVER_UNDER_VOLTAGE -> R.string.fault_gate_driver_under_voltage_title to R.string.fault_gate_driver_under_voltage_detail
        FaultCode.MCU_UNDER_VOLTAGE -> R.string.fault_mcu_under_voltage_title to R.string.fault_mcu_under_voltage_detail
        FaultCode.BOOTING_FROM_WATCHDOG_RESET -> R.string.fault_watchdog_reset_title to R.string.fault_watchdog_reset_detail
        FaultCode.ENCODER_SPI -> R.string.fault_encoder_spi_title to R.string.fault_encoder_spi_detail
        FaultCode.ENCODER_SINCOS_BELOW_MIN_AMPLITUDE -> R.string.fault_encoder_sincos_low_title to R.string.fault_encoder_sincos_low_detail
        FaultCode.ENCODER_SINCOS_ABOVE_MAX_AMPLITUDE -> R.string.fault_encoder_sincos_high_title to R.string.fault_encoder_sincos_high_detail
        FaultCode.FLASH_CORRUPTION -> R.string.fault_flash_corruption_title to R.string.fault_flash_corruption_detail
        FaultCode.HIGH_OFFSET_CURRENT_SENSOR_1 -> return sensorOffset(1)
        FaultCode.HIGH_OFFSET_CURRENT_SENSOR_2 -> return sensorOffset(2)
        FaultCode.HIGH_OFFSET_CURRENT_SENSOR_3 -> return sensorOffset(3)
        FaultCode.UNBALANCED_CURRENTS -> R.string.fault_unbalanced_currents_title to R.string.fault_unbalanced_currents_detail
        FaultCode.BRK -> R.string.fault_brk_title to R.string.fault_brk_detail
        FaultCode.RESOLVER_LOT -> R.string.fault_resolver_lot_title to R.string.fault_resolver_lot_detail
        FaultCode.RESOLVER_DOS -> R.string.fault_resolver_dos_title to R.string.fault_resolver_dos_detail
        FaultCode.RESOLVER_LOS -> R.string.fault_resolver_los_title to R.string.fault_resolver_los_detail
        FaultCode.FLASH_CORRUPTION_APP_CFG -> R.string.fault_flash_app_cfg_title to R.string.fault_flash_app_cfg_detail
        FaultCode.FLASH_CORRUPTION_MC_CFG -> R.string.fault_flash_mc_cfg_title to R.string.fault_flash_mc_cfg_detail
        FaultCode.ENCODER_NO_MAGNET -> R.string.fault_encoder_no_magnet_title to R.string.fault_encoder_no_magnet_detail
        FaultCode.ENCODER_MAGNET_TOO_STRONG -> R.string.fault_encoder_magnet_strong_title to R.string.fault_encoder_magnet_strong_detail
        FaultCode.PHASE_FILTER -> R.string.fault_phase_filter_title to R.string.fault_phase_filter_detail
        FaultCode.ENCODER_FAULT -> R.string.fault_encoder_fault_title to R.string.fault_encoder_fault_detail
        FaultCode.LV_OUTPUT_FAULT -> R.string.fault_lv_output_title to R.string.fault_lv_output_detail
        FaultCode.ENCODER_SLIP -> R.string.fault_encoder_slip_title to R.string.fault_encoder_slip_detail
        FaultCode.OVERSPEED -> R.string.fault_overspeed_title to R.string.fault_overspeed_detail
        FaultCode.UNDERSPEED -> R.string.fault_underspeed_title to R.string.fault_underspeed_detail
        FaultCode.ABS_OVERSPEED -> R.string.fault_abs_overspeed_title to R.string.fault_abs_overspeed_detail
    }
    return FaultStrings(stringResource(title), stringResource(detail))
}

@Composable
private fun sensorOffset(sensor: Int) = FaultStrings(
    stringResource(R.string.fault_current_sensor_offset_title, sensor),
    stringResource(R.string.fault_current_sensor_offset_detail),
)
