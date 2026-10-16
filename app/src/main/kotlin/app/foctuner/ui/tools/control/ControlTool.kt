package app.foctuner.ui.tools.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.components.grouped
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.ChoiceRow
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.ui.tools.parts.Meter
import app.foctuner.ui.tools.parts.MeterRow
import app.foctuner.vesc.PositionDisplay
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun ControlTool(context: ToolContext) {
    val model = context.model { ControlModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) {
        while (true) {
            model.refresh()
            delay(POLL_MS)
        }
    }
    DisposableEffect(model) { onDispose { model.leave() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.leave() }

    ControlContent(
        state = state,
        onArm = model::arm,
        onStop = model::stop,
        onMode = model::select,
        onSetpoint = model::setSetpoint,
        onPress = model::press,
        onLift = model::lift,
        onRelease = model::release,
        onKeepRunning = model::setKeepRunning,
        onRotor = model::showRotor,
        onServo = model::setServo,
    )
}

@Composable
private fun ControlContent(
    state: ControlState,
    onArm: () -> Unit,
    onStop: () -> Unit,
    onMode: (ControlMode) -> Unit,
    onSetpoint: (ControlMode, Float) -> Unit,
    onPress: (ControlAction) -> Unit,
    onLift: () -> Unit,
    onRelease: () -> Unit,
    onKeepRunning: (Boolean) -> Unit,
    onRotor: (PositionDisplay) -> Unit,
    onServo: (Float) -> Unit,
) {
    val colors = FocTheme.colors
    var confirmKeepRunning by rememberSaveable { mutableStateOf(false) }
    Column {
        StopButton(onStop, Modifier.padding(Spacing.x2))
        if (state.stopSeconds > 0) {
            Notice(
                stringResource(R.string.control_stopped, state.stopSeconds),
                body = stringResource(R.string.control_stopped_why),
                tone = NoticeTone.Warning,
            )
        }
        Hairline()
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (state.failed) Notice(stringResource(R.string.control_no_reply), tone = NoticeTone.Warning)
            val v = state.values
            MeterRow(
                Meter(stringResource(R.string.control_duty), v?.let { (it.dutyCycle * 100f).fixed(1) }, "%"),
                Meter(stringResource(R.string.control_current), v?.currentMotor?.fixed(1), "A"),
            )
            Hairline()
            MeterRow(
                Meter(stringResource(R.string.control_speed), v?.erpm?.grouped(), "ERPM"),
                Meter(stringResource(R.string.control_position), v?.pidPosition?.fixed(1), "°"),
            )
            Hairline()
            MeterRow(
                Meter(stringResource(R.string.control_voltage), v?.voltageInput?.fixed(1), "V"),
                Meter(stringResource(R.string.control_temp_mosfet), v?.tempMosfet?.fixed(1), "°C"),
                Meter(stringResource(R.string.control_temp_motor), v?.tempMotor?.fixed(1), "°C"),
            )
            Hairline()

            SectionHeader(stringResource(R.string.control_section_command))
            if (!state.armed) {
                Notice(
                    stringResource(R.string.control_arm_title),
                    body = stringResource(R.string.control_arm_body),
                    tone = NoticeTone.Warning,
                    action = stringResource(R.string.control_arm_action),
                    onAction = onArm,
                )
            } else {
                ChoiceRow(ControlMode.entries, state.mode, { stringResource(it.title) }, onMode)
                Hairline()
                SetpointField(state.mode, state.setpoints.getValue(state.mode), state.canCommand) { onSetpoint(state.mode, it) }
                ActionButtons(state, onPress, onLift)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.row)
                        .toggleable(value = state.keepRunning, enabled = state.canCommand, role = Role.Switch) {
                            if (it) confirmKeepRunning = true else onKeepRunning(false)
                        }
                        .padding(horizontal = Spacing.x2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.control_keep_running), Modifier.weight(1f), color = colors.text, style = FocTheme.type.body)
                    Switch(
                        checked = state.keepRunning,
                        onCheckedChange = null,
                        enabled = state.canCommand,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.onAccent,
                            checkedTrackColor = colors.accent,
                            uncheckedThumbColor = colors.outline,
                            uncheckedTrackColor = colors.surface,
                            uncheckedBorderColor = colors.outline,
                        ),
                    )
                }
                Hairline()
                PlainButton(
                    stringResource(R.string.control_release),
                    onRelease,
                    Modifier.padding(horizontal = Spacing.x1, vertical = Spacing.x1),
                    enabled = state.running != null,
                )
                RotorSection(state, onRotor)
                ServoSection(state.servo, onServo)
            }
            Spacer(Modifier.height(Spacing.x4))
        }
    }

    if (confirmKeepRunning) {
        ConfirmDialog(
            title = stringResource(R.string.control_keep_running_title),
            body = stringResource(R.string.control_keep_running_body),
            confirm = stringResource(R.string.control_keep_running_confirm),
            dismiss = stringResource(R.string.control_cancel),
            onConfirm = {
                confirmKeepRunning = false
                onKeepRunning(true)
            },
            onDismiss = { confirmKeepRunning = false },
        )
    }
}

@Composable
private fun SetpointField(mode: ControlMode, value: Float, enabled: Boolean, onValue: (Float) -> Unit) {
    var text by remember(mode) { mutableStateOf(edit(mode, value)) }
    val parsed = text.replace(',', '.').toFloatOrNull()?.takeIf { it in 0f..mode.max }
    fun step(by: Float) {
        val next = mode.clamp((parsed ?: value) + by)
        onValue(next)
        text = edit(mode, next)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.x1, vertical = Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        PlainButton("−", { step(-mode.step) }, Modifier.heightIn(min = Spacing.touchTarget), enabled = enabled)
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                input.replace(',', '.').toFloatOrNull()?.takeIf { it in 0f..mode.max }?.let(onValue)
            },
            modifier = Modifier.weight(1f),
            enabled = enabled,
            singleLine = true,
            textStyle = FocTheme.type.value,
            suffix = mode.unit.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
            isError = parsed == null,
            supportingText = { Text(stringResource(R.string.control_range, edit(mode, 0f), edit(mode, mode.max))) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = FocTheme.colors.accent,
                unfocusedBorderColor = FocTheme.colors.outline,
            ),
        )
        PlainButton("+", { step(mode.step) }, Modifier.heightIn(min = Spacing.touchTarget), enabled = enabled)
    }
}

@Composable
private fun ActionButtons(state: ControlState, onPress: (ControlAction) -> Unit, onLift: () -> Unit) {
    val enabled = state.canCommand
    Column(
        Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        when (state.mode) {
            ControlMode.Position, ControlMode.Handbrake -> HoldButton(
                stringResource(if (state.mode == ControlMode.Position) R.string.control_hold_position else R.string.control_hold_handbrake),
                state.running == ControlAction.Hold,
                enabled,
                { onPress(ControlAction.Hold) },
                onLift,
                Modifier.fillMaxWidth(),
            )
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                    HoldButton(stringResource(R.string.control_reverse), state.running == ControlAction.Reverse, enabled, { onPress(ControlAction.Reverse) }, onLift, Modifier.weight(1f))
                    HoldButton(stringResource(R.string.control_forward), state.running == ControlAction.Forward, enabled, { onPress(ControlAction.Forward) }, onLift, Modifier.weight(1f))
                }
                HoldButton(
                    stringResource(
                        when (state.mode) {
                            ControlMode.Current -> R.string.control_brake
                            ControlMode.Duty -> R.string.control_brake_duty
                            else -> R.string.control_brake_rpm
                        },
                    ),
                    state.running == ControlAction.Brake,
                    enabled,
                    { onPress(ControlAction.Brake) },
                    onLift,
                    Modifier.fillMaxWidth(),
                )
            }
        }
        Text(
            stringResource(if (state.keepRunning) R.string.control_hint_latched else R.string.control_hint_hold),
            color = FocTheme.colors.textTertiary,
            style = FocTheme.type.caption,
        )
    }
}

/** The value as typed into the field: plain digits with a dot, so it parses back in any locale. */
private fun edit(mode: ControlMode, value: Float): String = String.format(Locale.ROOT, "%.${mode.decimals}f", value)

private const val POLL_MS = 100L

internal val HOLD_BUTTON_HEIGHT = 64.dp
