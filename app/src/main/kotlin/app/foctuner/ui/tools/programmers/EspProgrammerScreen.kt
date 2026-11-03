package app.foctuner.ui.tools.programmers

import android.app.Application
import android.content.ContentResolver
import android.hardware.usb.UsbManager
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.foctuner.R
import app.foctuner.link.Links
import app.foctuner.sim.DemoArchive
import app.foctuner.sim.EspRomSim
import app.foctuner.transport.LinkDevice
import app.foctuner.transport.LinkKind
import app.foctuner.transport.Transport
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.ui.tools.parts.ReadingRow
import app.foctuner.vesc.esp.EspChip
import app.foctuner.vesc.esp.EspLoader
import app.foctuner.vesc.esp.SerialLines
import app.foctuner.vesc.firmware.archive.ExpressBuild
import app.foctuner.vesc.firmware.archive.FirmwareArchive
import app.foctuner.vesc.firmware.archive.FirmwareCache
import app.foctuner.vesc.firmware.archive.UrlConnectionFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

@Immutable
data class EspState(
    val devices: List<LinkDevice> = emptyList(),
    val chip: EspChip? = null,
    val flashSize: Int = 0,
    val simulated: Boolean = false,
    val builds: List<ExpressBuild>? = null,
    /** Bootloader, partition table and app, with where they came from. */
    val images: Pair<String, List<ByteArray>>? = null,
    val files: List<Pair<String, ByteArray>?> = listOf(null, null, null),
    val busy: Int? = null,
    val progress: Float? = null,
    val done: Int? = null,
    val failed: String? = null,
)

/**
 * VESC Tool's ESP programmer: a VESC Express (or any ESP32-C3 / S3) on the phone's USB port, flashed through the
 * chip's own ROM bootloader. Works without a VESC connection, so a blank or broken Express can be brought back.
 */
class EspProgrammerViewModel(application: Application) : AndroidViewModel(application) {
    private val links = Links(application)
    private val _state = MutableStateFlow(EspState())
    val state: StateFlow<EspState> = _state.asStateFlow()
    private var link: Transport? = null
    private var loader: EspLoader? = null
    private var job: Job? = null

    private fun archive(simulated: Boolean) = FirmwareArchive(
        if (simulated) DemoArchive.host() else UrlConnectionFetcher(),
        FirmwareCache(File(getApplication<Application>().filesDir, if (simulated) "firmware-demo/cache" else "firmware/cache")),
    )

    fun scan() = work(R.string.esp_busy_scan) {
        val devices = withTimeoutOrNull(1500) { links.scan(setOf(LinkKind.USB)).first() }.orEmpty()
        _state.update { it.copy(devices = devices) }
    }

    fun connect(device: LinkDevice?) = work(R.string.esp_busy_connect) {
        disconnectQuietly()
        val (transport, lines, builtin) = if (device == null) {
            val rom = EspRomSim()
            Triple(rom as Transport, rom as SerialLines, true)
        } else {
            val usb = getApplication<Application>().getSystemService(UsbManager::class.java).deviceList[device.id]
            val opened = links.open(device)
            val lines = opened as? SerialLines ?: throw IOException("This USB device can't drive the reset lines")
            Triple(opened, lines, usb?.vendorId == ESPRESSIF_VID)
        }
        link = transport
        val l = EspLoader(transport, lines, viewModelScope)
        loader = l
        val chip = l.connect(builtinUsb = builtin)
        _state.update { it.copy(chip = chip, flashSize = l.flashSize, simulated = device == null, images = null, builds = null) }
        val builds = runCatching { withContext(Dispatchers.IO) { archive(device == null).expressBuilds() } }.getOrNull()
        _state.update { it.copy(builds = builds?.filter { b -> b.chip.equals(chip.name, ignoreCase = true) }) }
    }

    fun choose(build: ExpressBuild) = work(R.string.esp_busy_download) {
        val images = withContext(Dispatchers.IO) { archive(_state.value.simulated).expressImages(build) }
        _state.update { it.copy(images = build.hardware to images) }
    }

    fun openFile(index: Int, resolver: ContentResolver, uri: Uri) = work(R.string.esp_busy_open) {
        val bytes = withContext(Dispatchers.IO) { resolver.openInputStream(uri)?.use { it.readBytes() } } ?: throw IOException("The file couldn't be opened")
        val files = _state.value.files.toMutableList().also { it[index] = (uri.lastPathSegment?.substringAfterLast('/') ?: "file") to bytes }
        _state.update { s -> s.copy(files = files, images = if (files.all { it != null }) "files" to files.map { it!!.second } else s.images) }
    }

    fun flashAll() = work(R.string.esp_busy_flash) {
        val l = loader ?: return@work
        val images = _state.value.images?.second ?: return@work
        val offsets = listOf(EspLoader.BOOTLOADER_OFFSET, EspLoader.PARTITION_OFFSET, EspLoader.APP_OFFSET)
        val total = images.sumOf { it.size }.toFloat()
        var before = 0
        images.forEachIndexed { i, image ->
            l.flash(image, offsets[i]) { n -> _state.update { it.copy(progress = (before + n) / total) } }
            before += image.size
        }
        l.reset()
        _state.update { it.copy(done = R.string.esp_done_flash, chip = null) }
        disconnectQuietly()
    }

    fun eraseLisp() = work(R.string.esp_busy_erase) {
        loader?.erase(EspLoader.LISP_OFFSET, EspLoader.LISP_SIZE)
        _state.update { it.copy(done = R.string.esp_done_erase_lisp) }
    }

    fun eraseQml() = work(R.string.esp_busy_erase) {
        loader?.erase(EspLoader.QML_OFFSET, EspLoader.QML_SIZE)
        _state.update { it.copy(done = R.string.esp_done_erase_qml) }
    }

    fun disconnect() = work(R.string.esp_busy_connect) {
        loader?.reset()
        disconnectQuietly()
        _state.update { it.copy(chip = null, images = null, builds = null) }
    }

    private suspend fun disconnectQuietly() {
        runCatching { link?.close() }
        link = null
        loader = null
    }

    private fun work(label: Int, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        _state.update { it.copy(busy = label, progress = null, done = null, failed = null) }
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: IOException) {
                _state.update { it.copy(failed = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(busy = null, progress = null) }
            }
        }
    }

    override fun onCleared() {
        viewModelScope.launch { disconnectQuietly() }
    }

    private companion object {
        const val ESPRESSIF_VID = 0x303A
    }
}

@Composable
fun EspProgrammerScreen(model: EspProgrammerViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val colors = FocTheme.colors
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var pickIndex by rememberSaveable { mutableIntStateOf(0) }
    val resolver = LocalContext.current.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.openFile(pickIndex, resolver, uri) }
    val busy = state.busy != null
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.esp_intro),
            Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
            style = FocTheme.type.supporting,
            color = colors.textSecondary,
        )
        val chip = state.chip
        if (chip == null) {
            SectionHeader(stringResource(R.string.esp_devices))
            PlainButton(stringResource(R.string.esp_scan), model::scan, Modifier.padding(horizontal = Spacing.x1), enabled = !busy)
            state.devices.forEach { d -> DeviceLine(d.name, enabled = !busy) { model.connect(d) } }
            DeviceLine(stringResource(R.string.esp_simulated), enabled = !busy) { model.connect(null) }
        } else {
            ReadingRow(stringResource(R.string.esp_chip), chip.label)
            ReadingRow(stringResource(R.string.esp_flash), "${state.flashSize / 1024 / 1024} MB")
            PlainButton(stringResource(R.string.esp_disconnect), model::disconnect, Modifier.padding(horizontal = Spacing.x1), enabled = !busy)
            SectionHeader(stringResource(R.string.esp_firmware))
            when {
                state.builds == null -> Notice(stringResource(R.string.esp_no_archive), body = stringResource(R.string.esp_no_archive_body))
                state.builds!!.isEmpty() -> Notice(stringResource(R.string.esp_no_builds))
                else -> state.builds!!.forEach { b ->
                    DeviceLine(b.hardware, enabled = !busy, selected = state.images?.first == b.hardware) { model.choose(b) }
                }
            }
            SectionHeader(stringResource(R.string.esp_files))
            listOf(R.string.esp_file_bootloader, R.string.esp_file_partitions, R.string.esp_file_app).forEachIndexed { i, label ->
                DeviceLine(stringResource(label) + ": " + (state.files[i]?.first ?: stringResource(R.string.esp_file_none)), enabled = !busy) {
                    pickIndex = i
                    picker.launch(arrayOf("*/*"))
                }
            }
            state.images?.let { (source, images) ->
                ReadingRow(stringResource(R.string.esp_selected), source)
                ReadingRow(stringResource(R.string.esp_sizes), images.joinToString(" / ") { "${it.size} B" })
            }
            Row(Modifier.padding(horizontal = Spacing.x1)) {
                OutlineButton(stringResource(R.string.esp_flash_all), { confirm = "flash" }, Modifier.padding(Spacing.x1), enabled = !busy && state.images != null)
            }
            Row(Modifier.padding(horizontal = Spacing.x1)) {
                PlainButton(stringResource(R.string.esp_erase_lisp), { confirm = "lisp" }, enabled = !busy)
                PlainButton(stringResource(R.string.esp_erase_qml), { confirm = "qml" }, enabled = !busy)
            }
        }
        state.busy?.let { label ->
            Text(stringResource(label), Modifier.padding(horizontal = Spacing.x2), style = FocTheme.type.supporting, color = colors.text)
            val p = state.progress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(Spacing.x2), color = colors.accent, trackColor = colors.outline)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(Spacing.x2), color = colors.accent, trackColor = colors.outline)
            }
        }
        state.done?.let { Notice(stringResource(it)) }
        state.failed?.let { Notice(stringResource(R.string.esp_failed), body = it, tone = NoticeTone.Fault) }
    }
    when (confirm) {
        "flash" -> ConfirmDialog(
            title = stringResource(R.string.esp_flash_title),
            body = stringResource(R.string.esp_flash_body, state.images?.first.orEmpty()),
            confirm = stringResource(R.string.esp_flash_all),
            dismiss = stringResource(R.string.esp_cancel),
            onConfirm = {
                confirm = null
                model.flashAll()
            },
            onDismiss = { confirm = null },
        )
        "lisp", "qml" -> ConfirmDialog(
            title = stringResource(if (confirm == "lisp") R.string.esp_erase_lisp else R.string.esp_erase_qml),
            body = stringResource(R.string.esp_erase_body),
            confirm = stringResource(R.string.esp_erase),
            dismiss = stringResource(R.string.esp_cancel),
            onConfirm = {
                if (confirm == "lisp") model.eraseLisp() else model.eraseQml()
                confirm = null
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun DeviceLine(text: String, enabled: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        style = FocTheme.type.body,
        color = if (selected) FocTheme.colors.accent else FocTheme.colors.text,
    )
    Hairline(Modifier.padding(start = Spacing.x2))
}
