package app.foctuner.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.config.Config
import app.foctuner.config.ConfigDefinition
import app.foctuner.config.ConfigKind
import app.foctuner.config.GroupEntry
import app.foctuner.config.ParamDefinition
import app.foctuner.config.ParamGroup
import app.foctuner.config.ParamSubgroup
import app.foctuner.config.ParamType
import app.foctuner.config.ParamValue
import app.foctuner.config.diff
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.TabStrip
import app.foctuner.ui.settings.curves.ChartBlock
import app.foctuner.ui.settings.curves.SettingChart
import app.foctuner.ui.settings.curves.SettingCharts
import app.foctuner.ui.theme.FocAnimatedContent
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.theme.ThemePreviews
import app.foctuner.ui.theme.lateral
import kotlin.math.sign

/**
 * One config (Motor Cfg or App Cfg) laid out like VESC Tool's mobile config pages: a picker for VESC Tool's groups,
 * tabs for the group's subgroups, and the subgroup's settings below with an editor on every row and charts of what
 * they do. Edits stay pending (marked on their rows and previewed on the charts) until Write, which lists them for
 * review before storing them on the controller.
 */
@Composable
fun SettingsScreen(kind: ConfigKind, state: SettingsState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val colors = FocTheme.colors
    var group by rememberSaveable(kind) { mutableIntStateOf(0) }
    var subgroup by rememberSaveable(kind) { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<String?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    val pane = state.pane(kind)
    val config = pane.edited

    LaunchedEffect(kind) { actions.open(kind) }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .weight(1f)
                .widthIn(max = 640.dp),
        ) {
            pane.error?.let { error ->
                Notice(
                    title = error,
                    tone = NoticeTone.Warning,
                    action = stringResource(R.string.settings_read_again),
                    onAction = { actions.read(kind) },
                )
            }
            val sections = pane.sections
            if (config == null || sections.isEmpty()) {
                if (pane.work != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.accent, trackColor = colors.hairline, gapSize = 0.dp)
                }
                Text(
                    stringResource(if (pane.work != null) R.string.settings_reading else R.string.settings_not_read),
                    Modifier
                        .weight(1f)
                        .padding(Spacing.x2),
                    color = colors.textSecondary,
                    style = FocTheme.type.supporting,
                )
            } else {
                val shownGroup = group.coerceIn(0, sections.lastIndex)
                val subgroups = sections[shownGroup].subgroups
                val shownSubgroup = subgroup.coerceIn(0, maxOf(subgroups.lastIndex, 0))
                GroupPicker(kind, sections, shownGroup, config.definition, pane.changed) {
                    group = it
                    subgroup = 0
                }
                if (subgroups.size > 1) {
                    val labels = subgroups.map { sub ->
                        val pending = sub.paramNames.count { it in pane.changed }
                        if (pending > 0) stringResource(R.string.settings_tab_pending, sub.name, pending) else sub.name
                    }
                    TabStrip(labels, shownSubgroup, { subgroup = it }, scrollable = true)
                } else {
                    Hairline()
                }
                if (pane.work != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.accent, trackColor = colors.hairline, gapSize = 0.dp)
                }
                FocAnimatedContent(
                    target = shownGroup to shownSubgroup,
                    label = "settings-page",
                    modifier = Modifier.weight(1f),
                    transition = {
                        val direction = if (targetState.first != initialState.first) {
                            targetState.first - initialState.first
                        } else {
                            targetState.second - initialState.second
                        }
                        lateral(direction.sign)
                    },
                ) { (g, s) ->
                    val page = sections.getOrNull(g)?.subgroups?.getOrNull(s)
                    if (page != null) {
                        SubgroupPage(
                            subgroup = page,
                            config = config,
                            stored = pane.stored,
                            changed = pane.changed,
                            onOpen = { editing = it },
                            onSet = { name, value -> actions.edit(kind, name, value) },
                        )
                    }
                }
            }
        }
        ActionBar(pane, kind, actions, onWrite = { reviewing = true })
    }

    val param = editing?.let { config?.definition?.params?.get(it) }
    if (param != null && config != null) {
        ParamDialog(
            param = param,
            config = config,
            stored = pane.stored?.get(param.name),
            onSet = {
                actions.edit(kind, param.name, it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    val stored = pane.stored
    if (reviewing && stored != null && config != null) {
        val changes = remember(stored, config) { stored.diff(config) }
        if (changes.isNotEmpty()) {
            ReviewDialog(
                changes = changes,
                definition = config.definition,
                onWrite = {
                    reviewing = false
                    actions.write(kind)
                },
                onDismiss = { reviewing = false },
            )
        }
    }
}

/** VESC Tool's group combo box: the selected group with its picture, opening a list of every group. */
@Composable
private fun GroupPicker(
    kind: ConfigKind,
    sections: List<ParamGroup>,
    selected: Int,
    definition: ConfigDefinition,
    changed: Set<String>,
    onSelect: (Int) -> Unit,
) {
    val colors = FocTheme.colors
    var open by remember { mutableStateOf(false) }
    val counts = remember(sections, definition) {
        sections.map { group -> group.subgroups.flatMap { it.paramNames }.filter { it in definition.params }.distinct() }
    }
    @Composable
    fun summary(index: Int): Pair<String, Boolean> {
        val names = counts[index]
        val pending = names.count { it in changed }
        return if (pending > 0) {
            pluralStringResource(R.plurals.settings_changed, pending, pending) to true
        } else {
            pluralStringResource(R.plurals.settings_count, names.size, names.size) to false
        }
    }
    val current = sections[selected]
    val (line, pending) = summary(selected)
    val pickerLabel = stringResource(R.string.settings_group_picker)
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.row)
                .clickable(onClickLabel = stringResource(R.string.settings_choose_group), role = Role.DropdownList) { open = true }
                .semantics {
                    contentDescription = pickerLabel
                    stateDescription = "${current.name}, $line"
                }
                .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(groupIcon(kind, current.name)), null, Modifier.size(24.dp), tint = colors.textSecondary)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = Spacing.x2),
            ) {
                Text(current.name, color = colors.text, style = FocTheme.type.bodyStrong)
                Text(line, color = if (pending) colors.warning else colors.textTertiary, style = FocTheme.type.caption)
            }
            Icon(painterResource(R.drawable.ic_expand), null, Modifier.size(24.dp), tint = colors.textSecondary)
        }
        DropdownMenu(open, { open = false }, Modifier.widthIn(min = 280.dp), containerColor = colors.surface) {
            sections.forEachIndexed { index, group ->
                val (text, groupPending) = summary(index)
                val isSelected = index == selected
                DropdownMenuItem(
                    text = {
                        Column(Modifier.padding(vertical = Spacing.half)) {
                            Text(group.name, color = colors.text, style = if (isSelected) FocTheme.type.bodyStrong else FocTheme.type.body)
                            Text(text, color = if (groupPending) colors.warning else colors.textTertiary, style = FocTheme.type.caption)
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(index)
                    },
                    modifier = Modifier.semantics { this.selected = isSelected },
                    leadingIcon = {
                        Icon(painterResource(groupIcon(kind, group.name)), null, Modifier.size(24.dp), tint = if (isSelected) colors.accent else colors.textSecondary)
                    },
                )
            }
        }
    }
}

@DrawableRes
private fun groupIcon(kind: ConfigKind, group: String): Int = sectionIcon(kind, group, "General")

/**
 * A picture for one of VESC Tool's settings pages, by its name, or by its section's name for the many pages called
 * General. The names are the same in every bundled firmware; one we don't know gets the settings sliders.
 */
@DrawableRes
private fun sectionIcon(kind: ConfigKind, group: String, subgroup: String): Int = when (subgroup) {
    "Sensors", "Hall Sensors" -> R.drawable.ic_tool_magnet
    "Current", "Field Weakening" -> R.drawable.ic_set_bolt
    "Voltage" -> R.drawable.ic_tool_battery
    "RPM" -> R.drawable.ic_nav_overview
    "Wattage" -> R.drawable.ic_tool_power
    "Temperature" -> R.drawable.ic_set_thermometer
    "BMS" -> R.drawable.ic_tool_chip
    "Sensorless" -> R.drawable.ic_tool_rotate
    "Encoder" -> R.drawable.ic_tool_encoder
    "HFI", "VSS" -> R.drawable.ic_tool_pulses
    "Filters", "Throttle Curve" -> R.drawable.ic_curve
    "Offsets" -> R.drawable.ic_tool_level
    "Setup" -> R.drawable.ic_tool_wizard
    "Quality" -> R.drawable.ic_set_star
    "Mapping" -> R.drawable.ic_tool_slider
    "General" -> when (group) {
        "General" -> if (kind == ConfigKind.APPCONF) R.drawable.ic_nav_controllers else R.drawable.ic_tool_rotate
        "BLDC" -> R.drawable.ic_tool_trapezoid
        "DC", "PAS" -> R.drawable.ic_tool_rotate
        "FOC" -> R.drawable.ic_tool_sine
        "PID Controllers" -> R.drawable.ic_set_target
        "Additional Info" -> R.drawable.ic_set_info
        "PPM" -> R.drawable.ic_tool_pulses
        "ADC" -> R.drawable.ic_tool_slider
        "UART" -> R.drawable.ic_nav_terminal
        "VESC Remote" -> R.drawable.ic_tool_joystick
        "NRF" -> R.drawable.ic_set_radio
        "IMU" -> R.drawable.ic_tool_axes
        else -> R.drawable.ic_nav_settings
    }
    else -> R.drawable.ic_nav_settings
}

@Composable
private fun SubgroupPage(
    subgroup: ParamSubgroup,
    config: Config,
    stored: Config?,
    changed: Set<String>,
    onOpen: (String) -> Unit,
    onSet: (String, ParamValue) -> Unit,
) {
    val colors = FocTheme.colors
    val charts = remember(subgroup, config.definition) { SettingCharts.on(subgroup, config.definition) }
    val anchored = remember(charts) { charts.filterNot { it.atTop }.associateBy { it.anchor } }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // The controller's values are drawn under a chart only while one of its settings has a pending edit.
        fun storedFor(chart: SettingChart) = stored?.takeIf { chart.params.any { it in changed } }
        val chartSet = { name: String, raw: Double ->
            config.definition.params[name]?.takeIf { it.isNumber }?.let { p -> onSet(name, p.fromChart(raw, config[name], stored?.get(name))) }
            Unit
        }
        val chartStep = { name: String, up: Boolean ->
            config.definition.params[name]?.takeIf { it.isNumber }?.let { p -> onSet(name, p.nudged(config[name], stored?.get(name), up)) }
            Unit
        }
        for (chart in charts.filter { it.atTop }) {
            item(key = "chart-${chart.key}") {
                ChartBlock(chart, config, storedFor(chart), R.string.curve_on_controller, Modifier.padding(Spacing.x2), onSet = chartSet, onStep = chartStep)
                Hairline()
            }
        }
        subgroup.entries.forEachIndexed { index, entry ->
            when (entry) {
                is GroupEntry.Separator -> item(key = "sep$index") { SectionHeader(entry.label) }
                is GroupEntry.Param -> {
                    anchored[entry.name]?.let { chart ->
                        item(key = "chart-${chart.key}") {
                            ChartBlock(chart, config, storedFor(chart), R.string.curve_on_controller, Modifier.padding(Spacing.x2), onSet = chartSet, onStep = chartStep)
                            Hairline()
                        }
                    }
                    val param = config.definition.params[entry.name] ?: return@forEachIndexed
                    item(key = "param$index") {
                        Column {
                            ParamRow(
                                param = param,
                                value = config[param.name],
                                stored = stored?.get(param.name),
                                changed = param.name in changed,
                                onOpen = { onOpen(param.name) },
                            ) { onSet(param.name, it) }
                            Hairline(Modifier.padding(start = Spacing.x2))
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(Spacing.x2)) }
    }
}

@Composable
private fun ActionBar(pane: ConfigPaneState, kind: ConfigKind, actions: SettingsActions, onWrite: () -> Unit) {
    val colors = FocTheme.colors
    val idle = pane.work == null
    val loaded = pane.stored != null
    val pending = pane.changed.size
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.row)
                .padding(horizontal = Spacing.x1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.half),
        ) {
            PlainButton(stringResource(R.string.settings_read), { actions.read(kind) }, enabled = idle)
            PlainButton(stringResource(R.string.settings_defaults), { actions.loadDefaults(kind) }, enabled = idle && loaded)
            Spacer(Modifier.weight(1f))
            PlainButton(stringResource(R.string.settings_revert), { actions.revert(kind) }, enabled = idle && pending > 0)
            OutlineButton(
                if (pending > 0) stringResource(R.string.settings_write_count, pending) else stringResource(R.string.settings_write),
                onWrite,
                enabled = idle && pending > 0,
            )
        }
    }
}

private val previewDefinition = ConfigDefinition(
    listOf(
        ParamDefinition("l_current_max", ParamType.DOUBLE, ParamValue.DoubleValue(60.0), longName = "Motor Current Max", suffix = " A", maxDouble = 5000.0),
        ParamDefinition("m_invert_direction", ParamType.BOOL, ParamValue.IntValue(0), longName = "Invert Motor Direction"),
        ParamDefinition("motor_type", ParamType.ENUM, ParamValue.IntValue(2), longName = "Motor Type", enumNames = listOf("BLDC", "DC", "FOC", "GPD")),
        ParamDefinition(
            "sensor_mode", ParamType.ENUM, ParamValue.IntValue(0), longName = "Sensor Mode",
            enumNames = listOf("Sensorless", "Sensored", "Hybrid", "Encoder plus hall sensors"),
        ),
    ),
    serializeOrder = emptyList(),
    groups = listOf(
        ParamGroup(
            "General",
            listOf(
                ParamSubgroup("General", listOf(GroupEntry.Param("motor_type"), GroupEntry.Param("sensor_mode"), GroupEntry.Param("m_invert_direction"))),
                ParamSubgroup("Current", listOf(GroupEntry.Separator("Motor"), GroupEntry.Param("l_current_max"))),
            ),
        ),
    ),
)

private object NoActions : SettingsActions {
    override fun open(kind: ConfigKind) = Unit
    override fun read(kind: ConfigKind) = Unit
    override fun loadDefaults(kind: ConfigKind) = Unit
    override fun edit(kind: ConfigKind, name: String, value: ParamValue) = Unit
    override fun revert(kind: ConfigKind) = Unit
    override fun write(kind: ConfigKind) = Unit
}

@ThemePreviews
@Composable
private fun SettingsPreview() {
    val stored = Config.defaults(previewDefinition)
    FocTheme {
        SettingsScreen(
            ConfigKind.MCCONF,
            SettingsState(
                motor = ConfigPaneState(
                    sections = previewDefinition.groups,
                    stored = stored,
                    edited = stored.with("l_current_max", 80.0),
                    changed = setOf("l_current_max"),
                ),
            ),
            NoActions,
        )
    }
}
