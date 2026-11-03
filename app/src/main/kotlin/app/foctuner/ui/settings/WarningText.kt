package app.foctuner.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.foctuner.R
import app.foctuner.config.Config
import app.foctuner.config.ConfigWarning
import app.foctuner.config.batteryVoltage
import app.foctuner.config.reachableErpm
import app.foctuner.ui.components.fixed
import app.foctuner.ui.components.grouped
import kotlin.math.roundToLong

/** What [warning] means for [config], in plain language with its numbers. */
@Composable
internal fun warningText(warning: ConfigWarning, config: Config): String {
    val battery = config.batteryVoltage()
    val cells = if ("si_battery_cells" in config.definition.params) config.int("si_battery_cells").toInt() else 0
    return when (warning) {
        ConfigWarning.ABS_CURRENT_CLOSE -> stringResource(R.string.safety_abs_current_close)
        ConfigWarning.SLOW_ABS_CURRENT -> stringResource(R.string.safety_slow_abs_current)
        ConfigWarning.OVERMODULATION -> stringResource(R.string.safety_overmodulation)
        ConfigWarning.BATTERY_CUTOFF_ORDER -> stringResource(R.string.safety_battery_cutoff_order)
        ConfigWarning.BATTERY_CUTOFF_BELOW_EMPTY ->
            stringResource(R.string.safety_battery_cutoff_below_empty, (battery?.start ?: 0.0).fixed(1), cells)
        ConfigWarning.BATTERY_CUTOFF_ABOVE_FULL ->
            stringResource(R.string.safety_battery_cutoff_above_full, (battery?.endInclusive ?: 0.0).fixed(1), cells)
        ConfigWarning.BATTERY_ABOVE_MAX_VIN ->
            stringResource(R.string.safety_battery_above_max_vin, cells, (battery?.endInclusive ?: 0.0).fixed(1))
        ConfigWarning.BATTERY_CUTOFF_BELOW_MIN_VIN -> stringResource(R.string.safety_battery_cutoff_below_min_vin)
        ConfigWarning.FET_TEMP_ORDER -> stringResource(R.string.safety_fet_temp_order)
        ConfigWarning.MOTOR_TEMP_ORDER -> stringResource(R.string.safety_motor_temp_order)
        ConfigWarning.DUTY_ORDER -> stringResource(R.string.safety_duty_order)
        ConfigWarning.ERPM_UNREACHABLE ->
            stringResource(R.string.safety_erpm_unreachable, (((config.reachableErpm() ?: 0.0) / 100).roundToLong() * 100f).grouped())
        ConfigWarning.CURRENT_ABOVE_HARDWARE -> stringResource(R.string.safety_current_above_hardware)
        ConfigWarning.VOLTAGE_ABOVE_HARDWARE -> stringResource(R.string.safety_voltage_above_hardware)
        ConfigWarning.ERPM_ABOVE_HARDWARE -> stringResource(R.string.safety_erpm_above_hardware)
        ConfigWarning.DUTY_ABOVE_HARDWARE -> stringResource(R.string.safety_duty_above_hardware)
    }
}
