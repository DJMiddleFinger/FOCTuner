package app.foctuner.ui.tools.psw

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.ui.tools.parts.ReadingRow
import app.foctuner.vesc.PowerSwitchStatus
import app.foctuner.vesc.VescException
import app.foctuner.vesc.powerSwitches
import app.foctuner.vesc.setPowerSwitch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class PowerSwitchState(val switches: List<PowerSwitchStatus>? = null, val failed: Boolean = false)

/** VESC power switches on the CAN bus, as the selected controller hears them, with their output switch. */
class PowerSwitchModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(PowerSwitchState())
    val state: StateFlow<PowerSwitchState> = _state.asStateFlow()

    fun set(id: Int, on: Boolean) {
        context.scope.launch {
            try {
                context.controller.setPowerSwitch(id, on)
            } catch (e: VescException) {
                _state.update { it.copy(failed = true) }
            }
        }
    }

    suspend fun refresh() {
        try {
            val switches = context.controller.powerSwitches()
            _state.update { it.copy(switches = switches, failed = false) }
        } catch (e: VescException) {
            _state.update { it.copy(failed = true) }
        }
    }
}

@Composable
fun PowerSwitchTool(context: ToolContext) {
    val model = context.model { PowerSwitchModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf<Pair<Int, Boolean>?>(null) }
    LaunchedEffect(model) {
        while (true) {
            model.refresh()
            delay(POLL_MS)
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (state.failed) Notice(stringResource(R.string.psw_no_reply), tone = NoticeTone.Warning)
        if (state.switches?.isEmpty() == true) {
            Notice(stringResource(R.string.psw_none), body = stringResource(R.string.psw_none_body))
        }
        state.switches?.forEach { s ->
            SectionHeader(stringResource(R.string.psw_title, s.id))
            ReadingRow(stringResource(R.string.psw_output), stringResource(if (s.outputOn) R.string.psw_on else R.string.psw_off))
            ReadingRow(stringResource(R.string.psw_voltage_in), s.voltageIn.fixed(2), "V")
            ReadingRow(stringResource(R.string.psw_voltage_out), s.voltageOut.fixed(2), "V")
            ReadingRow(stringResource(R.string.psw_temp), s.temp.fixed(1), "°C")
            ReadingRow(stringResource(R.string.psw_precharge), stringResource(if (s.prechargeOn) R.string.psw_on else R.string.psw_off))
            ReadingRow(stringResource(R.string.psw_discharge), stringResource(if (s.dischargeOn) R.string.psw_on else R.string.psw_off))
            ReadingRow(stringResource(R.string.psw_age), s.ageSeconds.fixed(2), "s")
            OutlineButton(
                stringResource(if (s.outputOn) R.string.psw_turn_off else R.string.psw_turn_on),
                { confirm = s.id to !s.outputOn },
                Modifier.padding(Spacing.x2),
            )
        }
    }
    confirm?.let { (id, on) ->
        ConfirmDialog(
            title = stringResource(if (on) R.string.psw_turn_on_title else R.string.psw_turn_off_title, id),
            body = stringResource(if (on) R.string.psw_turn_on_body else R.string.psw_turn_off_body),
            confirm = stringResource(if (on) R.string.psw_turn_on else R.string.psw_turn_off),
            dismiss = stringResource(R.string.psw_cancel),
            onConfirm = {
                confirm = null
                model.set(id, on)
            },
            onDismiss = { confirm = null },
        )
    }
}

private const val POLL_MS = 500L
