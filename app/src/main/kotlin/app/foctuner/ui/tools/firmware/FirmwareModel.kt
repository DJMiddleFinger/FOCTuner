package app.foctuner.ui.tools.firmware

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import app.foctuner.logging.RideLogHub
import app.foctuner.ui.session.UpdateStage
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
import app.foctuner.vesc.firmware.UploadCheckpoint
import app.foctuner.vesc.firmware.UploadTracker
import app.foctuner.vesc.firmware.archive.ArchiveDownload
import app.foctuner.vesc.firmware.archive.ArchiveEntry
import app.foctuner.vesc.firmware.archive.ArchiveException
import app.foctuner.vesc.firmware.archive.ArchiveListing
import app.foctuner.vesc.firmware.archive.ArchiveTarget
import app.foctuner.vesc.firmware.archive.Changelog
import app.foctuner.vesc.firmware.archive.ChangelogSection
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

/** The firmware archive for this controller: what is offered, and the download in progress. */
@Immutable
data class ArchiveState(
    val loading: Boolean = true,
    val listing: ArchiveListing? = null,
    val downloading: ArchiveEntry? = null,
    /** Bytes received and the size of the file, once known. */
    val received: Long = 0,
    val total: Long? = null,
    val error: ArchiveException.Reason? = null,
    val savedKeys: Set<String> = emptySet(),
)

/** What the changelog dialog shows: the sections between the installed version and the chosen one. */
@Immutable
data class ChangelogView(val installed: String, val target: String, val sections: List<ChangelogSection>, val available: Boolean)

@Immutable
data class FirmwareState(
    val archive: ArchiveState = ArchiveState(),
    /** Where [file] came from, when it was downloaded. */
    val source: ArchiveEntry? = null,
    val changelog: ChangelogView? = null,
    /** An upload that stopped after the erase, which can be continued with the same image. */
    val checkpoint: UploadCheckpoint? = null,
    val resuming: Boolean = false,
    /** Demo mode only: drop the simulated link part way through the next upload. */
    val demoDrop: Boolean = false,
    /** A ride log is recording; it polls the controller, which must be left alone while it writes flash. */
    val rideLogRecording: Boolean = false,
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
class FirmwareModel(private val context: ToolContext, private val storage: FirmwareStorage) {
    private val archiveTarget = ArchiveTarget(context.firmware.hardwareName, context.firmware.hardwareType)
    private var downloadJob: Job? = null
    private var tracker: UploadTracker? = null

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
        _state.update { it.copy(checkpoint = storage.checkpoint()?.takeIf(::belongsToController)) }
        context.scope.launch { loadArchive() }
        context.scope.launch { RideLogHub.state.collect { log -> _state.update { it.copy(rideLogRecording = log.recording) } } }
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
                    { s.copy(file = it, source = null, fileError = null, loadingFile = false, allowMismatch = false) },
                    { s.copy(file = null, fileError = (it as FirmwareFileException).reason, loadingFile = false) },
                )
            }
        }
    }

    fun setIncludeBootloader(include: Boolean) = _state.update { it.copy(includeBootloader = include) }

    fun setAllCan(allCan: Boolean) = _state.update { it.copy(allCan = allCan && canUpdateBus) }

    fun setAllowMismatch(allow: Boolean) = _state.update { it.copy(allowMismatch = allow) }

    fun setDemoDrop(drop: Boolean) = _state.update { it.copy(demoDrop = drop && context.simulator != null) }

    fun ask(kind: UploadKind) = _state.update { if (it.running == null) it.copy(confirming = kind) else it }

    fun dismissConfirmation() = _state.update { it.copy(confirming = null) }

    fun start() {
        val s = _state.value
        val kind = s.confirming ?: return
        if (s.running != null) return
        val firmwareImage = if (kind == UploadKind.Firmware) s.file ?: return else null
        val bootloaderImage = if (kind == UploadKind.BootloaderOnly || s.includeBootloader) s.bootloader else null
        if (firmwareImage == null && bootloaderImage == null) return
        upload(kind, firmwareImage, bootloaderImage, s, resume = null)
    }

    /** Continues the interrupted upload with the same image: no erase, from the last confirmed chunk. */
    fun resume() {
        val s = _state.value
        val checkpoint = s.checkpoint ?: return
        val image = s.file?.takeIf { it.sha256 == checkpoint.imageSha256 } ?: return
        if (s.running != null) return
        upload(UploadKind.Firmware, image, null, s.copy(allCan = false), resume = checkpoint)
    }

    /** Forgets the interrupted upload; the next install erases and writes everything again. */
    fun startOver() {
        storage.saveCheckpoint(null)
        _state.update { it.copy(checkpoint = null) }
    }

    private fun upload(kind: UploadKind, firmwareImage: FirmwareImage?, bootloaderImage: FirmwareImage?, s: FirmwareState, resume: UploadCheckpoint?) {
        val target = if (s.allCan) context.client else context.controller
        val tracking = firmwareImage?.let { UploadTracker(it, firmware) }
        resume?.let { tracking?.resumed(it) }
        tracker = tracking
        context.updateLock.report(null)
        context.updateLock.hold(UpdateStage.Uploading)
        if (s.demoDrop && resume == null && firmwareImage != null) context.simulator?.dropLinkDuringUpload(firmwareImage.bytes.size * 2 / 5)
        _state.update {
            it.copy(
                confirming = null, running = kind, progress = null, failure = null, cancelled = false,
                previousVersion = firmware.version, resuming = resume != null, demoDrop = false,
            )
        }
        job = context.scope.launch {
            var finished = false
            try {
                target.updateFirmware(firmwareImage, bootloaderImage, s.allCan, s.allowMismatch, resume = resume).collect { p ->
                    tracking?.observe(p)
                    if (p is FirmwareProgress.Installing) context.updateLock.hold(UpdateStage.Installing)
                    if (p is FirmwareProgress.Restarted || p is FirmwareProgress.LinkClosed || p is FirmwareProgress.NoReply) finished = true
                    _state.update { it.copy(progress = p) }
                }
            } catch (e: FirmwareUpdateException) {
                _state.update { it.copy(failure = e) }
            } catch (e: CancellationException) {
                _state.update { it.copy(cancelled = true) }
                throw e
            } finally {
                val interrupted = tracking?.checkpoint?.takeIf { !finished && !s.allCan }
                storage.saveCheckpoint(interrupted)
                // A closed link leaves the lock to the session, which is already reconnecting.
                if (context.client.connected.value) context.updateLock.release()
                _state.update { it.copy(running = null, checkpoint = interrupted) }
            }
        }
    }

    private fun belongsToController(checkpoint: UploadCheckpoint): Boolean =
        checkpoint.hardwareName.equals(firmware.hardwareName, ignoreCase = true) &&
            (checkpoint.uuid.isEmpty() || firmware.uuid.isEmpty() || checkpoint.uuid == firmware.uuid)

    private suspend fun loadArchive() {
        val listing = withContext(Dispatchers.IO) { storage.archive.listing(archiveTarget) }
        _state.update {
            it.copy(archive = it.archive.copy(loading = false, listing = listing, savedKeys = listing.entries.filter(storage.archive::isSaved).map(ArchiveEntry::key).toSet()))
        }
        val checkpoint = _state.value.checkpoint ?: return
        val saved = storage.cache.saved(firmware.hardwareName).firstOrNull { it.sha256 == checkpoint.imageSha256 } ?: return
        if (_state.value.file == null) download(saved.entry)
    }

    /** Fetches [entry] (or takes the saved copy) and makes it the image to install; the pre-flash checks run on the way. */
    fun download(entry: ArchiveEntry) {
        if (_state.value.running != null || _state.value.archive.downloading != null) return
        _state.update { it.copy(archive = it.archive.copy(downloading = entry, received = 0, total = null, error = null), fileError = null) }
        downloadJob = context.scope.launch {
            try {
                storage.archive.download(entry, archiveTarget).collect { event ->
                    when (event) {
                        is ArchiveDownload.Progress -> _state.update { it.copy(archive = it.archive.copy(received = event.received, total = event.total)) }
                        is ArchiveDownload.Done -> _state.update {
                            it.copy(
                                file = event.image.image, source = event.image.entry, allowMismatch = false,
                                archive = it.archive.copy(savedKeys = it.archive.savedKeys + event.image.entry.key),
                            )
                        }
                    }
                }
            } catch (e: ArchiveException) {
                _state.update { it.copy(archive = it.archive.copy(error = e.reason)) }
            } finally {
                _state.update { it.copy(archive = it.archive.copy(downloading = null)) }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Shows what changed between the installed version and [entry]'s. */
    fun showChangelog(entry: ArchiveEntry) {
        val target = entry.version ?: return
        context.scope.launch {
            val text = storage.archive.changelog()
            val sections = text?.let { Changelog.between(Changelog.parse(it), firmware.version, target) }.orEmpty()
            _state.update { it.copy(changelog = ChangelogView(firmware.version, target, sections, available = text != null)) }
        }
    }

    fun dismissChangelog() = _state.update { it.copy(changelog = null) }

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
