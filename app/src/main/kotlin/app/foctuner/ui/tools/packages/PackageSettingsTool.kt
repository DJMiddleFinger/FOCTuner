package app.foctuner.ui.tools.packages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.config.Config
import app.foctuner.config.ConfigDefinitionException
import app.foctuner.config.ParamValue
import app.foctuner.config.PayloadException
import app.foctuner.config.diff
import app.foctuner.config.notAppliedIn
import app.foctuner.config.sections
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.settings.ParamDialog
import app.foctuner.ui.settings.ParamRow
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.ChoiceRow
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.vesc.customConfigName
import app.foctuner.vesc.parseCustomConfigDefinition
import app.foctuner.vesc.readCustomConfig
import app.foctuner.vesc.readCustomConfigDefinition
import app.foctuner.vesc.readDefaultCustomConfig
import app.foctuner.vesc.writeCustomConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.zip.ZipException

@Immutable
data class CustomPane(
    val index: Int,
    val name: String,
    val stored: Config,
    val edited: Config,
    /** Names of parameters the last write didn't apply. */
    val notApplied: List<String> = emptyList(),
    val written: Boolean = false,
)

@Immutable
data class PackageSettingsState(
    /** Null until the controller said how many custom configs it has. */
    val count: Int? = null,
    val panes: List<CustomPane> = emptyList(),
    val selected: Int = 0,
    val loading: Boolean = false,
    val progress: Float? = null,
    val failed: String? = null,
)

/**
 * The settings a package (or the hardware) registers as custom configs (VESC Tool's `pagecustomconfig`), edited
 * with the same rows and dialog as the motor and app settings. The definitions come from the controller.
 */
class PackageSettingsModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(PackageSettingsState())
    val state: StateFlow<PackageSettingsState> = _state.asStateFlow()
    private var job: Job? = null

    fun load() = work {
        val count = context.controller.firmwareInfo().customConfigCount
        _state.update { it.copy(count = count, panes = emptyList(), selected = 0) }
        val panes = ArrayList<CustomPane>()
        for (index in 0 until count) {
            val data = context.controller.readCustomConfigDefinition(index) { read, total ->
                _state.update { it.copy(progress = if (total > 0) (index + read.toFloat() / total) / count else null) }
            }
            val definition = try {
                withContext(Dispatchers.Default) { parseCustomConfigDefinition(data) }
            } catch (e: ZipException) {
                throw IOException("The controller sent a damaged settings definition", e)
            } catch (e: ConfigDefinitionException) {
                throw IOException("The controller sent a settings definition this app can't read: ${e.message}", e)
            }
            val stored = context.controller.readCustomConfig(index, Config.defaults(definition))
            panes += CustomPane(index, definition.customConfigName ?: "Config ${index + 1}", stored, stored)
        }
        _state.update { it.copy(panes = panes, progress = null) }
    }

    fun select(index: Int) = _state.update { it.copy(selected = index) }

    fun set(name: String, value: ParamValue) = updatePane { it.copy(edited = it.edited.with(name, value), written = false, notApplied = emptyList()) }

    fun revert() = updatePane { it.copy(edited = it.stored, written = false, notApplied = emptyList()) }

    fun defaults() = work {
        val pane = current() ?: return@work
        val defaults = context.controller.readDefaultCustomConfig(pane.index, pane.stored)
        updatePane { it.copy(edited = defaults, written = false) }
    }

    fun write() = work {
        val pane = current() ?: return@work
        context.controller.writeCustomConfig(pane.index, pane.edited)
        val back = context.controller.readCustomConfig(pane.index, pane.edited)
        val missed = pane.edited.notAppliedIn(back).map { back.definition.param(it).longName }
        updatePane { it.copy(stored = back, edited = back, notApplied = missed, written = true) }
    }

    private fun current(): CustomPane? = _state.value.panes.getOrNull(_state.value.selected)

    private fun updatePane(transform: (CustomPane) -> CustomPane) = _state.update { s ->
        s.copy(panes = s.panes.mapIndexed { i, p -> if (i == s.selected) transform(p) else p })
    }

    private fun work(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        _state.update { it.copy(loading = true, failed = null) }
        job = context.scope.launch {
            try {
                block()
                _state.update { it.copy(loading = false) }
            } catch (e: PayloadException) {
                _state.update { it.copy(loading = false, progress = null, failed = e.message) }
            } catch (e: IOException) {
                _state.update { it.copy(loading = false, progress = null, failed = e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}

@Composable
fun PackageSettingsTool(context: ToolContext) {
    val model = context.model { PackageSettingsModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { if (state.count == null) model.load() }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmWrite by rememberSaveable { mutableStateOf(false) }
    var subgroupIndex by rememberSaveable { mutableIntStateOf(0) }
    val pane = state.panes.getOrNull(state.selected)
    val subgroups = remember(pane?.edited?.definition) {
        pane?.edited?.definition?.sections()?.flatMap { g -> g.subgroups.map { sub -> (if (g.subgroups.size > 1) "${g.name} · ${sub.name}" else g.name) to sub } }.orEmpty()
    }
    val changed = remember(pane?.stored, pane?.edited) { pane?.let { it.stored.diff(it.edited).map { d -> d.name }.toSet() }.orEmpty() }

    Column {
        if (state.loading) {
            val p = state.progress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = FocTheme.colors.accent, trackColor = FocTheme.colors.outline)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = FocTheme.colors.accent, trackColor = FocTheme.colors.outline)
            }
        }
        state.failed?.let { Notice(stringResource(R.string.pkgset_failed), body = it, tone = NoticeTone.Fault, action = stringResource(R.string.pkgset_retry), onAction = model::load) }
        if (state.count == 0) {
            Notice(stringResource(R.string.pkgset_none), body = stringResource(R.string.pkgset_none_body))
            return@Column
        }
        if (state.panes.size > 1) {
            ChoiceRow(state.panes.map { it.index }, state.selected, { i -> state.panes[i].name }, { model.select(it); subgroupIndex = 0 })
        }
        if (pane == null) return@Column
        SectionHeader(pane.name)
        if (subgroups.size > 1) {
            ChoiceRow(subgroups.indices.toList(), subgroupIndex.coerceIn(subgroups.indices), { subgroups[it].first }, { subgroupIndex = it })
        }
        Row(Modifier.padding(horizontal = Spacing.x1)) {
            PlainButton(stringResource(R.string.pkgset_read), model::load, enabled = !state.loading)
            PlainButton(stringResource(R.string.pkgset_defaults), model::defaults, enabled = !state.loading)
            PlainButton(stringResource(R.string.pkgset_revert), model::revert, enabled = !state.loading && changed.isNotEmpty())
            PlainButton(stringResource(R.string.pkgset_write), { confirmWrite = true }, enabled = !state.loading && changed.isNotEmpty())
        }
        if (pane.written) {
            if (pane.notApplied.isEmpty()) {
                Notice(stringResource(R.string.pkgset_written))
            } else {
                Notice(stringResource(R.string.pkgset_not_applied), body = pane.notApplied.joinToString(), tone = NoticeTone.Warning)
            }
        }
        Hairline()
        val entries = subgroups.getOrNull(subgroupIndex.coerceIn(subgroups.indices.takeIf { !it.isEmpty() } ?: 0..0))?.second?.paramNames.orEmpty()
            .mapNotNull { pane.edited.definition.params[it] }
        LazyColumn {
            items(entries, key = { it.name }) { param ->
                ParamRow(
                    param = param,
                    value = pane.edited[param.name],
                    stored = pane.stored[param.name],
                    changed = param.name in changed,
                    onOpen = { dialog = param.name },
                ) { model.set(param.name, it) }
                Hairline(Modifier.padding(start = Spacing.x2))
            }
        }
    }
    val open = dialog?.let { name -> pane?.edited?.definition?.params?.get(name) }
    if (open != null && pane != null) {
        ParamDialog(open, pane.edited, pane.stored[open.name], { model.set(open.name, it); dialog = null }, { dialog = null })
    }
    if (confirmWrite && pane != null) {
        ConfirmDialog(
            title = stringResource(R.string.pkgset_write_title, pane.name),
            body = changed.joinToString("\n") { pane.edited.definition.param(it).longName } + "\n\n" + stringResource(R.string.pkgset_write_body),
            confirm = stringResource(R.string.pkgset_write),
            dismiss = stringResource(R.string.pkgset_cancel),
            onConfirm = {
                confirmWrite = false
                model.write()
            },
            onDismiss = { confirmWrite = false },
        )
    }
}
