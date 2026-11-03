package app.foctuner.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.config.ConfigBackup
import app.foctuner.config.ConfigKind
import app.foctuner.config.ParamChange
import app.foctuner.config.writeWarnings
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.PageHeader
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.parts.ConfirmDialog
import java.text.DateFormat
import java.util.Date

/**
 * The selected controller's backups, newest first: the automatic ones taken before every write and the ones asked for,
 * each with what restoring it would change on the controller now. Restoring opens the review; nothing is written
 * before it is confirmed.
 */
@Composable
internal fun HistoryPage(state: SettingsState, actions: SettingsActions, breadcrumb: String, onBack: () -> Unit) {
    val colors = FocTheme.colors
    var deleting by remember { mutableStateOf<ConfigBackup?>(null) }
    val entries = state.history
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        PageHeader(stringResource(R.string.settings_history), onBack, breadcrumb = breadcrumb)
        Hairline()
        if (entries == null || state.restoring) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.accent, trackColor = colors.hairline, gapSize = 0.dp)
        }
        if (entries != null && entries.isEmpty()) {
            Text(stringResource(R.string.settings_history_empty), Modifier.padding(Spacing.x2), color = colors.textSecondary, style = FocTheme.type.supporting)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(entries.orEmpty(), key = { "${it.backup.uuid}/${it.backup.id}" }) { entry ->
                BackupRow(entry, state, onRestore = { actions.restore(entry.backup) }, onDelete = { deleting = entry.backup })
                Hairline(Modifier.padding(start = Spacing.x2))
            }
            item { Spacer(Modifier.height(Spacing.x2)) }
        }
    }
    deleting?.let { backup ->
        ConfirmDialog(
            title = stringResource(R.string.settings_history_delete_title),
            body = stringResource(R.string.settings_history_delete_body, backupTitle(backup), backupDate(backup)),
            confirm = stringResource(R.string.settings_history_delete),
            dismiss = stringResource(R.string.settings_cancel),
            onConfirm = {
                actions.deleteBackup(backup)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun BackupRow(entry: BackupEntry, state: SettingsState, onRestore: () -> Unit, onDelete: () -> Unit) {
    val colors = FocTheme.colors
    val backup = entry.backup
    val changes = entry.changes
    val count = changes?.values?.sumOf { it.size } ?: 0
    val canRestore = !entry.damaged && changes != null && count > 0 && !state.restoring
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.row)
            .clickable(enabled = canRestore, role = Role.Button, onClickLabel = stringResource(R.string.settings_history_restore), onClick = onRestore)
            .padding(start = Spacing.x2, end = Spacing.x1, top = Spacing.x1),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(backupTitle(backup), color = colors.text, style = FocTheme.type.bodyStrong)
        Text(
            listOf(backupDate(backup), stringResource(R.string.session_firmware, backup.firmware)).joinToString(" · "),
            color = colors.textTertiary,
            style = FocTheme.type.caption,
        )
        val summary = when {
            entry.damaged -> stringResource(R.string.settings_history_damaged)
            changes == null -> stringResource(R.string.settings_history_comparing)
            changes.isEmpty() -> stringResource(R.string.settings_history_not_read)
            count == 0 -> stringResource(R.string.settings_history_same)
            else -> pluralStringResource(R.plurals.settings_history_changes, count, count, names(changes, state))
        }
        Text(summary, color = if (entry.damaged) colors.warning else colors.textSecondary, style = FocTheme.type.supporting)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            PlainButton(stringResource(R.string.settings_history_delete), onDelete, enabled = !state.restoring)
            PlainButton(stringResource(R.string.settings_history_restore), onRestore, enabled = canRestore)
        }
    }
}

/** The first few settings restoring would change, by their long names. */
private fun names(changes: Map<ConfigKind, List<ParamChange>>, state: SettingsState): String {
    val all = changes.flatMap { (kind, list) ->
        val definition = state.pane(kind).stored?.definition
        list.map { definition?.params?.get(it.name)?.longName ?: it.name }
    }
    return if (all.size <= 3) all.joinToString() else all.take(3).joinToString() + " …"
}

@Composable
private fun backupTitle(backup: ConfigBackup): String = when {
    backup.name.isNotEmpty() -> backup.name
    backup.automatic -> stringResource(R.string.settings_history_automatic)
    else -> stringResource(R.string.settings_history_manual)
}

private fun backupDate(backup: ConfigBackup): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(backup.created))

/** The review for a restore or an undo, one section per config. */
@Composable
internal fun RestoreDialog(review: RestoreReview, actions: SettingsActions) {
    val motor = review.configs[ConfigKind.MCCONF]?.second
    ReviewDialog(
        groups = review.changes.map { (kind, changes) ->
            ReviewGroup(
                stringResource(if (kind == ConfigKind.MCCONF) R.string.nav_motor_cfg else R.string.nav_app_cfg).takeIf { review.configs.size > 1 },
                review.configs.getValue(kind).second.definition,
                changes,
            )
        },
        onWrite = actions::confirmRestore,
        onDismiss = actions::dismissRestore,
        warnings = motor?.let { config -> config.writeWarnings().map { warningText(it, config) } }.orEmpty(),
        title = stringResource(if (review.undo) R.string.settings_undo_title else R.string.settings_restore_title),
        confirm = stringResource(if (review.undo) R.string.settings_undo else R.string.settings_history_restore),
    )
}
