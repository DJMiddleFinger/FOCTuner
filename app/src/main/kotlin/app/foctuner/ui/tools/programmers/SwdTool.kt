package app.foctuner.ui.tools.programmers

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import app.foctuner.ui.tools.parts.ChoiceRow
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.ui.tools.parts.ReadingRow
import app.foctuner.vesc.SwdImage
import app.foctuner.vesc.SwdImages
import app.foctuner.vesc.SwdProgrammer
import app.foctuner.vesc.SwdTarget
import app.foctuner.vesc.firmware.FirmwareFileException
import app.foctuner.vesc.firmware.archive.HttpFetcher
import app.foctuner.vesc.firmware.archive.UrlConnectionFetcher
import app.foctuner.vesc.swdSegmentsFromHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** What will be written: runs of data at their addresses, and where they came from. */
@Immutable
class SwdPayload(val label: String, val segments: List<Pair<Long, ByteArray>>) {
    val size: Int get() = segments.sumOf { it.second.size }
}

@Immutable
data class SwdState(
    val nrfPins: Boolean = false,
    val target: SwdTarget? = null,
    val payload: SwdPayload? = null,
    val busy: String? = null,
    val progress: Float? = null,
    val done: String? = null,
    val failed: String? = null,
)

/** VESC Tool's SWD programmer: the controller's built-in probe flashes an nRF module, a remote or another STM32. */
class SwdModel(private val context: ToolContext) {
    private val swd = SwdProgrammer(context.controller)
    private val fetcher: HttpFetcher = if (context.demo) DemoImageHost else UrlConnectionFetcher()
    private val _state = MutableStateFlow(SwdState())
    val state: StateFlow<SwdState> = _state.asStateFlow()
    private var job: Job? = null

    fun setNrfPins(on: Boolean) = _state.update { it.copy(nrfPins = on, target = null, payload = null) }

    fun connect() = work("connect") {
        if (_state.value.nrfPins) {
            if (!swd.mapNrf5xPins()) throw IOException("This hardware has no built-in nRF module wired to the probe")
        } else {
            swd.mapDefaultPins()
        }
        val target = swd.connect()
        _state.update { it.copy(target = target, payload = null) }
    }

    fun disconnect() = work("disconnect") {
        swd.disconnect()
        _state.update { it.copy(target = null, payload = null) }
    }

    fun download(image: SwdImage) = work("download") {
        val bytes = withContext(Dispatchers.IO) { SwdImages.fetch(fetcher, image) }
        val start = _state.value.target?.flashStart ?: 0
        _state.update { it.copy(payload = SwdPayload(image.name, listOf(start to bytes))) }
    }

    fun openFile(resolver: ContentResolver, uri: Uri, name: String) = work("open") {
        val bytes = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_FILE) throw IOException("The file is larger than any target's flash")
                }
                out.toByteArray()
            } ?: throw IOException("The file couldn't be opened")
        }
        val segments = try {
            if (name.endsWith(".hex", ignoreCase = true)) swdSegmentsFromHex(bytes.decodeToString()) else listOf((_state.value.target?.flashStart ?: 0L) to bytes)
        } catch (e: FirmwareFileException) {
            throw IOException(e.message, e)
        }
        _state.update { it.copy(payload = SwdPayload(name, segments)) }
    }

    fun program(verify: Boolean) = work("program") {
        val payload = _state.value.payload ?: return@work
        _state.update { it.copy(busy = ERASING) }
        swd.eraseAll()
        var written = 0
        for ((address, data) in payload.segments) {
            _state.update { it.copy(busy = WRITING) }
            swd.write(data, address, verify) { n -> _state.update { it.copy(progress = (written + n).toFloat() / payload.size) } }
            written += data.size
        }
        swd.reboot()
        _state.update { it.copy(done = payload.label) }
    }

    fun erase() = work("erase") {
        _state.update { it.copy(busy = ERASING) }
        swd.eraseAll()
        _state.update { it.copy(done = ERASED) }
    }

    private fun work(what: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        _state.update { it.copy(busy = what, progress = null, done = null, failed = null) }
        job = context.scope.launch {
            try {
                block()
                _state.update { it.copy(busy = null, progress = null) }
            } catch (e: IOException) {
                _state.update { it.copy(busy = null, progress = null, failed = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    companion object {
        const val ERASING = "erasing"
        const val WRITING = "writing"
        const val ERASED = "erased"
        private const val MAX_FILE = 2 * 1024 * 1024
    }
}

/** Demo mode: VESC Tool's image names, served as 20 KB of filler after a short wait. */
private object DemoImageHost : HttpFetcher {
    override suspend fun get(url: String, range: String?, maxBytes: Int, onProgress: (Int) -> Unit): ByteArray {
        delay(300)
        return ByteArray(20_000) { (it * 31).toByte() }
    }
}

@Composable
fun SwdTool(context: ToolContext) {
    val model = context.model { SwdModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.openFile(resolver, uri, uri.lastPathSegment?.substringAfterLast('/') ?: "image.bin")
    }
    var verify by rememberSaveable { mutableStateOf(true) }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val colors = FocTheme.colors
    val busy = state.busy != null
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.swd_intro),
            Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
            style = FocTheme.type.supporting,
            color = colors.textSecondary,
        )
        SectionHeader(stringResource(R.string.swd_target))
        ChoiceRow(listOf(false, true), state.nrfPins, { stringResource(if (it) R.string.swd_pins_nrf else R.string.swd_pins_header) }, model::setNrfPins, enabled = !busy && state.target == null)
        val target = state.target
        if (target == null) {
            OutlineButton(stringResource(R.string.swd_connect), model::connect, Modifier.padding(Spacing.x2), enabled = !busy)
        } else {
            ReadingRow(stringResource(R.string.swd_chip), target.label)
            PlainButton(stringResource(R.string.swd_disconnect), model::disconnect, Modifier.padding(horizontal = Spacing.x1), enabled = !busy)

            SectionHeader(stringResource(R.string.swd_image))
            val images = SwdImages.forTarget(target)
            if (images.isEmpty()) {
                Notice(stringResource(R.string.swd_no_images), body = stringResource(R.string.swd_no_images_body))
            }
            images.forEach { image ->
                Text(
                    image.name,
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.touchTarget)
                        .clickable(enabled = !busy) { model.download(image) }
                        .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                    style = FocTheme.type.body,
                    color = if (state.payload?.label == image.name) colors.accent else colors.text,
                )
                Hairline(Modifier.padding(start = Spacing.x2))
            }
            PlainButton(stringResource(R.string.swd_open_file), { picker.launch(arrayOf("*/*")) }, Modifier.padding(horizontal = Spacing.x1), enabled = !busy)
            state.payload?.let { p ->
                ReadingRow(stringResource(R.string.swd_selected), p.label)
                ReadingRow(stringResource(R.string.swd_size), "${p.size} B, " + p.segments.joinToString { "0x%08X".format(it.first) })
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(verify, enabled = !busy, role = Role.Checkbox) { verify = it }
                    .padding(horizontal = Spacing.x2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(verify, null, colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.outline, checkmarkColor = colors.onAccent))
                Text(stringResource(R.string.swd_verify), Modifier.padding(start = Spacing.x2), style = FocTheme.type.body, color = colors.text)
            }
            Row(Modifier.padding(horizontal = Spacing.x1)) {
                OutlineButton(stringResource(R.string.swd_program), { confirm = "program" }, Modifier.padding(Spacing.x1), enabled = !busy && state.payload != null)
                PlainButton(stringResource(R.string.swd_erase), { confirm = "erase" }, enabled = !busy)
            }
        }
        if (busy) {
            Text(
                stringResource(
                    when (state.busy) {
                        SwdModel.ERASING -> R.string.swd_erasing
                        SwdModel.WRITING -> R.string.swd_writing
                        else -> R.string.swd_working
                    },
                ),
                Modifier.padding(horizontal = Spacing.x2),
                style = FocTheme.type.supporting,
                color = colors.text,
            )
            val p = state.progress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(Spacing.x2), color = colors.accent, trackColor = colors.outline)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(Spacing.x2), color = colors.accent, trackColor = colors.outline)
            }
        }
        state.done?.let {
            Notice(if (it == SwdModel.ERASED) stringResource(R.string.swd_erased) else stringResource(R.string.swd_programmed, it))
        }
        state.failed?.let { Notice(stringResource(R.string.swd_failed), body = it, tone = NoticeTone.Fault) }
    }
    when (confirm) {
        "program" -> ConfirmDialog(
            title = stringResource(R.string.swd_program_title),
            body = stringResource(R.string.swd_program_body, state.payload?.label.orEmpty(), state.target?.label.orEmpty()),
            confirm = stringResource(R.string.swd_program),
            dismiss = stringResource(R.string.swd_cancel),
            onConfirm = {
                confirm = null
                model.program(verify)
            },
            onDismiss = { confirm = null },
        )
        "erase" -> ConfirmDialog(
            title = stringResource(R.string.swd_erase_title),
            body = stringResource(R.string.swd_erase_body, state.target?.label.orEmpty()),
            confirm = stringResource(R.string.swd_erase),
            dismiss = stringResource(R.string.swd_cancel),
            onConfirm = {
                confirm = null
                model.erase()
            },
            onDismiss = { confirm = null },
        )
    }
}
