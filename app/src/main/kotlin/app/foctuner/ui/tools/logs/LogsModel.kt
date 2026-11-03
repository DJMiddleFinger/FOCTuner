package app.foctuner.ui.tools.logs

import android.content.Context
import app.foctuner.logging.LogStorage
import app.foctuner.logging.PartialFileSink
import app.foctuner.logging.StoredLog
import app.foctuner.logs.TransferItem
import app.foctuner.logs.TransferQueue
import app.foctuner.logs.runTransfers
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.DeviceFile
import app.foctuner.vesc.EXPRESS_LOG_FOLDER
import app.foctuner.vesc.VescException
import app.foctuner.vesc.downloadFile
import app.foctuner.vesc.hasFileSystem
import app.foctuner.vesc.listLogFiles
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class LogsState(
    val phone: List<StoredLog> = emptyList(),
    /** Logs downloaded from this controller. */
    val downloads: List<StoredLog> = emptyList(),
    /** Null until the controller has said whether it has storage. */
    val hasStorage: Boolean? = null,
    val folder: String = "",
    val entries: List<DeviceFile>? = null,
    val failed: Boolean = false,
    val selected: Set<String> = emptySet(),
    val queue: TransferQueue? = null,
    val downloading: Boolean = false,
    /** Bytes of each file in [entries] that an earlier, cut-off download already holds. */
    val partial: Map<String, Long> = emptyMap(),
    /** Files in [entries] that are already on the phone in full. */
    val downloaded: Set<String> = emptySet(),
) {
    val files: List<DeviceFile> get() = entries.orEmpty().filter { !it.isDirectory }
    val canGoUp: Boolean get() = folder.isNotEmpty()
}

/** The Logs tool's state: the phone's log files, and the controller's storage with downloads that carry on after a drop. */
class LogsModel(private val context: ToolContext, private val app: Context) {
    private val _state = MutableStateFlow(LogsState(phone = LogStorage.logs(LogStorage.phoneDir(app)), downloads = LogStorage.logs(LogStorage.controllerDir(app, context.firmware.uuid))))
    val state: StateFlow<LogsState> = _state.asStateFlow()
    private var download: Job? = null

    private val dir: File get() = LogStorage.controllerDir(app, context.firmware.uuid)

    fun refreshPhone() = _state.update { it.copy(phone = LogStorage.logs(LogStorage.phoneDir(app)), downloads = LogStorage.logs(dir)) }

    fun delete(log: StoredLog) {
        log.file.delete()
        refreshPhone()
    }

    /** Lists [folder], or the logger's folder when the storage has one and nothing was chosen yet. */
    fun refresh(folder: String? = null) {
        if (_state.value.downloading) return
        context.scope.launch {
            try {
                val controller = context.controller
                val storage = _state.value.hasStorage ?: controller.hasFileSystem()
                if (!storage) {
                    _state.update { it.copy(hasStorage = false, failed = false) }
                    return@launch
                }
                val target = folder ?: _state.value.folder.takeIf { _state.value.entries != null }
                    ?: EXPRESS_LOG_FOLDER.takeIf { root -> controller.listLogFiles("").any { it.isDirectory && "/${it.name}" == root } }
                    ?: ""
                val entries = controller.listLogFiles(target)
                _state.update { s ->
                    s.copy(
                        hasStorage = true,
                        folder = target,
                        entries = entries,
                        failed = false,
                        selected = s.selected.filter { name -> entries.any { it.name == name } }.toSet(),
                        partial = partials(entries),
                        downloaded = entries.filter { !it.isDirectory && File(dir, it.name).let { f -> f.isFile && f.length() == it.size } }.map { it.name }.toSet(),
                    )
                }
            } catch (e: VescException) {
                _state.update { it.copy(failed = true) }
            }
        }
    }

    fun open(name: String) = refresh("${_state.value.folder}/$name")

    fun up() = refresh(_state.value.folder.substringBeforeLast('/', ""))

    fun toggle(name: String) = _state.update { it.copy(selected = if (name in it.selected) it.selected - name else it.selected + name) }

    fun selectAll() = _state.update { s ->
        s.copy(selected = if (s.selected.size == s.files.size) emptySet() else s.files.map { it.name }.toSet())
    }

    /** Downloads the selected files one after another; run again after a drop to continue them. */
    fun download() {
        val s = _state.value
        val files = s.files.filter { it.name in s.selected }
        if (files.isEmpty() || s.downloading) return
        val folder = s.folder
        _state.update { it.copy(downloading = true, queue = TransferQueue(files.map { f -> TransferItem(f.name, f.size, s.partial[f.name] ?: 0) })) }
        download = context.scope.launch {
            try {
                runTransfers(
                    initial = _state.value.queue?.items.orEmpty(),
                    clock = System::currentTimeMillis,
                    isFatal = { it is VescException },
                    onUpdate = { queue -> _state.update { it.copy(queue = queue) } },
                ) { item, report ->
                    val target = File(dir, item.name)
                    val sink = PartialFileSink(LogStorage.partFile(dir, item.name))
                    context.controller.downloadFile("$folder/${item.name}", sink) { done, total -> report(done, total) }
                    sink.finish(target)
                }
            } finally {
                _state.update { it.copy(downloading = false) }
                refreshPhone()
                refresh(folder)
            }
        }
    }

    fun cancel() {
        download?.cancel()
    }

    fun dismissQueue() = _state.update { it.copy(queue = null) }

    private fun partials(entries: List<DeviceFile>): Map<String, Long> =
        entries.filter { !it.isDirectory }.mapNotNull { e -> LogStorage.partFile(dir, e.name).takeIf { it.isFile }?.let { e.name to it.length() } }.toMap()
}
