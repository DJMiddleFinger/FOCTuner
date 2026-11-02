package app.foctuner.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import app.foctuner.ui.components.PageHeader
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.settings.curves.ChartBlock
import app.foctuner.ui.settings.curves.SettingChart
import app.foctuner.ui.settings.curves.SettingCharts
import app.foctuner.ui.theme.FocAnimatedContent
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.theme.ThemePreviews
import app.foctuner.ui.theme.lateral
import app.foctuner.ui.theme.motionSpec
import app.foctuner.ui.theme.push

/** The subgroup page that is open, with the name of the section it belongs to for the breadcrumb. */
@Immutable
private data class OpenPage(val section: String, val subgroup: ParamSubgroup)

/**
 * Every motor and app setting, in VESC Tool's sections: a list of groups and their subgroups, and a page of
 * settings per subgroup with charts of what the settings do. Edits stay pending (marked on their rows and
 * previewed on the charts) until Write, which lists them for review before storing them on the controller.
 */
@Composable
fun SettingsScreen(state: SettingsState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val colors = FocTheme.colors
    var kind by rememberSaveable { mutableStateOf(ConfigKind.MCCONF) }
    var group by rememberSaveable { mutableIntStateOf(-1) }
    var subgroup by rememberSaveable { mutableIntStateOf(-1) }
    var editing by remember { mutableStateOf<String?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    val pane = state.pane(kind)
    val config = pane.edited
    val opened = pane.sections.getOrNull(group)?.subgroups?.getOrNull(subgroup)?.let { OpenPage(pane.sections[group].name, it) }

    LaunchedEffect(kind) { actions.open(kind) }
    BackHandler(enabled = opened != null) {
        group = -1
        subgroup = -1
    }

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
            KindTabs(kind) {
                kind = it
                group = -1
                subgroup = -1
            }
            if (pane.work != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.accent, trackColor = colors.hairline, gapSize = 0.dp)
            } else {
                Hairline(Modifier.padding(top = 2.dp))
            }
            FocAnimatedContent(
                target = kind,
                label = "settings-kind",
                modifier = Modifier.weight(1f),
                transition = { lateral(if (targetState.ordinal > initialState.ordinal) 1 else -1) },
            ) { shown ->
                val shownPane = state.pane(shown)
                val shownConfig = shownPane.edited
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(colors.background),
                ) {
                    shownPane.error?.let { error ->
                        Notice(
                            title = error,
                            tone = NoticeTone.Warning,
                            action = stringResource(R.string.settings_read_again),
                            onAction = { actions.read(shown) },
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        if (shownConfig == null) {
                            Text(
                                stringResource(if (shownPane.work != null) R.string.settings_reading else R.string.settings_not_read),
                                Modifier.padding(Spacing.x2),
                                color = colors.textSecondary,
                                style = FocTheme.type.supporting,
                            )
                        } else {
                            FocAnimatedContent(
                                target = if (shown == kind) opened else null,
                                label = "settings-page",
                                transition = { push(forward = targetState != null) },
                            ) { page ->
                                if (page != null) {
                                    SubgroupPage(
                                        breadcrumb = stringResource(
                                            R.string.breadcrumb,
                                            stringResource(if (shown == ConfigKind.MCCONF) R.string.settings_motor else R.string.settings_app),
                                            page.section,
                                        ),
                                        subgroup = page.subgroup,
                                        config = shownConfig,
                                        stored = shownPane.stored,
                                        changed = shownPane.changed,
                                        onBack = {
                                            group = -1
                                            subgroup = -1
                                        },
                                        onOpen = { editing = it },
                                        onSet = { name, value -> actions.edit(shown, name, value) },
                                    )
                                } else {
                                    SectionList(shownPane.sections, shownConfig.definition, shownPane.changed) { g, s ->
                                        group = g
                                        subgroup = s
                                    }
                                }
                            }
                        }
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

@Composable
private fun KindTabs(selected: ConfigKind, onSelect: (ConfigKind) -> Unit) {
    val colors = FocTheme.colors
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(Spacing.touchTarget),
    ) {
        val tabWidth = maxWidth / ConfigKind.entries.size
        val indicator = 48.dp
        val indicatorX by animateDpAsState(tabWidth * selected.ordinal + (tabWidth - indicator) / 2, motionSpec(), label = "tab-indicator")
        Row(
            Modifier
                .fillMaxSize()
                .selectableGroup(),
        ) {
            for (kind in ConfigKind.entries) {
                val isSelected = kind == selected
                val tint by animateColorAsState(if (isSelected) colors.text else colors.textTertiary, motionSpec(), label = "tab-text")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(kind) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(if (kind == ConfigKind.MCCONF) R.string.settings_motor else R.string.settings_app),
                        color = tint,
                        style = FocTheme.type.label,
                    )
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .offset { IntOffset(indicatorX.roundToPx(), 0) }
                .width(indicator)
                .height(2.dp)
                .background(colors.accent),
        )
    }
}

@Composable
private fun SectionList(
    sections: List<ParamGroup>,
    definition: ConfigDefinition,
    changed: Set<String>,
    onOpen: (group: Int, subgroup: Int) -> Unit,
) {
    val colors = FocTheme.colors
    val chartLabel = stringResource(R.string.curve_has_chart)
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        sections.forEachIndexed { g, group ->
            item(key = "g$g") { SectionHeader(group.name) }
            itemsIndexed(group.subgroups, key = { s, _ -> "g$g-s$s" }) { s, subgroup ->
                val names = subgroup.paramNames.filter { it in definition.params }
                val pending = names.count { it in changed }
                val charted = remember(subgroup, definition) { SettingCharts.on(subgroup, definition).isNotEmpty() }
                Column {
                    Hairline(Modifier.padding(start = if (s == 0) 0.dp else Spacing.x2))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.row)
                            .clickable(role = Role.Button) { onOpen(g, s) }
                            .padding(horizontal = Spacing.x2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(subgroup.name, Modifier.weight(1f), color = colors.text, style = FocTheme.type.body)
                        if (charted) {
                            Icon(
                                painterResource(R.drawable.ic_curve),
                                chartLabel,
                                Modifier
                                    .padding(end = Spacing.x1)
                                    .size(18.dp),
                                tint = colors.textTertiary,
                            )
                        }
                        Text(
                            if (pending > 0) {
                                pluralStringResource(R.plurals.settings_changed, pending, pending)
                            } else {
                                pluralStringResource(R.plurals.settings_count, names.size, names.size)
                            },
                            color = if (pending > 0) colors.warning else colors.textTertiary,
                            style = FocTheme.type.caption,
                        )
                        Text("›", Modifier.padding(start = Spacing.x1), color = colors.textTertiary, style = FocTheme.type.body)
                    }
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(Spacing.x2)) }
    }
}

@Composable
private fun SubgroupPage(
    breadcrumb: String,
    subgroup: ParamSubgroup,
    config: Config,
    stored: Config?,
    changed: Set<String>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onSet: (String, ParamValue) -> Unit,
) {
    val colors = FocTheme.colors
    val none = stringResource(R.string.settings_none)
    val charts = remember(subgroup, config.definition) { SettingCharts.on(subgroup, config.definition) }
    val anchored = remember(charts) { charts.filterNot { it.atTop }.associateBy { it.anchor } }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        PageHeader(subgroup.name, onBack, breadcrumb = breadcrumb)
        Hairline()
        LazyColumn(Modifier.fillMaxSize()) {
            // The controller's values are drawn under a chart only while one of its settings has a pending edit.
            fun storedFor(chart: SettingChart) = stored?.takeIf { chart.params.any { it in changed } }
            for (chart in charts.filter { it.atTop }) {
                item(key = "chart-${chart.key}") {
                    ChartBlock(chart, config, storedFor(chart), R.string.curve_on_controller, Modifier.padding(Spacing.x2))
                    Hairline()
                }
            }
            subgroup.entries.forEachIndexed { index, entry ->
                when (entry) {
                    is GroupEntry.Separator -> item(key = "sep$index") { SectionHeader(entry.label) }
                    is GroupEntry.Param -> {
                        anchored[entry.name]?.let { chart ->
                            item(key = "chart-${chart.key}") {
                                ChartBlock(chart, config, storedFor(chart), R.string.curve_on_controller, Modifier.padding(Spacing.x2))
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
                                    none = none,
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
}

@Composable
private fun ParamRow(
    param: ParamDefinition,
    value: ParamValue,
    stored: ParamValue?,
    changed: Boolean,
    none: String,
    onOpen: () -> Unit,
    onSet: (ParamValue) -> Unit,
) {
    val colors = FocTheme.colors
    val bar by animateColorAsState(if (changed) colors.warning else Color.Transparent, motionSpec(), label = "changed-bar")
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = Spacing.row)
            .clickable(role = Role.Button, onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(bar),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = Spacing.x2 - 3.dp, top = Spacing.x1, bottom = Spacing.x1),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(param.longName, color = colors.text, style = FocTheme.type.body)
            if (changed && stored != null) {
                Text(stringResource(R.string.settings_was, param.display(stored, none)), color = colors.warning, style = FocTheme.type.caption)
            }
            if (!param.transmittable) {
                Text(stringResource(R.string.settings_not_on_controller), color = colors.textTertiary, style = FocTheme.type.caption)
            }
        }
        if (param.type == ParamType.BOOL) {
            val on = (value as? ParamValue.IntValue)?.value == 1L
            Switch(
                checked = on,
                onCheckedChange = { onSet(ParamValue.IntValue(if (it) 1 else 0)) },
                Modifier.padding(horizontal = Spacing.x2),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                    uncheckedThumbColor = colors.outline,
                    uncheckedTrackColor = colors.surface,
                    uncheckedBorderColor = colors.outline,
                ),
            )
        } else {
            Text(
                param.display(value, none),
                Modifier
                    .widthIn(max = 200.dp)
                    .padding(horizontal = Spacing.x2),
                color = if (changed) colors.text else colors.textSecondary,
                style = FocTheme.type.value,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
        ParamDefinition("l_current_max", ParamType.DOUBLE, ParamValue.DoubleValue(60.0), longName = "Motor Current Max", suffix = " A", maxDouble = 500.0),
        ParamDefinition("m_invert_direction", ParamType.BOOL, ParamValue.IntValue(0), longName = "Invert Motor Direction"),
        ParamDefinition("motor_type", ParamType.ENUM, ParamValue.IntValue(2), longName = "Motor Type", enumNames = listOf("BLDC", "DC", "FOC", "GPD")),
    ),
    serializeOrder = emptyList(),
    groups = listOf(
        ParamGroup("General", listOf(ParamSubgroup("General", listOf(GroupEntry.Param("motor_type"), GroupEntry.Param("m_invert_direction"))))),
        ParamGroup("Limits", listOf(ParamSubgroup("Current", listOf(GroupEntry.Separator("Motor"), GroupEntry.Param("l_current_max"))))),
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
