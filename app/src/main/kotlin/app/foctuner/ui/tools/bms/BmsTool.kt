package app.foctuner.ui.tools.bms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.ui.tools.parts.ReadingRow
import app.foctuner.vesc.BmsValues
import kotlinx.coroutines.delay

@Composable
fun BmsTool(context: ToolContext) {
    val model = context.model { BmsModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) {
        while (true) {
            model.refresh()
            delay(POLL_MS)
        }
    }
    BmsContent(state, model::send)
}

@Composable
private fun BmsContent(state: BmsState, onCommand: (BmsCommand) -> Unit) {
    var confirm by rememberSaveable { mutableStateOf<BmsCommand?>(null) }
    val v = state.values
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (state.failed) Notice(stringResource(R.string.bms_no_reply), tone = NoticeTone.Warning)
        if (v != null && v.cells.isEmpty()) {
            Notice(stringResource(R.string.bms_none), body = stringResource(R.string.bms_none_body))
        }
        if (v != null && v.status.isNotEmpty()) Notice(v.status, label = stringResource(R.string.bms_status))

        SectionHeader(stringResource(R.string.bms_section_cells))
        if (v != null) CellBars(v.cells, v.balancing)
        val min = v?.cellMin
        val max = v?.cellMax
        ReadingRow(stringResource(R.string.bms_total), v?.voltageTotal?.fixed(2), "V")
        ReadingRow(stringResource(R.string.bms_cell_range), if (min != null && max != null) "${min.fixed(3)}–${max.fixed(3)}" else null, "V")
        ReadingRow(stringResource(R.string.bms_cell_difference), if (min != null && max != null) ((max - min) * 1000f).fixed(0) else null, "mV")

        SectionHeader(stringResource(R.string.bms_section_pack))
        ReadingRow(stringResource(R.string.bms_soc), v?.let { (it.soc * 100f).fixed(0) }, "%")
        ReadingRow(stringResource(R.string.bms_soh), v?.let { (it.soh * 100f).fixed(0) }, "%")
        ReadingRow(stringResource(R.string.bms_current), v?.current?.fixed(2), "A")
        ReadingRow(stringResource(R.string.bms_current_ic), v?.currentIc?.fixed(2), "A")
        ReadingRow(stringResource(R.string.bms_power), v?.let { (it.currentIc * it.voltageTotal).fixed(1) }, "W")
        ReadingRow(stringResource(R.string.bms_charge_voltage), v?.voltageCharge?.fixed(2), "V")
        ReadingRow(stringResource(R.string.bms_ah), v?.ampHours?.fixed(3), "Ah")
        ReadingRow(stringResource(R.string.bms_wh), v?.wattHours?.fixed(2), "Wh")

        SectionHeader(stringResource(R.string.bms_section_temps))
        v?.temps?.forEachIndexed { i, t ->
            ReadingRow(tempLabel(v, i), if (t <= BmsValues.NO_SENSOR) null else t.fixed(1), "°C")
        }
        ReadingRow(stringResource(R.string.bms_temp_cells_max), v?.tempCellsMax?.fixed(1), "°C")
        ReadingRow(stringResource(R.string.bms_temp_ic), v?.tempIc?.fixed(1), "°C")
        ReadingRow(stringResource(R.string.bms_humidity), v?.let { "${it.humidity.fixed(1)} % · ${it.tempHumiditySensor.fixed(1)}" }, "°C")
        state.external?.let { ReadingRow(stringResource(R.string.bms_external), "${it.humidity.fixed(1)} % · ${it.temperature.fixed(1)}", "°C") }
        ReadingRow(stringResource(R.string.bms_pressure), v?.pressure?.fixed(0), "Pa")

        SectionHeader(stringResource(R.string.bms_section_totals))
        ReadingRow(stringResource(R.string.bms_charged), v?.let { "${it.ampHoursChargedTotal.fixed(1)} Ah · ${it.wattHoursChargedTotal.fixed(0)}" }, "Wh")
        ReadingRow(stringResource(R.string.bms_discharged), v?.let { "${it.ampHoursDischargedTotal.fixed(1)} Ah · ${it.wattHoursDischargedTotal.fixed(0)}" }, "Wh")

        SectionHeader(stringResource(R.string.bms_section_commands))
        if (state.sent != null) {
            Text(
                stringResource(R.string.bms_sent, stringResource(state.sent.title())),
                Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                color = FocTheme.colors.textTertiary,
                style = FocTheme.type.caption,
            )
        }
        BmsCommand.entries.forEach { command ->
            Text(
                stringResource(command.title()),
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.row)
                    .clickable(role = Role.Button, enabled = v != null) { confirm = command }
                    .padding(horizontal = Spacing.x2, vertical = Spacing.x2),
                color = if (v != null) FocTheme.colors.text else FocTheme.colors.textTertiary,
                style = FocTheme.type.body,
            )
            Hairline(Modifier.padding(start = Spacing.x2))
        }
    }

    confirm?.let { command ->
        ConfirmDialog(
            title = stringResource(command.title()),
            body = stringResource(command.explanation()),
            confirm = stringResource(R.string.bms_send),
            dismiss = stringResource(R.string.bms_cancel),
            onConfirm = {
                confirm = null
                onCommand(command)
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun tempLabel(v: BmsValues, index: Int): String {
    val named = if (v.dataVersion == 1) VERSION_1_TEMPS.getOrNull(index) else null
    return if (named != null) stringResource(named) else stringResource(R.string.bms_temp_sensor, index + 1 - if (v.dataVersion == 1) VERSION_1_TEMPS.size else 0)
}

private fun BmsCommand.title(): Int = when (this) {
    BmsCommand.BalanceOn -> R.string.bms_balance_on
    BmsCommand.BalanceOff -> R.string.bms_balance_off
    BmsCommand.AllowCharging -> R.string.bms_charge_allow
    BmsCommand.DisableCharging -> R.string.bms_charge_disable
    BmsCommand.ResetAh -> R.string.bms_reset_ah
    BmsCommand.ResetWh -> R.string.bms_reset_wh
    BmsCommand.ZeroCurrent -> R.string.bms_zero_current
}

private fun BmsCommand.explanation(): Int = when (this) {
    BmsCommand.BalanceOn -> R.string.bms_balance_on_body
    BmsCommand.BalanceOff -> R.string.bms_balance_off_body
    BmsCommand.AllowCharging -> R.string.bms_charge_allow_body
    BmsCommand.DisableCharging -> R.string.bms_charge_disable_body
    BmsCommand.ResetAh -> R.string.bms_reset_ah_body
    BmsCommand.ResetWh -> R.string.bms_reset_wh_body
    BmsCommand.ZeroCurrent -> R.string.bms_zero_current_body
}

private val VERSION_1_TEMPS = listOf(R.string.bms_temp_ic, R.string.bms_temp_cell_min, R.string.bms_temp_cell_max, R.string.bms_temp_mosfet, R.string.bms_temp_ambient)

private const val POLL_MS = 200L
