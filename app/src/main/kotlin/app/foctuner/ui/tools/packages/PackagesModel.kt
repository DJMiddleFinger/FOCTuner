package app.foctuner.ui.tools.packages

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.Immutable
import app.foctuner.sim.DemoPackages
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.PackageCompatibility.Verdict
import app.foctuner.vesc.PackageException
import app.foctuner.vesc.PackageInstallStep
import app.foctuner.vesc.PackageStore
import app.foctuner.vesc.StorePackage
import app.foctuner.vesc.VescPackage
import app.foctuner.vesc.firmware.archive.UrlConnectionFetcher
import app.foctuner.vesc.installPackage
import app.foctuner.vesc.uninstallPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@Immutable
data class StoreRow(val item: StorePackage, val verdict: Verdict)

@Immutable
sealed interface InstallState {
    data class Running(val name: String, val step: PackageInstallStep?, val done: Int, val total: Int) : InstallState
    data class Installed(val name: String) : InstallState
    data object Uninstalled : InstallState
    data class Failed(val name: String?, val message: String) : InstallState
}

@Immutable
data class PackagesState(
    val rows: List<StoreRow>? = null,
    val loading: Boolean = false,
    val loadedBytes: Int = 0,
    /** Epoch millis of the store shown, null if never downloaded. */
    val fetchedAt: Long? = null,
    val loadFailed: String? = null,
    val filter: String = "",
    val showAll: Boolean = false,
    val showLibraries: Boolean = false,
    /** Path of the package whose page is open, or [FILE_KEY] for one opened from a file. */
    val open: String? = null,
    val fromFile: StoreRow? = null,
    val install: InstallState? = null,
) {
    val visible: List<StoreRow>
        get() {
            val words = filter.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return rows.orEmpty().filter { row ->
                (showLibraries || !row.item.isLibrary) &&
                    (showAll || row.item.isLibrary || row.verdict != Verdict.Incompatible) &&
                    words.all { w -> w in row.item.pkg.name.lowercase() || w in row.item.pkg.descriptionMarkdown.lowercase() }
            }
        }

    val opened: StoreRow? get() = if (open == FILE_KEY) fromFile else rows?.firstOrNull { it.item.path == open }

    companion object {
        const val FILE_KEY = "file"
    }
}

/**
 * VESC Tool's package store (`pagevescpackage`, mobile `Packages`): the store bundle, each package's compatibility
 * from its own check, install and uninstall. Demo mode uses a small simulated store.
 */
class PackagesModel(private val context: ToolContext, filesDir: File) {
    private val store = PackageStore(
        if (context.demo) DemoPackages.host() else UrlConnectionFetcher(),
        File(filesDir, if (context.demo) "packages-demo/vesc_pkg_all.rcc" else "packages/vesc_pkg_all.rcc"),
    )
    private val _state = MutableStateFlow(PackagesState())
    val state: StateFlow<PackagesState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        context.scope.launch {
            val cached = withContext(Dispatchers.IO) { store.cached() to store.cachedAt() }
            if (cached.first != null) {
                _state.update { it.copy(rows = rows(cached.first!!), fetchedAt = cached.second) }
            } else {
                refresh()
            }
        }
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, loadedBytes = 0, loadFailed = null) }
        context.scope.launch {
            try {
                val packages = withContext(Dispatchers.IO) { store.refresh { n -> _state.update { it.copy(loadedBytes = n) } } }
                _state.update { it.copy(rows = rows(packages), fetchedAt = System.currentTimeMillis(), loading = false) }
            } catch (e: IOException) {
                _state.update { it.copy(loading = false, loadFailed = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun setFilter(text: String) = _state.update { it.copy(filter = text) }
    fun setShowAll(on: Boolean) = _state.update { it.copy(showAll = on) }
    fun setShowLibraries(on: Boolean) = _state.update { it.copy(showLibraries = on) }
    fun open(path: String?) = _state.update { it.copy(open = path, install = if (path == null) null else it.install) }

    fun openFile(resolver: ContentResolver, uri: Uri) {
        context.scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            if (out.size() > MAX_FILE) throw IOException("The file is larger than any package")
                        }
                        out.toByteArray()
                    } ?: throw IOException("The file couldn't be opened")
                }
                val pkg = VescPackage.parse(bytes)
                val row = StoreRow(StorePackage(uri.toString(), pkg, isLibrary = false), verdict(pkg))
                _state.update { it.copy(fromFile = row, open = PackagesState.FILE_KEY, install = null) }
            } catch (e: PackageException) {
                _state.update { it.copy(install = InstallState.Failed(null, e.message ?: "")) }
            } catch (e: IOException) {
                _state.update { it.copy(install = InstallState.Failed(null, e.message ?: "")) }
            }
        }
    }

    fun install(row: StoreRow) {
        if (job?.isActive == true) return
        val name = row.item.pkg.name
        _state.update { it.copy(install = InstallState.Running(name, null, 0, 0)) }
        job = context.scope.launch {
            try {
                context.controller.installPackage(row.item.pkg) { step, done, total ->
                    _state.update { it.copy(install = InstallState.Running(name, step, done, total)) }
                }
                _state.update { it.copy(install = InstallState.Installed(name)) }
            } catch (e: IOException) {
                _state.update { it.copy(install = InstallState.Failed(name, e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun uninstall() {
        if (job?.isActive == true) return
        _state.update { it.copy(install = InstallState.Running("", null, 0, 0)) }
        job = context.scope.launch {
            try {
                context.controller.uninstallPackage()
                _state.update { it.copy(install = InstallState.Uninstalled) }
            } catch (e: IOException) {
                _state.update { it.copy(install = InstallState.Failed(null, e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    private fun verdict(pkg: VescPackage): Verdict =
        app.foctuner.vesc.PackageCompatibility.check(pkg.descriptionQml, context.firmware)

    private fun rows(packages: List<StorePackage>): List<StoreRow> = packages.map { StoreRow(it, it.compatibility(context.firmware)) }

    private companion object {
        const val MAX_FILE = 16 * 1024 * 1024
    }
}
