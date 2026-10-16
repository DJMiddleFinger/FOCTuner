package app.foctuner.ui.tools.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.foctuner.ui.components.fixed
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.EncoderParams
import app.foctuner.vesc.detectEncoder
import app.foctuner.vesc.updateConfig
import app.foctuner.vesc.withEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class EncoderDetectionState(
    val current: String = "10",
    val encoder: EncoderParams? = null,
    val noEncoder: Boolean = false,
    val applied: Boolean = false,
)

/** VESC Tool's "FOC encoder detection": offset, ratio and direction of the configured encoder. */
class EncoderDetectionModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(EncoderDetectionState())
    val state: StateFlow<EncoderDetectionState> = _state.asStateFlow()
    val runner = SetupRunner(context)

    fun editCurrent(text: String) = _state.update { it.copy(current = text) }

    fun detect() {
        val current = parseNumber(_state.value.current) ?: return
        runner.start(spins = true) {
            val encoder = context.controller.detectEncoder(current)
            _state.update { it.copy(encoder = encoder, noEncoder = encoder == null, applied = false) }
        }
    }

    fun apply() {
        val encoder = _state.value.encoder ?: return
        runner.start(spins = false) {
            context.controller.updateConfig(ConfigKind.MCCONF, context.baseConfig(ConfigKind.MCCONF)) { it.withEncoder(encoder) }
            _state.update { it.copy(applied = true) }
        }
    }
}

@Composable
fun EncoderDetectionTool(context: ToolContext) {
    val model = context.model { EncoderDetectionModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    val run by model.runner.state.collectAsStateWithLifecycle()
    val busy = run is RunState.Running
    var confirmDetect by remember { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        SectionHeader(stringResource(R.string.setup_measurement))
        NumberRow(stringResource(R.string.setup_current), state.current, "A", model::editCurrent, !busy)
        ActionRow {
            OutlineButton(stringResource(R.string.setup_detect), { confirmDetect = true }, enabled = !busy && parseNumber(state.current) != null)
        }
        RunStatus(run, stringResource(R.string.setup_measuring_long), model.runner::stop)
        if (state.noEncoder) Notice(stringResource(R.string.setup_no_encoder), body = stringResource(R.string.setup_no_encoder_body), tone = NoticeTone.Warning)
        state.encoder?.let { enc ->
            SectionHeader(stringResource(R.string.setup_results))
            ValueRow(stringResource(R.string.setup_encoder_offset), enc.offset.fixed(1), "°")
            ValueRow(stringResource(R.string.setup_encoder_ratio), enc.ratio.fixed(1))
            ValueRow(stringResource(R.string.setup_encoder_inverted), stringResource(if (enc.inverted) R.string.setup_yes else R.string.setup_no))
            if (state.applied) Notice(stringResource(R.string.setup_written_motor))
            ActionRow { OutlineButton(stringResource(R.string.setup_apply_motor), { confirmApply = true }, enabled = !busy) }
        }
    }
    if (confirmDetect) {
        SpinDialog(stringResource(R.string.setup_slow_turn_warning), { confirmDetect = false; model.detect() }, { confirmDetect = false })
    }
    if (confirmApply) {
        ConfirmDialog(
            stringResource(R.string.setup_apply_motor),
            stringResource(R.string.setup_apply_encoder_body),
            stringResource(R.string.setup_write),
            { confirmApply = false; model.apply() },
            { confirmApply = false },
        )
    }
}
