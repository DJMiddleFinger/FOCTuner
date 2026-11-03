package app.foctuner.ui.tools.firmware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.vesc.firmware.archive.ArchiveEntry
import app.foctuner.vesc.firmware.archive.ArchiveException
import app.foctuner.vesc.firmware.archive.ArchiveOrigin

/** The firmware the archive offers for this controller: latest first, older versions on request. */
@Composable
internal fun ArchiveSection(model: FirmwareModel, state: FirmwareState) {
    val colors = FocTheme.colors
    val archive = state.archive
    var showOlder by rememberSaveable { mutableStateOf(false) }
    SectionHeader(stringResource(R.string.fw_dl_title))
    val listing = archive.listing
    when {
        archive.loading -> Note(stringResource(R.string.fw_dl_loading, model.firmware.hardwareName))
        listing == null || listing.entries.isEmpty() -> Note(stringResource(R.string.fw_dl_none, model.firmware.hardwareName))
        else -> {
            if (listing.offline) Notice(stringResource(R.string.fw_dl_offline), tone = NoticeTone.Warning)
            if (listing.incomplete) Notice(stringResource(R.string.fw_dl_incomplete), tone = NoticeTone.Warning)
            val newest = listing.entries.first().version
            val latest = listing.entries.filter { it.version == newest }
            val older = listing.entries - latest.toSet()
            latest.forEach { EntryRow(it, model, state) }
            if (older.isNotEmpty()) {
                PlainButton(
                    stringResource(if (showOlder) R.string.fw_dl_hide_older else R.string.fw_dl_show_older),
                    { showOlder = !showOlder },
                    Modifier.padding(horizontal = Spacing.x1),
                )
                if (showOlder) older.forEach { EntryRow(it, model, state) }
            }
            Text(
                stringResource(R.string.fw_dl_http_note),
                Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                color = colors.textTertiary,
                style = FocTheme.type.supporting,
            )
        }
    }
    val downloading = archive.downloading
    if (downloading != null) {
        val total = archive.total
        Text(
            stringResource(R.string.fw_dl_progress, downloading.displayName),
            Modifier.padding(horizontal = Spacing.x2),
            color = colors.text,
            style = FocTheme.type.body,
        )
        val fraction = total?.let { archive.received.toFloat() / it }
        LinearProgressIndicator(
            progress = { fraction ?: 0f },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.x2, vertical = Spacing.x1).heightIn(min = 4.dp),
            color = colors.accent,
            trackColor = colors.hairline,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        if (total != null) {
            Text(
                stringResource(R.string.fw_dl_progress_size, archive.received / 1024, total / 1024),
                Modifier.padding(horizontal = Spacing.x2),
                color = colors.textSecondary,
                style = FocTheme.type.value,
            )
        }
        PlainButton(stringResource(R.string.fw_dl_cancel), model::cancelDownload, Modifier.padding(horizontal = Spacing.x1))
    }
    archive.error?.let { Notice(stringResource(downloadErrorText(it)), tone = NoticeTone.Fault) }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
}

@Composable
private fun EntryRow(entry: ArchiveEntry, model: FirmwareModel, state: FirmwareState) {
    val colors = FocTheme.colors
    val saved = entry.key in state.archive.savedKeys
    val idle = state.running == null && state.archive.downloading == null
    val selected = state.source?.key == entry.key
    val title = listOfNotNull(entry.version, entry.file.removeSuffix(".bin")).joinToString(" · ")
    val detail = if (saved) stringResource(R.string.fw_dl_saved) else null
    val summary = stringResource(R.string.fw_dl_row, title, detail ?: "")
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.row).padding(start = Spacing.x2).semantics(mergeDescendants = true) { contentDescription = summary },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        Column(Modifier.weight(1f).padding(vertical = Spacing.x1)) {
            Text(title, color = if (selected) colors.accent else colors.text, style = FocTheme.type.body)
            if (detail != null) Text(detail, color = colors.textTertiary, style = FocTheme.type.supporting)
            if (entry.withoutLimits) Text(stringResource(R.string.fw_dl_no_limits), color = colors.warning, style = FocTheme.type.supporting)
        }
        PlainButton(stringResource(if (saved) R.string.fw_dl_use else R.string.fw_dl_download), { model.download(entry) }, enabled = idle)
    }
    Hairline(Modifier.padding(start = Spacing.x2))
}

private fun downloadErrorText(reason: ArchiveException.Reason): Int = when (reason) {
    ArchiveException.Reason.HardwareMismatch -> R.string.fw_dl_error_mismatch
    ArchiveException.Reason.Damaged, ArchiveException.Reason.TooLarge, ArchiveException.Reason.BadBundle -> R.string.fw_dl_error_damaged
    else -> R.string.fw_dl_error_network
}

@Composable
internal fun sourceLabel(entry: ArchiveEntry): String =
    stringResource(if (entry.origin == ArchiveOrigin.Repository) R.string.fw_source_repository else R.string.fw_source_archive)

/** An interrupted upload: what is safe, and the choice to continue it or start again. */
@Composable
internal fun ResumeSection(model: FirmwareModel, state: FirmwareState) {
    val checkpoint = state.checkpoint ?: return
    if (state.running != null) return
    val matches = state.file?.sha256 == checkpoint.imageSha256
    Notice(
        stringResource(R.string.fw_resume_title, (checkpoint.fraction * 100).toInt()),
        tone = NoticeTone.Warning,
        body = (listOf(R.string.fw_resume_safe, R.string.fw_resume_body, R.string.fw_resume_erased) + listOfNotNull(R.string.fw_resume_need_file.takeUnless { matches }))
            .map { stringResource(it) }.joinToString("\n"),
    )
    Row(Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1)) {
        OutlineButton(stringResource(R.string.fw_resume), model::resume, enabled = matches)
        PlainButton(stringResource(R.string.fw_start_over), model::startOver, Modifier.padding(start = Spacing.x1))
    }
}

@Composable
internal fun ChangelogDialog(view: ChangelogView, onClose: () -> Unit) {
    val colors = FocTheme.colors
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.fw_changelog_title, view.target), style = FocTheme.type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.fw_changelog_since, view.installed), color = colors.textTertiary, style = FocTheme.type.supporting)
                when {
                    !view.available -> Note(stringResource(R.string.fw_changelog_offline))
                    view.sections.isEmpty() -> Note(stringResource(R.string.fw_changelog_none))
                    else -> view.sections.forEach { section ->
                        Text(
                            listOfNotNull(section.version, section.date).joinToString(" · "),
                            Modifier.padding(top = Spacing.x2),
                            color = colors.text,
                            style = FocTheme.type.bodyStrong,
                        )
                        Text(section.notes, color = colors.textSecondary, style = FocTheme.type.supporting)
                    }
                }
            }
        },
        confirmButton = { PlainButton(stringResource(R.string.fw_changelog_close), onClose) },
        containerColor = colors.surface,
    )
}

@Composable
internal fun RecoveryDialog(onClose: () -> Unit) {
    val colors = FocTheme.colors
    val steps = listOf(
        R.string.fw_recovery_wait, R.string.fw_recovery_powercycle, R.string.fw_recovery_old_version,
        R.string.fw_recovery_leds, R.string.fw_recovery_usb, R.string.fw_recovery_swd,
    )
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.fw_recovery_title), style = FocTheme.type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                steps.forEachIndexed { i, step ->
                    Text("${i + 1}. " + stringResource(step), Modifier.padding(bottom = Spacing.x1), color = colors.textSecondary, style = FocTheme.type.supporting)
                }
            }
        },
        confirmButton = { PlainButton(stringResource(R.string.fw_recovery_close), onClose) },
        containerColor = colors.surface,
    )
}
