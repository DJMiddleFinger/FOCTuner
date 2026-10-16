package app.foctuner.ui.tools.setup

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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.overview.faultStrings
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.FocDetectionFailure

private enum class WizardDialog { RestoreDefaults, MotorSize, NoBatteryLimits, Detect }

@Composable
fun FocWizardTool(context: ToolContext) {
    val model = context.model { FocWizardModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    val run by model.runner.state.collectAsStateWithLifecycle()
    val busy = run is RunState.Running
    var dialog by remember { mutableStateOf<WizardDialog?>(null) }
    LaunchedEffect(model) { if (model.state.value.targets == null && !model.runner.busy) model.scan() }

    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.setup_wizard_step, state.step.ordinal + 1, WizardStep.entries.size, stringResource(stepTitle(state.step))),
            Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
            color = FocTheme.colors.textSecondary,
            style = FocTheme.type.label,
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            when (state.step) {
                WizardStep.Prepare -> PreparePage(state, busy) { dialog = WizardDialog.RestoreDefaults }
                WizardStep.Usage -> UsagePage(state, model, busy)
                WizardStep.Motor -> MotorPage(state, model, busy)
                WizardStep.Battery -> BatteryPage(state, model, busy)
                WizardStep.Setup -> SetupPage(state, model, busy)
                WizardStep.Detect -> DetectPage(state, model, busy) { dialog = WizardDialog.Detect }
                WizardStep.Result -> ResultPage(state)
                WizardStep.Direction -> DirectionPanel(context, state.targets.orEmpty())
            }
            RunStatus(run, stringResource(if (state.step == WizardStep.Detect) R.string.setup_detecting_all else R.string.setup_working), model.runner::stop)
        }
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .background(FocTheme.colors.background)
                .padding(horizontal = Spacing.x1, vertical = Spacing.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val back = previous(state.step)
            if (back != null) PlainButton(stringResource(R.string.setup_back), { model.go(back) }, enabled = !busy)
            Spacer(Modifier.weight(1f))
            val canGoOn = !busy && state.config != null
            when (state.step) {
                WizardStep.Prepare -> OutlineButton(stringResource(R.string.setup_next), { model.go(WizardStep.Usage) }, enabled = canGoOn)
                WizardStep.Usage -> OutlineButton(stringResource(R.string.setup_next), { model.go(WizardStep.Motor) }, enabled = canGoOn)
                WizardStep.Motor -> OutlineButton(stringResource(R.string.setup_next), { dialog = WizardDialog.MotorSize }, enabled = canGoOn)
                WizardStep.Battery -> OutlineButton(
                    stringResource(R.string.setup_next),
                    { if (state.batteryLimits) model.go(WizardStep.Setup) else dialog = WizardDialog.NoBatteryLimits },
                    enabled = canGoOn,
                )
                WizardStep.Setup -> OutlineButton(stringResource(R.string.setup_next), { model.go(WizardStep.Detect) }, enabled = canGoOn && state.gearRatio != null)
                WizardStep.Detect -> Unit
                WizardStep.Result -> if (state.result?.ok == true) {
                    OutlineButton(stringResource(R.string.setup_next), { model.go(WizardStep.Direction) }, enabled = !busy)
                }
                WizardStep.Direction -> Unit
            }
        }
    }

    when (dialog) {
        WizardDialog.RestoreDefaults -> ConfirmDialog(
            stringResource(R.string.setup_restore_defaults),
            stringResource(R.string.setup_restore_defaults_body, state.targets.orEmpty().size),
            stringResource(R.string.setup_restore),
            { dialog = null; model.restoreDefaults() },
            { dialog = null },
        )
        WizardDialog.MotorSize -> ConfirmDialog(
            stringResource(R.string.setup_motor_size_title),
            stringResource(R.string.setup_motor_size_body),
            stringResource(R.string.setup_motor_size_confirm),
            { dialog = null; model.confirmMotor() },
            { dialog = null },
        )
        WizardDialog.NoBatteryLimits -> ConfirmDialog(
            stringResource(R.string.setup_no_battery_limits_title),
            stringResource(R.string.setup_no_battery_limits_body),
            stringResource(R.string.setup_continue),
            { dialog = null; model.go(WizardStep.Setup) },
            { dialog = null },
        )
        WizardDialog.Detect -> SpinDialog(
            stringResource(if (state.detectCan && state.targets.orEmpty().size > 1) R.string.setup_detect_all_warning_can else R.string.setup_detect_all_warning),
            { dialog = null; model.detect() },
            { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun PreparePage(state: FocWizardState, busy: Boolean, onRestore: () -> Unit) {
    Text(stringResource(R.string.setup_wizard_intro), Modifier.padding(Spacing.x2), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
    val targets = state.targets ?: return
    if (targets.isEmpty()) {
        Notice(stringResource(R.string.setup_no_controllers), body = stringResource(R.string.setup_no_controllers_body), tone = NoticeTone.Warning)
        return
    }
    SectionHeader(stringResource(R.string.setup_controllers_found))
    targets.forEach { ValueRow(targetName(it), it.hardwareName) }
    if (state.defaultsRestored) Notice(stringResource(R.string.setup_defaults_restored))
    Text(stringResource(R.string.setup_restore_defaults_hint), Modifier.padding(Spacing.x2), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
    ActionRow { OutlineButton(stringResource(R.string.setup_restore_defaults), onRestore, enabled = !busy) }
}

@Composable
private fun UsagePage(state: FocWizardState, model: FocWizardModel, busy: Boolean) {
    SectionHeader(stringResource(R.string.setup_usage_question))
    Choices(UsagePreset.entries, state.usage, { stringResource(it.title) }, model::selectUsage, !busy)
    SwitchRow(stringResource(R.string.setup_override), state.overrideUsage, { on -> model.edit { it.copy(overrideUsage = on) } }, !busy)
    val config = state.config ?: return
    if (state.overrideUsage) {
        listOf("bms.limit_mode", "l_duty_start", "m_fault_stop_time_ms").forEach { SettingRow(config, it, { v -> model.set(it, v) }, !busy) }
    }
}

@Composable
private fun MotorPage(state: FocWizardState, model: FocWizardModel, busy: Boolean) {
    SectionHeader(stringResource(R.string.setup_motor_question))
    Choices(MotorPreset.entries, state.motor, { stringResource(it.title) }, model::selectMotor, !busy)
    SwitchRow(stringResource(R.string.setup_override), state.overrideMotor, { on ->
        if (!on) model.selectMotor(state.motor)
        model.edit { it.copy(overrideMotor = on) }
    }, !busy)
    val editable = state.overrideMotor && !busy
    NumberRow(stringResource(R.string.setup_max_power_loss), state.maxPowerLoss, "W", { v -> model.edit { it.copy(maxPowerLoss = v) } }, editable)
    NumberRow(stringResource(R.string.setup_openloop_erpm), state.openloopErpm, "ERPM", { v -> model.edit { it.copy(openloopErpm = v) } }, editable)
    NumberRow(stringResource(R.string.setup_sensorless_erpm), state.sensorlessErpm, "ERPM", { v -> model.edit { it.copy(sensorlessErpm = v) } }, editable)
    state.config?.let { SettingRow(it, "si_motor_poles", { v -> model.set("si_motor_poles", v) }, editable) }
}

@Composable
private fun BatteryPage(state: FocWizardState, model: FocWizardModel, busy: Boolean) {
    val config = state.config ?: return
    SectionHeader(stringResource(R.string.setup_battery))
    listOf("si_battery_type", "si_battery_cells", "si_battery_ah").forEach { SettingRow(config, it, { v -> model.set(it, v) }, !busy) }
    SwitchRow(stringResource(R.string.setup_battery_limits), state.batteryLimits, { on ->
        model.edit { it.copy(batteryLimits = on, currentInMin = if (on) it.currentInMin else "0", currentInMax = if (on) it.currentInMax else "0") }
    }, !busy)
    if (state.batteryLimits) {
        NumberRow(stringResource(R.string.setup_battery_regen), state.currentInMin, "A", { v -> model.edit { it.copy(currentInMin = v) } }, !busy)
        NumberRow(stringResource(R.string.setup_battery_max), state.currentInMax, "A", { v -> model.edit { it.copy(currentInMax = v) } }, !busy)
        Text(stringResource(R.string.setup_battery_limits_hint), Modifier.padding(Spacing.x2), color = FocTheme.colors.textTertiary, style = FocTheme.type.caption)
    }
}

@Composable
private fun SetupPage(state: FocWizardState, model: FocWizardModel, busy: Boolean) {
    val config = state.config ?: return
    SectionHeader(stringResource(R.string.setup_gearing))
    SwitchRow(stringResource(R.string.setup_direct_drive), state.directDrive, { on -> model.edit { it.copy(directDrive = on) } }, !busy)
    if (!state.directDrive) {
        NumberRow(stringResource(R.string.setup_motor_pulley), state.motorPulley, "", { v -> model.edit { it.copy(motorPulley = v) } }, !busy)
        NumberRow(stringResource(R.string.setup_wheel_pulley), state.wheelPulley, "", { v -> model.edit { it.copy(wheelPulley = v) } }, !busy)
    }
    ValueRow(stringResource(R.string.setup_gear_ratio), state.gearRatio?.fixed(2) ?: "—")
    SettingRow(config, "si_wheel_diameter", { v -> model.set("si_wheel_diameter", v) }, !busy)
    SectionHeader(stringResource(R.string.setup_only_if_needed))
    listOf("si_motor_poles", "m_motor_temp_sens_type", "m_ntc_motor_beta").forEach { SettingRow(config, it, { v -> model.set(it, v) }, !busy) }
}

@Composable
private fun DetectPage(state: FocWizardState, model: FocWizardModel, busy: Boolean, onStart: () -> Unit) {
    Notice(stringResource(R.string.setup_detect_all_title), body = stringResource(R.string.setup_detect_all_body), tone = NoticeTone.Warning)
    if (state.targets.orEmpty().size > 1) {
        SwitchRow(stringResource(R.string.setup_detect_can), state.detectCan, { on -> model.edit { it.copy(detectCan = on) } }, !busy)
    }
    ValueRow(stringResource(R.string.setup_max_power_loss), state.maxPowerLoss, "W")
    ActionRow { OutlineButton(stringResource(R.string.setup_run_detection), onStart, enabled = !busy && state.detectionInputsValid) }
}

@Composable
private fun ResultPage(state: FocWizardState) {
    val result = state.result ?: return
    if (!result.ok) {
        val fault = result.fault?.let { faultStrings(it.ordinal) }
        val reason = when (result.failure) {
            FocDetectionFailure.PersistentFault -> stringResource(R.string.setup_detect_persistent_fault)
            FocDetectionFailure.FluxLinkage -> stringResource(R.string.setup_detect_linkage)
            FocDetectionFailure.CanTimeout -> stringResource(R.string.setup_detect_can_timeout)
            FocDetectionFailure.CanFailed -> stringResource(R.string.setup_detect_can_failed)
            FocDetectionFailure.Fault -> fault?.let { "${it.title}. ${it.detail}" } ?: stringResource(R.string.setup_detect_no_fault)
            FocDetectionFailure.Unknown, null -> stringResource(R.string.setup_detect_unknown, result.code)
        }
        Notice(stringResource(R.string.setup_detect_failed), body = reason, tone = NoticeTone.Fault)
        return
    }
    Notice(stringResource(R.string.setup_detect_done))
    state.detected.forEach { m ->
        SectionHeader(stringResource(R.string.setup_controller_id, m.canId))
        ValueRow(stringResource(R.string.setup_motor_current), m.currentMax.fixed(2), "A")
        ValueRow(stringResource(R.string.setup_resistance), (m.resistance * 1e3).fixed(2), "mΩ")
        ValueRow(stringResource(R.string.setup_inductance), (m.inductance * 1e6).fixed(2), "µH")
        ValueRow(stringResource(R.string.setup_ld_lq_diff), (m.ldLqDiff * 1e6).fixed(2), "µH")
        ValueRow(stringResource(R.string.setup_flux_linkage), (m.linkage * 1e3).fixed(2), "mWb")
        ValueRow(stringResource(R.string.setup_temp_comp), stringResource(if (m.tempComp) R.string.setup_yes else R.string.setup_no))
        ValueRow(stringResource(R.string.setup_sensors), m.sensors)
    }
}

@Composable
private fun <T> Choices(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, enabled: Boolean) {
    val colors = FocTheme.colors
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            val on = option == selected
            Text(
                label(option),
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.row)
                    .background(if (on) colors.surface else colors.background)
                    .selectable(on, enabled = enabled, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = Spacing.x2, vertical = Spacing.x2),
                color = if (on) colors.accent else colors.text,
                style = if (on) FocTheme.type.bodyStrong else FocTheme.type.body,
            )
            Hairline(Modifier.padding(start = Spacing.x2))
        }
    }
}

@Composable
fun targetName(target: SetupTarget): String =
    target.canId?.let { stringResource(R.string.setup_can_controller, it) } ?: stringResource(R.string.setup_connected_controller)

private fun stepTitle(step: WizardStep) = when (step) {
    WizardStep.Prepare -> R.string.setup_step_prepare
    WizardStep.Usage -> R.string.setup_step_usage
    WizardStep.Motor -> R.string.setup_step_motor
    WizardStep.Battery -> R.string.setup_step_battery
    WizardStep.Setup -> R.string.setup_step_setup
    WizardStep.Detect -> R.string.setup_step_detect
    WizardStep.Result -> R.string.setup_step_result
    WizardStep.Direction -> R.string.setup_step_direction
}

private fun previous(step: WizardStep): WizardStep? = when (step) {
    WizardStep.Prepare, WizardStep.Direction -> null
    WizardStep.Result -> WizardStep.Detect
    else -> WizardStep.entries[step.ordinal - 1]
}
