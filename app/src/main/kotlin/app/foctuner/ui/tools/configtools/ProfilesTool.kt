package app.foctuner.ui.tools.configtools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.config.PayloadException
import app.foctuner.prefs.Units
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.prefs.appPreferences
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.vesc.ConfigProfile
import app.foctuner.vesc.applyProfile
import app.foctuner.vesc.readMatchingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Immutable
data class ProfilesState(
    val profiles: List<ConfigProfile> = emptyList(),
    /** The controller's motor config, for the in-use mark and new profiles; null until read. */
    val mcconf: Config? = null,
    val busy: Boolean = false,
    val failure: String? = null,
)

/** VESC Tool mobile's Profiles: riding limits kept on the phone, switched to with COMM_SET_MCCONF_TEMP_SETUP. */
class ProfilesModel(private val context: ToolContext, private val storage: ConfigStorage) {
    private val _state = MutableStateFlow(ProfilesState())
    val state: StateFlow<ProfilesState> = _state.asStateFlow()

    fun load() = work { _state.update { it.copy(profiles = storage.profiles()) } }

    /** Replaces the profile at [index], or adds [profile] when [index] is null. */
    fun save(index: Int?, profile: ConfigProfile) = work {
        val list = _state.value.profiles.toMutableList().apply { if (index == null) add(profile) else set(index, profile) }
        storage.saveProfiles(list)
        _state.update { it.copy(profiles = list) }
    }

    fun delete(index: Int) = work {
        val list = _state.value.profiles.filterIndexed { i, _ -> i != index }
        storage.saveProfiles(list)
        _state.update { it.copy(profiles = list) }
    }

    fun apply(profile: ConfigProfile, permanently: Boolean) = work { context.controller.applyProfile(profile, permanently) }

    /** Runs [block] off the main thread, one at a time, then reads the motor config again for the in-use mark. */
    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, failure = null) }
        context.scope.launch {
            val failure = try {
                withContext(Dispatchers.IO) {
                    block()
                    val mcconf = context.controller.readMatchingConfig(ConfigKind.MCCONF, context.firmware.version)
                    _state.update { it.copy(mcconf = mcconf) }
                }
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
fun ProfilesTool(context: ToolContext) {
    val storage = ConfigStorage.of(LocalContext.current)
    val model = context.model { ProfilesModel(context, storage) }
    val state by model.state.collectAsStateWithLifecycle()
    val units = appPreferences().units
    val colors = FocTheme.colors
    var editing by remember { mutableStateOf<Pair<Int?, ConfigProfile>?>(null) }
    var applying by remember { mutableStateOf<Pair<ConfigProfile, Boolean>?>(null) }
    var deleting by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(model) { model.load() }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.accent, trackColor = colors.hairline, gapSize = 0.dp)
        state.failure?.let { Notice(stringResource(R.string.configtools_no_reply), body = it, tone = NoticeTone.Warning) }
        SectionHeader(stringResource(R.string.profiles_title)) {
            val mcconf = state.mcconf
            PlainButton(stringResource(R.string.profiles_add), { editing = mcconf?.let { null to ConfigProfile.fromConfig(it, "") } }, enabled = mcconf != null && !state.busy)
        }
        if (state.profiles.isEmpty()) {
            Text(stringResource(R.string.profiles_none), Modifier.padding(Spacing.x2), color = colors.textSecondary, style = FocTheme.type.supporting)
        }
        state.profiles.forEachIndexed { index, profile ->
            val inUse = state.mcconf?.let(profile::isInUse) == true
            Column(Modifier.fillMaxWidth().padding(start = Spacing.x2, end = Spacing.x1, top = Spacing.x1), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (inUse) stringResource(R.string.profiles_in_use, profile.name) else profile.name,
                    color = if (inUse) colors.accent else colors.text,
                    style = FocTheme.type.bodyStrong,
                )
                Text(summary(profile, units), color = colors.textSecondary, style = FocTheme.type.supporting)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PlainButton(stringResource(R.string.settings_history_delete), { deleting = index }, enabled = !state.busy)
                    PlainButton(stringResource(R.string.profiles_edit), { editing = index to profile }, enabled = !state.busy)
                    PlainButton(stringResource(R.string.profiles_use_until_reboot), { applying = profile to false }, enabled = !state.busy)
                    PlainButton(stringResource(R.string.profiles_use_permanently), { applying = profile to true }, enabled = !state.busy)
                }
            }
            Hairline(Modifier.padding(start = Spacing.x2))
        }
        Text(stringResource(R.string.profiles_body), Modifier.padding(Spacing.x2), color = colors.textTertiary, style = FocTheme.type.caption)
    }

    editing?.let { (index, profile) ->
        ProfileDialog(profile, units, adding = index == null, onSave = {
            editing = null
            model.save(index, it)
        }, onDismiss = { editing = null })
    }
    applying?.let { (profile, permanently) ->
        ConfirmDialog(
            title = stringResource(if (permanently) R.string.profiles_use_permanently else R.string.profiles_use_until_reboot) + ": " + profile.name,
            body = stringResource(if (permanently) R.string.profiles_apply_permanently_body else R.string.profiles_apply_body, summary(profile, units)),
            confirm = stringResource(R.string.profiles_apply),
            dismiss = stringResource(R.string.settings_cancel),
            onConfirm = {
                applying = null
                model.apply(profile, permanently)
            },
            onDismiss = { applying = null },
        )
    }
    deleting?.let { index ->
        ConfirmDialog(
            title = stringResource(R.string.profiles_delete_title, state.profiles.getOrNull(index)?.name.orEmpty()),
            body = stringResource(R.string.profiles_delete_body),
            confirm = stringResource(R.string.settings_history_delete),
            dismiss = stringResource(R.string.settings_cancel),
            onConfirm = {
                deleting = null
                model.delete(index)
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun summary(p: ConfigProfile, units: Units): String = listOfNotNull(
    stringResource(R.string.profiles_speed, units.speed(p.speedMax.toFloat()).fixed(0), units.speed(-p.speedMin.toFloat()).fixed(0), units.speed),
    stringResource(R.string.profiles_currents, (p.currentMaxScale * 100).fixed(0), (p.currentMinScale * 100).fixed(0)),
    if (p.limitsPower) stringResource(R.string.profiles_power, p.wattMax.fixed(0), (-p.wattMin).fixed(0)) else stringResource(R.string.profiles_no_power_limit),
).joinToString(" · ")

/** Name, speeds in [units], current scales in percent and power in watts; an empty power field means no limit. */
@Composable
private fun ProfileDialog(profile: ConfigProfile, units: Units, adding: Boolean, onSave: (ConfigProfile) -> Unit, onDismiss: () -> Unit) {
    val colors = FocTheme.colors
    val perMs = units.speed(1f).toDouble()
    var name by remember { mutableStateOf(profile.name) }
    val fields = remember {
        mutableStateListOf(
            (profile.speedMax * perMs).fixed(1),
            (-profile.speedMin * perMs).fixed(1),
            (profile.currentMaxScale * 100).fixed(0),
            (profile.currentMinScale * 100).fixed(0),
            if (profile.limitsPower) profile.wattMax.fixed(0) else "",
            if (profile.limitsPower) (-profile.wattMin).fixed(0) else "",
        )
    }
    val labels = listOf(
        stringResource(R.string.profiles_max_speed, units.speed),
        stringResource(R.string.profiles_max_reverse, units.speed),
        stringResource(R.string.profiles_motor_current),
        stringResource(R.string.profiles_brake_current),
        stringResource(R.string.profiles_max_power),
        stringResource(R.string.profiles_max_regen),
    )
    val numbers = fields.map { it.replace(',', '.').trim().toDoubleOrNull()?.takeIf { v -> v >= 0 } }
    val powerOff = fields[4].isBlank() && fields[5].isBlank()
    val valid = name.isNotBlank() && numbers.take(4).all { it != null } && numbers[2]!! <= 100 && numbers[3]!! <= 100 &&
        (powerOff || numbers[4] != null && numbers[5] != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            PlainButton(stringResource(R.string.profiles_save), {
                onSave(
                    profile.copy(
                        name = name.trim(),
                        speedMax = numbers[0]!! / perMs,
                        speedMin = -numbers[1]!! / perMs,
                        currentMaxScale = numbers[2]!! / 100,
                        currentMinScale = numbers[3]!! / 100,
                        wattMax = if (powerOff) ConfigProfile.NO_POWER_LIMIT else numbers[4]!!,
                        wattMin = if (powerOff) -ConfigProfile.NO_POWER_LIMIT else -numbers[5]!!,
                    ),
                )
            }, enabled = valid)
        },
        dismissButton = { PlainButton(stringResource(R.string.settings_cancel), onDismiss) },
        title = { Text(stringResource(if (adding) R.string.profiles_add_title else R.string.profiles_edit_title), style = FocTheme.type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.profiles_name)) }, isError = name.isBlank())
                labels.forEachIndexed { i, label ->
                    OutlinedTextField(
                        fields[i],
                        { fields[i] = it },
                        Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(label) },
                        isError = numbers[i] == null && !(i >= 4 && powerOff),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            }
        },
        shape = MaterialTheme.shapes.small,
        containerColor = colors.surface,
        titleContentColor = colors.text,
    )
}
