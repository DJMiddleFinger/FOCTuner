package app.foctuner.config

import kotlin.math.abs
import kotlin.math.max

/**
 * The parameters [readBack] (what the controller reports after a write of this config) did not take over, compared
 * the way VESC Tool checks a motor config write (`ConfigParams::checkDifference`): integers and strings exactly,
 * doubles within a relative 1e-4 (1e-2 for 16-bit ones), so the firmware's float rounding doesn't count. The FOC
 * offsets are left out unless the read-back config says the firmware accepts written offsets, because otherwise it
 * keeps its own measured ones on purpose.
 */
fun Config.notAppliedIn(readBack: Config): List<String> {
    require(definition.signature == readBack.definition.signature) { "configs belong to different definitions" }
    val keepsOffsets = "foc_offsets_cal_mode" in readBack.definition.params &&
        readBack.int("foc_offsets_cal_mode") and OFFSETS_WRITABLE == 0L
    return definition.params.values.filter { p ->
        !(keepsOffsets && p.name in OFFSET_PARAMS) && !same(p, this[p.name], readBack[p.name])
    }.map { it.name }
}

private fun same(p: ParamDefinition, a: ParamValue, b: ParamValue): Boolean {
    if (a !is ParamValue.DoubleValue || b !is ParamValue.DoubleValue) return a == b
    val eps = if (p.wire == WireType.DOUBLE16) 0.01f else 0.0001f
    val x = a.value.toFloat()
    val y = b.value.toFloat()
    return abs(x - y) <= eps * max(1f, max(abs(x), abs(y)))
}

private const val OFFSETS_WRITABLE = 1L shl 1

private val OFFSET_PARAMS = listOf("foc_offsets_current", "foc_offsets_voltage", "foc_offsets_voltage_undriven")
    .flatMap { prefix -> (0..2).map { "${prefix}__$it" } }
    .toSet()

/** The motor config checks VESC Tool runs when a config is written (6.05, "Potential Configuration Issues"). */
enum class ConfigWarning {
    /** Motor or brake current is more than 1/1.3 of the absolute maximum, so current peaks trip overcurrent faults. */
    ABS_CURRENT_CLOSE,

    /** The slow absolute current limit greatly increases the chance of damaging the controller. */
    SLOW_ABS_CURRENT,

    /** Overmodulation above 1.15 adds a lot of distortion for little gain. */
    OVERMODULATION,
}

/** Empty for app configs and for definitions without the checked parameters. */
fun Config.writeWarnings(): List<ConfigWarning> {
    val has = definition.params::containsKey
    val warnings = mutableListOf<ConfigWarning>()
    if (has("l_current_max") && has("l_current_min") && has("l_abs_current_max")) {
        val absMax = double("l_abs_current_max")
        if (double("l_current_max") * 1.3 > absMax || abs(double("l_current_min")) * 1.3 > absMax) {
            warnings += ConfigWarning.ABS_CURRENT_CLOSE
        }
    }
    if (has("l_slow_abs_current") && bool("l_slow_abs_current")) warnings += ConfigWarning.SLOW_ABS_CURRENT
    if (has("foc_overmod_factor") && double("foc_overmod_factor") >= 1.16) warnings += ConfigWarning.OVERMODULATION
    return warnings
}
