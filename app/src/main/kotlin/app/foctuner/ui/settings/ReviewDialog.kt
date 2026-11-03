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
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** The changes to one config; [title] names it when a dialog lists more than one. */
internal data class ReviewGroup(val title: String?, val definition: ConfigDefinition, val changes: List<ParamChange>)

/**
 * Lists every pending change, from the value on the controller to the new one, before anything is written, with the
 * [warnings] the new values raise above them. With nothing to change it only says so.
 */
@Composable
internal fun ReviewDialog(
    groups: List<ReviewGroup>,
    onWrite: () -> Unit,
    onDismiss: () -> Unit,
    warnings: List<String> = emptyList(),
    title: String = stringResource(R.string.settings_review_title),
    confirm: String = stringResource(R.string.settings_review_confirm),
) {
    val colors = FocTheme.colors
    val none = stringResource(R.string.settings_none)
    val count = groups.sumOf { it.changes.size }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { if (count > 0) PlainButton(confirm, onWrite) },
        dismissButton = { PlainButton(stringResource(R.string.settings_cancel), onDismiss) },
        title = { Text(title, style = FocTheme.type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (warnings.isNotEmpty() && count > 0) {
                    Notice(
                        stringResource(R.string.safety_review_heading),
                        Modifier.padding(bottom = Spacing.x2),
                        tone = NoticeTone.Warning,
                        checks = warnings,
                    )
                }
                Text(
                    if (count > 0) pluralStringResource(R.plurals.settings_review_body, count, count) else stringResource(R.string.settings_review_same),
                    Modifier.padding(bottom = Spacing.x1),
                    color = colors.textSecondary,
                    style = FocTheme.type.supporting,
                )
                for (group in groups.filter { it.changes.isNotEmpty() }) {
                    group.title?.let { SectionHeader(it) }
                    group.changes.forEach { change ->
                        val param = group.definition.params[change.name] ?: return@forEach
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
            }
        },
        shape = MaterialTheme.shapes.small,
        containerColor = colors.surface,
        titleContentColor = colors.text,
        textContentColor = colors.textSecondary,
    )
}
