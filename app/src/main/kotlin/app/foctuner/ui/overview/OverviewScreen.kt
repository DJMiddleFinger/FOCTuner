package app.foctuner.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.Readout
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.Sparkline
import app.foctuner.ui.components.fixed
import app.foctuner.ui.components.grouped
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.theme.StressPreview
import app.foctuner.ui.theme.TabletPreview
import app.foctuner.ui.theme.ThemePreviews
import app.foctuner.vesc.FaultCode
import app.foctuner.vesc.RealtimeValues
import kotlin.math.abs
import kotlin.math.sin

/** Live values, laid out to be read at arm's length on a handlebar mount. Two columns on tablets. */
@Composable
fun OverviewScreen(state: OverviewState, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(FocTheme.colors.background),
    ) {
        val wide = maxWidth >= Spacing.wideBreakpoint
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Alerts(state)
            if (wide) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Column(Modifier.weight(1f)) { Battery(state) }
                    VerticalDivider(Modifier.fillMaxHeight(), thickness = Dp.Hairline, color = FocTheme.colors.hairline)
                    Column(Modifier.weight(1f)) {
                        Motor(state)
                        Temperatures(state)
                        Energy(state.values)
                    }
                }
            } else {
                Battery(state)
                Motor(state)
                Temperatures(state)
                Energy(state.values)
            }
            Spacer(Modifier.height(Spacing.x3))
        }
    }
}

@Composable
private fun Alerts(state: OverviewState) {
    val values = state.values
    val fault = faultStrings(values.faultCode)
    if (fault != null) {
        val code = FaultCode.fromCode(values.faultCode)?.name ?: values.faultCode.toString()
        Notice(
            title = fault.title,
            body = fault.detail,
            label = stringResource(R.string.overview_fault_label) + " · " + code,
            tone = NoticeTone.Fault,
        )
    }
    if (values.killSwitchActive) {
        Notice(
            title = stringResource(R.string.overview_kill_switch_title),
            body = stringResource(R.string.overview_kill_switch_body),
            tone = NoticeTone.Warning,
        )
    }
    if (state.staleSeconds > 0) {
        Notice(title = stringResource(R.string.overview_stale, state.staleSeconds), tone = NoticeTone.Warning)
    }
}

@Composable
private fun Battery(state: OverviewState) {
    val values = state.values
    SectionHeader(stringResource(R.string.overview_section_battery)) {
        Text(
            stringResource(R.string.overview_trend_window, state.trendSeconds),
            Modifier.padding(end = Spacing.x1),
            color = FocTheme.colors.textTertiary,
            style = FocTheme.type.caption,
        )
    }
    val stale = state.staleSeconds > 0
    TrendReadout(
        stringResource(R.string.overview_voltage), values.voltageInput.fixed(1), stringResource(R.string.unit_volt),
        state.voltageTrend, state.trendCapacity, minSpan = 1f, stale,
    )
    Hairline(Modifier.padding(start = Spacing.x2))
    TrendReadout(
        stringResource(R.string.overview_battery_current), values.currentInput.fixed(1), stringResource(R.string.unit_amp),
        state.currentTrend, state.trendCapacity, minSpan = 4f, stale,
    )
    Hairline(Modifier.padding(start = Spacing.x2))
    TrendReadout(
        stringResource(R.string.overview_power), state.power.fixed(0), stringResource(R.string.unit_watt),
        state.powerTrend, state.trendCapacity, minSpan = 100f, stale,
    )
}

@Composable
private fun TrendReadout(
    label: String,
    value: String,
    unit: String,
    samples: List<Float>,
    capacity: Int,
    minSpan: Float,
    stale: Boolean,
) {
    val colors = FocTheme.colors
    Column(Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                label,
                Modifier
                    .weight(1f)
                    .alignByBaseline()
                    .padding(end = Spacing.x1),
                color = colors.textSecondary,
                style = FocTheme.type.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Readout(
                value,
                unit,
                Modifier.alignByBaseline(),
                style = FocTheme.type.readoutLarge,
                unitStyle = FocTheme.type.unitLarge,
                color = if (stale) colors.textTertiary else colors.text,
            )
        }
        Sparkline(
            samples,
            capacity,
            minSpan,
            Modifier
                .fillMaxWidth()
                .height(Spacing.x4)
                .padding(top = Spacing.half),
            color = if (stale) colors.textTertiary else colors.accent,
        )
    }
}

@Composable
private fun Motor(state: OverviewState) {
    val values = state.values
    val stale = state.staleSeconds > 0
    SectionHeader(stringResource(R.string.overview_section_motor)) {
        if (values.timedOut) {
            Text(
                stringResource(R.string.overview_timed_out_title),
                Modifier.padding(end = Spacing.x1),
                color = FocTheme.colors.textTertiary,
                style = FocTheme.type.caption,
            )
        }
    }
    CellRow {
        Cell(stringResource(R.string.overview_motor_current), values.currentMotor.fixed(1), stringResource(R.string.unit_amp), stale, Modifier.weight(1f))
        CellDivider()
        Cell(stringResource(R.string.overview_duty), (values.dutyCycle * 100).fixed(1), stringResource(R.string.unit_percent), stale, Modifier.weight(1f)) {
            DutyMeter(abs(values.dutyCycle))
        }
    }
    Hairline(Modifier.padding(horizontal = Spacing.x2))
    CellRow {
        Cell(stringResource(R.string.overview_erpm), values.erpm.grouped(), "", stale, Modifier.weight(1f))
        state.speedKmh?.let { speed ->
            CellDivider()
            Cell(stringResource(R.string.overview_speed), speed.fixed(1), stringResource(R.string.unit_kmh), stale, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Temperatures(state: OverviewState) {
    val values = state.values
    val stale = state.staleSeconds > 0
    val celsius = stringResource(R.string.unit_celsius)
    SectionHeader(stringResource(R.string.overview_section_temperature))
    CellRow {
        Cell(stringResource(R.string.overview_temp_mosfet), values.tempMosfet.fixed(1), celsius, stale, Modifier.weight(1f))
        CellDivider()
        Cell(stringResource(R.string.overview_temp_motor), values.tempMotor.fixed(1), celsius, stale, Modifier.weight(1f))
    }
}

@Composable
private fun Energy(values: RealtimeValues) {
    SectionHeader(stringResource(R.string.overview_section_energy))
    EnergyLine("", stringResource(R.string.overview_energy_used), stringResource(R.string.overview_energy_regen), "", header = true)
    EnergyLine(stringResource(R.string.overview_amp_hours), values.ampHours.fixed(3), values.ampHoursCharged.fixed(3), stringResource(R.string.unit_amp_hour))
    Hairline(Modifier.padding(horizontal = Spacing.x2))
    EnergyLine(stringResource(R.string.overview_watt_hours), values.wattHours.fixed(1), values.wattHoursCharged.fixed(1), stringResource(R.string.unit_watt_hour))
}

@Composable
private fun EnergyLine(label: String, used: String, regen: String, unit: String, header: Boolean = false) {
    val colors = FocTheme.colors
    val numberStyle = if (header) FocTheme.type.caption else FocTheme.type.value.copy(fontSize = FocTheme.type.bodyStrong.fontSize)
    val numberColor = if (header) colors.textTertiary else colors.text
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.x2, vertical = if (header) 0.dp else Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1.3f), color = colors.textSecondary, style = FocTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(used, Modifier.weight(1f), color = numberColor, style = numberStyle, textAlign = TextAlign.End, maxLines = 1)
        Text(regen, Modifier.weight(1f), color = numberColor, style = numberStyle, textAlign = TextAlign.End, maxLines = 1)
        Text(
            unit,
            Modifier
                .padding(start = Spacing.x1)
                .weight(0.35f),
            color = colors.textTertiary,
            style = FocTheme.type.unit,
            maxLines = 1,
        )
    }
}

@Composable
private fun CellRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.height(IntrinsicSize.Min), content = content)
}

@Composable
private fun CellDivider() {
    VerticalDivider(
        Modifier
            .fillMaxHeight()
            .padding(vertical = Spacing.x1),
        thickness = Dp.Hairline,
        color = FocTheme.colors.hairline,
    )
}

/** Label top left, reading bottom right: the layout of a panel meter. */
@Composable
private fun Cell(
    label: String,
    value: String,
    unit: String,
    stale: Boolean,
    modifier: Modifier = Modifier,
    below: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        verticalArrangement = Arrangement.spacedBy(Spacing.half),
    ) {
        Text(label, color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Readout(
            value,
            unit,
            Modifier.align(Alignment.End),
            color = if (stale) FocTheme.colors.textTertiary else FocTheme.colors.text,
        )
        below?.invoke()
    }
}

@Composable
private fun DutyMeter(fraction: Float) {
    val track = FocTheme.colors.hairline
    val fill = FocTheme.colors.textSecondary
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(Spacing.half),
    ) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        drawRoundRect(fill, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = radius)
    }
}

private fun previewValues(fault: FaultCode = FaultCode.NONE) = RealtimeValues(
    tempMosfet = 41.3f, tempMotor = 56.8f, currentMotor = 35.2f, currentInput = 12.4f, currentD = -1.1f, currentQ = 35.0f,
    dutyCycle = 0.423f, erpm = 18_420f, voltageInput = 50.4f, ampHours = 1.234f, ampHoursCharged = 0.112f,
    wattHours = 62.1f, wattHoursCharged = 5.4f, tachometer = 120_000, tachometerAbs = 130_000, faultCode = fault.ordinal,
)

private fun previewTrend(base: Float, swing: Float) = List(90) { i ->
    base + swing * sin(i / 9f) + swing * 0.4f * sin(i / 2.3f)
}

private fun previewState(fault: FaultCode = FaultCode.NONE) = OverviewState(
    values = previewValues(fault),
    voltageTrend = previewTrend(50.6f, 0.5f),
    currentTrend = previewTrend(8f, 10f),
    powerTrend = previewTrend(400f, 500f),
)

@ThemePreviews
@Composable
private fun OverviewPreview() {
    FocTheme { OverviewScreen(previewState()) }
}

@StressPreview
@Composable
private fun OverviewFaultPreview() {
    FocTheme { OverviewScreen(previewState(FaultCode.OVER_TEMP_FET).copy(speedKmh = 32.4f, staleSeconds = 3)) }
}

@TabletPreview
@Composable
private fun OverviewTabletPreview() {
    FocTheme { OverviewScreen(previewState().copy(speedKmh = 32.4f)) }
}
