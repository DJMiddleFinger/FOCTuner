package app.foctuner.ui.tools.logs

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.logging.StoredLog
import app.foctuner.logs.LogNames
import app.foctuner.logs.TransferItem
import app.foctuner.logs.TransferQueue
import app.foctuner.logs.TransferState
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.NO_VALUE
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.ui.tools.parts.ReadingRow
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Ride logging on the phone, the logs it has, and the logs on the controller's storage. */
@Composable
fun LogsTool(context: ToolContext) {
    val app = LocalContext.current.applicationContext
    val model = context.model { LogsModel(context, app) }
    val state by model.state.collectAsStateWithLifecycle()
    val ride by rideLogState()
    val toggle = rememberRideLogToggle()
    var deleting by remember { mutableStateOf<StoredLog?>(null) }
    LaunchedEffect(model) { model.refresh() }
    LaunchedEffect(ride.recording) { model.refreshPhone() }

    LazyColumn(Modifier.fillMaxWidth()) {
        item { SectionHeader(stringResource(R.string.logging_section_record)) }
        item { RecordSection(ride, toggle) }
        item { SectionHeader(stringResource(R.string.logging_section_phone)) }
        if (state.phone.isEmpty()) {
            item { Text(stringResource(R.string.logging_phone_empty), Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting) }
        }
        items(state.phone, key = { it.name }) { log -> PhoneLogRow(log, onDelete = { deleting = log }) }
        item { SectionHeader(stringResource(R.string.logging_section_controller)) }
        controllerSection(state, model)
    }

    deleting?.let { log ->
        ConfirmDialog(
            title = stringResource(R.string.logging_delete),
            body = log.name,
            confirm = stringResource(R.string.logging_delete),
            dismiss = stringResource(R.string.logging_cancel),
            onConfirm = { model.delete(log); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun RecordSection(ride: app.foctuner.logging.RideLogState, toggle: () -> Unit) {
    Column {
        when (ride.failure) {
            app.foctuner.logging.RideLogState.Failure.LinkLost ->
                Notice(stringResource(R.string.logging_failure_link_title), body = stringResource(R.string.logging_failure_link_body), tone = NoticeTone.Warning)
            app.foctuner.logging.RideLogState.Failure.NoConnection ->
                Notice(stringResource(R.string.logging_failure_none_title), body = stringResource(R.string.logging_failure_none_body), tone = NoticeTone.Warning)
            null -> Unit
        }
        if (ride.recording) {
            if (!ride.status.replying) Notice(stringResource(R.string.logging_no_reply), tone = NoticeTone.Warning)
            ReadingRow(stringResource(R.string.logging_time), clock(ride.status.seconds))
            ReadingRow(stringResource(R.string.logging_rows), ride.status.rows.toString())
            ReadingRow(
                stringResource(R.string.logging_gps),
                stringResource(
                    when {
                        !ride.locationAllowed -> R.string.logging_gps_off
                        ride.status.hasFix -> R.string.logging_gps_fix
                        else -> R.string.logging_gps_waiting
                    },
                ),
            )
        } else {
            Text(stringResource(R.string.logging_idle), Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
            ride.lastFile?.let {
                Text(stringResource(R.string.logging_saved, it), Modifier.padding(horizontal = Spacing.x2), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
            }
        }
        OutlineButton(
            stringResource(if (ride.recording) R.string.logging_stop_recording else R.string.logging_start),
            toggle,
            Modifier.padding(Spacing.x2),
        )
    }
}

@Composable
private fun PhoneLogRow(log: StoredLog, onDelete: () -> Unit) {
    val context = LocalContext.current
    val title = rememberLogTitle(log.name)
    val size = byteCount(log.size)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.twoLineRow)
                .padding(start = Spacing.x2, end = Spacing.x1)
                .semantics(mergeDescendants = true) { contentDescription = context.getString(R.string.logging_log_description, title, size) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = FocTheme.colors.text, style = FocTheme.type.body)
                Text("${log.name}  $size", color = FocTheme.colors.textTertiary, style = FocTheme.type.supporting)
            }
            PlainButton(stringResource(R.string.logging_share), { share(context, log) })
            PlainButton(stringResource(R.string.logging_delete), onDelete)
        }
        Hairline(Modifier.padding(start = Spacing.x2))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.controllerSection(state: LogsState, model: LogsModel) {
    when {
        state.hasStorage == null && !state.failed -> item { Hint(stringResource(R.string.logging_controller_loading)) }
        state.failed -> item {
            Notice(stringResource(R.string.logging_controller_failed), tone = NoticeTone.Warning, action = stringResource(R.string.logging_refresh), onAction = { model.refresh() })
        }
        state.hasStorage == false -> item { Hint(stringResource(R.string.logging_controller_none)) }
        else -> {
            item { ControllerActions(state, model) }
            state.queue?.let { queue -> item { QueueSummary(queue, state.downloading, model::cancel) } }
            if (state.canGoUp) item { FolderRow(stringResource(R.string.logging_up), onClick = model::up) }
            val entries = state.entries.orEmpty()
            if (entries.isEmpty()) item { Hint(stringResource(R.string.logging_controller_empty, state.folder.ifEmpty { "/" })) }
            items(entries, key = { it.name }) { entry ->
                if (entry.isDirectory) {
                    FolderRow("${entry.name}/", onClick = { model.open(entry.name) })
                } else {
                    FileRow(entry.name, entry.size, entry.name in state.selected, state.queue?.items?.firstOrNull { it.name == entry.name }, state.partial[entry.name], entry.name in state.downloaded, !state.downloading) {
                        model.toggle(entry.name)
                    }
                }
            }
        }
    }
}

@Composable
private fun ControllerActions(state: LogsState, model: LogsModel) {
    val selected = state.files.filter { it.name in state.selected }
    val resume = selected.any { (state.partial[it.name] ?: 0L) > 0L }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.x1),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlainButton(stringResource(if (state.selected.size == state.files.size && state.files.isNotEmpty()) R.string.logging_select_none else R.string.logging_select_all), model::selectAll, enabled = state.files.isNotEmpty() && !state.downloading)
        PlainButton(stringResource(R.string.logging_refresh), { model.refresh() }, enabled = !state.downloading)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            OutlineButton(
                stringResource(if (resume) R.string.logging_download_resume else R.string.logging_download, selected.size),
                model::download,
                enabled = selected.isNotEmpty() && !state.downloading,
            )
        }
    }
}

@Composable
private fun QueueSummary(queue: TransferQueue, active: Boolean, onCancel: () -> Unit) {
    val line = stringResource(R.string.logging_progress_overall, queue.doneCount, queue.items.size, byteCount(queue.doneBytes), byteCount(queue.totalBytes))
    val rate = if (queue.bytesPerSecond > 0) stringResource(R.string.logging_progress_rate, byteCount(queue.bytesPerSecond.toLong())) else null
    val left = queue.secondsLeft?.let { stringResource(R.string.logging_progress_left, duration(it)) }
    Column(Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1)) {
        LinearProgressIndicator(
            progress = { queue.fraction },
            Modifier.fillMaxWidth(),
            color = FocTheme.colors.accent,
            trackColor = FocTheme.colors.surfaceHigh,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(line, rate, left).joinToString("  "), Modifier.weight(1f), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
            if (active) PlainButton(stringResource(R.string.logging_cancel), onCancel)
        }
    }
}

@Composable
private fun FolderRow(name: String, onClick: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.row)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = Spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, color = FocTheme.colors.text, style = FocTheme.type.body)
        }
        Hairline(Modifier.padding(start = Spacing.x2))
    }
}

@Composable
private fun FileRow(name: String, size: Long, selected: Boolean, item: TransferItem?, partial: Long?, downloaded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val sizeText = byteCount(size)
    val status = when {
        item?.state == TransferState.Done || (item == null && downloaded) -> stringResource(R.string.logging_file_state_done)
        item?.state == TransferState.Failed -> stringResource(R.string.logging_file_state_failed, item.error.orEmpty())
        item?.state == TransferState.Queued -> stringResource(R.string.logging_file_state_queued)
        item == null && partial != null && partial > 0 -> stringResource(R.string.logging_file_partial, byteCount(partial))
        else -> null
    }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.twoLineRow)
                .clickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle)
                .padding(start = Spacing.half, end = Spacing.x2)
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(name, sizeText, status).joinToString(", ")
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(selected, null, Modifier.padding(Spacing.x1).clearAndSetSemantics { }, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(name, color = FocTheme.colors.text, style = FocTheme.type.body)
                Text(listOfNotNull(sizeText, status).joinToString("  "), color = FocTheme.colors.textTertiary, style = FocTheme.type.supporting)
                if (item?.state == TransferState.Active) {
                    LinearProgressIndicator(
                        progress = { if (item.totalBytes > 0) (item.doneBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f) else 0f },
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.half),
                        color = FocTheme.colors.accent,
                        trackColor = FocTheme.colors.surfaceHigh,
                    )
                }
            }
        }
        Hairline(Modifier.padding(start = Spacing.x2))
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
}

@Composable
private fun rememberLogTitle(name: String): String = remember(name) {
    LogNames.start(name)?.let { start ->
        val zone = if (start.utc) ZoneId.of("UTC") else ZoneId.systemDefault()
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(start.dateTime.atZone(zone).withZoneSameInstant(ZoneId.systemDefault()))
    } ?: name
}

@Composable
internal fun byteCount(bytes: Long): String = when {
    bytes < 1024 -> stringResource(R.string.logging_unit_bytes, bytes.toInt())
    bytes < 1024 * 1024 -> stringResource(R.string.logging_unit_kb, (bytes / 1024f).fixed(1))
    else -> stringResource(R.string.logging_unit_mb, (bytes / 1048576f).fixed(1))
}

@Composable
private fun duration(seconds: Double): String {
    val s = seconds.toInt().coerceAtLeast(1)
    return if (s < 60) stringResource(R.string.logging_seconds, s) else stringResource(R.string.logging_minutes, s / 60, s % 60)
}

private fun share(context: android.content.Context, log: StoredLog) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", log.file)
    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.logging_share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
