package app.foctuner.vesc

import app.foctuner.config.ConfigKind
import app.foctuner.config.GroupEntry
import app.foctuner.config.ParamValue

/** The tabs of VESC Tool mobile's Multi Settings, with its parameters and separators in its order. */
enum class MultiSettingGroup(val entries: List<GroupEntry>) {
    General(params("foc_sensor_mode", "l_duty_start", "m_motor_temp_sens_type", "m_ntc_motor_beta")),
    Limits(
        listOf(GroupEntry.Separator("Current")) + params("l_current_max", "l_current_min", "l_in_current_max", "l_in_current_min", "l_abs_current_max") +
            GroupEntry.Separator("ERPM") + params("l_min_erpm", "l_max_erpm") +
            GroupEntry.Separator("Voltage") + params("l_battery_cut_start", "l_battery_cut_end") +
            GroupEntry.Separator("Temperature") + params("l_temp_fet_start", "l_temp_fet_end", "l_temp_motor_start", "l_temp_motor_end", "l_temp_accel_dec") +
            GroupEntry.Separator("Wattage") + params("l_watt_max", "l_watt_min"),
    ),
    Foc(params("foc_f_zv", "foc_openloop_rpm", "foc_motor_r", "foc_motor_l", "foc_motor_flux_linkage", "foc_current_kp", "foc_current_ki")),
    Bms(params("bms.t_limit_start", "bms.t_limit_end", "bms.soc_limit_start", "bms.soc_limit_end")),
    ;

    val params: List<String> get() = entries.filterIsInstance<GroupEntry.Param>().map { it.name }
}

private fun params(vararg names: String) = names.map { GroupEntry.Param(it) }

/** One controller's outcome of [writeMotorParams]: [skipped] are parameters its firmware doesn't have. */
data class MultiSettingResult(val write: ConfigWriteResult, val skipped: List<String>)

/**
 * Reads this controller's motor config, sets [values] in it and writes it back, checked by reading it again, as VESC
 * Tool's `setMcParamsFromCurrentConfigAllCan` does for each controller.
 */
suspend fun VescController.writeMotorParams(firmware: FirmwareInfo, values: Map<String, ParamValue>): MultiSettingResult {
    val current = readMatchingConfig(ConfigKind.MCCONF, firmware.version)
    val (known, skipped) = values.entries.partition { (name, value) -> current.definition.params[name]?.accepts(value) == true }
    val edited = known.fold(current) { c, (name, value) -> c.with(name, value) }
    return MultiSettingResult(writeConfigChecked(ConfigKind.MCCONF, edited), skipped.map { it.key })
}
