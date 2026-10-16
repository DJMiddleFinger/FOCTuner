package app.foctuner.ui.tools.ride

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.NO_VALUE
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.Readout
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.ChoiceRow
import app.foctuner.ui.tools.parts.Meter
import app.foctuner.ui.tools.parts.MeterRow
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun RideTool(context: ToolContext) {
    val model = context.model { RideModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) {
        while (true) {
            model.refresh()
            delay(POLL_MS)
        }
    }
    RideContent(state, model::setUnit, model::setOdometer)
}

@Composable
private fun RideContent(state: RideState, onUnit: (DistanceUnit) -> Unit, onOdometer: (Float) -> Unit) {
    val v = state.values
    val u = state.unit
    var editOdometer by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (state.failed) Notice(stringResource(R.string.ride_no_reply), tone = NoticeTone.Warning)
        ChoiceRow(DistanceUnit.entries, u, { it.distance }, onUnit)
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.x2, vertical = Spacing.x2),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(stringResource(R.string.ride_speed), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
                Readout(
                    v?.let { (it.speed * 3.6f * u.perKm).fixed(1) } ?: NO_VALUE,
                    u.speed,
                    style = FocTheme.type.readoutLarge,
                    unitStyle = FocTheme.type.unitLarge,
                    color = if (v == null) FocTheme.colors.textTertiary else FocTheme.colors.accent,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.ride_battery), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
                Readout(v?.let { (it.batteryLevel * 100f).fixed(0) } ?: NO_VALUE, "%")
            }
        }
        Hairline()
        MeterRow(
            Meter(stringResource(R.string.ride_range), state.rangeKm?.let { (it * u.perKm).fixed(1) }, u.distance),
            Meter(stringResource(R.string.ride_battery_energy), v?.batteryWattHours?.fixed(0), "Wh"),
        )
        Hairline()
        MeterRow(
            Meter(stringResource(R.string.ride_efficiency_trip), state.whPerKmTrip?.let { (it / u.perKm).fixed(1) }, u.efficiency),
            Meter(stringResource(R.string.ride_efficiency_now), v?.let { (state.whPerKmNow / u.perKm).fixed(1) }, u.efficiency),
        )
        Hairline()
        MeterRow(
            Meter(stringResource(R.string.ride_power), v?.let { (it.currentInput * it.voltageInput).fixed(0) }, "W"),
            Meter(stringResource(R.string.ride_incline), state.incline?.fixed(1), "%"),
        )
        Hairline()

        SectionHeader(stringResource(R.string.ride_section_distance))
        MeterRow(
            Meter(stringResource(R.string.ride_trip), v?.let { (it.distanceAbs / 1000f * u.perKm).fixed(2) }, u.distance),
            Meter(stringResource(R.string.ride_ride_time), v?.let { duration(it.uptimeMs) }),
        )
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.row)
                .padding(start = Spacing.x2, end = Spacing.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.ride_odometer), Modifier.weight(1f), color = FocTheme.colors.text, style = FocTheme.type.body)
            Readout(v?.let { (it.odometer / 1000f * u.perKm).fixed(1) } ?: NO_VALUE, if (v == null) "" else u.distance, style = FocTheme.type.value)
            PlainButton(stringResource(R.string.ride_odometer_set), { editOdometer = true }, enabled = v != null)
        }
        Hairline()

        SectionHeader(stringResource(R.string.ride_section_motors))
        MeterRow(
            Meter(stringResource(R.string.ride_current_motor), v?.currentMotor?.fixed(1), "A"),
            Meter(stringResource(R.string.ride_current_input), v?.currentInput?.fixed(1), "A"),
            Meter(stringResource(R.string.ride_voltage), v?.voltageInput?.fixed(1), "V"),
        )
        Hairline()
        MeterRow(
            Meter(stringResource(R.string.ride_temp_mosfet), v?.tempMosfet?.fixed(1), "°C"),
            Meter(stringResource(R.string.ride_temp_motor), v?.tempMotor?.fixed(1), "°C"),
            Meter(stringResource(R.string.ride_controllers), v?.controllerCount?.toString()),
        )
        Hairline()
        MeterRow(
            Meter(stringResource(R.string.ride_used), v?.wattHours?.fixed(1), "Wh"),
            Meter(stringResource(R.string.ride_regenerated), v?.wattHoursCharged?.fixed(1), "Wh"),
        )
        Hairline()
    }

    if (editOdometer && v != null) {
        OdometerDialog(
            current = v.odometer / 1000f * u.perKm,
            unit = u,
            onSet = {
                editOdometer = false
                onOdometer(it / u.perKm)
            },
            onDismiss = { editOdometer = false },
        )
    }
}

/** Sets the controller's stored odometer, in the unit shown. The value is entered, then confirmed with Set. */
@Composable
private fun OdometerDialog(current: Float, unit: DistanceUnit, onSet: (Float) -> Unit, onDismiss: () -> Unit) {
    val colors = FocTheme.colors
    var text by remember { mutableStateOf(String.format(Locale.ROOT, "%.1f", current)) }
    val value = text.replace(',', '.').toFloatOrNull()?.takeIf { it >= 0f && it / unit.perKm * 1000f <= MAX_ODOMETER_M }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { PlainButton(stringResource(R.string.ride_odometer_confirm), { value?.let(onSet) }, enabled = value != null) },
        dismissButton = { PlainButton(stringResource(R.string.ride_cancel), onDismiss) },
        title = { Text(stringResource(R.string.ride_odometer_title), style = FocTheme.type.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                Text(stringResource(R.string.ride_odometer_body), style = FocTheme.type.supporting)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = FocTheme.type.value,
                    suffix = { Text(unit.distance) },
                    isError = value == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        shape = MaterialTheme.shapes.small,
        containerColor = colors.surface,
        titleContentColor = colors.text,
        textContentColor = colors.textSecondary,
    )
}

private fun duration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
}

private const val POLL_MS = 100L
private const val MAX_ODOMETER_M = 4_294_967_295f
