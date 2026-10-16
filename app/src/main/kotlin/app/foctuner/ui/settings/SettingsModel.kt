package app.foctuner.ui.settings

import app.foctuner.R
import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.config.ParamValue
import app.foctuner.config.PayloadException
import app.foctuner.config.SignatureMismatchException
import app.foctuner.config.diff
import app.foctuner.config.sections
import app.foctuner.config.validate
import app.foctuner.vesc.VescController
import app.foctuner.vesc.VescTimeoutException
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
 * disconnected). Definitions come from the bundled series matching [firmware], falling back to the newest.
 * If the controller's config signature says it uses another bundled layout, that one is used instead; only a
 * layout we don't bundle is refused. Called on the main thread.
 */
class SettingsModel(
    private val scope: CoroutineScope,
    private val controller: () -> VescController?,
    private val firmware: () -> String?,
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
        update(kind) { it.copy(stored = config, edited = config) }
    }

    override fun loadDefaults(kind: ConfigKind) = run(kind, SettingsWork.LoadingDefaults) { target, base ->
        val defaults = target.readDefaultConfig(kind, base)
        update(kind) { it.copy(edited = defaults) }
    }

    override fun write(kind: ConfigKind) = run(kind, SettingsWork.Writing) { target, edited ->
        val issue = edited.validate().firstOrNull()
        if (issue != null) {
            val name = edited.definition.param(issue.param).longName
            update(kind) { it.copy(error = text(R.string.settings_error_invalid, name)) }
            return@run
        }
        target.writeConfig(kind, edited)
        val applied = target.readConfig(kind, edited)
        update(kind) { it.copy(stored = applied, edited = applied) }
    }

    override fun edit(kind: ConfigKind, name: String, value: ParamValue) {
        update(kind) { pane -> pane.copy(edited = pane.edited?.with(name, value)) }
    }

    override fun revert(kind: ConfigKind) {
        update(kind) { it.copy(edited = it.stored) }
    }

    private fun run(kind: ConfigKind, work: SettingsWork, block: suspend (VescController, Config) -> Unit) {
        val target = controller() ?: return
        if (jobs[kind]?.isActive == true) return
        update(kind) { it.copy(work = work, error = null) }
        jobs[kind] = scope.launch {
            val failure = try {
                try {
                    attempt(kind, target, block)
                } catch (e: SignatureMismatchException) {
                    val match = withContext(Dispatchers.Default) { BundledDefinitions.versionWithSignature(kind, e.actual) }
                    if (match == null || match == layout(kind)) throw e
                    layouts[kind] = match
                    update(kind) { it.copy(sections = emptyList(), stored = null, edited = null) }
                    attempt(kind, target, block)
                }
                null
            } catch (e: SignatureMismatchException) {
                text(R.string.settings_error_layout, firmware() ?: "?", BundledDefinitions.versions.joinToString())
            } catch (e: PayloadException) {
                e.message
            } catch (e: VescTimeoutException) {
                text(R.string.settings_error_no_reply)
            } catch (e: IOException) {
                e.message ?: text(R.string.settings_error_no_reply)
            }
            update(kind) { it.copy(work = null, error = failure ?: it.error) }
        }
    }

    private suspend fun attempt(kind: ConfigKind, target: VescController, block: suspend (VescController, Config) -> Unit) {
        val version = layout(kind)
        if (_state.value.pane(kind).sections.isEmpty()) {
            val sections = withContext(Dispatchers.Default) { BundledDefinitions.load(version, kind).sections() }
            update(kind) { it.copy(sections = sections) }
        }
        val base = _state.value.pane(kind).edited
            ?: withContext(Dispatchers.Default) { Config.defaults(BundledDefinitions.load(version, kind)) }
        block(target, base)
    }

    private fun layout(kind: ConfigKind): String = layouts[kind]
        ?: firmware()?.takeIf { it in BundledDefinitions.versions }
        ?: BundledDefinitions.versions.last()

    private fun update(kind: ConfigKind, transform: (ConfigPaneState) -> ConfigPaneState) {
        _state.update { state ->
            val pane = transform(state.pane(kind)).let { pane ->
                val stored = pane.stored
                val edited = pane.edited
                pane.copy(changed = if (stored != null && edited != null) stored.diff(edited).map { it.name }.toSet() else emptySet())
            }
            if (kind == ConfigKind.MCCONF) state.copy(motor = pane) else state.copy(app = pane)
        }
    }

    private fun text(id: Int, vararg args: Any): String = string(id, args)
}
