package app.foctuner.ui.tools.lisp

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.foctuner.vesc.LispCode
import app.foctuner.vesc.LispImportException
import app.foctuner.vesc.LispStreamMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** One open script. [uri] is null until it has been saved somewhere. */
@Stable
class LispDocument(val id: Long, uri: Uri?, name: String?, text: String, dirty: Boolean) {
    var uri by mutableStateOf(uri)
    var name by mutableStateOf(name)
    var value by mutableStateOf(TextFieldValue(text))
    var dirty by mutableStateOf(dirty)
    val history = EditHistory()

    val isBlank: Boolean get() = uri == null && !dirty && value.text.isEmpty()
}

data class RecentFile(val uri: Uri, val name: String)

/** Packing needs the folder that relative imports are read from, and none has been picked yet. */
class ImportsFolderNeeded : Exception()

/**
 * The editor's documents, recent files and settings. Activity-scoped rather than tied to the connection like
 * tool models: unsaved code must survive a dropped link (VESC Tool keeps its tabs regardless of the
 * connection), and drafts are written to app storage so they survive the process too.
 */
class LispWorkspace(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("lisp", Context.MODE_PRIVATE)
    private val drafts = File(app.filesDir, "lisp-drafts")
    private val assets = app.assets
    private val draftJobs = HashMap<Long, Job>()
    private var nextId = 1L

    val files = LispFiles(app.contentResolver)
    val documents = mutableStateListOf<LispDocument>()
    var currentIndex by mutableIntStateOf(0)
    val current: LispDocument get() = documents[currentIndex.coerceIn(documents.indices)]
    val recent = mutableStateListOf<RecentFile>()
    var symbols by mutableStateOf(LispSymbols.Empty)
        private set
    var examples by mutableStateOf(emptyList<String>())
        private set

    var autoRun by setting(true, { getBoolean(AUTO_RUN, it) }) { putBoolean(AUTO_RUN, it) }
    var reduce by setting(false, { getBoolean(REDUCE, it) }) { putBoolean(REDUCE, it) }
    var streamMode by setting(LispStreamMode.LoadOnTop, { LispStreamMode.entries.getOrElse(getInt(STREAM_MODE, it.ordinal)) { _ -> it } }) {
        putInt(STREAM_MODE, it.ordinal)
    }
    var fontSize by setting(13, { getInt(FONT_SIZE, it) }) { putInt(FONT_SIZE, it) }
    var importsFolder by setting<Uri?>(null, { getString(IMPORTS_FOLDER, null)?.let(Uri::parse) }) { putString(IMPORTS_FOLDER, it?.toString()) }
    var pollMode by setting(PollMode.Off, { PollMode.entries.getOrElse(getInt(POLL_MODE, it.ordinal)) { _ -> it } }) { putInt(POLL_MODE, it.ordinal) }
    var pollRate by setting(10, { getInt(POLL_RATE, it) }) { putInt(POLL_RATE, it) }

    init {
        restore()
        viewModelScope.launch {
            val (loadedSymbols, loadedExamples) = withContext(Dispatchers.IO) {
                val text = assets.open(SYMBOLS).bufferedReader().use { it.readText() }
                LispSymbols.parse(text) to (assets.list(EXAMPLES)?.filter { it.endsWith(".lbm") || it.endsWith(".lisp") }?.sorted().orEmpty())
            }
            symbols = loadedSymbols
            examples = loadedExamples
        }
    }

    fun select(document: LispDocument) {
        currentIndex = documents.indexOf(document).coerceAtLeast(0)
    }

    /** Applies an edit from the field: records undo, marks the document unsaved and schedules a draft save. */
    fun edit(document: LispDocument, value: TextFieldValue) {
        val previous = document.value
        if (value.text == previous.text) {
            document.value = value
            return
        }
        val next = autoIndent(previous, value)
        document.history.record(previous, SystemClock.uptimeMillis())
        document.value = next
        changed(document)
    }

    /** Replaces the whole text in one undo step (comment toggle, replace all). */
    fun replace(document: LispDocument, value: TextFieldValue) {
        document.history.cut()
        document.history.record(document.value, SystemClock.uptimeMillis())
        document.history.cut()
        document.value = value
        changed(document)
    }

    fun undo(document: LispDocument) {
        document.history.undo(document.value)?.let {
            document.value = it
            changed(document)
        }
    }

    fun redo(document: LispDocument) {
        document.history.redo(document.value)?.let {
            document.value = it
            changed(document)
        }
    }

    /** Opens a new tab, or fills the current one if it is empty and untitled (as VESC Tool does). */
    fun newDocument(text: String = "", name: String? = null, uri: Uri? = null): LispDocument {
        val reuse = documents.getOrNull(currentIndex)?.takeIf { it.isBlank && text.isNotEmpty() }
        val document = LispDocument(reuse?.id ?: nextId++, uri, name, text, dirty = false)
        if (reuse != null) documents[currentIndex] = document else documents += document
        select(document)
        saveDraft(document)
        return document
    }

    /** Reads [uri] into a tab, or switches to it if it is open already. */
    suspend fun open(uri: Uri) {
        documents.firstOrNull { it.uri == uri }?.let { return select(it) }
        val (bytes, name) = withContext(Dispatchers.IO) { files.read(uri) to files.displayName(uri) }
        files.keep(uri, write = true)
        newDocument(bytes.decodeToString(), name, uri)
        remember(uri, name)
    }

    suspend fun openExample(name: String) {
        val text = withContext(Dispatchers.IO) { assets.open("$EXAMPLES/$name").bufferedReader().use { it.readText() } }
        newDocument(text, name)
    }

    /**
     * Writes [document] back to its file. Returns false when it has none yet, so the caller asks where to save.
     *
     * @throws IOException if the file can't be written (moved, or access was revoked)
     */
    suspend fun save(document: LispDocument): Boolean {
        val uri = document.uri ?: return false
        writeOut(document, uri)
        return true
    }

    suspend fun saveAs(document: LispDocument, uri: Uri) {
        files.keep(uri, write = true)
        writeOut(document, uri)
        document.uri = uri
        document.name = withContext(Dispatchers.IO) { files.displayName(uri) }
        saveDraft(document)
    }

    fun close(document: LispDocument) {
        val index = documents.indexOf(document)
        if (index < 0) return
        documents.removeAt(index)
        draftJobs.remove(document.id)?.cancel()
        File(drafts, "${document.id}.lbm").delete()
        if (documents.isEmpty()) newDocument() else currentIndex = currentIndex.coerceAtMost(documents.lastIndex)
        saveIndex()
    }

    fun forget(file: RecentFile) {
        recent.remove(file)
        saveRecent()
    }

    fun clearRecent() {
        recent.clear()
        saveRecent()
    }

    /**
     * Packs [document] for upload with VESC Tool's format, reading relative imports from [importsFolder].
     *
     * @throws ImportsFolderNeeded if the code imports files and no folder has been picked
     * @throws LispImportException if an import is malformed, missing, or comes from a package
     */
    suspend fun pack(document: LispDocument, reduce: Boolean): ByteArray = withContext(Dispatchers.IO) {
        val code = document.value.text
        val folder = importsFolder
        if (LispCode.imports(code).isNotEmpty() && folder == null) throw ImportsFolderNeeded()
        LispCode.pack(code, reduce) { import ->
            when {
                import.path.startsWith("pkg::") || import.path.startsWith("pkg@") ->
                    throw LispImportException("Line ${import.line}: ${import.path} comes from a VESC package")
                import.path.startsWith("/") || import.path.startsWith("\\") ->
                    throw LispImportException("Line ${import.line}: ${import.path} is an absolute path")
                folder == null -> throw ImportsFolderNeeded()
                else -> try {
                    files.readInTree(folder, import.path)
                } catch (e: FileNotFoundException) {
                    throw LispImportException("Line ${import.line}: ${import.path} is not in the imports folder")
                } catch (e: SecurityException) {
                    throw ImportsFolderNeeded()
                }
            }
        }
    }

    private suspend fun writeOut(document: LispDocument, uri: Uri) {
        val text = document.value.text
        withContext(Dispatchers.IO) { files.write(uri, text.encodeToByteArray()) }
        if (document.value.text == text) document.dirty = false
        remember(uri, document.name ?: files.displayName(uri) ?: uri.toString())
        saveDraft(document)
    }

    private fun remember(uri: Uri, name: String?) {
        recent.removeAll { it.uri == uri }
        recent.add(0, RecentFile(uri, name ?: uri.toString()))
        while (recent.size > MAX_RECENT) recent.removeAt(recent.lastIndex)
        saveRecent()
    }

    private fun changed(document: LispDocument) {
        document.dirty = true
        draftJobs.remove(document.id)?.cancel()
        draftJobs[document.id] = viewModelScope.launch {
            delay(DRAFT_DELAY_MS)
            saveDraft(document)
        }
    }

    private fun saveDraft(document: LispDocument) {
        val text = document.value.text
        viewModelScope.launch(Dispatchers.IO) {
            drafts.mkdirs()
            File(drafts, "${document.id}.lbm").writeText(text)
        }
        saveIndex()
    }

    /** One line per open tab: id, uri, name, dirty. The text is in the draft file named by the id. */
    private fun saveIndex() {
        val lines = documents.joinToString("\n") { d ->
            listOf(d.id.toString(), d.uri?.toString().orEmpty(), d.name.orEmpty(), if (d.dirty) "1" else "0").joinToString("\t")
        }
        prefs.edit().putString(OPEN, lines).putInt(CURRENT, currentIndex).apply()
    }

    private fun saveRecent() {
        prefs.edit().putString(RECENT, recent.joinToString("\n") { "${it.uri}\t${it.name}" }).apply()
    }

    private fun restore() {
        prefs.getString(RECENT, null)?.lineSequence()?.forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 2) recent += RecentFile(Uri.parse(parts[0]), parts[1])
        }
        prefs.getString(OPEN, null)?.lineSequence()?.forEach { line ->
            val parts = line.split('\t')
            val id = parts.getOrNull(0)?.toLongOrNull() ?: return@forEach
            val text = File(drafts, "$id.lbm").takeIf { it.exists() }?.readText() ?: return@forEach
            documents += LispDocument(id, parts[1].ifEmpty { null }?.let(Uri::parse), parts[2].ifEmpty { null }, text, parts[3] == "1")
            nextId = maxOf(nextId, id + 1)
        }
        if (documents.isEmpty()) documents += LispDocument(nextId++, null, null, "", dirty = false)
        currentIndex = prefs.getInt(CURRENT, 0).coerceIn(documents.indices)
    }

    private fun <T> setting(
        default: T,
        load: android.content.SharedPreferences.(T) -> T,
        store: android.content.SharedPreferences.Editor.(T) -> Unit,
    ) = object : ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(prefs.load(default))

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            prefs.edit().apply { store(value) }.apply()
        }
    }

    private companion object {
        const val SYMBOLS = "lisp/symbols.txt"
        const val EXAMPLES = "lisp/examples"
        const val MAX_RECENT = 30
        const val DRAFT_DELAY_MS = 1000L
        const val OPEN = "open"
        const val CURRENT = "current"
        const val RECENT = "recent"
        const val AUTO_RUN = "auto_run"
        const val REDUCE = "reduce"
        const val STREAM_MODE = "stream_mode"
        const val FONT_SIZE = "font_size"
        const val IMPORTS_FOLDER = "imports_folder"
        const val POLL_MODE = "poll_mode"
        const val POLL_RATE = "poll_rate"
    }
}

/** VESC Tool's poll choices: nothing, only bindings named vt..., or all numeric bindings. */
enum class PollMode { Off, Vt, All }
