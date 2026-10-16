package app.foctuner.ui.tools.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.config.Config
import app.foctuner.config.ParamType
import app.foctuner.config.ParamValue
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.settings.ParamDialog
import app.foctuner.ui.settings.display
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlin.time.DurationUnit

/** Asks before anything that moves a motor or changes the controller, saying what will happen. */
@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { PlainButton(confirm, onConfirm) },
        dismissButton = { PlainButton(stringResource(R.string.setup_cancel), onDismiss) },
        title = { Text(title, style = FocTheme.type.title) },
        text = { Text(body, color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting) },
    )
}

/** The standard warning before a motor turns. */
@Composable
fun SpinDialog(body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(stringResource(R.string.setup_spin_title), body, stringResource(R.string.setup_spin_confirm), onConfirm, onDismiss)
}

/** Progress, elapsed time and Stop while a job runs; what happened once it stopped or failed. */
@Composable
fun RunStatus(state: RunState, runningLabel: String, onStop: () -> Unit) {
    when (state) {
        RunState.Idle -> Unit
        is RunState.Running -> {
            val seconds by produceState(0L, state.started) {
                while (true) {
                    value = state.started.elapsedNow().toLong(DurationUnit.SECONDS)
                    delay(250)
                }
            }
            Column(Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(runningLabel, Modifier.weight(1f), color = FocTheme.colors.text, style = FocTheme.type.body)
                    Text(stringResource(R.string.setup_elapsed, seconds), color = FocTheme.colors.textSecondary, style = FocTheme.type.value)
                }
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = FocTheme.colors.accent, trackColor = FocTheme.colors.surfaceHigh)
                OutlineButton(stringResource(R.string.setup_stop), onStop)
                if (state.spins) Text(stringResource(R.string.setup_stop_note), color = FocTheme.colors.textTertiary, style = FocTheme.type.caption)
            }
        }
        RunState.Stopped -> Notice(stringResource(R.string.setup_stopped), body = stringResource(R.string.setup_stopped_body))
        is RunState.Failed -> FailureNotice(state.failure)
    }
}

@Composable
fun FailureNotice(failure: Failure) {
    val title = when (failure.kind) {
        FailureKind.NoReply -> R.string.setup_failure_no_reply
        FailureKind.LinkLost -> R.string.setup_failure_link
        FailureKind.Layout -> R.string.setup_failure_layout
        FailureKind.Refused -> R.string.setup_failure_refused
    }
    Notice(stringResource(title), body = failure.detail, tone = NoticeTone.Warning)
}

/** A number the user types, e.g. a detection current; red while it isn't a number. */
@Composable
fun NumberRow(label: String, text: String, unit: String, onText: (String) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.row)
            .padding(horizontal = Spacing.x2, vertical = Spacing.half),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = FocTheme.colors.text, style = FocTheme.type.body)
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier.widthIn(min = 96.dp, max = 160.dp),
            enabled = enabled,
            singleLine = true,
            textStyle = FocTheme.type.value.copy(textAlign = TextAlign.End),
            suffix = unit.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
            isError = parseNumber(text) == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
    Hairline(Modifier.padding(start = Spacing.x2))
}

/** A result: label left, number right, unit after it. */
@Composable
fun ValueRow(label: String, value: String, unit: String = "") {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.row)
            .padding(horizontal = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = FocTheme.colors.text, style = FocTheme.type.body)
        Text(value, color = FocTheme.colors.text, style = FocTheme.type.value)
        if (unit.isNotEmpty()) Text(" $unit", color = FocTheme.colors.textTertiary, style = FocTheme.type.unit)
    }
    Hairline(Modifier.padding(start = Spacing.x2))
}

/** An on/off choice of the wizard itself (not a controller setting). */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit, enabled: Boolean = true) {
    val colors = FocTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.row)
            .clickable(enabled = enabled, role = Role.Switch) { onChecked(!checked) }
            .padding(horizontal = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = if (enabled) colors.text else colors.textTertiary, style = FocTheme.type.body)
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.outline,
                uncheckedTrackColor = colors.surface,
                uncheckedBorderColor = colors.outline,
            ),
        )
    }
    Hairline(Modifier.padding(start = Spacing.x2))
}

/** One controller setting in [config], edited with the Settings screen's dialog. */
@Composable
fun SettingRow(config: Config, name: String, onSet: (ParamValue) -> Unit, enabled: Boolean = true) {
    val param = config.definition.param(name)
    val colors = FocTheme.colors
    var editing by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.row)
            .clickable(enabled = enabled, role = Role.Button) {
                if (param.type == ParamType.BOOL) onSet(ParamValue.IntValue(if (config.bool(name)) 0 else 1)) else editing = true
            }
            .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(param.longName, Modifier.weight(1f), color = if (enabled) colors.text else colors.textTertiary, style = FocTheme.type.body)
        Text(
            param.display(config[name], stringResource(R.string.settings_none)),
            Modifier
                .widthIn(max = 200.dp)
                .padding(start = Spacing.x2),
            color = colors.textSecondary,
            style = FocTheme.type.value,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Hairline(Modifier.padding(start = Spacing.x2))
    if (editing) {
        ParamDialog(param, config.definition, config[name], null, onSet = { onSet(it); editing = false }, onDismiss = { editing = false })
    }
}

/** The action at the end of a tool page. */
@Composable
fun ActionRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(Spacing.x2),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
