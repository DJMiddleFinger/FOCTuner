package app.foctuner.ui.tools.configtools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.config.ConfigBackup
import app.foctuner.config.ConfigXmlException
import app.foctuner.config.PayloadException
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.settings.backupDate
import app.foctuner.ui.settings.backupTitle
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.vesc.AUTOMATIC_BACKUPS
import app.foctuner.vesc.ConfigTarget
import app.foctuner.vesc.ConfigWriteResult
import app.foctuner.vesc.backUpConfigs
import app.foctuner.vesc.configTargets
import app.foctuner.vesc.controller
import app.foctuner.vesc.restoreConfigs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** What a config tool did on one controller: why it failed, or the settings it didn't take (by long name). */
@Immutable
data class TargetOutcome(val target: ConfigTarget, val failure: String? = null, val notApplied: List<String> = emptyList())

/** Runs [block] for [target] and turns what went wrong into an outcome instead of failing the others. */
internal suspend fun outcome(target: ConfigTarget, block: suspend () -> List<String>): TargetOutcome = try {
    TargetOutcome(target, notApplied = block())
} catch (e: IOException) {
    TargetOutcome(target, failure = e.message ?: e.javaClass.simpleName)
} catch (e: PayloadException) {
    TargetOutcome(target, failure = e.message)
} catch (e: ConfigXmlException) {
    TargetOutcome(target, failure = e.message)
}

internal fun ConfigWriteResult.notAppliedNames(): List<String> = notApplied.map { applied.definition.param(it).longName }

@Composable
internal fun targetLabel(target: ConfigTarget): String = listOfNotNull(
    target.canId?.let { stringResource(R.string.configtools_can, it) } ?: stringResource(R.string.configtools_attached),
    target.firmware.hardwareName.takeIf { it.isNotEmpty() },
).joinToString(" · ")

@Composable
internal fun Outcomes(outcomes: List<TargetOutcome>) {
    outcomes.forEach { o ->
        val body = when {
            o.failure != null -> stringResource(R.string.configtools_failed, o.failure)
            o.notApplied.isNotEmpty() -> stringResource(R.string.configtools_not_applied, o.notApplied.joinToString())
            else -> stringResource(R.string.configtools_done)
        }
        val tone = if (o.failure != null || o.notApplied.isNotEmpty()) NoticeTone.Warning else NoticeTone.Neutral
        Notice(targetLabel(o.target), body = body, tone = tone)
    }
}

@Immutable
data class BackupsState(
    val backups: List<ConfigBackup>? = null,
    val busy: Boolean = false,
    /** Restore all, waiting for confirmation: each controller and its newest backup asked for (none: skipped). */
    val plan: List<Pair<ConfigTarget, ConfigBackup?>>? = null,
    val outcomes: List<TargetOutcome> = emptyList(),
    val failure: String? = null,
)

/**
 * VESC Tool's Backup configs and Restore configs for the controller and every controller on its CAN bus, and the
 * saved backups of all of them. Each restore takes an automatic backup first, as every settings write does.
 */
class BackupsModel(private val context: ToolContext, private val storage: ConfigStorage) {
    private val _state = MutableStateFlow(BackupsState())
    val state: StateFlow<BackupsState> = _state.asStateFlow()
    private val store get() = storage.backups

    fun load() {
        context.scope.launch {
            val list = withContext(Dispatchers.IO) { store.list().sortedByDescending { it.created } }
            _state.update { it.copy(backups = list) }
        }
    }

    fun backUpAll(name: String) = work {
        context.client.configTargets().map { t ->
            outcome(t) {
                context.client.controller(t).backUpConfigs(store, t.firmware, name.trim(), base = storage.base(t.uuid))
                emptyList()
            }
        }
    }

    fun planRestore() = work {
        val targets = context.client.configTargets()
        val manual = store.list().filter { !it.automatic }
        val plan = targets.map { t -> t to manual.filter { it.uuid == t.uuid }.maxByOrNull { it.created } }
        _state.update { it.copy(plan = plan) }
        emptyList()
    }

    fun cancelRestore() = _state.update { it.copy(plan = null) }

    fun restoreAll() {
        val plan = _state.value.plan ?: return
        _state.update { it.copy(plan = null) }
        work {
            plan.mapNotNull { (t, backup) -> backup?.let { t to it } }.map { (t, backup) ->
                outcome(t) {
                    val controller = context.client.controller(t)
                    controller.backUpConfigs(store, t.firmware, automatic = true, base = storage.base(t.uuid))
                    store.pruneAutomatic(t.uuid, AUTOMATIC_BACKUPS)
                    controller.restoreConfigs(store, backup, t.firmware).values.flatMap { it.notAppliedNames() }
                }
            }
        }
    }

    fun delete(backup: ConfigBackup) = work {
        store.delete(backup)
        emptyList()
    }

    fun deleteAll() = work {
        store.deleteAll()
        emptyList()
    }

    /** Runs [block] off the main thread, one at a time, then shows its outcomes (if any) and the backups again. */
    private fun work(block: suspend () -> List<TargetOutcome>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, failure = null) }
        context.scope.launch {
            val failure = try {
                val outcomes = withContext(Dispatchers.IO) { block() }
                if (outcomes.isNotEmpty()) _state.update { it.copy(outcomes = outcomes) }
                null
            } catch (e: IOException) {
                e.message ?: e.javaClass.simpleName
            }
            _state.update { it.copy(busy = false, failure = failure) }
            load()
        }
    }
}

@Composable
fun BackupsTool(context: ToolContext) {
    val storage = ConfigStorage.of(LocalContext.current)
    val model = context.model { BackupsModel(context, storage) }
    val state by model.state.collectAsStateWithLifecycle()
    val colors = FocTheme.colors
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ConfigBackup?>(null) }
    var deletingAll by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model.load() }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        SectionHeader(stringResource(R.string.backups_all))
        Text(stringResource(R.string.backups_all_body), Modifier.padding(horizontal = Spacing.x2), color = colors.textSecondary, style = FocTheme.type.supporting)
        Row(Modifier.padding(Spacing.x2), horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
            OutlineButton(stringResource(R.string.backups_back_up_all), { naming = true }, enabled = !state.busy)
            OutlineButton(stringResource(R.string.backups_restore_all), model::planRestore, enabled = !state.busy)
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.accent, trackColor = colors.hairline, gapSize = 0.dp)
        state.failure?.let { Notice(stringResource(R.string.configtools_no_reply), body = it, tone = NoticeTone.Warning) }
        Outcomes(state.outcomes)

        val backups = state.backups.orEmpty()
        SectionHeader(stringResource(R.string.backups_saved)) {
            if (backups.isNotEmpty()) PlainButton(stringResource(R.string.backups_delete_all), { deletingAll = true }, enabled = !state.busy)
        }
        if (state.backups?.isEmpty() == true) {
            Text(stringResource(R.string.backups_none), Modifier.padding(Spacing.x2), color = colors.textSecondary, style = FocTheme.type.supporting)
        }
        backups.forEach { backup ->
            Row(Modifier.fillMaxWidth().padding(start = Spacing.x2, end = Spacing.x1, top = Spacing.x1)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(backupTitle(backup), color = colors.text, style = FocTheme.type.bodyStrong)
                    Text(
                        listOf(backup.hardware, "…" + backup.uuid.takeLast(6), backupDate(backup), stringResource(R.string.session_firmware, backup.firmware))
                            .filter { it.isNotEmpty() }.joinToString(" · "),
                        color = colors.textTertiary,
                        style = FocTheme.type.caption,
                    )
                }
                PlainButton(stringResource(R.string.settings_history_delete), { deleting = backup }, enabled = !state.busy)
            }
            Hairline(Modifier.padding(start = Spacing.x2))
        }
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            confirmButton = {
                PlainButton(stringResource(R.string.backups_back_up_all), {
                    naming = false
                    model.backUpAll(name)
                })
            },
            dismissButton = { PlainButton(stringResource(R.string.settings_cancel), { naming = false }) },
            title = { Text(stringResource(R.string.backups_back_up_all), style = FocTheme.type.title) },
            text = {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.backups_name)) })
            },
            shape = MaterialTheme.shapes.small,
            containerColor = colors.surface,
            titleContentColor = colors.text,
        )
    }
    state.plan?.let { plan ->
        val lines = plan.map { (t, backup) ->
            targetLabel(t) + ": " + (backup?.let { backupTitle(it) + ", " + backupDate(it) } ?: stringResource(R.string.backups_restore_none))
        }
        ConfirmDialog(
            title = stringResource(R.string.backups_restore_all),
            body = stringResource(R.string.backups_restore_body, lines.joinToString("\n")),
            confirm = stringResource(R.string.settings_history_restore),
            dismiss = stringResource(R.string.settings_cancel),
            onConfirm = model::restoreAll,
            onDismiss = model::cancelRestore,
        )
    }
    deleting?.let { backup ->
        ConfirmDialog(
            title = stringResource(R.string.settings_history_delete_title),
            body = stringResource(R.string.settings_history_delete_body, backupTitle(backup), backupDate(backup)),
            confirm = stringResource(R.string.settings_history_delete),
            dismiss = stringResource(R.string.settings_cancel),
            onConfirm = {
                deleting = null
                model.delete(backup)
            },
            onDismiss = { deleting = null },
        )
    }
    if (deletingAll) {
        ConfirmDialog(
            title = stringResource(R.string.backups_delete_all),
            body = state.backups.orEmpty().size.let { pluralStringResource(R.plurals.backups_delete_all_body, it, it) },
            confirm = stringResource(R.string.settings_history_delete),
            dismiss = stringResource(R.string.settings_cancel),
            onConfirm = {
                deletingAll = false
                model.deleteAll()
            },
            onDismiss = { deletingAll = false },
        )
    }
}
