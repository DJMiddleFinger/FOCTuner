package app.foctuner.ui.tools.firmware

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.firmware.FirmwareFailure
import app.foctuner.vesc.firmware.FirmwareFileException
import app.foctuner.vesc.firmware.FirmwareProgress
import app.foctuner.vesc.firmware.FlashRegion
import app.foctuner.vesc.firmware.HardwareCheck

@Composable
fun FirmwareTool(context: ToolContext) {
    val model = context.model { FirmwareModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.openFile(resolver, uri)
    }
    // Leaving mid-upload wouldn't stop it, but the user should stay and watch until it is over.
    BackHandler(enabled = state.running != null) {}

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (state.running != null) {
            Notice(stringResource(R.string.fw_keep_powered), tone = NoticeTone.Warning, body = stringResource(R.string.fw_keep_powered_body))
        }
        Installed(model, state)
        FileSection(model, state) { picker.launch(arrayOf("application/octet-stream", "text/plain", "*/*")) }
        BootloaderSection(model, state)
        TargetSection(model, state)
        ProgressSection(model, state)
    }
    state.confirming?.let { Confirmation(model, state, it) }
}

@Composable
private fun Installed(model: FirmwareModel, state: FirmwareState) {
    val fw = model.firmware
    SectionHeader(stringResource(R.string.fw_installed))
    InfoRow(R.string.fw_version, if (fw.testVersion > 0) stringResource(R.string.fw_version_beta, fw.version, fw.testVersion) else fw.version)
    InfoRow(R.string.fw_hardware, fw.hardwareName)
    InfoRow(R.string.fw_hardware_type, fw.hardwareType.name)
    state.build?.let { InfoRow(R.string.fw_build, it.gitHash.take(12)) }
    if (fw.uuid.isNotEmpty()) InfoRow(R.string.fw_uuid, fw.uuid.uppercase())
}

@Composable
private fun FileSection(model: FirmwareModel, state: FirmwareState, onChoose: () -> Unit) {
    val colors = FocTheme.colors
    SectionHeader(stringResource(R.string.fw_file))
    val file = state.file
    if (file != null) {
        InfoRow(R.string.fw_file_name, file.name)
        InfoRow(R.string.fw_file_size, stringResource(R.string.fw_bytes, file.bytes.size))
        when (model.hardwareCheck(file)) {
            HardwareCheck.Match -> InfoRow(R.string.fw_built_for, file.hardware.joinToString())
            HardwareCheck.Mismatch -> Notice(
                stringResource(R.string.fw_mismatch_title, file.hardware.joinToString(), model.firmware.hardwareName),
                tone = NoticeTone.Fault,
                body = stringResource(R.string.fw_mismatch_body),
            )
            HardwareCheck.Unknown -> Notice(stringResource(R.string.fw_unknown_title), tone = NoticeTone.Warning, body = stringResource(R.string.fw_unknown_body))
        }
        if (model.hardwareCheck(file) != HardwareCheck.Match) {
            CheckRow(stringResource(R.string.fw_override, model.firmware.hardwareName), state.allowMismatch, state.running == null, model::setAllowMismatch)
        }
    } else {
        Text(
            stringResource(if (state.loadingFile) R.string.fw_file_loading else R.string.fw_file_none),
            Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
            color = colors.textSecondary,
            style = FocTheme.type.supporting,
        )
    }
    state.fileError?.let { Notice(stringResource(fileErrorText(it)), tone = NoticeTone.Warning) }
    if (state.bootloader != null && model.canEraseBootloader) {
        CheckRow(stringResource(R.string.fw_include_bootloader), state.includeBootloader, state.running == null, model::setIncludeBootloader)
    }
    Row(Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1)) {
        OutlineButton(stringResource(R.string.fw_choose_file), onChoose, enabled = state.running == null)
        val ready = file != null && (model.hardwareCheck(file) == HardwareCheck.Match || state.allowMismatch)
        PlainButton(stringResource(R.string.fw_install), { model.ask(UploadKind.Firmware) }, Modifier.padding(start = Spacing.x1), enabled = ready && state.running == null)
    }
}

@Composable
private fun BootloaderSection(model: FirmwareModel, state: FirmwareState) {
    SectionHeader(stringResource(R.string.fw_bootloader))
    val bootloader = state.bootloader
    if (bootloader == null) {
        Text(stringResource(R.string.fw_bootloader_none), Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
        return
    }
    InfoRow(R.string.fw_bootloader_image, bootloader.name)
    Text(
        stringResource(R.string.fw_bootloader_hint),
        Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        color = FocTheme.colors.textTertiary,
        style = FocTheme.type.supporting,
    )
    OutlineButton(
        stringResource(R.string.fw_bootloader_only),
        { model.ask(UploadKind.BootloaderOnly) },
        Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        enabled = state.running == null,
    )
}

@Composable
private fun TargetSection(model: FirmwareModel, state: FirmwareState) {
    SectionHeader(stringResource(R.string.fw_target))
    ChoiceRow(stringResource(R.string.fw_target_this, model.firmware.hardwareName), !state.allCan, state.running == null) { model.setAllCan(false) }
    ChoiceRow(stringResource(R.string.fw_target_all), state.allCan, state.running == null && model.canUpdateBus) { model.setAllCan(true) }
    if (!model.canUpdateBus) {
        Text(stringResource(R.string.fw_target_all_unavailable), Modifier.padding(horizontal = Spacing.x2), color = FocTheme.colors.textTertiary, style = FocTheme.type.supporting)
    }
}

@Composable
private fun ProgressSection(model: FirmwareModel, state: FirmwareState) {
    val colors = FocTheme.colors
    val progress = state.progress
    if (state.running != null) {
        SectionHeader(stringResource(R.string.fw_progress))
        Text(stringResource(stageText(progress)), Modifier.padding(horizontal = Spacing.x2), color = colors.text, style = FocTheme.type.body)
        val fraction = (progress as? FirmwareProgress.Writing)?.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.x2, vertical = Spacing.x1).height(4.dp),
                color = colors.accent,
                trackColor = colors.hairline,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Text("%.1f %%".format(fraction * 100), Modifier.padding(horizontal = Spacing.x2), color = colors.textSecondary, style = FocTheme.type.value)
        } else {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.x2, vertical = Spacing.x1).height(4.dp),
                color = colors.accent,
                trackColor = colors.hairline,
                gapSize = 0.dp,
            )
        }
        PlainButton(stringResource(R.string.fw_cancel), model::cancel, Modifier.padding(horizontal = Spacing.x1), enabled = state.cancellable)
        return
    }
    val failure = state.failure
    when {
        failure != null && failure.region == FlashRegion.Bootloader ->
            Notice(stringResource(R.string.fw_failed_bootloader), tone = NoticeTone.Fault, body = stringResource(failureText(failure.failure)))
        failure != null ->
            Notice(stringResource(R.string.fw_failed), tone = NoticeTone.Fault, body = stringResource(failureText(failure.failure)) + "\n" + stringResource(R.string.fw_failed_old_kept))
        state.cancelled -> Notice(stringResource(R.string.fw_cancelled), body = stringResource(R.string.fw_failed_old_kept))
        progress is FirmwareProgress.Restarted && progress.firmware.version == state.previousVersion ->
            Notice(stringResource(R.string.fw_same_version, progress.firmware.version), tone = NoticeTone.Warning, body = stringResource(R.string.fw_same_version_body))
        progress is FirmwareProgress.Restarted ->
            Notice(stringResource(R.string.fw_done, progress.firmware.version, progress.firmware.hardwareName), body = canSummary(progress))
        progress is FirmwareProgress.LinkClosed -> Notice(stringResource(R.string.fw_link_closed), tone = NoticeTone.Warning, body = stringResource(R.string.fw_link_closed_body))
        progress is FirmwareProgress.NoReply -> Notice(stringResource(R.string.fw_no_reply), tone = NoticeTone.Warning, body = stringResource(R.string.fw_no_reply_body))
        progress is FirmwareProgress.BootloaderWritten -> Notice(stringResource(R.string.fw_bootloader_done))
    }
}

@Composable
private fun canSummary(done: FirmwareProgress.Restarted): String? {
    if (done.canDevices.isEmpty()) return null
    val lines = mutableListOf<String>()
    for ((id, info) in done.canDevices) lines += stringResource(R.string.fw_done_can, id, info.hardwareName, info.version)
    return lines.joinToString("\n")
}

@Composable
private fun Confirmation(model: FirmwareModel, state: FirmwareState, kind: UploadKind) {
    val colors = FocTheme.colors
    val target = if (state.allCan) stringResource(R.string.fw_confirm_target_all) else stringResource(R.string.fw_confirm_target_this, model.firmware.hardwareName)
    val lines = mutableListOf<String>()
    if (kind == UploadKind.Firmware) {
        lines += stringResource(R.string.fw_confirm_install, state.file?.name.orEmpty(), target)
        if (state.includeBootloader) lines += stringResource(R.string.fw_confirm_bootloader_first)
        lines += stringResource(R.string.fw_confirm_settings)
        lines += stringResource(R.string.fw_confirm_wrong_hardware)
        lines += stringResource(R.string.fw_confirm_power)
        lines += stringResource(R.string.fw_confirm_failure)
    } else {
        lines += stringResource(R.string.fw_confirm_bootloader_only, state.bootloader?.name.orEmpty(), target)
        lines += stringResource(if (model.canEraseBootloader) R.string.fw_confirm_bootloader_risk else R.string.fw_confirm_bootloader_no_erase)
    }
    if (state.allCan) lines += stringResource(R.string.fw_confirm_all_can)
    AlertDialog(
        onDismissRequest = model::dismissConfirmation,
        title = { Text(stringResource(if (kind == UploadKind.Firmware) R.string.fw_confirm_title else R.string.fw_confirm_bootloader_title), style = FocTheme.type.title) },
        text = {
            Column {
                lines.forEach { Text(it, Modifier.padding(bottom = Spacing.x1), color = colors.textSecondary, style = FocTheme.type.supporting) }
            }
        },
        confirmButton = { PlainButton(stringResource(if (kind == UploadKind.Firmware) R.string.fw_confirm_go else R.string.fw_confirm_bootloader_go), model::start) },
        dismissButton = { PlainButton(stringResource(R.string.fw_cancel), model::dismissConfirmation) },
        containerColor = colors.surface,
    )
}

@Composable
private fun InfoRow(label: Int, value: String) {
    val colors = FocTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.row).padding(horizontal = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f), color = colors.text, style = FocTheme.type.body)
        Text(value, Modifier.padding(start = Spacing.x2), color = colors.text, style = FocTheme.type.value)
    }
    Hairline(Modifier.padding(start = Spacing.x2))
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = FocTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.row)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = if (enabled) colors.text else colors.textTertiary, style = FocTheme.type.body)
        Checkbox(checked, null, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.outline, checkmarkColor = colors.onAccent))
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val colors = FocTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.row)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.outline))
        Text(label, Modifier.padding(start = Spacing.x2), color = if (enabled) colors.text else colors.textTertiary, style = FocTheme.type.body)
    }
}

private fun stageText(progress: FirmwareProgress?): Int = when (progress) {
    is FirmwareProgress.Erasing -> if (progress.region == FlashRegion.Bootloader) R.string.fw_stage_erase_bootloader else R.string.fw_stage_erase
    is FirmwareProgress.Writing -> if (progress.region == FlashRegion.Bootloader) R.string.fw_stage_write_bootloader else R.string.fw_stage_write
    FirmwareProgress.Installing -> R.string.fw_stage_installing
    else -> R.string.fw_stage_preparing
}

private fun fileErrorText(reason: FirmwareFileException.Reason): Int = when (reason) {
    FirmwareFileException.Reason.NotFirmwareName -> R.string.fw_file_error_name
    FirmwareFileException.Reason.Empty -> R.string.fw_file_error_empty
    FirmwareFileException.Reason.TooLarge -> R.string.fw_file_error_large
    FirmwareFileException.Reason.BadHex -> R.string.fw_file_error_hex
}

private fun failureText(failure: FirmwareFailure): Int = when (failure) {
    FirmwareFailure.HardwareMismatch -> R.string.fw_fail_mismatch
    FirmwareFailure.CanBusMismatch -> R.string.fw_fail_can
    FirmwareFailure.Unsupported -> R.string.fw_fail_unsupported
    FirmwareFailure.TooLarge -> R.string.fw_fail_large
    FirmwareFailure.EraseFailed -> R.string.fw_fail_erase
    FirmwareFailure.WriteFailed -> R.string.fw_fail_write
    FirmwareFailure.NoReply -> R.string.fw_fail_no_reply
    FirmwareFailure.LinkLost -> R.string.fw_fail_link
}
