package app.foctuner.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.config.ConfigDefinition
import app.foctuner.config.ParamDefinition
import app.foctuner.config.ParamType
import app.foctuner.config.ParamValue
import app.foctuner.config.validate
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** Edits one setting with the editor its type needs, and shows VESC Tool's description of it. */
@Composable
internal fun ParamDialog(
    param: ParamDefinition,
    definition: ConfigDefinition,
    value: ParamValue,
    stored: ParamValue?,
    onSet: (ParamValue) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FocTheme.colors
    val none = stringResource(R.string.settings_none)
    var text by remember { mutableStateOf(param.editText(value)) }
    var option by remember { mutableLongStateOf((value as? ParamValue.IntValue)?.value ?: 0L) }
    val usesText = param.type == ParamType.DOUBLE || param.type == ParamType.INT || param.type == ParamType.STRING
    val candidate = if (usesText) param.parse(text) else ParamValue.IntValue(option)
    val issue = candidate?.let { definition.validate(param.name, it) }
    val description = remember(param) { plainDescription(param.description) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { PlainButton(stringResource(R.string.settings_set), { candidate?.let(onSet) }, enabled = candidate != null && issue == null) },
        dismissButton = { PlainButton(stringResource(R.string.settings_cancel), onDismiss) },
        title = { Text(param.longName, style = FocTheme.type.title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.x1),
            ) {
                when (param.type) {
                    ParamType.DOUBLE, ParamType.INT, ParamType.STRING -> {
                        val (min, max) = param.range()
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = param.type != ParamType.STRING,
                            maxLines = 6,
                            textStyle = FocTheme.type.value,
                            suffix = param.displaySuffix.trim().takeIf { it.isNotEmpty() && param.type != ParamType.STRING }?.let { { Text(it) } },
                            isError = candidate == null || issue != null,
                            supportingText = when {
                                param.type == ParamType.STRING -> null
                                candidate == null -> { { Text(stringResource(R.string.settings_invalid_number)) } }
                                else -> { { Text(stringResource(R.string.settings_range, min, max)) } }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (param.type == ParamType.STRING) KeyboardType.Text else KeyboardType.Decimal,
                            ),
                        )
                    }
                    ParamType.BOOL -> Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.touchTarget)
                            .toggleable(value = option == 1L, role = Role.Switch) { option = if (it) 1 else 0 },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(if (option == 1L) R.string.settings_on else R.string.settings_off), Modifier.weight(1f), color = colors.text, style = FocTheme.type.body)
                        Switch(
                            checked = option == 1L,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.onAccent,
                                checkedTrackColor = colors.accent,
                                uncheckedThumbColor = colors.outline,
                                uncheckedTrackColor = colors.surface,
                                uncheckedBorderColor = colors.outline,
                            ),
                        )
                    }
                    ParamType.ENUM -> Column(Modifier.selectableGroup()) {
                        param.enumNames.forEachIndexed { index, name ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = Spacing.touchTarget)
                                    .selectable(selected = option == index.toLong(), role = Role.RadioButton) { option = index.toLong() },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = option == index.toLong(), onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.outline))
                                Text(name, Modifier.padding(start = Spacing.x2), color = colors.text, style = FocTheme.type.body)
                            }
                        }
                    }
                    ParamType.BITFIELD -> Column {
                        param.bitNames().forEachIndexed { bit, name ->
                            val mask = 1L shl bit
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = Spacing.touchTarget)
                                    .toggleable(value = option and mask != 0L, role = Role.Checkbox) { option = if (it) option or mask else option and mask.inv() },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = option and mask != 0L, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.outline, checkmarkColor = colors.onAccent))
                                Text(name, Modifier.padding(start = Spacing.x2), color = colors.text, style = FocTheme.type.body)
                            }
                        }
                    }
                    ParamType.UNDEFINED -> Unit
                }
                if (stored != null && stored != value) {
                    Text(stringResource(R.string.settings_on_controller, param.display(stored, none)), color = colors.textTertiary, style = FocTheme.type.caption)
                }
                if (description.isNotEmpty()) {
                    Text(description, Modifier.padding(top = Spacing.x1), color = colors.textSecondary, style = FocTheme.type.supporting)
                }
                Text(param.name, color = colors.textTertiary, style = FocTheme.type.code)
            }
        },
        shape = MaterialTheme.shapes.small,
        containerColor = colors.surface,
        titleContentColor = colors.text,
        textContentColor = colors.textSecondary,
        modifier = Modifier.padding(vertical = Spacing.x3),
    )
}
