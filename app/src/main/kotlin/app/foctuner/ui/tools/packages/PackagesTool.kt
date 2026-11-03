package app.foctuner.ui.tools.packages

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.vesc.PackageCompatibility.Verdict
import app.foctuner.vesc.PackageInstallStep
import java.text.DateFormat
import java.util.Date

@Composable
fun PackagesTool(context: ToolContext, onOpenRefloat: () -> Unit = {}) {
    val android = LocalContext.current
    val model = context.model { PackagesModel(context, android.filesDir) }
    val state by model.state.collectAsStateWithLifecycle()
    val resolver = android.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.openFile(resolver, uri)
    }
    val opened = state.opened
    if (opened != null) {
        BackHandler { model.open(null) }
        PackagePage(opened, state.install, context.demo, model::install, { model.open(null) }, onOpenRefloat)
    } else {
        StoreList(state, model, onOpenFile = { picker.launch(arrayOf("*/*")) })
    }
}

@Composable
private fun StoreList(state: PackagesState, model: PackagesModel, onOpenFile: () -> Unit) {
    val colors = FocTheme.colors
    var confirmUninstall by rememberSaveable { mutableStateOf(false) }
    val running = state.install is InstallState.Running
    LazyColumn {
        item {
            Text(
                stringResource(R.string.packages_intro),
                Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                style = FocTheme.type.supporting,
                color = colors.textSecondary,
            )
            OutlinedTextField(
                state.filter, model::setFilter,
                Modifier.fillMaxWidth().padding(horizontal = Spacing.x2),
                singleLine = true,
                label = { Text(stringResource(R.string.packages_filter)) },
            )
            CheckRow(stringResource(R.string.packages_show_all), state.showAll, model::setShowAll)
            CheckRow(stringResource(R.string.packages_show_libraries), state.showLibraries, model::setShowLibraries)
            if (state.loading) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                    color = colors.accent,
                    trackColor = colors.outline,
                )
            }
            Row(Modifier.padding(horizontal = Spacing.x1), verticalAlignment = Alignment.CenterVertically) {
                PlainButton(stringResource(R.string.packages_refresh), model::refresh, enabled = !state.loading)
                PlainButton(stringResource(R.string.packages_open_file), onOpenFile)
                PlainButton(stringResource(R.string.packages_uninstall), { confirmUninstall = true }, enabled = !running)
            }
            state.fetchedAt?.let {
                Text(
                    stringResource(R.string.packages_fetched, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))),
                    Modifier.padding(horizontal = Spacing.x2),
                    style = FocTheme.type.caption,
                    color = colors.textTertiary,
                )
            }
            state.loadFailed?.let {
                Notice(
                    stringResource(R.string.packages_load_failed),
                    body = if (state.rows != null) stringResource(R.string.packages_load_failed_cached, it) else it,
                    tone = NoticeTone.Warning,
                )
            }
            InstallNotice(state.install)
            Hairline()
        }
        items(state.visible, key = { it.item.path }) { row -> StoreRowItem(row) { model.open(row.item.path) } }
        if (state.rows != null && state.visible.isEmpty()) {
            item { Notice(stringResource(R.string.packages_none_match)) }
        }
    }
    if (confirmUninstall) {
        ConfirmDialog(
            title = stringResource(R.string.packages_uninstall_title),
            body = stringResource(R.string.packages_uninstall_body),
            confirm = stringResource(R.string.packages_uninstall),
            dismiss = stringResource(R.string.packages_cancel),
            onConfirm = {
                confirmUninstall = false
                model.uninstall()
            },
            onDismiss = { confirmUninstall = false },
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = FocTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, null, colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.outline, checkmarkColor = colors.onAccent))
        Text(label, Modifier.padding(start = Spacing.x2), style = FocTheme.type.body, color = colors.text)
    }
}

@Composable
private fun StoreRowItem(row: StoreRow, onClick: () -> Unit) {
    val colors = FocTheme.colors
    val summary = remember(row.item.path) { firstParagraph(row.item.pkg.descriptionMarkdown, row.item.pkg.description) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = Spacing.touchTarget)
            .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.item.pkg.name, Modifier.weight(1f), style = FocTheme.type.body.copy(fontWeight = FontWeight.Medium), color = colors.text)
            Text(verdictLabel(row), style = FocTheme.type.caption, color = if (row.verdict == Verdict.Incompatible) colors.warning else colors.textSecondary)
        }
        if (summary.isNotEmpty()) Text(summary, style = FocTheme.type.supporting, color = colors.textSecondary, maxLines = 2)
    }
    Hairline()
}

@Composable
private fun verdictLabel(row: StoreRow): String = stringResource(
    when {
        row.item.isLibrary -> R.string.packages_library
        row.verdict == Verdict.Compatible -> R.string.packages_fits
        row.verdict == Verdict.Incompatible -> R.string.packages_not_for_this
        else -> R.string.packages_unchecked
    },
)

@Composable
private fun PackagePage(
    row: StoreRow,
    install: InstallState?,
    demo: Boolean,
    onInstall: (StoreRow) -> Unit,
    onBack: () -> Unit,
    onOpenRefloat: () -> Unit,
) {
    val colors = FocTheme.colors
    val pkg = row.item.pkg
    var confirm by rememberSaveable { mutableStateOf(false) }
    val running = install is InstallState.Running
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PlainButton(stringResource(R.string.packages_back), onBack, Modifier.padding(horizontal = Spacing.x1))
        SectionHeader(pkg.name)
        when {
            row.item.isLibrary -> Notice(stringResource(R.string.packages_library_title), body = stringResource(R.string.packages_library_body))
            row.verdict == Verdict.Incompatible -> Notice(stringResource(R.string.packages_not_for_this_title), body = stringResource(R.string.packages_not_for_this_body), tone = NoticeTone.Warning)
            row.verdict == Verdict.Unknown -> Notice(stringResource(R.string.packages_unchecked_title), body = stringResource(R.string.packages_unchecked_body))
            else -> Unit
        }
        val contents = listOfNotNull(
            pkg.lisp?.let { stringResource(R.string.packages_has_lisp) },
            pkg.qml?.let { stringResource(if (pkg.qmlFullscreen) R.string.packages_has_qml_fullscreen else R.string.packages_has_qml) },
        )
        if (contents.isNotEmpty()) {
            Text(
                contents.joinToString("\n"),
                Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                style = FocTheme.type.supporting,
                color = colors.textSecondary,
            )
        }
        if (isRefloat(pkg.name)) OutlineButton(stringResource(R.string.packages_open_refloat), onOpenRefloat, Modifier.padding(horizontal = Spacing.x2))
        if (!row.item.isLibrary) {
            OutlineButton(
                stringResource(R.string.packages_install),
                { confirm = true },
                Modifier.padding(Spacing.x2),
                enabled = !running,
            )
        }
        InstallNotice(install)
        Hairline()
        MarkdownText(pkg.descriptionMarkdown.ifBlank { htmlToText(pkg.description) }, Modifier.padding(Spacing.x2))
    }
    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.packages_install_title, pkg.name),
            body = buildString {
                append(stringResource(R.string.packages_install_body))
                if (!demo && !row.item.path.startsWith("content:")) append("\n\n").append(stringResource(R.string.packages_install_source))
                if (row.verdict == Verdict.Incompatible) append("\n\n").append(stringResource(R.string.packages_install_incompatible))
            },
            confirm = stringResource(R.string.packages_install),
            dismiss = stringResource(R.string.packages_cancel),
            onConfirm = {
                confirm = false
                onInstall(row)
            },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun InstallNotice(install: InstallState?) {
    val colors = FocTheme.colors
    when (install) {
        is InstallState.Running -> {
            Text(
                stringResource(stepLabel(install.step)),
                Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                style = FocTheme.type.supporting,
                color = colors.text,
            )
            if (install.total > 0) {
                LinearProgressIndicator(
                    progress = { install.done.toFloat() / install.total },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.x2),
                    color = colors.accent,
                    trackColor = colors.outline,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Spacing.x2), color = colors.accent, trackColor = colors.outline)
            }
        }
        is InstallState.Installed -> Notice(stringResource(R.string.packages_installed, install.name), body = stringResource(R.string.packages_installed_body))
        InstallState.Uninstalled -> Notice(stringResource(R.string.packages_uninstalled), body = stringResource(R.string.packages_uninstalled_body))
        is InstallState.Failed -> Notice(stringResource(R.string.packages_failed), body = install.message, tone = NoticeTone.Fault)
        null -> Unit
    }
}

private fun stepLabel(step: PackageInstallStep?): Int = when (step) {
    PackageInstallStep.ErasingQml -> R.string.packages_step_erase_qml
    PackageInstallStep.WritingQml -> R.string.packages_step_write_qml
    PackageInstallStep.ErasingLisp -> R.string.packages_step_erase_lisp
    PackageInstallStep.WritingLisp -> R.string.packages_step_write_lisp
    PackageInstallStep.StartingLisp -> R.string.packages_step_start
    null -> R.string.packages_step_working
}

internal fun isRefloat(name: String) = name.trim().equals("Refloat", ignoreCase = true)
