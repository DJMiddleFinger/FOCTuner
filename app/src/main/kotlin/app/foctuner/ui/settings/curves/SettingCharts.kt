package app.foctuner.ui.settings.curves

import androidx.annotation.StringRes
import app.foctuner.R
import app.foctuner.config.Config
import app.foctuner.config.ConfigDefinition
import app.foctuner.config.ParamSubgroup
import app.foctuner.config.ParamValue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Which way a handle moves. */
internal enum class HandleAxis { X, Y }

/**
 * A point on a chart that is dragged to set [param]. [at] is where it sits, in plot units; [value] is the setting's
 * stored value that puts it at a position along [axis], or null when no value can. [active] hides it while the
 * settings make it meaningless (the regen cutoff while it's off, the curve shape of a linear throttle).
 */
internal class ChartHandle(
    val param: String,
    val axis: HandleAxis,
    val at: (Config) -> Pair<Double, Double>,
    val active: (Config) -> Boolean = { true },
    val value: (Config, Double) -> Double?,
)

/**
 * A chart of what a few related settings do, shown on the settings page that holds [anchor] and in the edit
 * dialog of each of [params], where it previews the value being typed. On the page, [handles] set its settings.
 */
internal class SettingChart(
    val key: String,
    @param:StringRes val title: Int,
    @param:StringRes val caption: Int,
    /** The setting the chart sits right above on its page. */
    val anchor: String,
    /** Every setting the chart reads. */
    val params: List<String>,
    /** Above the first row of the page instead of above [anchor]. */
    val atTop: Boolean = false,
    val handles: List<ChartHandle> = emptyList(),
    /** Null for the current limits, which [LimitBars] draws as bars. */
    val plot: ((Config) -> Plot)?,
) {
    fun availableIn(definition: ConfigDefinition) = params.all { it in definition.params }
}

internal object SettingCharts {
    private val all: List<SettingChart> = buildList {
        add(
            SettingChart(
                "limits", R.string.curve_limits_title, R.string.curve_limits_caption, "l_current_max",
                listOf(
                    "l_current_max", "l_current_min", "l_in_current_max", "l_in_current_min", "l_abs_current_max",
                    "l_current_max_scale", "l_current_min_scale", "l_in_current_max_scale", "l_in_current_min_scale",
                ),
                atTop = true,
                plot = null,
            ),
        )
        add(
            SettingChart(
                "battery", R.string.curve_battery_title, R.string.curve_battery_caption, "l_battery_cut_start",
                listOf(
                    "l_battery_cut_start", "l_battery_cut_end", "l_battery_regen_cut_start", "l_battery_regen_cut_end",
                    "l_in_current_max", "l_in_current_min", "l_min_vin", "l_max_vin",
                ),
                handles = listOf(
                    ChartHandle("l_battery_cut_start", HandleAxis.X, { it.number("l_battery_cut_start") to it.number("l_in_current_max") }) { _, x -> x },
                    ChartHandle("l_battery_cut_end", HandleAxis.X, { it.number("l_battery_cut_end") to 0.0 }) { _, x -> x },
                    ChartHandle("l_battery_regen_cut_start", HandleAxis.X, { it.number("l_battery_regen_cut_start") to it.number("l_in_current_min") }, ::regenShown) { _, x -> x },
                    ChartHandle("l_battery_regen_cut_end", HandleAxis.X, { it.number("l_battery_regen_cut_end") to 0.0 }, ::regenShown) { _, x -> x },
                ),
                plot = ::batteryPlot,
            ),
        )
        add(
            SettingChart(
                "cells", R.string.curve_cells_title, R.string.curve_cells_caption, "l_battery_cut_end",
                listOf("l_battery_cut_start", "l_battery_cut_end", "si_battery_cells", "si_battery_type"),
                handles = listOf(
                    ChartHandle("l_battery_cut_start", HandleAxis.Y, { cellsAxisMax(it) * 0.85 to it.number("l_battery_cut_start") }) { _, y -> y },
                    ChartHandle("l_battery_cut_end", HandleAxis.Y, { cellsAxisMax(it) * 0.65 to it.number("l_battery_cut_end") }) { _, y -> y },
                    ChartHandle("si_battery_cells", HandleAxis.X, { it.number("si_battery_cells") to 0.0 }) { _, x -> x },
                ),
                plot = ::cellsPlot,
            ),
        )
        add(
            SettingChart(
                "erpm", R.string.curve_erpm_title, R.string.curve_erpm_caption, "l_max_erpm",
                listOf("l_max_erpm", "l_min_erpm", "l_erpm_start", "l_current_max", "l_current_min"),
                handles = listOf(
                    ChartHandle("l_max_erpm", HandleAxis.X, { it.number("l_max_erpm") to 0.0 }) { _, x -> x },
                    ChartHandle("l_min_erpm", HandleAxis.X, { it.number("l_min_erpm") to 0.0 }) { _, x -> x },
                    ChartHandle("l_erpm_start", HandleAxis.X, { it.number("l_max_erpm") * it.number("l_erpm_start") to it.number("l_current_max") }) { c, x ->
                        c.number("l_max_erpm").takeIf { it > 0 }?.let { x / it }
                    },
                ),
                plot = ::erpmPlot,
            ),
        )
        for ((sensor, title) in listOf("fet" to R.string.curve_temp_fet_title, "motor" to R.string.curve_temp_motor_title)) {
            add(
                SettingChart(
                    "temp_$sensor", title, R.string.curve_temp_caption, "l_temp_${sensor}_start",
                    listOf("l_temp_${sensor}_start", "l_temp_${sensor}_end", "l_temp_accel_dec", "l_current_max", "l_current_min"),
                    handles = listOf(
                        ChartHandle("l_temp_${sensor}_start", HandleAxis.X, { c ->
                            val start = c.number("l_temp_${sensor}_start")
                            start to CurveMath.thermal(
                                start, start, c.number("l_temp_${sensor}_end"), c.number("l_temp_accel_dec"), c.number("l_current_max"), c.number("l_current_min"),
                            ).first
                        }) { _, x -> x },
                        ChartHandle("l_temp_${sensor}_end", HandleAxis.X, { it.number("l_temp_${sensor}_end") to 0.0 }) { _, x -> x },
                    ),
                    plot = { thermalPlot(it, sensor) },
                ),
            )
        }
        add(
            SettingChart(
                "duty", R.string.curve_duty_title, R.string.curve_duty_caption, "l_duty_start",
                listOf("l_duty_start", "l_max_duty", "l_current_max", "cc_min_current"),
                handles = listOf(
                    ChartHandle("l_duty_start", HandleAxis.X, { it.number("l_duty_start") * it.number("l_max_duty") * 100 to it.number("l_current_max") }) { c, x ->
                        c.number("l_max_duty").takeIf { it > 0 }?.let { x / 100 / it }
                    },
                    ChartHandle("l_max_duty", HandleAxis.X, { it.number("l_max_duty") * 100 to 0.0 }) { _, x -> x / 100 },
                ),
                plot = ::dutyPlot,
            ),
        )
        add(
            SettingChart(
                "fw", R.string.curve_fw_title, R.string.curve_fw_caption, "foc_fw_current_max",
                listOf("foc_fw_current_max", "foc_fw_duty_start", "l_max_duty"),
                handles = listOf(
                    ChartHandle("foc_fw_duty_start", HandleAxis.X, { it.number("foc_fw_duty_start") * it.number("l_max_duty") * 100 to 0.0 }) { c, x ->
                        c.number("l_max_duty").takeIf { it > 0 }?.let { x / 100 / it }
                    },
                    ChartHandle("foc_fw_current_max", HandleAxis.Y, { it.number("l_max_duty") * 100 to it.number("foc_fw_current_max") }) { _, y -> y },
                ),
                plot = ::fieldWeakeningPlot,
            ),
        )
        add(
            SettingChart(
                "ppm_map", R.string.curve_ppm_title, R.string.curve_ppm_caption, "app_ppm_conf.pulse_start",
                listOf("app_ppm_conf.pulse_start", "app_ppm_conf.pulse_end", "app_ppm_conf.pulse_center", "app_ppm_conf.hyst", "app_ppm_conf.ctrl_type"),
                handles = listOf("pulse_start", "pulse_center", "pulse_end").map { name ->
                    ChartHandle("app_ppm_conf.$name", HandleAxis.X, { c -> c.number("app_ppm_conf.$name").let { it to 100 * ppmThrottle(c, it) } }, { name != "pulse_center" || ppmCentered(it) }) { _, x -> x }
                },
                plot = ::ppmPlot,
            ),
        )
        // The PPM, ADC and VESC Remote apps share the throttle curve and ramping settings.
        for (app in listOf("app_ppm_conf", "app_adc_conf", "app_chuk_conf")) {
            add(
                SettingChart(
                    "throttle_$app", R.string.curve_throttle_title, R.string.curve_throttle_caption, "$app.throttle_exp",
                    listOf("$app.throttle_exp", "$app.throttle_exp_brake", "$app.throttle_exp_mode"),
                    handles = listOf("$app.throttle_exp" to 0.5, "$app.throttle_exp_brake" to -0.5).map { (name, input) ->
                        ChartHandle(
                            name,
                            HandleAxis.Y,
                            { c -> 100 * input to 100 * throttleOf(c, app, input) },
                            { c -> c.int("$app.throttle_exp_mode") in 0L..2L },
                        ) { c, y -> throttleShape(c, app, name, input, y / 100) }
                    },
                    plot = { throttlePlot(it, app) },
                ),
            )
            add(
                SettingChart(
                    "ramp_$app", R.string.curve_ramp_title, R.string.curve_ramp_caption, "$app.ramp_time_pos",
                    listOf("$app.ramp_time_pos", "$app.ramp_time_neg"),
                    handles = listOf(
                        ChartHandle("$app.ramp_time_pos", HandleAxis.X, { max(it.number("$app.ramp_time_pos"), 0.0) to 100.0 }) { _, x -> x },
                        ChartHandle("$app.ramp_time_neg", HandleAxis.X, { c -> rampTimes(c, app).second to 0.0 }) { c, x ->
                            val rise = max(c.number("$app.ramp_time_pos"), 0.0)
                            val limit = c.definition.param("$app.ramp_time_neg").maxDouble
                            increasingRoot(0.0, limit, x) { fall -> rampTimes(rise, fall).second }
                        },
                    ),
                    plot = { rampPlot(it, app) },
                ),
            )
        }
    }

    /** The charts of a settings page, in list order. */
    fun on(subgroup: ParamSubgroup, definition: ConfigDefinition): List<SettingChart> {
        val names = subgroup.paramNames.toHashSet()
        return all.filter { it.anchor in names && it.availableIn(definition) }
    }

    /** The chart that changes when [name] is edited, if any. */
    fun forParam(name: String, definition: ConfigDefinition): SettingChart? =
        all.firstOrNull { name in it.params && it.availableIn(definition) }
}

/** A double or integer setting as a number; the charts read both kinds (temperatures are integers). */
private fun Config.number(name: String): Double = when (val value = get(name)) {
    is ParamValue.DoubleValue -> value.value
    is ParamValue.IntValue -> value.value.toDouble()
    is ParamValue.TextValue -> 0.0
}

/** [low]..[high] widened by [fraction] of its span on both sides, and by at least [minPad]. */
private fun padded(low: Double, high: Double, fraction: Double, minPad: Double = 0.0): Pair<Double, Double> {
    val pad = max((high - low) * fraction, minPad)
    return low - pad to high + pad
}

/** The x where increasing [f] reaches [target] in [low]..[high], or the nearer end when it doesn't. */
private fun increasingRoot(low: Double, high: Double, target: Double, f: (Double) -> Double): Double {
    var lo = low
    var hi = high
    repeat(60) {
        val mid = (lo + hi) / 2
        if (f(mid) < target) lo = mid else hi = mid
    }
    return (lo + hi) / 2
}

private fun regenShown(c: Config) = c.number("l_battery_regen_cut_start") < c.number("l_max_vin")

/** The throttle curve's output at [input] (−1..1). */
private fun throttleOf(c: Config, app: String, input: Double) =
    CurveMath.throttle(input, c.number("$app.throttle_exp"), c.number("$app.throttle_exp_brake"), c.int("$app.throttle_exp_mode").toInt())

/** The value of [name] (the acceleration or brake shape) that makes the curve pass through [output] at [input]. */
private fun throttleShape(c: Config, app: String, name: String, input: Double, output: Double): Double {
    val p = c.definition.param(name)
    val mode = c.int("$app.throttle_exp_mode").toInt()
    // Both halves rise with their shape value; the brake half mirrors the drive half.
    val target = (if (input < 0) -output else output).coerceAtLeast(0.0)
    return increasingRoot(p.minDouble, p.maxDouble, target) { shape -> CurveMath.throttle(kotlin.math.abs(input), shape, shape, mode) }
}

/** When the input is let go and when the output is back at zero, for the ramp chart. */
private fun rampTimes(rise: Double, fall: Double): Pair<Double, Double> {
    val release = max(rise, 0.05) + 0.25 * max(rise + fall, 0.2)
    val peak = CurveMath.ramp(release, rise, fall, release)
    return release to release + if (fall > 0.01) peak * fall else 0.0
}

private fun rampTimes(c: Config, app: String) = rampTimes(max(c.number("$app.ramp_time_pos"), 0.0), max(c.number("$app.ramp_time_neg"), 0.0))

private fun ppmCentered(c: Config): Boolean {
    val names = c.definition.param("app_ppm_conf.ctrl_type").enumNames
    return !names.getOrNull(c.int("app_ppm_conf.ctrl_type").toInt()).orEmpty().endsWith("No Reverse")
}

private fun ppmThrottle(c: Config, ms: Double) = CurveMath.ppm(
    ms,
    c.number("app_ppm_conf.pulse_start"),
    c.number("app_ppm_conf.pulse_end"),
    c.number("app_ppm_conf.pulse_center"),
    c.number("app_ppm_conf.hyst"),
    ppmCentered(c),
)

/** The cell count axis reaches 24 cells, or a quarter past the configured count in steps of four. */
private fun cellsAxisMax(c: Config): Double = max(24.0, kotlin.math.ceil(c.number("si_battery_cells") * 1.25 / 4) * 4)

/** VESC Tool's battery calculator suggests 3.4 V per cell to start reducing current and 3.1 V to stop, for Li-ion only. */
private const val LI_ION_CUT_START = 3.4
private const val LI_ION_CUT_END = 3.1

private fun cellsPlot(c: Config): Plot {
    val cells = c.number("si_battery_cells")
    val start = c.number("l_battery_cut_start")
    val end = c.number("l_battery_cut_end")
    val liIon = c.int("si_battery_type") == 0L
    val xMax = cellsAxisMax(c)
    val yMax = max(maxOf(start, end, if (liIon) LI_ION_CUT_START * cells else 0.0) * 1.3, 10.0)
    return Plot(
        x = PlotAxis(0.0, xMax, null, 0, R.string.curve_axis_cells),
        y = PlotAxis(0.0, yMax, R.string.unit_volt, 1),
        lines = buildList {
            if (liIon) {
                add(PlotLine(R.string.curve_line_suggest_start, LineTone.Reference) { n -> LI_ION_CUT_START * n })
                add(PlotLine(R.string.curve_line_suggest_end, LineTone.Reference) { n -> LI_ION_CUT_END * n })
            }
            add(PlotLine(R.string.curve_line_cut_start, LineTone.Primary) { start })
            add(PlotLine(R.string.curve_line_cut_end, LineTone.Secondary) { end })
        },
        markers = listOf(PlotMarker(cells, R.string.curve_marker_cells)),
        note = if (liIon) null else R.string.curve_cells_no_suggestion,
    )
}

/** A current axis from the most negative to the most positive of [values], with headroom, always including zero. */
private fun ampAxis(vararg values: Double): PlotAxis {
    val hi = max(values.max(), 0.0)
    val lo = min(values.min(), 0.0)
    val span = max(hi - lo, 1.0)
    return PlotAxis(if (lo < 0) lo - span * 0.08 else 0.0, if (hi > 0) hi + span * 0.08 else span * 0.08, R.string.unit_amp, 0)
}

private fun batteryPlot(c: Config): Plot {
    val cutStart = c.number("l_battery_cut_start")
    val cutEnd = c.number("l_battery_cut_end")
    val regenStart = c.number("l_battery_regen_cut_start")
    val regenEnd = c.number("l_battery_regen_cut_end")
    val inMax = c.number("l_in_current_max")
    val inMin = c.number("l_in_current_min")
    val vMin = c.number("l_min_vin")
    val vMax = c.number("l_max_vin")
    // The regen cutoff defaults far above any battery; only show its range when it starts below the fault limit.
    val regenShown = regenStart < vMax
    val low = max(0.0, min(min(cutEnd, cutStart), vMin))
    val high = if (regenShown) max(vMax, min(max(regenStart, regenEnd), vMax * 1.2)) else max(vMax, cutStart)
    val (xMin, xMax) = padded(low, high, 0.08, 1.0)
    val zones = buildList {
        if (cutStart > cutEnd) add(PlotZone(cutEnd, cutStart, ZoneTone.Reduced, R.string.curve_zone_reduced))
        if (regenShown && regenEnd > regenStart) add(PlotZone(regenStart, regenEnd, ZoneTone.Reduced, R.string.curve_zone_reduced))
        add(PlotZone(xMin, vMin, ZoneTone.Cutoff, R.string.curve_zone_fault))
        add(PlotZone(vMax, xMax, ZoneTone.Cutoff, R.string.curve_zone_fault))
    }
    return Plot(
        x = PlotAxis(xMin, xMax, R.string.unit_volt, 1),
        y = ampAxis(inMax, inMin),
        lines = listOf(
            PlotLine(R.string.curve_line_draw, LineTone.Primary, listOf(cutStart, cutEnd)) { v -> CurveMath.batteryDrive(v, cutStart, cutEnd, inMax) },
            PlotLine(R.string.curve_line_regen, LineTone.Secondary, listOf(regenStart, regenEnd)) { v -> CurveMath.batteryRegen(v, regenStart, regenEnd, inMin) },
        ),
        zones = zones,
        note = if (regenShown) null else R.string.curve_regen_off,
    )
}

private fun erpmPlot(c: Config): Plot {
    val maxErpm = c.number("l_max_erpm")
    val minErpm = c.number("l_min_erpm")
    val start = c.number("l_erpm_start")
    val currentMax = c.number("l_current_max")
    val currentMin = c.number("l_current_min")
    val span = maxOf(maxErpm, -minErpm, 1000.0)
    val xMin = min(minErpm, 0.0) - span * 0.12
    val xMax = max(maxErpm, 0.0) + span * 0.12
    return Plot(
        x = PlotAxis(xMin, xMax, R.string.overview_erpm, 0),
        y = ampAxis(currentMax, currentMin),
        lines = listOf(
            PlotLine(R.string.curve_line_drive, LineTone.Primary, listOf(maxErpm * start, maxErpm, minErpm * start, minErpm)) { e ->
                CurveMath.erpmDrive(e, maxErpm, minErpm, start, currentMax)
            },
            PlotLine(R.string.curve_line_brake, LineTone.Secondary) { currentMin },
        ),
        zones = listOf(
            PlotZone(minErpm, minErpm * start, ZoneTone.Reduced, R.string.curve_zone_reduced),
            PlotZone(maxErpm * start, maxErpm, ZoneTone.Reduced, R.string.curve_zone_reduced),
            PlotZone(xMin, minErpm, ZoneTone.Cutoff, R.string.curve_zone_no_drive),
            PlotZone(maxErpm, xMax, ZoneTone.Cutoff, R.string.curve_zone_no_drive),
        ),
        markers = listOf(PlotMarker(maxErpm * start, R.string.curve_marker_start)),
    )
}

private fun thermalPlot(c: Config, sensor: String): Plot {
    val start = c.number("l_temp_${sensor}_start")
    val end = c.number("l_temp_${sensor}_end")
    val accelDec = c.number("l_temp_accel_dec")
    val currentMax = c.number("l_current_max")
    val currentMin = c.number("l_current_min")
    val accelStart = CurveMath.map(accelDec, 0.0, 1.0, start, 25.0)
    val accelEnd = CurveMath.map(accelDec, 0.0, 1.0, end, 25.0)
    // Where the shared limit meets each configured current, which is where that line bends.
    val shared = max(abs(currentMax), abs(currentMin))
    val driveBend = CurveMath.map(abs(currentMax), shared, 0.0, start, end)
    val brakeBend = CurveMath.map(abs(currentMin), shared, 0.0, start, end)
    val (xMin, xMax) = padded(minOf(accelStart, start, 25.0), max(end, start), 0.12, 5.0)
    return Plot(
        x = PlotAxis(xMin, xMax, R.string.unit_celsius, 0),
        y = ampAxis(currentMax, currentMin),
        lines = listOf(
            PlotLine(R.string.curve_line_drive, LineTone.Primary, listOf(start, end, accelStart, accelEnd, driveBend)) { t ->
                CurveMath.thermal(t, start, end, accelDec, currentMax, currentMin).first
            },
            PlotLine(R.string.curve_line_brake, LineTone.Secondary, listOf(start, end, brakeBend)) { t ->
                CurveMath.thermal(t, start, end, accelDec, currentMax, currentMin).second
            },
        ),
        zones = listOf(
            PlotZone(min(accelStart, start), end, ZoneTone.Reduced, R.string.curve_zone_reduced),
            PlotZone(end, xMax, ZoneTone.Cutoff, R.string.curve_zone_fault),
        ),
        markers = listOf(PlotMarker(start, R.string.curve_marker_start), PlotMarker(end, R.string.curve_marker_end)),
    )
}

private fun dutyPlot(c: Config): Plot {
    val dutyStart = c.number("l_duty_start")
    val maxDuty = c.number("l_max_duty")
    val currentMax = c.number("l_current_max")
    val ccMin = c.number("cc_min_current")
    val off = dutyStart > 0.99
    return Plot(
        x = PlotAxis(0.0, 100.0, R.string.unit_percent, 0, R.string.curve_axis_duty),
        y = ampAxis(currentMax),
        lines = listOf(
            PlotLine(R.string.curve_line_drive, LineTone.Primary, listOf(dutyStart * maxDuty * 100, maxDuty * 100)) { d ->
                CurveMath.dutyDrive(d / 100, dutyStart, maxDuty, currentMax, ccMin)
            },
        ),
        zones = buildList {
            if (!off) add(PlotZone(dutyStart * maxDuty * 100, maxDuty * 100, ZoneTone.Reduced, R.string.curve_zone_reduced))
            add(PlotZone(maxDuty * 100, 100.0, ZoneTone.Cutoff, R.string.curve_zone_above_max_duty))
        },
        note = if (off) R.string.curve_duty_off else null,
    )
}

private fun fieldWeakeningPlot(c: Config): Plot {
    val currentMax = c.number("foc_fw_current_max")
    val dutyStart = c.number("foc_fw_duty_start")
    val maxDuty = c.number("l_max_duty")
    val off = currentMax <= 0.0
    return Plot(
        x = PlotAxis(0.0, 100.0, R.string.unit_percent, 0, R.string.curve_axis_duty),
        y = PlotAxis(0.0, max(currentMax, 10.0) * 1.15, R.string.unit_amp, 0),
        lines = listOf(
            PlotLine(R.string.curve_line_fw, LineTone.Primary, listOf(dutyStart * maxDuty * 100, maxDuty * 100)) { d ->
                CurveMath.fieldWeakening(d / 100, dutyStart, maxDuty, currentMax)
            },
        ),
        zones = buildList {
            if (!off && dutyStart < 0.99) add(PlotZone(dutyStart * maxDuty * 100, maxDuty * 100, ZoneTone.Neutral, R.string.curve_zone_active))
            add(PlotZone(maxDuty * 100, 100.0, ZoneTone.Cutoff, R.string.curve_zone_above_max_duty))
        },
        // Where it starts even while it's off, so setting a current shows where it will apply.
        markers = if (dutyStart < 0.99) listOf(PlotMarker(dutyStart * maxDuty * 100, R.string.curve_marker_start)) else emptyList(),
        note = if (off) R.string.curve_fw_off else null,
    )
}

private fun throttlePlot(c: Config, app: String): Plot {
    val accel = c.number("$app.throttle_exp")
    val brake = c.number("$app.throttle_exp_brake")
    val mode = c.int("$app.throttle_exp_mode").toInt()
    return Plot(
        x = PlotAxis(-100.0, 100.0, R.string.unit_percent, 0, R.string.curve_axis_input),
        y = PlotAxis(-100.0, 100.0, R.string.unit_percent, 0, R.string.curve_axis_output),
        lines = listOf(
            PlotLine(R.string.curve_line_linear, LineTone.Reference) { it },
            PlotLine(R.string.curve_line_output, LineTone.Primary, listOf(0.0)) { x -> 100 * CurveMath.throttle(x / 100, accel, brake, mode) },
        ),
    )
}

private fun ppmPlot(c: Config): Plot {
    val start = c.number("app_ppm_conf.pulse_start")
    val end = c.number("app_ppm_conf.pulse_end")
    val center = c.number("app_ppm_conf.pulse_center")
    val band = c.number("app_ppm_conf.hyst")
    val control = c.definition.params.getValue("app_ppm_conf.ctrl_type").enumNames.getOrNull(c.int("app_ppm_conf.ctrl_type").toInt()).orEmpty()
    // The "No Reverse" control types use the whole pulse range for 0..100 %; the others split it at the center.
    val centered = !control.endsWith("No Reverse")
    val (xMin, xMax) = padded(min(start, end), max(start, end), 0.15, 0.05)
    val deadband = if (centered) {
        center - band * (center - start) to center + band * (end - center)
    } else {
        start to start + band * (end - start)
    }
    return Plot(
        x = PlotAxis(xMin, xMax, R.string.unit_ms, 2, R.string.curve_axis_pulse),
        y = PlotAxis(if (centered) -100.0 else 0.0, 100.0, R.string.unit_percent, 0, R.string.curve_axis_throttle),
        lines = listOf(
            PlotLine(R.string.curve_line_throttle, LineTone.Primary, listOf(start, end, center, deadband.first, deadband.second)) { ms ->
                100 * CurveMath.ppm(ms, start, end, center, band, centered)
            },
        ),
        zones = if (band > 0) listOf(PlotZone(deadband.first, deadband.second, ZoneTone.Neutral, R.string.curve_zone_deadband)) else emptyList(),
        markers = buildList {
            add(PlotMarker(start, R.string.curve_marker_start))
            if (centered) add(PlotMarker(center, R.string.curve_marker_center))
            add(PlotMarker(end, R.string.curve_marker_end))
        },
    )
}

private fun rampPlot(c: Config, app: String): Plot {
    val rise = max(c.number("$app.ramp_time_pos"), 0.0)
    val fall = max(c.number("$app.ramp_time_neg"), 0.0)
    val (release, settled) = rampTimes(rise, fall)
    val span = settled + 0.15 * max(settled, 0.2)
    val step = span * 1e-6
    return Plot(
        x = PlotAxis(-0.06 * span, span, R.string.unit_s, 2, R.string.curve_axis_time),
        y = PlotAxis(0.0, 112.0, R.string.unit_percent, 0, R.string.curve_axis_output),
        lines = listOf(
            PlotLine(R.string.curve_line_input, LineTone.Reference, listOf(0.0, step, release, release + step)) { t -> if (t > 0 && t <= release) 100.0 else 0.0 },
            PlotLine(R.string.curve_line_output, LineTone.Primary, listOf(0.0, step, min(rise, release), release, settled)) { t ->
                100 * CurveMath.ramp(t, rise, fall, release)
            },
        ),
    )
}
