package app.foctuner.ui.settings.help

import androidx.annotation.StringRes
import app.foctuner.R

/**
 * Plain-language help and typical values for the settings people change most, in this app's own words. VESC Tool's
 * description still follows in the dialog; this comes first because it says what the setting does to the vehicle.
 */
object SettingHelp {
    class Entry(@param:StringRes val summary: Int, @param:StringRes val typical: Int)

    private val entries: Map<String, Entry> = mapOf(
        "motor_type" to (R.string.help_motor_type to R.string.help_motor_type_typical),
        "l_current_max" to (R.string.help_l_current_max to R.string.help_l_current_max_typical),
        "l_current_min" to (R.string.help_l_current_min to R.string.help_l_current_min_typical),
        "l_in_current_max" to (R.string.help_l_in_current_max to R.string.help_l_in_current_max_typical),
        "l_in_current_min" to (R.string.help_l_in_current_min to R.string.help_l_in_current_min_typical),
        "l_abs_current_max" to (R.string.help_l_abs_current_max to R.string.help_l_abs_current_max_typical),
        "l_battery_cut_start" to (R.string.help_l_battery_cut_start to R.string.help_l_battery_cut_start_typical),
        "l_battery_cut_end" to (R.string.help_l_battery_cut_end to R.string.help_l_battery_cut_end_typical),
        "l_min_vin" to (R.string.help_l_min_vin to R.string.help_l_min_vin_typical),
        "l_max_vin" to (R.string.help_l_max_vin to R.string.help_l_max_vin_typical),
        "l_max_erpm" to (R.string.help_l_max_erpm to R.string.help_l_max_erpm_typical),
        "l_min_erpm" to (R.string.help_l_min_erpm to R.string.help_l_min_erpm_typical),
        "l_temp_fet_start" to (R.string.help_l_temp_fet_start to R.string.help_l_temp_fet_start_typical),
        "l_temp_fet_end" to (R.string.help_l_temp_fet_end to R.string.help_l_temp_fet_end_typical),
        "l_temp_motor_start" to (R.string.help_l_temp_motor_start to R.string.help_l_temp_motor_start_typical),
        "l_temp_motor_end" to (R.string.help_l_temp_motor_end to R.string.help_l_temp_motor_end_typical),
        "l_max_duty" to (R.string.help_l_max_duty to R.string.help_l_max_duty_typical),
        "l_watt_max" to (R.string.help_l_watt_max to R.string.help_l_watt_max_typical),
        "l_watt_min" to (R.string.help_l_watt_min to R.string.help_l_watt_min_typical),
        "foc_motor_r" to (R.string.help_foc_motor_r to R.string.help_foc_motor_r_typical),
        "foc_motor_l" to (R.string.help_foc_motor_l to R.string.help_foc_motor_l_typical),
        "foc_motor_flux_linkage" to (R.string.help_foc_motor_flux_linkage to R.string.help_foc_motor_flux_linkage_typical),
        "foc_current_kp" to (R.string.help_foc_current_kp to R.string.help_foc_current_kp_typical),
        "foc_current_ki" to (R.string.help_foc_current_ki to R.string.help_foc_current_ki_typical),
        "foc_sensor_mode" to (R.string.help_foc_sensor_mode to R.string.help_foc_sensor_mode_typical),
        "foc_f_zv" to (R.string.help_foc_f_zv to R.string.help_foc_f_zv_typical),
        "foc_observer_gain" to (R.string.help_foc_observer_gain to R.string.help_foc_observer_gain_typical),
        "foc_openloop_rpm" to (R.string.help_foc_openloop_rpm to R.string.help_foc_openloop_rpm_typical),
        "foc_sl_erpm" to (R.string.help_foc_sl_erpm to R.string.help_foc_sl_erpm_typical),
        "m_invert_direction" to (R.string.help_m_invert_direction to R.string.help_m_invert_direction_typical),
        "si_battery_cells" to (R.string.help_si_battery_cells to R.string.help_si_battery_cells_typical),
        "si_battery_ah" to (R.string.help_si_battery_ah to R.string.help_si_battery_ah_typical),
        "si_wheel_diameter" to (R.string.help_si_wheel_diameter to R.string.help_si_wheel_diameter_typical),
        "si_motor_poles" to (R.string.help_si_motor_poles to R.string.help_si_motor_poles_typical),
        "si_gear_ratio" to (R.string.help_si_gear_ratio to R.string.help_si_gear_ratio_typical),
        "controller_id" to (R.string.help_controller_id to R.string.help_controller_id_typical),
        "app_to_use" to (R.string.help_app_to_use to R.string.help_app_to_use_typical),
        "timeout_msec" to (R.string.help_timeout_msec to R.string.help_timeout_msec_typical),
        "timeout_brake_current" to (R.string.help_timeout_brake_current to R.string.help_timeout_brake_current_typical),
        "can_baud_rate" to (R.string.help_can_baud_rate to R.string.help_can_baud_rate_typical),
        "app_ppm_conf.ctrl_type" to (R.string.help_app_ppm_conf_ctrl_type to R.string.help_app_ppm_conf_ctrl_type_typical),
        "app_ppm_conf.hyst" to (R.string.help_app_ppm_conf_hyst to R.string.help_app_ppm_conf_hyst_typical),
        "app_ppm_conf.ramp_time_pos" to (R.string.help_app_ppm_conf_ramp_time_pos to R.string.help_app_ppm_conf_ramp_time_pos_typical),
        "app_ppm_conf.ramp_time_neg" to (R.string.help_app_ppm_conf_ramp_time_neg to R.string.help_app_ppm_conf_ramp_time_neg_typical),
        "app_adc_conf.ctrl_type" to (R.string.help_app_adc_conf_ctrl_type to R.string.help_app_adc_conf_ctrl_type_typical),
        "app_adc_conf.voltage_start" to (R.string.help_app_adc_conf_voltage_start to R.string.help_app_adc_conf_voltage_start_typical),
        "app_adc_conf.voltage_end" to (R.string.help_app_adc_conf_voltage_end to R.string.help_app_adc_conf_voltage_end_typical),
    ).mapValues { (_, ids) -> Entry(ids.first, ids.second) }

    /** Help for setting [name], or null if it has none. */
    fun forParam(name: String): Entry? = entries[name]

    val names: Set<String> get() = entries.keys
}
