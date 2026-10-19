package app.foctuner.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.prefs.LocalUiPreferences
import app.foctuner.ui.prefs.UiPreferences
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.theme.ThemePreviews

/**
 * Every motor and app setting, in VESC Tool's sections: a list of groups and their subgroups, and a page of
 * settings per subgroup. Edits stay pending (marked on their rows) until Write stores them on the controller.
 */
@Composable
fun SettingsScreen(state: SettingsState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val colors = FocTheme.colors
    var kind by rememberSaveable { mutableStateOf(ConfigKind.MCCONF) }
    var group by rememberSaveable { mutableIntStateOf(-1) }
    var subgroup by rememberSaveable { mutableIntStateOf(-1) }
    var editing by remember { mutableStateOf<String?>(null) }
    val pane = state.pane(kind)
    val config = pane.edited
    val opened = pane.sections.getOrNull(group)?.subgroups?.getOrNull(subgroup)

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
            pane.error?.let { error ->
                Notice(
                    title = error,
                    tone = NoticeTone.Warning,
                    action = stringResource(R.string.settings_read_again),
                    onAction = { actions.read(kind) },
                )
            }
            Box(Modifier.weight(1f)) {
                if (config == null) {
                    Text(
                        stringResource(if (pane.work != null) R.string.settings_reading else R.string.settings_not_read),
                        Modifier.padding(Spacing.x2),
                        color = colors.textSecondary,
                        style = FocTheme.type.supporting,
                    )
                } else {
                    val onBack = {
                        group = -1
                        subgroup = -1
                    }
                    // The open subgroup, paired with its section name for the breadcrumb; null shows the list.
                    val openPage = opened?.let { pane.sections[group].name to it }
                    val renderList: @Composable () -> Unit = {
                        SectionList(pane.sections, config.definition, pane.changed) { g, s ->
                            group = g
                            subgroup = s
                        }
                    }
                    val renderPage: @Composable (Pair<String, ParamSubgroup>) -> Unit = { page ->
                        SubgroupPage(
                            section = page.first,
                            subgroupName = page.second.name,
                            subgroup = page.second,
                            config = config,
                            changed = pane.changed,
                            onBack = onBack,
                            onOpen = { editing = it },
                            onSet = { name, value -> actions.edit(kind, name, value) },
                        )
                    }
                    if (LocalUiPreferences.current.animations) {
                        AnimatedContent(
                            targetState = openPage,
                            transitionSpec = {
                                val dir = if (targetState != null) 1 else -1
                                (slideInHorizontally(tween(240)) { full -> dir * full } + fadeIn(tween(160)))
                                    .togetherWith(slideOutHorizontally(tween(240)) { full -> -dir * full } + fadeOut(tween(120)))
                            },
                            label = "settings-page",
                        ) { page ->
                            if (page != null) renderPage(page) else renderList()
                        }
                    } else {
                        if (openPage != null) renderPage(openPage) else renderList()
                    }
                }
            }
        }
        ActionBar(pane, kind, actions)
    }

    val param = editing?.let { config?.definition?.params?.get(it) }
    if (param != null && config != null) {
        ParamDialog(
            param = param,
            definition = config.definition,
            value = config[param.name],
            stored = pane.stored?.get(param.name),
            onSet = {
                actions.edit(kind, param.name, it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun KindTabs(selected: ConfigKind, onSelect: (ConfigKind) -> Unit) {
    val colors = FocTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(Spacing.touchTarget)
            .selectableGroup(),
    ) {
        for (kind in ConfigKind.entries) {
            val isSelected = kind == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(kind) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (kind == ConfigKind.MCCONF) R.string.settings_motor else R.string.settings_app),
                    color = if (isSelected) colors.text else colors.textTertiary,
                    style = FocTheme.type.label,
                )
                if (isSelected) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .width(48.dp)
                            .height(2.dp)
                            .background(colors.accent),
                    )
                }
            }
        }
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
    LazyColumn(Modifier.fillMaxSize()) {
        sections.forEachIndexed { g, group ->
            item(key = "g$g") { SectionHeader(group.name) }
            itemsIndexed(group.subgroups, key = { s, _ -> "g$g-s$s" }) { s, subgroup ->
                val names = subgroup.paramNames.filter { it in definition.params }
                val pending = names.count { it in changed }
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
    section: String,
    subgroupName: String,
    subgroup: ParamSubgroup,
    config: Config,
    changed: Set<String>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onSet: (String, ParamValue) -> Unit,
) {
    val colors = FocTheme.colors
    val none = stringResource(R.string.settings_none)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchTarget)
                .padding(start = Spacing.x1, end = Spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlainButton("‹ " + stringResource(R.string.settings_back), onBack)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_in_section, section),
                    color = colors.accent,
                    style = FocTheme.type.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subgroupName,
                    color = colors.text,
                    style = FocTheme.type.bodyStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Hairline()
        LazyColumn(Modifier.fillMaxSize()) {
            items(subgroup.entries) { entry ->
                when (entry) {
                    is GroupEntry.Separator -> SectionHeader(entry.label)
                    is GroupEntry.Param -> config.definition.params[entry.name]?.let { param ->
                        Column {
                            ParamRow(param, config[param.name], param.name in changed, none, { onOpen(param.name) }) { onSet(param.name, it) }
                            Hairline(Modifier.padding(start = Spacing.x2))
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
    changed: Boolean,
    none: String,
    onOpen: () -> Unit,
    onSet: (ParamValue) -> Unit,
) {
    val colors = FocTheme.colors
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
                .background(if (changed) colors.warning else Color.Transparent),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = Spacing.x2 - 3.dp, top = Spacing.x1, bottom = Spacing.x1),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(param.longName, color = colors.text, style = FocTheme.type.body)
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
                color = colors.textSecondary,
                style = FocTheme.type.value,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ActionBar(pane: ConfigPaneState, kind: ConfigKind, actions: SettingsActions) {
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
                { actions.write(kind) },
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
    val prefs = UiPreferences.from(LocalContext.current)
    FocTheme {
        CompositionLocalProvider(LocalUiPreferences provides prefs) {
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
}
