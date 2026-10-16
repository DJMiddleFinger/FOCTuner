package app.foctuner.ui.tools.lisp

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.LispCode
import app.foctuner.vesc.LispImportException
import app.foctuner.vesc.LispProgram
import app.foctuner.vesc.LispStreamMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private enum class Pane(@param:StringRes val label: Int) {
    Editor(R.string.lisp_pane_editor),
    Console(R.string.lisp_pane_console),
    Stats(R.string.lisp_pane_stats),
    Files(R.string.lisp_pane_files),
}

/** What to do once the imports folder has been picked. */
private enum class AfterFolder { Upload, Info }

/** Dialogs; the ones that write flash or start code say so before anything happens. */
private sealed interface Ask {
    class Upload(val packed: ByteArray) : Ask
    data object Run : Ask
    data object Stream : Ask
    data object Erase : Ask
    data class Close(val document: LispDocument) : Ask
    data class ImportsFolder(val then: AfterFolder) : Ask
    data class SaveImports(val program: LispProgram) : Ask
    data class Info(val size: Int, val reduced: Int) : Ask
    data object Help : Ask
    data object ClearRecent : Ask
}

/** VESC Tool's LispBM page: editor, console and REPL, stats and binding plot, recent files and examples. */
@Composable
fun LispTool(context: ToolContext) {
    val workspace: LispWorkspace = viewModel()
    val model = context.model { LispModel(context) }
    val scope = rememberCoroutineScope()
    var pane by rememberSaveable { mutableStateOf(Pane.Editor) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var savingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var afterFolder by rememberSaveable { mutableStateOf<AfterFolder?>(null) }
    var importsToSave by remember { mutableStateOf<LispProgram?>(null) }
    val find = remember { FindState() }
    val fromVesc = stringResource(R.string.lisp_from_vesc)
    val untitledFile = stringResource(R.string.lisp_untitled_file)

    LaunchedEffect(workspace.pollMode, workspace.pollRate) { model.poll(workspace.pollMode, workspace.pollRate) }
    LaunchedEffect(model.readBack) {
        val program = model.readBack ?: return@LaunchedEffect
        model.readBack = null
        workspace.newDocument(program.code, fromVesc)
        pane = Pane.Editor
        if (program.imports.isNotEmpty()) ask = Ask.SaveImports(program)
    }

    fun fileFailed(e: Exception) {
        model.notice = LispNotice(R.string.lisp_file_failed, e.message)
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    workspace.open(uri)
                    pane = Pane.Editor
                } catch (e: IOException) {
                    fileFailed(e)
                } catch (e: SecurityException) {
                    fileFailed(e)
                }
            }
        }
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LispFiles.BINARY)) { uri ->
        val document = workspace.documents.firstOrNull { it.id == savingId }
        savingId = null
        if (uri != null && document != null) {
            scope.launch {
                try {
                    workspace.saveAs(document, uri)
                } catch (e: IOException) {
                    fileFailed(e)
                } catch (e: SecurityException) {
                    fileFailed(e)
                }
            }
        }
    }

    fun saveAs(document: LispDocument) {
        savingId = document.id
        val name = document.name ?: untitledFile
        saveAs.launch(if (name.endsWith(".lbm") || name.endsWith(".lisp")) name else "$name.lbm")
    }

    fun save(document: LispDocument = workspace.current) {
        scope.launch {
            val saved = try {
                workspace.save(document)
            } catch (e: IOException) {
                false
            } catch (e: SecurityException) {
                false
            }
            if (!saved) saveAs(document)
        }
    }

    /** Packs the current code; on failure explains why (or asks for the imports folder) and returns null. */
    suspend fun pack(reduce: Boolean, then: AfterFolder): ByteArray? = try {
        workspace.pack(workspace.current, reduce)
    } catch (e: ImportsFolderNeeded) {
        ask = Ask.ImportsFolder(then)
        null
    } catch (e: LispImportException) {
        model.notice = LispNotice(R.string.lisp_import_failed, e.message, R.string.lisp_import_failed_hint)
        null
    } catch (e: IOException) {
        model.notice = LispNotice(R.string.lisp_import_failed, e.message, R.string.lisp_import_failed_hint)
        null
    }

    fun upload() {
        scope.launch {
            val packed = pack(workspace.reduce, AfterFolder.Upload) ?: return@launch
            val max = LispCode.maxImageSize(context.firmware.hardwareType)
            if (packed.size + IMAGE_HEADER > max) {
                model.notice = LispNotice(R.string.lisp_too_large, "${packed.size + IMAGE_HEADER} / $max B")
            } else {
                ask = Ask.Upload(packed)
            }
        }
    }

    fun info() {
        scope.launch {
            val plain = pack(false, AfterFolder.Info) ?: return@launch
            val reduced = pack(true, AfterFolder.Info) ?: return@launch
            ask = Ask.Info(plain.size, reduced.size)
        }
    }

    fun stream() {
        val code = workspace.current.value.text
        val hasImports = try {
            LispCode.imports(code).isNotEmpty()
        } catch (e: LispImportException) {
            true
        }
        if (hasImports) model.notice = LispNotice(R.string.lisp_stream_imports) else ask = Ask.Stream
    }

    fun evaluateSelection() {
        val value = workspace.current.value
        val text = if (value.selection.collapsed) {
            val start = value.text.lastIndexOf('\n', value.selection.start - 1) + 1
            val end = value.text.indexOf('\n', value.selection.start).let { if (it < 0) value.text.length else it }
            value.text.substring(start, end)
        } else {
            value.text.substring(value.selection.min, value.selection.max)
        }
        if (text.isNotBlank()) {
            model.evaluate(text)
            pane = Pane.Console
        }
    }

    fun close(document: LispDocument) {
        if (document.dirty) ask = Ask.Close(document) else workspace.close(document)
    }

    val pickImportsFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val then = afterFolder
        afterFolder = null
        if (uri != null) {
            workspace.files.keep(uri, write = true)
            workspace.importsFolder = uri
            when (then) {
                AfterFolder.Upload -> upload()
                AfterFolder.Info -> info()
                null -> Unit
            }
        }
    }
    val pickImportsTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val program = importsToSave
        importsToSave = null
        if (uri != null && program != null) {
            scope.launch {
                try {
                    saveReadBack(workspace, uri, program, fromVesc)
                } catch (e: IOException) {
                    fileFailed(e)
                } catch (e: SecurityException) {
                    fileFailed(e)
                }
            }
        }
    }

    val document = workspace.current
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.S -> save()
                    Key.F -> {
                        find.open = true
                        pane = Pane.Editor
                    }
                    Key.Z -> if (event.isShiftPressed) workspace.redo(document) else workspace.undo(document)
                    Key.Y -> workspace.redo(document)
                    Key.E -> upload()
                    Key.W -> stream()
                    Key.Q -> model.stop()
                    Key.D -> if (event.isShiftPressed) workspace.replace(document, duplicateLines(document.value)) else model.clearConsole()
                    Key.R -> evaluateSelection()
                    Key.Slash -> workspace.replace(document, toggleComment(document.value))
                    Key.Equals, Key.Plus -> workspace.fontSize = (workspace.fontSize + 1).coerceAtMost(MAX_FONT)
                    Key.Minus -> workspace.fontSize = (workspace.fontSize - 1).coerceAtLeast(MIN_FONT)
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchTarget)
                .padding(start = Spacing.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pane.entries.forEach { entry -> Choice(stringResource(entry.label), selected = pane == entry) { pane = entry } }
            Box(Modifier.weight(1f))
            Menu(
                workspace = workspace,
                busy = model.busy,
                onOpen = { openFile.launch(arrayOf("*/*")) },
                onSave = { save() },
                onSaveAs = { saveAs(document) },
                onClose = { close(document) },
                onFind = {
                    find.open = true
                    pane = Pane.Editor
                },
                onEvaluate = ::evaluateSelection,
                onRead = model::read,
                onErase = { ask = Ask.Erase },
                onInfo = ::info,
                onImportsFolder = { pickImportsFolder.launch(workspace.importsFolder) },
                onHelp = { ask = Ask.Help },
            )
        }
        Hairline()
        model.progress?.let { progress -> ProgressStrip(progress, model::cancel) }
        model.notice?.let { notice ->
            Notice(
                stringResource(notice.title),
                body = notice.detail,
                tone = NoticeTone.Warning,
                checks = if (notice.hint != 0) listOf(stringResource(notice.hint)) else emptyList(),
                action = stringResource(R.string.lisp_dismiss),
                onAction = { model.notice = null },
            )
        }
        val paneModifier = Modifier
            .weight(1f)
            .fillMaxWidth()
        when (pane) {
            Pane.Editor -> EditorPane(
                workspace = workspace,
                find = find,
                busy = model.busy,
                onUpload = ::upload,
                onStream = ::stream,
                onRun = { ask = Ask.Run },
                onStop = model::stop,
                onClose = ::close,
                modifier = paneModifier,
            )
            Pane.Console -> ConsolePane(model, paneModifier)
            Pane.Stats -> StatsPane(model, workspace, paneModifier)
            Pane.Files -> FilesPane(
                workspace = workspace,
                onOpenFile = { openFile.launch(arrayOf("*/*")) },
                onOpenRecent = { file ->
                    scope.launch {
                        try {
                            workspace.open(file.uri)
                            pane = Pane.Editor
                        } catch (e: IOException) {
                            fileFailed(e)
                        } catch (e: SecurityException) {
                            fileFailed(e)
                        }
                    }
                },
                onOpenExample = { name ->
                    scope.launch {
                        workspace.openExample(name)
                        pane = Pane.Editor
                    }
                },
                onClearRecent = { ask = Ask.ClearRecent },
                modifier = paneModifier,
            )
        }
    }

    when (val current = ask) {
        null -> Unit
        is Ask.Upload -> ConfirmDialog(
            title = stringResource(R.string.lisp_confirm_upload_title),
            body = stringResource(
                if (workspace.autoRun) R.string.lisp_confirm_upload_run else R.string.lisp_confirm_upload,
                current.packed.size,
            ),
            confirm = stringResource(if (workspace.autoRun) R.string.lisp_upload_and_run else R.string.lisp_upload),
            onConfirm = { model.upload(current.packed, workspace.autoRun) },
            onDismiss = { ask = null },
        )
        Ask.Run -> ConfirmDialog(
            title = stringResource(R.string.lisp_confirm_run_title),
            body = stringResource(R.string.lisp_confirm_run),
            confirm = stringResource(R.string.lisp_run),
            onConfirm = model::run,
            onDismiss = { ask = null },
        )
        Ask.Stream -> ConfirmDialog(
            title = stringResource(R.string.lisp_confirm_stream_title),
            body = stringResource(R.string.lisp_confirm_stream, stringResource(workspace.streamMode.label)),
            confirm = stringResource(R.string.lisp_stream),
            onConfirm = { model.stream(workspace.current.value.text, workspace.streamMode) },
            onDismiss = { ask = null },
        )
        Ask.Erase -> ConfirmDialog(
            title = stringResource(R.string.lisp_confirm_erase_title),
            body = stringResource(R.string.lisp_confirm_erase),
            confirm = stringResource(R.string.lisp_erase),
            onConfirm = model::erase,
            onDismiss = { ask = null },
        )
        is Ask.Close -> ConfirmDialog(
            title = stringResource(R.string.lisp_confirm_close_title, current.document.name ?: stringResource(R.string.lisp_untitled)),
            body = stringResource(R.string.lisp_confirm_close),
            confirm = stringResource(R.string.lisp_close_discard),
            onConfirm = { workspace.close(current.document) },
            onDismiss = { ask = null },
        )
        is Ask.ImportsFolder -> ConfirmDialog(
            title = stringResource(R.string.lisp_imports_folder_title),
            body = stringResource(R.string.lisp_imports_folder_body),
            confirm = stringResource(R.string.lisp_pick_folder),
            onConfirm = {
                afterFolder = current.then
                pickImportsFolder.launch(workspace.importsFolder)
            },
            onDismiss = { ask = null },
        )
        is Ask.SaveImports -> ConfirmDialog(
            title = stringResource(R.string.lisp_save_imports_title, current.program.imports.size),
            body = stringResource(R.string.lisp_save_imports_body),
            confirm = stringResource(R.string.lisp_pick_folder),
            onConfirm = {
                importsToSave = current.program
                pickImportsTarget.launch(null)
            },
            onDismiss = { ask = null },
        )
        is Ask.Info -> MessageDialog(
            title = stringResource(R.string.lisp_info_title),
            body = stringResource(
                R.string.lisp_info_body,
                current.size,
                current.reduced,
                stringResource(if (workspace.reduce) R.string.lisp_on else R.string.lisp_off),
            ),
            onDismiss = { ask = null },
        )
        Ask.Help -> HelpDialog(onDismiss = { ask = null })
        Ask.ClearRecent -> ConfirmDialog(
            title = stringResource(R.string.lisp_confirm_clear_recent_title),
            body = stringResource(R.string.lisp_confirm_clear_recent),
            confirm = stringResource(R.string.lisp_clear),
            onConfirm = workspace::clearRecent,
            onDismiss = { ask = null },
        )
    }
}

/**
 * Writes code read from the controller and its imports into [folder]: the code as "From VESC.lbm", each import
 * at the relative path its import line names (or as tag.bin). The folder becomes the imports folder, so the
 * code can be uploaded again as it is.
 */
private suspend fun saveReadBack(workspace: LispWorkspace, folder: Uri, program: LispProgram, name: String) {
    val paths = try {
        LispCode.imports(program.code).associate { it.tag to it.path }
    } catch (e: LispImportException) {
        emptyMap()
    }
    withContext(Dispatchers.IO) {
        for ((tag, bytes) in program.imports) {
            val path = paths[tag]?.takeUnless { it.startsWith("/") || it.startsWith("\\") || it.startsWith("pkg") } ?: "$tag.bin"
            workspace.files.writeInTree(folder, path, bytes)
        }
        workspace.files.writeInTree(folder, "$name.lbm", program.code.encodeToByteArray())
    }
    workspace.files.keep(folder, write = true)
    workspace.importsFolder = folder
}

@Composable
private fun ProgressStrip(progress: Progress, onCancel: () -> Unit) {
    val colors = FocTheme.colors
    Column(Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val label = stringResource(progress.transfer.label)
            Text(
                if (progress.total > 0) stringResource(R.string.lisp_progress, label, progress.done, progress.total) else label,
                Modifier.weight(1f),
                color = colors.text,
                style = FocTheme.type.supporting,
            )
            PlainButton(stringResource(R.string.lisp_cancel), onCancel, enabled = progress.transfer != Transfer.Erase)
        }
        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
                color = colors.accent,
                trackColor = colors.surfaceHigh,
                drawStopIndicator = {},
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.surfaceHigh)
        }
    }
    Hairline()
}

@Composable
private fun Menu(
    workspace: LispWorkspace,
    busy: Boolean,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onClose: () -> Unit,
    onFind: () -> Unit,
    onEvaluate: () -> Unit,
    onRead: () -> Unit,
    onErase: () -> Unit,
    onInfo: () -> Unit,
    onImportsFolder: () -> Unit,
    onHelp: () -> Unit,
) {
    val colors = FocTheme.colors
    var open by remember { mutableStateOf(false) }
    val document = workspace.current
    Box {
        PlainButton(stringResource(R.string.lisp_more), { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = colors.surfaceHigh,
            shape = MaterialTheme.shapes.small,
        ) {
            @Composable
            fun item(@StringRes label: Int, enabled: Boolean = true, checked: Boolean? = null, action: () -> Unit) {
                DropdownMenuItem(
                    text = { Text(stringResource(label), color = if (enabled) colors.text else colors.textTertiary, style = FocTheme.type.body) },
                    onClick = {
                        open = false
                        action()
                    },
                    enabled = enabled,
                    trailingIcon = if (checked == true) {
                        { Text("✓", color = colors.accent, style = FocTheme.type.body) }
                    } else {
                        null
                    },
                    modifier = Modifier.semantics { if (checked != null) selected = checked },
                )
            }

            @Composable
            fun divider() = HorizontalDivider(color = colors.hairline)

            item(R.string.lisp_menu_new) { workspace.newDocument() }
            item(R.string.lisp_menu_open, action = onOpen)
            item(R.string.lisp_menu_save, action = onSave)
            item(R.string.lisp_menu_save_as, action = onSaveAs)
            item(R.string.lisp_menu_close, action = onClose)
            divider()
            item(R.string.lisp_menu_undo, enabled = document.history.canUndo) { workspace.undo(document) }
            item(R.string.lisp_menu_redo, enabled = document.history.canRedo) { workspace.redo(document) }
            item(R.string.lisp_menu_find, action = onFind)
            item(R.string.lisp_menu_comment) { workspace.replace(document, toggleComment(document.value)) }
            item(R.string.lisp_menu_duplicate) { workspace.replace(document, duplicateLines(document.value)) }
            item(R.string.lisp_menu_evaluate, enabled = !busy, action = onEvaluate)
            item(R.string.lisp_menu_text_larger, enabled = workspace.fontSize < MAX_FONT) { workspace.fontSize++ }
            item(R.string.lisp_menu_text_smaller, enabled = workspace.fontSize > MIN_FONT) { workspace.fontSize-- }
            divider()
            item(R.string.lisp_menu_read, enabled = !busy, action = onRead)
            item(R.string.lisp_menu_erase, enabled = !busy, action = onErase)
            item(R.string.lisp_menu_info, action = onInfo)
            item(R.string.lisp_menu_imports_folder, action = onImportsFolder)
            divider()
            item(R.string.lisp_menu_auto_run, checked = workspace.autoRun) { workspace.autoRun = !workspace.autoRun }
            item(R.string.lisp_menu_reduce, checked = workspace.reduce) { workspace.reduce = !workspace.reduce }
            LispStreamMode.entries.forEach { mode ->
                item(mode.label, checked = workspace.streamMode == mode) { workspace.streamMode = mode }
            }
            divider()
            item(R.string.lisp_menu_help, action = onHelp)
        }
    }
}

private val Transfer.label: Int
    get() = when (this) {
        Transfer.Upload -> R.string.lisp_uploading
        Transfer.Read -> R.string.lisp_reading
        Transfer.Stream -> R.string.lisp_streaming
        Transfer.Erase -> R.string.lisp_erasing
    }

private val LispStreamMode.label: Int
    get() = when (this) {
        LispStreamMode.LoadOnTop -> R.string.lisp_stream_on_top
        LispStreamMode.ResetAndLoad -> R.string.lisp_stream_reset
        LispStreamMode.ReloadFlashAndLoad -> R.string.lisp_stream_reload
    }

/** Length and CRC in front of the packed code in flash. */
private const val IMAGE_HEADER = 6
private const val MIN_FONT = 10
private const val MAX_FONT = 24
