package app.foctuner.ui.tools.configtools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.config.GroupEntry
import app.foctuner.config.ParamValue
import app.foctuner.config.PayloadException
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.TabStrip
import app.foctuner.ui.settings.ParamDialog
import app.foctuner.ui.settings.ParamRow
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.vesc.AUTOMATIC_BACKUPS
import app.foctuner.vesc.ConfigTarget
import app.foctuner.vesc.MultiSettingGroup
import app.foctuner.vesc.backUpConfigs
import app.foctuner.vesc.configTargets
import app.foctuner.vesc.controller
import app.foctuner.vesc.readMatchingConfig
import app.foctuner.vesc.writeMotorParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Immutable
data class MultiSettingsState(
    val stored: Config? = null,
    val edited: Config? = null,
    val busy: Boolean = false,
    val failure: String? = null,
    /** Write to all, waiting for confirmation: the group and the controllers it goes to. */
    val plan: Pair<MultiSettingGroup, List<ConfigTarget>>? = null,
    val outcomes: List<TargetOutcome> = emptyList(),
)

/**
 * VESC Tool mobile's Multi Settings: one group of motor settings, edited on the selected controller's values and
 * written to every motor controller on the bus, each after an automatic backup.
 */
class MultiSettingsModel(private val context: ToolContext, private val storage: ConfigStorage) {
    private val _state = MutableStateFlow(MultiSettingsState())
    val state: StateFlow<MultiSettingsState> = _state.asStateFlow()

    fun read() = work {
        val mcconf = context.controller.readMatchingConfig(ConfigKind.MCCONF, context.firmware.version)
        _state.update { it.copy(stored = mcconf, edited = mcconf) }
    }

    fun edit(name: String, value: ParamValue) = _state.update { it.copy(edited = it.edited?.with(name, value)) }

    fun planWrite(group: MultiSettingGroup) = work {
        val targets = context.client.configTargets()
        _state.update { it.copy(plan = group to targets) }
    }

    fun cancelWrite() = _state.update { it.copy(plan = null) }

    fun writeAll() {
        val (group, targets) = _state.value.plan ?: return
        val edited = _state.value.edited ?: return
        _state.update { it.copy(plan = null) }
        val values = group.params.filter { it in edited.definition.params }.associateWith { edited[it] }
        work {
            val outcomes = targets.map { t ->
                outcome(t) {
                    val controller = context.client.controller(t)
                    controller.backUpConfigs(storage.backups, t.firmware, automatic = true, base = storage.base(t.uuid))
                    storage.backups.pruneAutomatic(t.uuid, AUTOMATIC_BACKUPS)
                    val result = controller.writeMotorParams(t.firmware, values)
                    result.write.notAppliedNames() + result.skipped
                }
            }
            val mcconf = context.controller.readMatchingConfig(ConfigKind.MCCONF, context.firmware.version)
            _state.update { it.copy(outcomes = outcomes, stored = mcconf, edited = mcconf) }
        }
    }

    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, failure = null) }
        context.scope.launch {
            val failure = try {
                withContext(Dispatchers.IO) { block() }
                null
            } catch (e: IOException) {
                e.message ?: e.javaClass.simpleName
            } catch (e: PayloadException) {
                e.message
            }
            _state.update { it.copy(busy = false, failure = failure) }
        }
    }
}

@Composable
fun MultiSettingsTool(context: ToolContext) {
    val storage = ConfigStorage.of(LocalContext.current)
    val model = context.model { MultiSettingsModel(context, storage) }
    val state by model.state.collectAsStateWithLifecycle()
    val colors = FocTheme.colors
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<String?>(null) }
    val groups = MultiSettingGroup.entries
    val group = groups[tab]
    LaunchedEffect(model) { model.read() }

    Column {
        TabStrip(groups.map { stringResource(it.title) }, tab, { tab = it })
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.accent, trackColor = colors.hairline, gapSize = 0.dp)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            state.failure?.let { Notice(stringResource(R.string.configtools_no_reply), body = it, tone = NoticeTone.Warning) }
            Outcomes(state.outcomes)
            val stored = state.stored
            val edited = state.edited
            if (stored != null && edited != null) {
                group.entries.forEach { entry ->
                    when (entry) {
                        is GroupEntry.Separator -> SectionHeader(entry.label)
                        is GroupEntry.Param -> edited.definition.params[entry.name]?.let { param ->
                            ParamRow(
                                param = param,
                                value = edited[param.name],
                                stored = stored[param.name],
                                changed = edited[param.name] != stored[param.name],
                                onOpen = { editing = param.name },
                                onSet = { model.edit(param.name, it) },
                            )
                        }
                    }
                }
            }
            Text(stringResource(R.string.multi_body), Modifier.padding(Spacing.x2), color = colors.textTertiary, style = FocTheme.type.caption)
        }
        OutlineButton(
            stringResource(R.string.multi_write_all, stringResource(group.title)),
            { model.planWrite(group) },
            Modifier.fillMaxWidth().padding(Spacing.x2),
            enabled = state.edited != null && !state.busy,
        )
    }

    val config = state.edited
    val param = editing?.let { config?.definition?.params?.get(it) }
    if (param != null && config != null) {
        ParamDialog(param, config, state.stored?.get(param.name), onSet = {
            model.edit(param.name, it)
            editing = null
        }, onDismiss = { editing = null })
    }
    state.plan?.let { (planned, targets) ->
        ConfirmDialog(
            title = stringResource(R.string.multi_write_all, stringResource(planned.title)),
            body = pluralStringResource(R.plurals.multi_write_body, planned.params.size, planned.params.size, targets.map { targetLabel(it) }.joinToString("\n")),
            confirm = stringResource(R.string.multi_write),
            dismiss = stringResource(R.string.settings_cancel),
            onConfirm = model::writeAll,
            onDismiss = model::cancelWrite,
        )
    }
}

private val MultiSettingGroup.title: Int
    get() = when (this) {
        MultiSettingGroup.General -> R.string.multi_general
        MultiSettingGroup.Limits -> R.string.multi_limits
        MultiSettingGroup.Foc -> R.string.multi_foc
        MultiSettingGroup.Bms -> R.string.multi_bms
    }
