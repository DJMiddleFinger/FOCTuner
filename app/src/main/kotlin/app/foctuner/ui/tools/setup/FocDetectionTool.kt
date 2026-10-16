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
import app.foctuner.ui.components.NO_VALUE
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.FluxLinkage
import app.foctuner.vesc.FocGains
import app.foctuner.vesc.ResistanceInductance
import app.foctuner.vesc.focGains
import app.foctuner.vesc.measureFluxLinkage
import app.foctuner.vesc.measureResistanceInductance
import app.foctuner.vesc.updateConfig
import app.foctuner.vesc.withEncoder
import app.foctuner.vesc.withMotorParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

@Immutable
data class FocDetectionState(
    val current: String = "",
    val duty: String = "0.3",
    val erpmPerSecond: String = "2000",
    val timeConstant: String = "1000",
    val rl: ResistanceInductance? = null,
    val linkage: FluxLinkage? = null,
    val motorTemp: Float? = null,
    val applied: Boolean = false,
) {
    val gains: FocGains?
        get() {
            val tc = parseNumber(timeConstant)?.takeIf { it > 0f } ?: return null
            val rl = rl?.takeIf { it.ok } ?: return null
            val linkage = linkage?.takeIf { it.ok } ?: return null
            return focGains(rl.resistance, rl.inductance, linkage.linkage, tc)
        }
}

/** VESC Tool's "FOC detection" page: R and L, flux linkage, the gains that follow, and writing them. */
class FocDetectionModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(FocDetectionState())
    val state: StateFlow<FocDetectionState> = _state.asStateFlow()
    val runner = SetupRunner(context)

    init {
        context.scope.launch {
            try {
                val limit = context.controller.readConfig(ConfigKind.MCCONF, context.baseConfig(ConfigKind.MCCONF)).double("l_current_max")
                _state.update { if (it.current.isEmpty()) it.copy(current = (limit / 3).toFloat().fixed(1)) else it }
            } catch (e: IOException) {
                // The current stays empty for the user to fill in.
            }
        }
    }

    fun edit(transform: (FocDetectionState) -> FocDetectionState) = _state.update { transform(it).copy(applied = false) }

    fun measureResistanceInductance() = runner.start(spins = true) {
        val rl = context.controller.measureResistanceInductance()
        val temp = context.controller.values().tempMotor.takeIf { it > 1f }
        _state.update { it.copy(rl = rl, motorTemp = temp, applied = false) }
    }

    fun measureFluxLinkage() {
        val s = _state.value
        val rl = s.rl ?: return
        val current = parseNumber(s.current) ?: return
        val duty = parseNumber(s.duty) ?: return
        val erpm = parseNumber(s.erpmPerSecond) ?: return
        runner.start(spins = true) {
            val linkage = context.controller.measureFluxLinkage(current, erpm, duty, rl.resistance, rl.inductance)
            _state.update { it.copy(linkage = linkage, applied = false) }
        }
    }

    fun apply() {
        val s = _state.value
        val rl = s.rl ?: return
        val linkage = s.linkage ?: return
        val gains = s.gains ?: return
        runner.start(spins = false) {
            context.controller.updateConfig(ConfigKind.MCCONF, context.baseConfig(ConfigKind.MCCONF)) { config ->
                val c = config.withMotorParameters(rl, linkage.linkage, gains, s.motorTemp)
                linkage.encoder?.let(c::withEncoder) ?: c
            }
            _state.update { it.copy(applied = true) }
        }
    }
}

private enum class FocAction { ResistanceInductance, FluxLinkage, Apply }

@Composable
fun FocDetectionTool(context: ToolContext) {
    val model = context.model { FocDetectionModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    val run by model.runner.state.collectAsStateWithLifecycle()
    val busy = run is RunState.Running
    var confirm by remember { mutableStateOf<FocAction?>(null) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        SectionHeader(stringResource(R.string.setup_measurement))
        NumberRow(stringResource(R.string.setup_current), state.current, "A", { v -> model.edit { it.copy(current = v) } }, !busy)
        NumberRow(stringResource(R.string.setup_duty), state.duty, "", { v -> model.edit { it.copy(duty = v) } }, !busy)
        NumberRow(stringResource(R.string.setup_erpm_per_second), state.erpmPerSecond, "ERPM/s", { v -> model.edit { it.copy(erpmPerSecond = v) } }, !busy)
        NumberRow(stringResource(R.string.setup_time_constant), state.timeConstant, "µs", { v -> model.edit { it.copy(timeConstant = v) } }, !busy)
        ActionRow {
            OutlineButton(stringResource(R.string.setup_measure_rl), { confirm = FocAction.ResistanceInductance }, enabled = !busy)
            OutlineButton(
                stringResource(R.string.setup_measure_linkage),
                { confirm = FocAction.FluxLinkage },
                enabled = !busy && state.rl?.ok == true && parseNumber(state.current) != null,
            )
        }
        RunStatus(run, stringResource(R.string.setup_measuring), model.runner::stop)

        SectionHeader(stringResource(R.string.setup_results))
        val rl = state.rl
        if (rl != null && !rl.ok) Notice(stringResource(R.string.setup_rl_failed), tone = NoticeTone.Warning)
        if (state.linkage?.ok == false) {
            Notice(stringResource(R.string.setup_linkage_failed), body = stringResource(R.string.setup_linkage_failed_body), tone = NoticeTone.Warning)
        }
        ValueRow(stringResource(R.string.setup_resistance), rl?.let { (it.resistance * 1e3f).fixed(2) } ?: NO_VALUE, "mΩ")
        ValueRow(stringResource(R.string.setup_inductance), rl?.let { (it.inductance * 1e6f).fixed(2) } ?: NO_VALUE, "µH")
        ValueRow(stringResource(R.string.setup_ld_lq_diff), rl?.let { (it.ldLqDiff * 1e6f).fixed(2) } ?: NO_VALUE, "µH")
        ValueRow(stringResource(R.string.setup_flux_linkage), state.linkage?.let { (it.linkage * 1e3f).fixed(3) } ?: NO_VALUE, "mWb")
        val gains = state.gains
        ValueRow(stringResource(R.string.setup_kp), gains?.kp?.fixed(4) ?: NO_VALUE)
        ValueRow(stringResource(R.string.setup_ki), gains?.ki?.fixed(2) ?: NO_VALUE)
        ValueRow(stringResource(R.string.setup_observer_gain), gains?.observerGain?.fixed(2) ?: NO_VALUE)
        state.linkage?.encoder?.let { enc ->
            ValueRow(stringResource(R.string.setup_encoder_offset), enc.offset.fixed(1), "°")
            ValueRow(stringResource(R.string.setup_encoder_ratio), enc.ratio.fixed(1))
            ValueRow(stringResource(R.string.setup_encoder_inverted), stringResource(if (enc.inverted) R.string.setup_yes else R.string.setup_no))
        }
        if (state.applied) Notice(stringResource(R.string.setup_written_motor))
        ActionRow {
            OutlineButton(stringResource(R.string.setup_apply_motor), { confirm = FocAction.Apply }, enabled = !busy && gains != null)
        }
    }

    when (confirm) {
        FocAction.ResistanceInductance -> ConfirmDialog(
            stringResource(R.string.setup_measure_rl),
            stringResource(R.string.setup_rl_warning),
            stringResource(R.string.setup_start),
            { confirm = null; model.measureResistanceInductance() },
            { confirm = null },
        )
        FocAction.FluxLinkage -> SpinDialog(
            stringResource(if (parseNumber(state.current) == 0f) R.string.setup_linkage_passive_warning else R.string.setup_linkage_warning),
            { confirm = null; model.measureFluxLinkage() },
            { confirm = null },
        )
        FocAction.Apply -> ConfirmDialog(
            stringResource(R.string.setup_apply_motor),
            stringResource(R.string.setup_apply_foc_body),
            stringResource(R.string.setup_write),
            { confirm = null; model.apply() },
            { confirm = null },
        )
        null -> Unit
    }
}
