package app.foctuner.ui.tools.canbus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import app.foctuner.config.ConfigKind
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.ChoiceRow
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.ui.tools.parts.ReadingRow
import app.foctuner.ui.tools.setup.baseConfig
import app.foctuner.vesc.CanBaud
import app.foctuner.vesc.CanMode
import app.foctuner.vesc.setCanMode
import app.foctuner.vesc.updateCanBaudAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

@Immutable
data class CanBusState(
    val baud: CanBaud? = null,
    val mode: CanMode? = null,
    val busy: Boolean = false,
    /** String resource of the last change's outcome. */
    val result: Int? = null,
    val failed: String? = null,
)

/** The selected controller's CAN bit rate and mode (appconf `can_baud_rate`, `can_mode`) and changing them. */
class CanBusModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(CanBusState())
    val state: StateFlow<CanBusState> = _state.asStateFlow()

    suspend fun read() {
        try {
            val app = context.controller.readConfig(ConfigKind.APPCONF, context.baseConfig(ConfigKind.APPCONF))
            _state.update {
                it.copy(
                    baud = CanBaud.fromAppconf(app.int("can_baud_rate").toInt()),
                    mode = CanMode.fromValue(app.int("can_mode").toInt()),
                    failed = null,
                )
            }
        } catch (e: IOException) {
            _state.update { it.copy(failed = e.message) }
        }
    }

    fun setBaud(baud: CanBaud) = run {
        val ok = context.controller.updateCanBaudAll(baud)
        // The firmware stores the new rate after its switch delay; read once it has.
        delay(BAUD_SETTLE_MS)
        read()
        if (ok) R.string.canbus_baud_done else R.string.canbus_baud_refused
    }

    fun setMode(mode: CanMode) = run {
        context.controller.setCanMode(mode, store = true)
        read()
        R.string.canbus_mode_done
    }

    private fun run(block: suspend () -> Int) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, result = null, failed = null) }
        context.scope.launch {
            try {
                val message = block()
                _state.update { it.copy(busy = false, result = message) }
            } catch (e: IOException) {
                _state.update { it.copy(busy = false, failed = e.message) }
            }
        }
    }
}

@Composable
fun CanBusTool(context: ToolContext) {
    val model = context.model { CanBusModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    var baudChoice by rememberSaveable { mutableStateOf<CanBaud?>(null) }
    var modeChoice by rememberSaveable { mutableStateOf<CanMode?>(null) }
    var confirmBaud by rememberSaveable { mutableStateOf(false) }
    var confirmMode by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(model) { model.read() }
    val selectedBaud = baudChoice ?: state.baud
    val selectedMode = modeChoice ?: state.mode
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SectionHeader(stringResource(R.string.canbus_baud_section))
        ReadingRow(stringResource(R.string.canbus_baud_now), state.baud?.let { baudLabel(it) })
        Text(
            stringResource(R.string.canbus_baud_intro),
            Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
            style = FocTheme.type.supporting,
            color = FocTheme.colors.textSecondary,
        )
        if (selectedBaud != null) {
            ChoiceRow(CanBaud.entries, selectedBaud, { baudLabel(it) }, { baudChoice = it }, enabled = !state.busy)
        }
        OutlineButton(
            stringResource(R.string.canbus_baud_apply),
            { confirmBaud = true },
            Modifier.padding(Spacing.x2),
            enabled = !state.busy && selectedBaud != null && selectedBaud != state.baud,
        )

        SectionHeader(stringResource(R.string.canbus_mode_section))
        ReadingRow(stringResource(R.string.canbus_mode_now), state.mode?.let { modeLabel(it) })
        if (selectedMode != null) {
            ChoiceRow(CanMode.entries, selectedMode, { modeLabel(it) }, { modeChoice = it }, enabled = !state.busy)
        }
        if (selectedMode != null && !selectedMode.forwardsVescCommands) {
            Notice(stringResource(R.string.canbus_mode_no_forward), body = stringResource(R.string.canbus_mode_no_forward_body), tone = NoticeTone.Warning)
        }
        OutlineButton(
            stringResource(R.string.canbus_mode_apply),
            { confirmMode = true },
            Modifier.padding(Spacing.x2),
            enabled = !state.busy && selectedMode != null && selectedMode != state.mode,
        )
        state.result?.let { Notice(stringResource(it)) }
        state.failed?.let { Notice(stringResource(R.string.canbus_failed), body = it, tone = NoticeTone.Fault) }
    }
    if (confirmBaud && selectedBaud != null) {
        ConfirmDialog(
            title = stringResource(R.string.canbus_baud_confirm_title, baudLabel(selectedBaud)),
            body = stringResource(R.string.canbus_baud_confirm_body),
            confirm = stringResource(R.string.canbus_baud_apply),
            dismiss = stringResource(R.string.canbus_cancel),
            onConfirm = {
                confirmBaud = false
                baudChoice = null
                model.setBaud(selectedBaud)
            },
            onDismiss = { confirmBaud = false },
        )
    }
    if (confirmMode && selectedMode != null) {
        ConfirmDialog(
            title = stringResource(R.string.canbus_mode_confirm_title, modeLabel(selectedMode)),
            body = stringResource(
                if (selectedMode.forwardsVescCommands) R.string.canbus_mode_confirm_body else R.string.canbus_mode_confirm_body_no_forward,
            ),
            confirm = stringResource(R.string.canbus_mode_apply),
            dismiss = stringResource(R.string.canbus_cancel),
            onConfirm = {
                confirmMode = false
                modeChoice = null
                model.setMode(selectedMode)
            },
            onDismiss = { confirmMode = false },
        )
    }
}

@Composable
private fun baudLabel(baud: CanBaud): String =
    if (baud == CanBaud.M1) stringResource(R.string.canbus_mbit, 1) else stringResource(R.string.canbus_kbit, baud.kbits)

@Composable
private fun modeLabel(mode: CanMode): String = stringResource(
    when (mode) {
        CanMode.Vesc -> R.string.canbus_mode_vesc
        CanMode.Uavcan -> R.string.canbus_mode_uavcan
        CanMode.CommBridge -> R.string.canbus_mode_bridge
        CanMode.Unused -> R.string.canbus_mode_unused
        CanMode.VescUavcan -> R.string.canbus_mode_vesc_uavcan
    },
)

private const val BAUD_SETTLE_MS = 1500L
