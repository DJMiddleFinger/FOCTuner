package app.foctuner.ui.prefs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.foctuner.R
import app.foctuner.prefs.AppPreferences
import app.foctuner.prefs.Units
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.theme.ThemePreviews
import app.foctuner.ui.tools.parts.ChoiceRow

/** VESC Tool's Preferences (desktop dialog and mobile Settings), for the settings that mean something here. */
@Composable
fun PreferencesRoute(modifier: Modifier = Modifier) {
    val model: PreferencesViewModel = viewModel()
    val prefs by model.state.collectAsStateWithLifecycle()
    PreferencesContent(prefs ?: AppPreferences(), model::update, model::restoreDefaults, modifier)
}

@Composable
private fun PreferencesContent(
    prefs: AppPreferences,
    onChange: ((AppPreferences) -> AppPreferences) -> Unit,
    onRestoreDefaults: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(FocTheme.colors.background)
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader(stringResource(R.string.prefs_section_units))
        Choices(
            stringResource(R.string.prefs_units_summary),
            Units.entries,
            prefs.units,
            { stringResource(if (it == Units.Metric) R.string.prefs_units_metric else R.string.prefs_units_imperial) },
        ) { units -> onChange { it.copy(units = units) } }

        SectionHeader(stringResource(R.string.prefs_section_stop))
        Choices(
            stringResource(R.string.prefs_stop_summary),
            AppPreferences.STOP_CHOICES,
            prefs.stopSeconds,
            { if (it == 0) stringResource(R.string.prefs_stop_off) else stringResource(R.string.prefs_seconds, it) },
        ) { seconds -> onChange { it.copy(stopSeconds = seconds) } }

        SectionHeader(stringResource(R.string.prefs_section_connection))
        Hairline()
        Toggle(stringResource(R.string.prefs_keep_screen_on), stringResource(R.string.prefs_keep_screen_on_summary), prefs.keepScreenOn) { on ->
            onChange { it.copy(keepScreenOn = on) }
        }
        Toggle(stringResource(R.string.prefs_scan_can), stringResource(R.string.prefs_scan_can_summary), prefs.scanCanOnConnect) { on ->
            onChange { it.copy(scanCanOnConnect = on) }
        }
        Toggle(stringResource(R.string.prefs_reconnect_can), stringResource(R.string.prefs_reconnect_can_summary), prefs.reconnectLastCan) { on ->
            onChange { it.copy(reconnectLastCan = on) }
        }
        Text(
            stringResource(R.string.prefs_realtime_rate),
            Modifier.padding(start = Spacing.x2, end = Spacing.x2, top = Spacing.x2),
            color = FocTheme.colors.text,
            style = FocTheme.type.body,
        )
        Choices(
            stringResource(R.string.prefs_realtime_rate_summary),
            AppPreferences.REALTIME_CHOICES,
            prefs.realtimeHz,
            { stringResource(R.string.prefs_hertz, it) },
        ) { hz -> onChange { it.copy(realtimeHz = hz) } }

        SectionHeader(stringResource(R.string.prefs_section_scripting))
        Choices(
            stringResource(R.string.prefs_repl_lines_summary),
            AppPreferences.REPL_CHOICES,
            prefs.replMaxLines,
            { stringResource(R.string.prefs_lines, it) },
        ) { lines -> onChange { it.copy(replMaxLines = lines) } }

        OutlineButton(stringResource(R.string.prefs_restore_defaults), onRestoreDefaults, Modifier.padding(Spacing.x2))
    }
}

@Composable
private fun <T> Choices(summary: String, choices: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Text(
        summary,
        Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.half),
        color = FocTheme.colors.textSecondary,
        style = FocTheme.type.supporting,
    )
    ChoiceRow(choices, selected, label, onSelect)
    Hairline()
}

@Composable
private fun Toggle(title: String, summary: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val colors = FocTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.twoLineRow)
            .clickable(role = Role.Switch) { onChecked(!checked) }
            .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Spacing.x2)) {
            Text(title, color = colors.text, style = FocTheme.type.body)
            Text(summary, color = colors.textTertiary, style = FocTheme.type.supporting)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.outline,
                uncheckedTrackColor = colors.surface,
                uncheckedBorderColor = colors.outline,
            ),
        )
    }
    Hairline(Modifier.padding(start = Spacing.x2))
}

@ThemePreviews
@Composable
private fun PreferencesPreview() {
    FocTheme { PreferencesContent(AppPreferences(units = Units.Metric), {}, {}) }
}
