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

/**
 * This config with the values of [saved] (a backup or file read back from VESC Tool's XML) wherever they differ by more
 * than [notAppliedIn] tolerates, so the XML's six significant digits don't count as changes and the values not in
 * question keep their full precision.
 */
fun Config.restoredFrom(saved: Config): Config = notAppliedIn(saved).fold(this) { config, name -> config.with(name, saved[name]) }

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
