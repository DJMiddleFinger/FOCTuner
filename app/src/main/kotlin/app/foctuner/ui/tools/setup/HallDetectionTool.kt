package app.foctuner.ui.tools.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.foctuner.vesc.FocHallTable
import app.foctuner.vesc.detectFocHalls
import app.foctuner.vesc.updateConfig
import app.foctuner.vesc.withFocHallTable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

@Immutable
data class HallDetectionState(
    val current: String = "10",
    /** The configured sensor port's name when it isn't hall sensors (the firmware then can't detect). */
    val otherPort: String? = null,
    val table: FocHallTable? = null,
    val applied: Boolean = false,
)

/** VESC Tool's "FOC hall sensor detection": the angle of each hall state, written to the FOC hall table. */
class HallDetectionModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(HallDetectionState())
    val state: StateFlow<HallDetectionState> = _state.asStateFlow()
    val runner = SetupRunner(context)

    init {
        context.scope.launch {
            try {
                val config = context.controller.readConfig(ConfigKind.MCCONF, context.baseConfig(ConfigKind.MCCONF))
                val port = config.int("m_sensor_port_mode").toInt()
                if (port != 0) _state.update { it.copy(otherPort = config.definition.param("m_sensor_port_mode").enumNames.getOrNull(port)) }
            } catch (e: IOException) {
                // Detection reports the problem if there is one.
            }
        }
    }

    fun editCurrent(text: String) = _state.update { it.copy(current = text) }

    fun detect() {
        val current = parseNumber(_state.value.current) ?: return
        runner.start(spins = true) {
            val table = context.controller.detectFocHalls(current)
            _state.update { it.copy(table = table, applied = false) }
        }
    }

    fun apply() {
        val table = _state.value.table?.takeIf { it.ok } ?: return
        runner.start(spins = false) {
            context.controller.updateConfig(ConfigKind.MCCONF, context.baseConfig(ConfigKind.MCCONF)) { it.withFocHallTable(table) }
            _state.update { it.copy(applied = true) }
        }
    }
}

@Composable
fun HallDetectionTool(context: ToolContext) {
    val model = context.model { HallDetectionModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    val run by model.runner.state.collectAsStateWithLifecycle()
    val busy = run is RunState.Running
    var confirmDetect by remember { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        state.otherPort?.let { Notice(stringResource(R.string.setup_hall_port, it), body = stringResource(R.string.setup_hall_port_body), tone = NoticeTone.Warning) }
        SectionHeader(stringResource(R.string.setup_measurement))
        NumberRow(stringResource(R.string.setup_current), state.current, "A", model::editCurrent, !busy)
        ActionRow {
            OutlineButton(stringResource(R.string.setup_detect), { confirmDetect = true }, enabled = !busy && parseNumber(state.current) != null)
        }
        RunStatus(run, stringResource(R.string.setup_measuring), model.runner::stop)
        val table = state.table
        if (table != null) {
            SectionHeader(stringResource(R.string.setup_hall_table))
            if (!table.ok) Notice(stringResource(R.string.setup_hall_failed), body = stringResource(R.string.setup_hall_failed_body), tone = NoticeTone.Warning)
            Text(table.table.joinToString(", "), Modifier.padding(horizontal = Spacing.x2), color = FocTheme.colors.text, style = FocTheme.type.value)
            if (state.applied) Notice(stringResource(R.string.setup_written_motor))
            ActionRow {
                OutlineButton(stringResource(R.string.setup_apply_motor), { confirmApply = true }, enabled = !busy && table.ok)
            }
        }
    }
    if (confirmDetect) {
        SpinDialog(stringResource(R.string.setup_slow_turn_warning), { confirmDetect = false; model.detect() }, { confirmDetect = false })
    }
    if (confirmApply) {
        ConfirmDialog(
            stringResource(R.string.setup_apply_motor),
            stringResource(R.string.setup_apply_hall_body),
            stringResource(R.string.setup_write),
            { confirmApply = false; model.apply() },
            { confirmApply = false },
        )
    }
}
