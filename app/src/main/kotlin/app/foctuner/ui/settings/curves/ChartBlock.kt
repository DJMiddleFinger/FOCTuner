package app.foctuner.ui.settings.curves

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.config.Config
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/**
 * A setting chart with its title, legend and a sentence on what it shows. [compareTo] is drawn dashed under
 * [compareLabel]: the values on the controller on a settings page, the value before the edit in the edit dialog.
 * Callers pass it only when one of the chart's settings has really changed. [compact] is the dialog version:
 * shorter, no caption.
 */
@Composable
internal fun ChartBlock(
    chart: SettingChart,
    config: Config,
    compareTo: Config?,
    @StringRes compareLabel: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = FocTheme.colors
    val title = stringResource(chart.title)
    val values = chart.params.map { config[it] }
    val compared = compareTo?.let { c -> chart.params.map { c[it] } }
    val differs = compared != null
    val fill = if (compact) colors.background else colors.surface
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = colors.text, style = FocTheme.type.label)
            if (differs && !compact) {
                Text(stringResource(R.string.curve_preview), color = colors.warning, style = FocTheme.type.caption)
            }
        }
        val build = chart.plot
        if (build != null) {
            val plot = remember(values) { build(config) }
            val ghost = compareTo?.takeIf { differs }?.let { stored -> remember(compared) { build(stored) } }
            CurveChart(plot, ghost, title, plotHeight = if (compact) 136.dp else 176.dp, fill = fill)
            Legend(plot, ghost != null, compareLabel)
            plot.note?.let { Text(stringResource(it), color = colors.textSecondary, style = FocTheme.type.supporting) }
        } else {
            LimitBars(config, compareTo.takeIf { differs }, title, fill = fill)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.x2), verticalArrangement = Arrangement.spacedBy(Spacing.half)) {
                LegendItem(stringResource(R.string.curve_line_drive)) { LineSwatch(colors.accent, dashed = false) }
                LegendItem(stringResource(R.string.curve_line_brake_regen)) { LineSwatch(colors.textSecondary, dashed = false) }
                LegendItem(stringResource(R.string.curve_line_abs)) { LineSwatch(colors.fault, dashed = true) }
                if (differs) LegendItem(stringResource(compareLabel)) { LineSwatch(colors.textTertiary, dashed = true) }
            }
        }
        if (!compact) {
            val hint = if (build != null) " " + stringResource(R.string.curve_hint) else ""
            Text(stringResource(chart.caption) + hint, color = colors.textTertiary, style = FocTheme.type.supporting)
        }
    }
}

@Composable
private fun Legend(plot: Plot, ghost: Boolean, @StringRes ghostLabel: Int) {
    val colors = FocTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.x2), verticalArrangement = Arrangement.spacedBy(Spacing.half)) {
        for (line in plot.lines) {
            LegendItem(stringResource(line.label)) { LineSwatch(colors.line(line.tone), dashed = false, thin = line.tone == LineTone.Reference) }
        }
        if (ghost) LegendItem(stringResource(ghostLabel)) { LineSwatch(colors.textTertiary, dashed = true) }
        for (zone in plot.zones.distinctBy { it.tone to it.label }) {
            LegendItem(stringResource(zone.label)) { ZoneSwatch(colors.zoneFill(zone.tone), colors.zoneEdge(zone.tone)) }
        }
    }
}

@Composable
private fun LegendItem(label: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        swatch()
        Text(label, Modifier.padding(start = Spacing.x1), color = FocTheme.colors.textSecondary, style = FocTheme.type.caption)
    }
}

@Composable
private fun LineSwatch(color: Color, dashed: Boolean, thin: Boolean = false) {
    Canvas(Modifier.size(16.dp, 12.dp)) {
        val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) else null
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), (if (thin) 1 else 2).dp.toPx(), pathEffect = effect)
    }
}

@Composable
private fun ZoneSwatch(fill: Color, edge: Color) {
    Canvas(Modifier.size(12.dp)) {
        drawRect(fill)
        drawRect(edge, size = Size(size.width, 2.dp.toPx()))
    }
}
