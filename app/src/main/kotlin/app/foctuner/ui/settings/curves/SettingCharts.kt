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

/**
 * A chart of what a few related settings do, shown on the settings page that holds [anchor] and in the edit
 * dialog of each of [params], where it previews the value being typed.
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
                plot = ::batteryPlot,
            ),
        )
        add(
            SettingChart(
                "erpm", R.string.curve_erpm_title, R.string.curve_erpm_caption, "l_max_erpm",
                listOf("l_max_erpm", "l_min_erpm", "l_erpm_start", "l_current_max", "l_current_min"),
                plot = ::erpmPlot,
            ),
        )
        for ((sensor, title) in listOf("fet" to R.string.curve_temp_fet_title, "motor" to R.string.curve_temp_motor_title)) {
            add(
                SettingChart(
                    "temp_$sensor", title, R.string.curve_temp_caption, "l_temp_${sensor}_start",
                    listOf("l_temp_${sensor}_start", "l_temp_${sensor}_end", "l_temp_accel_dec", "l_current_max", "l_current_min"),
                    plot = { thermalPlot(it, sensor) },
                ),
            )
        }
        add(
            SettingChart(
                "duty", R.string.curve_duty_title, R.string.curve_duty_caption, "l_duty_start",
                listOf("l_duty_start", "l_max_duty", "l_current_max", "cc_min_current"),
                plot = ::dutyPlot,
            ),
        )
        add(
            SettingChart(
                "fw", R.string.curve_fw_title, R.string.curve_fw_caption, "foc_fw_current_max",
                listOf("foc_fw_current_max", "foc_fw_duty_start", "l_max_duty"),
                plot = ::fieldWeakeningPlot,
            ),
        )
        add(
            SettingChart(
                "ppm_map", R.string.curve_ppm_title, R.string.curve_ppm_caption, "app_ppm_conf.pulse_start",
                listOf("app_ppm_conf.pulse_start", "app_ppm_conf.pulse_end", "app_ppm_conf.pulse_center", "app_ppm_conf.hyst", "app_ppm_conf.ctrl_type"),
                plot = ::ppmPlot,
            ),
        )
        // The PPM, ADC and VESC Remote apps share the throttle curve and ramping settings.
        for (app in listOf("app_ppm_conf", "app_adc_conf", "app_chuk_conf")) {
            add(
                SettingChart(
                    "throttle_$app", R.string.curve_throttle_title, R.string.curve_throttle_caption, "$app.throttle_exp",
                    listOf("$app.throttle_exp", "$app.throttle_exp_brake", "$app.throttle_exp_mode"),
                    plot = { throttlePlot(it, app) },
                ),
            )
            add(
                SettingChart(
                    "ramp_$app", R.string.curve_ramp_title, R.string.curve_ramp_caption, "$app.ramp_time_pos",
                    listOf("$app.ramp_time_pos", "$app.ramp_time_neg"),
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
    val release = max(rise, 0.05) + 0.25 * max(rise + fall, 0.2)
    val peak = CurveMath.ramp(release, rise, fall, release)
    val settled = release + if (fall > 0.01) peak * fall else 0.0
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
