package app.foctuner.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.config.ConfigDefinition
import app.foctuner.config.ConfigKind
import app.foctuner.config.ParamDefinition
import app.foctuner.config.ParamGroup
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** A setting found by [searchSettings] and where it sits on its config's pages. */
data class SettingHit(val kind: ConfigKind, val name: String, val longName: String, val group: Int, val subgroup: Int, val place: String)

/**
 * The settings of one config whose long name, name or [description] contain every word of [query], ignoring case:
 * long-name matches first (those starting with the first word ahead), then name matches, then description matches,
 * each in page order. A setting placed on several pages is found on the first.
 */
internal fun searchSettings(
    kind: ConfigKind,
    sections: List<ParamGroup>,
    definition: ConfigDefinition,
    query: String,
    description: (ParamDefinition) -> String,
): List<SettingHit> {
    val terms = query.lowercase().split(' ', '\t').filter { it.isNotEmpty() }
    if (terms.isEmpty()) return emptyList()
    val seen = HashSet<String>()
    val hits = mutableListOf<Pair<Int, SettingHit>>()
    sections.forEachIndexed { g, group ->
        group.subgroups.forEachIndexed { s, subgroup ->
            for (name in subgroup.paramNames) {
                val p = definition.params[name] ?: continue
                if (!seen.add(name)) continue
                val long = p.longName.lowercase()
                val id = name.lowercase()
                val rank = when {
                    terms.all { it in long } -> if (long.startsWith(terms.first())) 0 else 1
                    terms.all { it in long || it in id } -> 2
                    terms.all { it in long || it in id || it in description(p).lowercase() } -> 3
                    else -> continue
                }
                val place = if (group.subgroups.size == 1) group.name else "${group.name} › ${subgroup.name}"
                hits += rank to SettingHit(kind, name, p.longName, g, s, place)
            }
        }
    }
    return hits.sortedBy { it.first }.map { it.second }
}

/** A search field over both configs with the matches below it; picking one opens its page. */
@Composable
internal fun SettingsSearch(
    query: String,
    onQuery: (String) -> Unit,
    state: SettingsState,
    onPick: (SettingHit) -> Unit,
    onClose: () -> Unit,
) {
    val colors = FocTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val descriptions = remember(state.motor.edited?.definition, state.app.edited?.definition) { HashMap<ParamDefinition, String>() }
    val hits = remember(query, state.motor.sections, state.app.sections) {
        ConfigKind.entries.flatMap { kind ->
            val pane = state.pane(kind)
            val definition = pane.edited?.definition ?: return@flatMap emptyList()
            searchSettings(kind, pane.sections, definition, query) { p -> descriptions.getOrPut(p) { plainDescription(p.description) } }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(Modifier.padding(start = Spacing.x2, end = Spacing.half, top = Spacing.x1, bottom = Spacing.x1), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
                placeholder = { Text(stringResource(R.string.settings_search_hint), style = FocTheme.type.body) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null, tint = colors.textSecondary) },
                singleLine = true,
                textStyle = FocTheme.type.body,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.outline,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                ),
            )
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.settings_search_close), tint = colors.textSecondary)
            }
        }
        Hairline()
        if (query.isNotBlank() && hits.isEmpty()) {
            Text(stringResource(R.string.settings_search_none), Modifier.padding(Spacing.x2), color = colors.textSecondary, style = FocTheme.type.supporting)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(hits, key = { "${it.kind}/${it.name}" }) { hit ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.row)
                        .clickable(role = Role.Button) { onPick(hit) }
                        .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(hit.longName, color = colors.text, style = FocTheme.type.body)
                    val tab = stringResource(if (hit.kind == ConfigKind.MCCONF) R.string.nav_motor_cfg else R.string.nav_app_cfg)
                    Text("$tab › ${hit.place}", color = colors.textTertiary, style = FocTheme.type.caption)
                }
                Hairline(Modifier.padding(start = Spacing.x2))
            }
        }
    }
}

/** The search button at the end of the group picker. */
@Composable
internal fun SearchButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_search), stringResource(R.string.settings_search), Modifier.size(24.dp), tint = FocTheme.colors.textSecondary)
    }
}
