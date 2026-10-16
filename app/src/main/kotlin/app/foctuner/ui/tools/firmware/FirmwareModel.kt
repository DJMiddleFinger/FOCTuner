package app.foctuner.ui.tools.firmware

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.VescException
import app.foctuner.vesc.FirmwareInfo
import app.foctuner.vesc.firmware.Bootloaders
import app.foctuner.vesc.firmware.FirmwareBuild
import app.foctuner.vesc.firmware.FirmwareFileException
import app.foctuner.vesc.firmware.FirmwareImage
import app.foctuner.vesc.firmware.FirmwareProgress
import app.foctuner.vesc.firmware.FirmwareUpdateException
import app.foctuner.vesc.firmware.FlashRegion
import app.foctuner.vesc.firmware.HardwareCheck
import app.foctuner.vesc.firmware.firmwareBuild
import app.foctuner.vesc.firmware.updateFirmware
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** What gets uploaded: a firmware file (optionally with the bootloader first), or only the bootloader. */
enum class UploadKind { Firmware, BootloaderOnly }

@Immutable
data class FirmwareState(
    val build: FirmwareBuild? = null,
    val file: FirmwareImage? = null,
    val fileError: FirmwareFileException.Reason? = null,
    val loadingFile: Boolean = false,
    val bootloader: FirmwareImage? = null,
    val includeBootloader: Boolean = false,
    val allCan: Boolean = false,
    val allowMismatch: Boolean = false,
    val confirming: UploadKind? = null,
    val running: UploadKind? = null,
    val progress: FirmwareProgress? = null,
    val failure: FirmwareUpdateException? = null,
    val cancelled: Boolean = false,
    /** Version before the update, to tell an install from a rejected image. */
    val previousVersion: String = "",
) {
    /** Cancelling is only possible while the firmware image itself is being uploaded. */
    val cancellable: Boolean
        get() = running != null && when (val p = progress) {
            is FirmwareProgress.Erasing -> p.region == FlashRegion.Firmware
            is FirmwareProgress.Writing -> p.region == FlashRegion.Firmware
            else -> false
        }
}

/** VESC Tool's firmware page: install a firmware file or a bootloader on the selected controller or the whole bus. */
class FirmwareModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(FirmwareState(bootloader = Bootloaders.forController(context.firmware).firstOrNull()))
    val state: StateFlow<FirmwareState> = _state.asStateFlow()
    private var job: Job? = null

    val firmware get() = context.firmware

    /** All-CAN updates go through the attached controller and need firmware 3.45. */
    val canUpdateBus: Boolean
        get() = context.controller === context.client && firmware.atLeast(3, 45)

    /** Before 3.59 a bootloader is written over the old one without erasing it first. */
    val canEraseBootloader: Boolean get() = firmware.atLeast(3, 59)

    init {
        context.scope.launch {
            try {
                val build = context.controller.firmwareBuild()
                _state.update { it.copy(build = build) }
            } catch (e: VescException) {
                // Firmware before 6.05 has no COMM_FW_INFO.
            }
        }
    }

    fun hardwareCheck(image: FirmwareImage): HardwareCheck = image.check(firmware.hardwareName)

    fun openFile(resolver: ContentResolver, uri: Uri) {
        if (_state.value.running != null) return
        _state.update { it.copy(loadingFile = true, fileError = null) }
        context.scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val name = displayName(resolver, uri) ?: uri.lastPathSegment.orEmpty()
                    val bytes = resolver.openInputStream(uri)?.use { readAtMost(it, FirmwareImage.MAX_FILE_SIZE + 1) }
                        ?: throw FirmwareFileException(FirmwareFileException.Reason.Empty, "Could not open $uri")
                    Result.success(FirmwareImage.fromFile(name, bytes))
                } catch (e: FirmwareFileException) {
                    Result.failure(e)
                } catch (e: IOException) {
                    Result.failure(FirmwareFileException(FirmwareFileException.Reason.Empty, e.message.orEmpty()))
                }
            }
            _state.update { s ->
                result.fold(
                    { s.copy(file = it, fileError = null, loadingFile = false, allowMismatch = false) },
                    { s.copy(file = null, fileError = (it as FirmwareFileException).reason, loadingFile = false) },
                )
            }
        }
    }

    fun setIncludeBootloader(include: Boolean) = _state.update { it.copy(includeBootloader = include) }

    fun setAllCan(allCan: Boolean) = _state.update { it.copy(allCan = allCan && canUpdateBus) }

    fun setAllowMismatch(allow: Boolean) = _state.update { it.copy(allowMismatch = allow) }

    fun ask(kind: UploadKind) = _state.update { if (it.running == null) it.copy(confirming = kind) else it }

    fun dismissConfirmation() = _state.update { it.copy(confirming = null) }

    fun start() {
        val s = _state.value
        val kind = s.confirming ?: return
        if (s.running != null) return
        val firmwareImage = if (kind == UploadKind.Firmware) s.file ?: return else null
        val bootloaderImage = if (kind == UploadKind.BootloaderOnly || s.includeBootloader) s.bootloader else null
        if (firmwareImage == null && bootloaderImage == null) return
        val target = if (s.allCan) context.client else context.controller
        _state.update {
            it.copy(confirming = null, running = kind, progress = null, failure = null, cancelled = false, previousVersion = firmware.version)
        }
        job = context.scope.launch {
            try {
                target.updateFirmware(firmwareImage, bootloaderImage, s.allCan, s.allowMismatch).collect { p ->
                    _state.update { it.copy(progress = p) }
                }
            } catch (e: FirmwareUpdateException) {
                _state.update { it.copy(failure = e) }
            } catch (e: CancellationException) {
                _state.update { it.copy(cancelled = true) }
                throw e
            } finally {
                _state.update { it.copy(running = null) }
            }
        }
    }

    fun cancel() {
        if (_state.value.cancellable) job?.cancel()
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}

private fun FirmwareInfo.atLeast(major: Int, minor: Int) = this.major > major || (this.major == major && this.minor >= minor)

/** Enough to tell a too-large file apart without reading all of it. */
private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
