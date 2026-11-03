package app.foctuner.ui.settings

import app.foctuner.R
import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigBackup
import app.foctuner.config.ConfigDefinition
import app.foctuner.config.ConfigKind
import app.foctuner.config.ConfigXmlException
import app.foctuner.config.ParamValue
import app.foctuner.config.PayloadException
import app.foctuner.config.SignatureMismatchException
import app.foctuner.vesc.RemoteDefinitions
import app.foctuner.config.diff
import app.foctuner.config.restoredFrom
import app.foctuner.config.sections
import app.foctuner.config.validate
import app.foctuner.config.writeWarnings
import app.foctuner.ui.tools.configtools.ConfigStorage
import app.foctuner.vesc.ConfigEdit
import app.foctuner.vesc.FirmwareInfo
import app.foctuner.vesc.VescController
import app.foctuner.vesc.VescTimeoutException
import app.foctuner.vesc.readMatchingConfig
import app.foctuner.vesc.writeConfigChecked
import app.foctuner.vesc.writeWithBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Reads, edits and writes the motor and app config of the controller [controller] returns (null while
 * disconnected), whose firmware [firmware] describes. Definitions come from the bundled series matching its version,
 * falling back to the newest. If the controller's config signature says it uses another bundled layout, that one is
 * used instead; only a layout we don't bundle is refused. The last config read from each controller ([storage]'s
 * cache) supplies the settings it doesn't store. Every write first saves both configs as an automatic backup, which
 * Undo and the History page restore. Called on the main thread.
 */
class SettingsModel(
    private val scope: CoroutineScope,
    private val controller: () -> VescController?,
    private val firmware: () -> FirmwareInfo?,
    private val storage: ConfigStorage,
    private val string: (Int, Array<out Any>) -> String,
) : SettingsActions {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val jobs = HashMap<ConfigKind, Job>()

    /** The bundled series chosen by signature, when it differs from the one the firmware version points to. */
    private val layouts = HashMap<ConfigKind, String>()

    /** Forgets both configs, for a new connection or another selected controller. */
    fun reset() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        layouts.clear()
        _state.value = SettingsState()
    }

    override fun open(kind: ConfigKind) {
        val pane = _state.value.pane(kind)
        if (pane.stored == null && pane.work == null && pane.error == null) read(kind)
    }

    override fun read(kind: ConfigKind) = run(kind, SettingsWork.Reading) { target, base ->
        val config = target.readConfig(kind, base)
        cache(kind, config)
        update(kind) { it.copy(stored = config, edited = config, notice = null) }
    }

    override fun loadDefaults(kind: ConfigKind) = run(kind, SettingsWork.LoadingDefaults) { target, base ->
        val defaults = target.readDefaultConfig(kind, base)
        update(kind) { it.copy(edited = defaults) }
    }

    override fun write(kind: ConfigKind) = run(kind, SettingsWork.Writing) { target, _ ->
        val pane = _state.value.pane(kind)
        val stored = pane.stored ?: return@run
        val edited = pane.edited ?: return@run
        val issue = edited.validate().firstOrNull()
        if (issue != null) {
            val name = edited.definition.param(issue.param).longName
            update(kind) { it.copy(error = text(R.string.settings_error_invalid, name)) }
            return@run
        }
        writeEdits(target, mapOf(kind to ConfigEdit(stored, edited)))
    }

    override fun edit(kind: ConfigKind, name: String, value: ParamValue) {
        update(kind) { pane -> pane.copy(edited = pane.edited?.with(name, value), notice = pane.notice?.takeUnless { it.afterWrite }) }
    }

    override fun revert(kind: ConfigKind) {
        update(kind) { it.copy(edited = it.stored, notice = null) }
    }

    override fun loadFile(kind: ConfigKind, config: Config, notes: List<String>) {
        update(kind) { pane ->
            val stored = pane.stored ?: return@update pane
            pane.copy(
                edited = stored.restoredFrom(config),
                notice = PaneNotice(text(R.string.settings_file_loaded), notes, warning = notes.isNotEmpty()),
            )
        }
    }

    override fun notify(kind: ConfigKind, notice: PaneNotice) {
        update(kind) { it.copy(notice = notice) }
    }

    override fun dismissNotice(kind: ConfigKind) {
        update(kind) { it.copy(notice = null) }
    }

    override fun undo(kind: ConfigKind) {
        val undo = _state.value.pane(kind).undo ?: return
        prepareRestore(undo.backup, undo.kinds, undo = true)
    }

    override fun restore(backup: ConfigBackup) = prepareRestore(backup, backup.kinds, undo = false)

    override fun confirmRestore() {
        val review = _state.value.restore ?: return
        val target = controller() ?: return
        val edits = review.configs.filterKeys { review.changes[it].orEmpty().isNotEmpty() }.mapValues { (_, c) -> ConfigEdit(c.first, c.second) }
        _state.update { it.copy(restore = null, restoring = true) }
        edits.keys.forEach { kind -> update(kind) { it.copy(work = SettingsWork.Writing, error = null) } }
        scope.launch {
            val failure = failureOf { writeEdits(target, edits) }
            edits.keys.forEach { kind -> update(kind) { it.copy(work = null, error = failure ?: it.error) } }
            _state.update { it.copy(restoring = false) }
            if (_state.value.history != null) loadHistory()
        }
    }

    override fun jumpTo(hit: SettingHit?) {
        _state.update { it.copy(jump = hit) }
    }

    override fun dismissRestore() {
        _state.update { it.copy(restore = null) }
    }

    override fun loadHistory() {
        val uuid = firmware()?.uuid?.uppercase().orEmpty()
        val target = controller()
        scope.launch {
            val backups = withContext(Dispatchers.IO) { storage.backups.list().filter { it.uuid == uuid && uuid.isNotEmpty() } }
            _state.update { state -> state.copy(history = backups.map { b -> state.history?.firstOrNull { it.backup == b } ?: BackupEntry(b) }) }
            if (target != null) failureOf { ConfigKind.entries.forEach { storedOrRead(target, it) } }
            val entries = withContext(Dispatchers.IO) { backups.map(::entry) }
            _state.update { it.copy(history = entries) }
        }
    }

    override fun deleteBackup(backup: ConfigBackup) {
        scope.launch {
            withContext(Dispatchers.IO) { storage.backups.delete(backup) }
            _state.update { state -> state.copy(history = state.history?.filterNot { it.backup == backup }) }
        }
    }

    private fun prepareRestore(backup: ConfigBackup, kinds: Set<ConfigKind>, undo: Boolean) {
        val target = controller() ?: return
        if (_state.value.restoring) return
        _state.update { it.copy(restoring = true) }
        scope.launch {
            var review: RestoreReview? = null
            val failure = failureOf {
                val configs = ConfigKind.entries.filter { it in kinds && it in backup.kinds }.associateWith { kind ->
                    val stored = storedOrRead(target, kind)
                    val saved = withContext(Dispatchers.IO) { storage.backups.load(backup, kind, stored) }?.config ?: stored
                    stored to stored.restoredFrom(saved)
                }
                review = RestoreReview(backup, configs, undo)
            }
            _state.update { it.copy(restoring = false, restore = review) }
            if (failure != null) update(kinds.first()) { it.copy(error = failure) }
        }
    }

    /**
     * Writes [edits] after an automatic backup of both configs and shows what the controller stored. A controller
     * without a UUID (firmware before 3.x) has nothing to keep backups under, so it gets a plain checked write.
     */
    private suspend fun writeEdits(target: VescController, edits: Map<ConfigKind, ConfigEdit>) {
        val info = firmware()
        val (results, undo) = if (info != null && info.uuid.isNotEmpty()) {
            val done = withContext(Dispatchers.IO) { target.writeWithBackup(edits, storage.backups, info, base = ::baseFor) }
            done.results to UndoState(done.backup, edits.keys)
        } else {
            edits.mapValues { (kind, edit) -> target.writeConfigChecked(kind, edit.edited) } to null
        }
        for ((kind, result) in results) {
            cache(kind, result.applied)
            val names = result.notApplied.map { result.applied.definition.param(it).longName }
            val notice = if (names.isEmpty()) {
                PaneNotice(text(R.string.settings_written), afterWrite = true)
            } else {
                PaneNotice(text(R.string.settings_not_applied), names, warning = true, afterWrite = true)
            }
            update(kind) { it.copy(stored = result.applied, edited = result.applied, undo = undo, notice = notice) }
        }
    }

    private suspend fun storedOrRead(target: VescController, kind: ConfigKind): Config {
        _state.value.pane(kind).stored?.let { return it }
        val config = target.readMatchingConfig(kind, layout(kind)) { baseFor(kind, it) }
        cache(kind, config)
        update(kind) { it.copy(sections = it.sections.ifEmpty { config.definition.sections() }, stored = config, edited = config) }
        return config
    }

    private fun entry(backup: ConfigBackup): BackupEntry = try {
        val changes = backup.kinds.mapNotNull { kind ->
            val stored = _state.value.pane(kind).stored ?: return@mapNotNull null
            val saved = storage.backups.load(backup, kind, stored)?.config ?: return@mapNotNull null
            kind to stored.diff(stored.restoredFrom(saved))
        }.toMap()
        BackupEntry(backup, changes)
    } catch (e: ConfigXmlException) {
        BackupEntry(backup, damaged = true)
    } catch (e: IOException) {
        BackupEntry(backup, damaged = true)
    }

    /** The settings the controller doesn't store: from what was read this session, else the cache, else defaults. */
    private fun baseFor(kind: ConfigKind, definition: ConfigDefinition): Config {
        _state.value.pane(kind).stored?.takeIf { it.definition.signature == definition.signature }?.let { return it }
        val defaults = Config.defaults(definition)
        val uuid = firmware()?.uuid?.takeIf { it.isNotEmpty() } ?: return defaults
        return storage.cache.load(uuid, kind, defaults) ?: defaults
    }

    private suspend fun cache(kind: ConfigKind, config: Config) {
        val uuid = firmware()?.uuid?.takeIf { it.isNotEmpty() } ?: return
        withContext(Dispatchers.IO) {
            try {
                storage.cache.save(uuid, kind, config)
            } catch (e: IOException) {
                // The cache only saves a read later; the controller has the config.
            }
        }
    }

    private fun run(kind: ConfigKind, work: SettingsWork, block: suspend (VescController, Config) -> Unit) {
        val target = controller() ?: return
        if (jobs[kind]?.isActive == true || _state.value.restoring) return
        update(kind) { it.copy(work = work, error = null) }
        jobs[kind] = scope.launch {
            val failure = failureOf {
                try {
                    attempt(kind, target, block)
                } catch (e: SignatureMismatchException) {
                    val found = withContext(Dispatchers.IO) { storage.definitionFor(kind, firmware()?.version.orEmpty(), e.actual) }
                    val match = found.version ?: throw UnknownLayoutException(found.outcome, e)
                    if (match == layout(kind)) throw e
                    layouts[kind] = match
                    update(kind) { it.copy(sections = emptyList(), stored = null, edited = null) }
                    attempt(kind, target, block)
                }
            }
            update(kind) { it.copy(work = null, error = failure ?: it.error) }
        }
    }

    /** Runs [block] and says in plain words why it failed, or null if it didn't. */
    private suspend fun failureOf(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (e: UnknownLayoutException) {
        val version = firmware()?.version ?: "?"
        when (e.outcome) {
            RemoteDefinitions.Outcome.Unreachable -> text(R.string.settings_error_layout_offline, version)
            RemoteDefinitions.Outcome.WrongLayout -> text(R.string.settings_error_layout_mismatch, version)
            else -> text(R.string.settings_error_layout, version, BundledDefinitions.versions.first(), BundledDefinitions.versions.last())
        }
    } catch (e: SignatureMismatchException) {
        text(R.string.settings_error_layout, firmware()?.version ?: "?", BundledDefinitions.versions.first(), BundledDefinitions.versions.last())
    } catch (e: PayloadException) {
        e.message
    } catch (e: VescTimeoutException) {
        text(R.string.settings_error_no_reply)
    } catch (e: ConfigXmlException) {
        text(R.string.settings_error_backup_damaged)
    } catch (e: IOException) {
        e.message ?: text(R.string.settings_error_no_reply)
    }

    private suspend fun attempt(kind: ConfigKind, target: VescController, block: suspend (VescController, Config) -> Unit) {
        val version = layout(kind)
        if (_state.value.pane(kind).sections.isEmpty()) {
            val sections = withContext(Dispatchers.Default) { BundledDefinitions.load(version, kind).sections() }
            update(kind) { it.copy(sections = sections) }
        }
        val base = _state.value.pane(kind).edited
            ?: withContext(Dispatchers.IO) { baseFor(kind, BundledDefinitions.load(version, kind)) }
        block(target, base)
    }

    private fun layout(kind: ConfigKind): String = layouts[kind]
        ?: firmware()?.version?.takeIf { BundledDefinitions.has(it, kind) }
        ?: BundledDefinitions.versions.last()

    private fun update(kind: ConfigKind, transform: (ConfigPaneState) -> ConfigPaneState) {
        _state.update { state ->
            val pane = transform(state.pane(kind)).let { pane ->
                val stored = pane.stored
                val edited = pane.edited
                pane.copy(
                    changed = if (stored != null && edited != null) stored.diff(edited).map { it.name }.toSet() else emptySet(),
                    warnings = edited?.writeWarnings().orEmpty(),
                )
            }
            if (kind == ConfigKind.MCCONF) state.copy(motor = pane) else state.copy(app = pane)
        }
    }

    private fun text(id: Int, vararg args: Any): String = string(id, args)
}

/** No bundled, kept or downloadable definition has the controller's layout; [outcome] says why none was fetched. */
private class UnknownLayoutException(val outcome: RemoteDefinitions.Outcome, cause: SignatureMismatchException) :
    Exception("Unknown config layout ${cause.actual}", cause)
