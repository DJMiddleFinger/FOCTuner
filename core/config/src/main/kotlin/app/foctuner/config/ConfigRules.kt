package app.foctuner.config

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * What the controller's hardware allows, where known: the `HW_LIM_*` limits the firmware clamps a written motor
 * config to (`commands_apply_mcconf_hw_limits` in bldc `comm/commands.c`). Null fields are unknown and not checked.
 */
data class HardwareLimits(
    val currentMax: Double? = null,
    val absCurrentMax: Double? = null,
    val inputVoltageMax: Double? = null,
    val erpmMax: Double? = null,
    val dutyMax: Double? = null,
)

/**
 * Motor config values that are dangerous or pointless together, checked before a write. Each lists the settings it
 * reads and the condition; a rule is skipped for definitions that lack one of them. [rows] are the settings whose
 * rows show it: all of [params] unless [marks] narrows them to the ones worth changing.
 */
enum class ConfigWarning(
    val params: List<String>,
    private val broken: Config.(HardwareLimits?) -> Boolean,
    marks: List<String>? = null,
) {
    /**
     * Motor or brake current is more than 1/1.3 of the absolute maximum, so current peaks trip overcurrent faults.
     * VESC Tool 6.05 `VescInterface::updateMcconfFromCurrent` ("Potential Configuration Issues").
     */
    ABS_CURRENT_CLOSE(listOf("l_current_max", "l_current_min", "l_abs_current_max"), {
        val absMax = number("l_abs_current_max")
        number("l_current_max") * 1.3 > absMax || abs(number("l_current_min")) * 1.3 > absMax
    }),

    /** The slow absolute current limit greatly increases the chance of damaging the controller. VESC Tool 6.05, same check. */
    SLOW_ABS_CURRENT(listOf("l_slow_abs_current"), { bool("l_slow_abs_current") }),

    /** Overmodulation above 1.15 adds a lot of distortion for little gain. VESC Tool 6.05, same check. */
    OVERMODULATION(listOf("foc_overmod_factor"), { number("foc_overmod_factor") >= 1.16 }),

    /**
     * Cutoff start at or below cutoff end: bldc `mc_interface.c` (`update_override_limits`) maps the input voltage from
     * start (full current) to end (none), so the reduction never happens and the battery is drained to the
     * undervoltage fault.
     */
    BATTERY_CUTOFF_ORDER(listOf("l_battery_cut_start", "l_battery_cut_end"), {
        number("l_battery_cut_start") <= number("l_battery_cut_end")
    }),

    /**
     * Cutoff end below the empty voltage of the configured cells (`si_battery_type`'s per-cell range, 3.0–4.2 V for
     * Li-ion, 2.6–3.6 V for LiFePO4, as the firmware's type names say), so the cells are discharged too far.
     */
    BATTERY_CUTOFF_BELOW_EMPTY(listOf("l_battery_cut_end", "si_battery_cells", "si_battery_type"), {
        batteryVoltage()?.let { number("l_battery_cut_end") < it.start } ?: false
    }, listOf("l_battery_cut_end", "si_battery_cells")),

    /** Cutoff start above the full voltage of the configured cells, so current is reduced even on a full battery. */
    BATTERY_CUTOFF_ABOVE_FULL(listOf("l_battery_cut_start", "si_battery_cells", "si_battery_type"), {
        batteryVoltage()?.let { number("l_battery_cut_start") > it.endInclusive } ?: false
    }, listOf("l_battery_cut_start", "si_battery_cells")),

    /**
     * The configured cells, fully charged, are above Maximum Input Voltage: the firmware stops the motor with an
     * overvoltage fault (`FAULT_CODE_OVER_VOLTAGE`, bldc `mc_interface.c`) on a full battery or while braking.
     */
    BATTERY_ABOVE_MAX_VIN(listOf("l_max_vin", "si_battery_cells", "si_battery_type"), {
        batteryVoltage()?.let { it.endInclusive > number("l_max_vin") } ?: false
    }, listOf("l_max_vin", "si_battery_cells")),

    /**
     * Cutoff end below Minimum Input Voltage: the undervoltage fault (`FAULT_CODE_UNDER_VOLTAGE`) releases the motor
     * at once before the cutoff has brought the current down, instead of a gradual loss of power.
     */
    BATTERY_CUTOFF_BELOW_MIN_VIN(listOf("l_battery_cut_end", "l_min_vin"), {
        number("l_battery_cut_end") < number("l_min_vin")
    }),

    /** MOSFET temperature cutback start at or above its end: `mc_interface.c` maps between them, so there is no gradual cutback. */
    FET_TEMP_ORDER(listOf("l_temp_fet_start", "l_temp_fet_end"), { number("l_temp_fet_start") >= number("l_temp_fet_end") }),

    /** Motor temperature cutback start at or above its end, as [FET_TEMP_ORDER]. */
    MOTOR_TEMP_ORDER(listOf("l_temp_motor_start", "l_temp_motor_end"), { number("l_temp_motor_start") >= number("l_temp_motor_end") }),

    /** Maximum duty cycle at or below the minimum the firmware runs the motor at (`l_min_duty`), so it can't turn. */
    DUTY_ORDER(listOf("l_max_duty", "l_min_duty"), { number("l_max_duty") <= number("l_min_duty") }),

    /** Maximum ERPM above [reachableErpm], so the limit never acts and top speed is set by the voltage. */
    ERPM_UNREACHABLE(ERPM_INPUTS, { reachableErpm()?.let { number("l_max_erpm") > it } ?: false }, listOf("l_max_erpm")),

    /** Current limits above what the hardware allows; the firmware lowers them on write (`HW_LIM_CURRENT`, `HW_LIM_CURRENT_ABS`). */
    CURRENT_ABOVE_HARDWARE(listOf("l_current_max", "l_current_min", "l_abs_current_max"), { limits ->
        val max = limits?.currentMax
        val absMax = limits?.absCurrentMax
        (max != null && (number("l_current_max") > max || abs(number("l_current_min")) > max)) ||
            (absMax != null && number("l_abs_current_max") > absMax)
    }),

    /** Maximum input voltage above the hardware's (`HW_LIM_VIN`), so a full battery can damage the controller. */
    VOLTAGE_ABOVE_HARDWARE(listOf("l_max_vin"), { limits ->
        limits?.inputVoltageMax?.let { number("l_max_vin") > it } ?: false
    }),

    /** ERPM limits beyond the hardware's (`HW_LIM_ERPM`); the firmware lowers them on write. */
    ERPM_ABOVE_HARDWARE(listOf("l_max_erpm", "l_min_erpm"), { limits ->
        limits?.erpmMax?.let { number("l_max_erpm") > it || -number("l_min_erpm") > it } ?: false
    }),

    /** Maximum duty above the hardware's (`HW_LIM_DUTY_MAX`). */
    DUTY_ABOVE_HARDWARE(listOf("l_max_duty"), { limits ->
        limits?.dutyMax?.let { number("l_max_duty") > it } ?: false
    }),
    ;

    val rows: List<String> = marks ?: params

    internal fun appliesTo(config: Config, limits: HardwareLimits?): Boolean =
        params.all(config.definition.params::containsKey) && config.broken(limits)
}

/** The [ConfigWarning]s this config raises, in declaration order; empty for app configs. */
fun Config.writeWarnings(limits: HardwareLimits? = null): List<ConfigWarning> =
    ConfigWarning.entries.filter { it.appliesTo(this, limits) }

/** The warnings that involve each setting, for marking rows. */
fun List<ConfigWarning>.byParam(): Map<String, List<ConfigWarning>> =
    flatMap { warning -> warning.rows.map { it to warning } }.groupBy({ it.first }, { it.second })

/**
 * The battery's voltage range from empty to full, from the cell count and the per-cell range in `si_battery_type`'s
 * name (`BATTERY_TYPE_LIION_3_0__4_2` is 3.0–4.2 V); null without cells or for a type without one (lead acid).
 */
fun Config.batteryVoltage(): ClosedFloatingPointRange<Double>? {
    val cells = int("si_battery_cells")
    if (cells <= 0) return null
    val name = definition.param("si_battery_type").enumNames.getOrNull(int("si_battery_type").toInt()) ?: return null
    val (a, b, c, d) = CELL_RANGE.find(name)?.destructured ?: return null
    return "$a.$b".toDouble() * cells.."$c.$d".toDouble() * cells
}

/**
 * The ERPM the motor reaches at maximum duty on a full battery without load, or null when it can't be told (not FOC,
 * no flux linkage, no cell range). FOC's largest voltage vector is `V_bus × duty / √3` (bldc `foc_math.c` saturates
 * the modulation to √3/2 × max duty × overmodulation, of 2/3 V_bus); the no-load speed is that over the flux linkage.
 */
fun Config.reachableErpm(): Double? {
    if (!ERPM_INPUTS.all(definition.params::containsKey) || !isFoc()) return null
    val lambda = number("foc_motor_flux_linkage")
    val full = batteryVoltage()?.endInclusive ?: return null
    if (lambda <= 0) return null
    val overmod = if ("foc_overmod_factor" in definition.params) number("foc_overmod_factor") else 1.0
    return full * number("l_max_duty") * overmod / sqrt(3.0) / lambda * 60 / (2 * PI)
}

private val ERPM_INPUTS = listOf("l_max_erpm", "l_max_duty", "foc_motor_flux_linkage", "motor_type", "si_battery_cells", "si_battery_type")

private fun Config.isFoc(): Boolean =
    definition.param("motor_type").enumNames.getOrNull(int("motor_type").toInt())?.endsWith("FOC") == true

private val CELL_RANGE = Regex("""_(\d+)_(\d+)__(\d+)_(\d+)$""")

/** Temperatures are integers in some firmware series and doubles in others. */
private fun Config.number(name: String): Double = when (val v = get(name)) {
    is ParamValue.DoubleValue -> v.value
    is ParamValue.IntValue -> v.value.toDouble()
    is ParamValue.TextValue -> Double.NaN
}
