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
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.BldcParams
import app.foctuner.vesc.HallSensors
import app.foctuner.vesc.detectBldc
import app.foctuner.vesc.updateConfig
import app.foctuner.vesc.withBldcParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class BldcDetectionState(
    val current: String = "5",
    val minErpm: String = "450",
    val lowDuty: String = "0.05",
    val params: BldcParams? = null,
    val applied: Boolean = false,
)

/** VESC Tool's "BLDC detection": sensorless integrator limit and BEMF coupling, plus the hall table. */
class BldcDetectionModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(BldcDetectionState())
    val state: StateFlow<BldcDetectionState> = _state.asStateFlow()
    val runner = SetupRunner(context)

    fun edit(transform: (BldcDetectionState) -> BldcDetectionState) = _state.update(transform)

    fun detect() {
        val s = _state.value
        val current = parseNumber(s.current) ?: return
        val erpm = parseNumber(s.minErpm) ?: return
        val duty = parseNumber(s.lowDuty) ?: return
        runner.start(spins = true) {
            val params = context.controller.detectBldc(current, erpm, duty)
            _state.update { it.copy(params = params, applied = false) }
        }
    }

    fun apply() {
        val params = _state.value.params?.takeIf { it.ok } ?: return
        runner.start(spins = false) {
            context.controller.updateConfig(ConfigKind.MCCONF, context.baseConfig(ConfigKind.MCCONF)) { it.withBldcParams(params) }
            _state.update { it.copy(applied = true) }
        }
    }
}

@Composable
fun BldcDetectionTool(context: ToolContext) {
    val model = context.model { BldcDetectionModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    val run by model.runner.state.collectAsStateWithLifecycle()
    val busy = run is RunState.Running
    var confirmDetect by remember { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        SectionHeader(stringResource(R.string.setup_measurement))
        NumberRow(stringResource(R.string.setup_current), state.current, "A", { v -> model.edit { it.copy(current = v) } }, !busy)
        NumberRow(stringResource(R.string.setup_min_erpm), state.minErpm, "ERPM", { v -> model.edit { it.copy(minErpm = v) } }, !busy)
        NumberRow(stringResource(R.string.setup_low_duty), state.lowDuty, "", { v -> model.edit { it.copy(lowDuty = v) } }, !busy)
        ActionRow {
            val valid = listOf(state.current, state.minErpm, state.lowDuty).all { parseNumber(it) != null }
            OutlineButton(stringResource(R.string.setup_detect), { confirmDetect = true }, enabled = !busy && valid)
        }
        RunStatus(run, stringResource(R.string.setup_measuring), model.runner::stop)
        state.params?.let { p ->
            SectionHeader(stringResource(R.string.setup_results))
            if (!p.ok) Notice(stringResource(R.string.setup_bldc_failed), tone = NoticeTone.Warning)
            if (p.bemfCouplingK > BEMF_COUPLING_SUSPICIOUS) Notice(stringResource(R.string.setup_bldc_coupling_high), tone = NoticeTone.Warning)
            ValueRow(stringResource(R.string.setup_cycle_int_limit), p.cycleIntLimit.fixed(2))
            ValueRow(stringResource(R.string.setup_bemf_coupling), p.bemfCouplingK.fixed(2))
            val halls = when (p.halls) {
                HallSensors.Detected -> R.string.setup_bldc_halls_detected
                HallSensors.Failed -> R.string.setup_bldc_halls_failed
                HallSensors.Ws2811Enabled -> R.string.setup_bldc_halls_ws2811
                HallSensors.EncoderEnabled -> R.string.setup_bldc_halls_encoder
                HallSensors.Unknown -> R.string.setup_bldc_halls_unknown
            }
            Text(stringResource(halls), Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
            if (p.halls == HallSensors.Detected) {
                Text(p.hallTable.joinToString(", "), Modifier.padding(horizontal = Spacing.x2), color = FocTheme.colors.text, style = FocTheme.type.value)
            }
            if (state.applied) Notice(stringResource(R.string.setup_written_motor))
            ActionRow { OutlineButton(stringResource(R.string.setup_apply_motor), { confirmApply = true }, enabled = !busy && p.ok) }
        }
    }
    if (confirmDetect) {
        SpinDialog(stringResource(R.string.setup_spin_warning), { confirmDetect = false; model.detect() }, { confirmDetect = false })
    }
    if (confirmApply) {
        ConfirmDialog(
            stringResource(R.string.setup_apply_motor),
            stringResource(R.string.setup_apply_bldc_body),
            stringResource(R.string.setup_write),
            { confirmApply = false; model.apply() },
            { confirmApply = false },
        )
    }
}

/** VESC Tool warns above this coupling: the motor probably never ran properly during detection. */
private const val BEMF_COUPLING_SUSPICIOUS = 900f
