package app.foctuner.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.config.ConfigDefinition
import app.foctuner.config.ParamChange
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** Lists every pending change, from the value on the controller to the new one, before anything is written. */
@Composable
internal fun ReviewDialog(changes: List<ParamChange>, definition: ConfigDefinition, onWrite: () -> Unit, onDismiss: () -> Unit) {
    val colors = FocTheme.colors
    val none = stringResource(R.string.settings_none)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { PlainButton(stringResource(R.string.settings_review_confirm), onWrite) },
        dismissButton = { PlainButton(stringResource(R.string.settings_cancel), onDismiss) },
        title = { Text(stringResource(R.string.settings_review_title), style = FocTheme.type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    pluralStringResource(R.plurals.settings_review_body, changes.size, changes.size),
                    Modifier.padding(bottom = Spacing.x1),
                    color = colors.textSecondary,
                    style = FocTheme.type.supporting,
                )
                changes.forEach { change ->
                    val param = definition.params[change.name] ?: return@forEach
                    Hairline()
                    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.x1), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(param.longName, color = colors.text, style = FocTheme.type.body)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(param.display(change.from, none), color = colors.textTertiary, style = FocTheme.type.value)
                            Text("  →  ", color = colors.textTertiary, style = FocTheme.type.value)
                            Text(param.display(change.to, none), color = colors.text, style = FocTheme.type.value)
                        }
                    }
                }
            }
        },
        shape = MaterialTheme.shapes.small,
        containerColor = colors.surface,
        titleContentColor = colors.text,
        textContentColor = colors.textSecondary,
    )
}
