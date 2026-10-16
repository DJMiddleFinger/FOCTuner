package app.foctuner.ui.tools.setup

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.config.ParamValue
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** Input types of VESC Tool's input wizard, with the `app_to_use` each selects and the settings its setup page shows. */
enum class InputType(@param:StringRes val title: Int, val app: Long, val mapping: MappedInput?, val settings: List<String>) {
    Ppm(
        R.string.setup_input_ppm, 4, MappedInput.Ppm,
        listOf("ctrl_type", "median_filter", "safe_start", "ramp_time_pos", "ramp_time_neg", "pid_max_erpm", "max_erpm_for_dir", "smart_rev_max_duty", "smart_rev_ramp_time", "tc", "tc_max_diff")
            .map { "app_ppm_conf.$it" },
    ),
    Nrf(R.string.setup_input_nrf, 3, null, CHUK_SETTINGS),
    Adc(
        R.string.setup_input_adc, 5, MappedInput.Adc,
        listOf("ctrl_type", "use_filter", "safe_start", "cc_button_inverted", "rev_button_inverted", "ramp_time_pos", "ramp_time_neg", "tc", "tc_max_diff")
            .map { "app_adc_conf.$it" },
    ),
    Nunchuk(R.string.setup_input_nunchuk, 6, null, CHUK_SETTINGS),
}

private val CHUK_SETTINGS = listOf(
    "ctrl_type", "ramp_time_pos", "ramp_time_neg", "stick_erpm_per_s_in_cc", "hyst", "use_smart_rev", "smart_rev_max_duty", "smart_rev_ramp_time", "tc", "tc_max_diff",
).map { "app_chuk_conf.$it" }

enum class InputStep { Connection, Type, Mapping, Setup }

@Immutable
data class InputWizardState(
    val step: InputStep = InputStep.Connection,
    val targets: List<SetupTarget>? = null,
    val target: SetupTarget? = null,
    val type: InputType = InputType.Ppm,
    /** The chosen controller's app config while the setup page edits it. */
    val app: Config? = null,
    val finished: Boolean = false,
)

/** VESC Tool's input wizard (mobile SetupWizardInput): which controller, which input, mapping, then its settings. */
class InputWizardModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(InputWizardState())
    val state: StateFlow<InputWizardState> = _state.asStateFlow()
    val runner = SetupRunner(context)
    val ppm = InputMappingModel(context, MappedInput.Ppm)
    val adc = InputMappingModel(context, MappedInput.Adc)

    fun scan() = runner.start(spins = false) {
        val targets = context.scanTargets()
        _state.update { it.copy(targets = targets, target = targets.firstOrNull()) }
    }

    fun select(target: SetupTarget) = _state.update { it.copy(target = target) }

    fun selectType(type: InputType) = _state.update { it.copy(type = type) }

    /**
     * Like VESC Tool's resetInputCan: every controller's app config goes back to defaults except its CAN id and
     * status messages, so no other controller keeps reading an old input.
     */
    fun toType() = runner.start(spins = false) {
        val base = context.baseConfig(ConfigKind.APPCONF)
        for (target in _state.value.targets.orEmpty()) {
            val current = target.controller.readConfig(ConfigKind.APPCONF, base)
            val defaults = target.controller.readDefaultConfig(ConfigKind.APPCONF, base)
            val kept = listOf("controller_id", "can_status_msgs_r1", "can_status_msgs_r2").fold(defaults) { c, name -> c.with(name, current[name]) }
            target.controller.writeConfig(ConfigKind.APPCONF, kept)
        }
        _state.update { it.copy(step = InputStep.Type) }
    }

    /** Selects the input's app with its control off, so the motor stays still while the throttle is moved. */
    fun toMapping() {
        val s = _state.value
        val target = s.target ?: return
        runner.start(spins = false) {
            write(target) { c ->
                listOf("app_ppm_conf.multi_esc", "app_adc_conf.multi_esc", "app_chuk_conf.multi_esc").fold(c) { x, name -> x.with(name, true) }
                    .with("app_ppm_conf.ctrl_type", 0L)
                    .with("app_adc_conf.ctrl_type", 0L)
                    .with("app_to_use", s.type.app)
            }
            if (s.type.mapping == null) {
                toSetup()
            } else {
                _state.update { it.copy(step = InputStep.Mapping) }
                context.scope.launch {
                    // VESC Tool clears the capture once the new app has started reading.
                    delay(MAPPING_SETTLE)
                    ppm.reset()
                    adc.reset()
                }
            }
        }
    }

    fun next() = runner.start(spins = false) { toSetup() }

    /** Stores a valid PPM capture, turns the control back on (VESC Tool's defaults) and opens the settings. */
    private suspend fun toSetup() {
        val target = _state.value.target ?: return
        if (_state.value.type == InputType.Ppm && ppm.state.value.valid) ppm.store(target.controller)
        val stored = write(target) { it.with("app_ppm_conf.ctrl_type", PPM_CURRENT_NO_REVERSE_BRAKE).with("app_adc_conf.ctrl_type", ADC_CURRENT) }
        _state.update { it.copy(step = InputStep.Setup, app = stored) }
    }

    fun set(name: String, value: ParamValue) = _state.update { s -> s.copy(app = s.app?.with(name, value), finished = false) }

    fun finish() {
        val target = _state.value.target ?: return
        val app = _state.value.app ?: return
        runner.start(spins = false) {
            target.controller.writeConfig(ConfigKind.APPCONF, app)
            val stored = target.controller.readConfig(ConfigKind.APPCONF, app)
            _state.update { it.copy(app = stored, finished = true) }
        }
    }

    fun back() = when (_state.value.step) {
        InputStep.Connection -> Unit
        InputStep.Type -> _state.update { it.copy(step = InputStep.Connection) }
        InputStep.Mapping -> _state.update { it.copy(step = InputStep.Type) }
        InputStep.Setup -> if (_state.value.type.mapping != null) toMapping() else _state.update { it.copy(step = InputStep.Type) }
    }

    private suspend fun write(target: SetupTarget, transform: (Config) -> Config): Config {
        val base = context.baseConfig(ConfigKind.APPCONF)
        target.controller.writeConfig(ConfigKind.APPCONF, transform(target.controller.readConfig(ConfigKind.APPCONF, base)))
        return target.controller.readConfig(ConfigKind.APPCONF, base)
    }

    private companion object {
        val MAPPING_SETTLE = 1500.milliseconds
        const val PPM_CURRENT_NO_REVERSE_BRAKE = 3L
        const val ADC_CURRENT = 1L
    }
}

@Composable
fun InputWizardTool(context: ToolContext) {
    val model = context.model { InputWizardModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    val run by model.runner.state.collectAsStateWithLifecycle()
    val busy = run is RunState.Running
    LaunchedEffect(model) { if (model.state.value.targets == null && !model.runner.busy) model.scan() }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            when (state.step) {
                InputStep.Connection -> {
                    SectionHeader(stringResource(R.string.setup_input_which))
                    state.targets.orEmpty().forEach { target ->
                        Option(targetName(target), target == state.target, !busy) { model.select(target) }
                    }
                    if (state.targets?.isNotEmpty() == true) Text(stringResource(R.string.setup_input_reset_note), Modifier.padding(Spacing.x2), color = FocTheme.colors.textTertiary, style = FocTheme.type.caption)
                }
                InputStep.Type -> {
                    SectionHeader(stringResource(R.string.setup_input_type))
                    InputType.entries.forEach { type -> Option(stringResource(type.title), type == state.type, !busy) { model.selectType(type) } }
                    if (state.type == InputType.Nrf) Notice(stringResource(R.string.setup_nrf_pairing), body = stringResource(R.string.setup_nrf_pairing_body))
                    if (state.type == InputType.Nunchuk) Notice(stringResource(R.string.setup_nunchuk_uart))
                }
                InputStep.Mapping -> {
                    val target = state.target
                    val mapping = if (state.type.mapping == MappedInput.Adc) model.adc else model.ppm
                    if (target != null) MappingPanel(mapping, target.controller)
                }
                InputStep.Setup -> {
                    val app = state.app
                    if (app != null) {
                        SectionHeader(stringResource(R.string.setup_input_settings))
                        state.type.settings.forEach { SettingRow(app, it, { v -> model.set(it, v) }, !busy) }
                    }
                    if (state.finished) Notice(stringResource(R.string.setup_written_app))
                }
            }
            RunStatus(run, stringResource(R.string.setup_working), model.runner::stop)
        }
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(Spacing.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.step != InputStep.Connection) PlainButton(stringResource(R.string.setup_back), model::back, enabled = !busy)
            Spacer(Modifier.weight(1f))
            when (state.step) {
                InputStep.Connection -> OutlineButton(stringResource(R.string.setup_next), model::toType, enabled = !busy && state.target != null)
                InputStep.Type -> OutlineButton(stringResource(R.string.setup_next), model::toMapping, enabled = !busy)
                InputStep.Mapping -> OutlineButton(stringResource(R.string.setup_next), model::next, enabled = !busy)
                InputStep.Setup -> OutlineButton(stringResource(R.string.setup_finish), model::finish, enabled = !busy && state.app != null)
            }
        }
    }
}

@Composable
private fun Option(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val colors = FocTheme.colors
    Text(
        label,
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.row)
            .background(if (selected) colors.surface else colors.background)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(Spacing.x2),
        color = if (selected) colors.accent else colors.text,
        style = if (selected) FocTheme.type.bodyStrong else FocTheme.type.body,
    )
    Hairline(Modifier.padding(start = Spacing.x2))
}
