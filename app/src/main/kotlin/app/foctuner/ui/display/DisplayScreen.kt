package app.foctuner.ui.display

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.prefs.LocalUiPreferences
import app.foctuner.ui.prefs.ThemeMode
import app.foctuner.ui.prefs.UiPreferences
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.theme.ThemePreviews

/** App appearance and motion preferences: theme choice and whether screens animate between each other. */
@Composable
fun DisplayScreen(prefs: UiPreferences = LocalUiPreferences.current, modifier: Modifier = Modifier) {
    DisplayContent(
        themeMode = prefs.themeMode,
        animations = prefs.animations,
        onThemeMode = { prefs.themeMode = it },
        onAnimations = { prefs.animations = it },
        modifier = modifier,
    )
}

@Composable
private fun DisplayContent(
    themeMode: ThemeMode,
    animations: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onAnimations: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.display_appearance))
            Column(Modifier.selectableGroup()) {
                ThemeChoice(ThemeMode.SYSTEM, R.string.display_theme_system, R.string.display_theme_system_desc, themeMode, onThemeMode)
                ThemeChoice(ThemeMode.LIGHT, R.string.display_theme_light, null, themeMode, onThemeMode)
                ThemeChoice(ThemeMode.DARK, R.string.display_theme_dark, null, themeMode, onThemeMode)
            }

            SectionHeader(stringResource(R.string.display_motion))
            SwitchRow(
                title = stringResource(R.string.display_animations),
                description = stringResource(R.string.display_animations_desc),
                checked = animations,
                onCheckedChange = onAnimations,
            )
            Hairline()
            Spacer(Modifier.height(Spacing.x2))
        }
    }
}

@Composable
private fun ThemeChoice(
    mode: ThemeMode,
    titleRes: Int,
    descriptionRes: Int?,
    selectedMode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val colors = FocTheme.colors
    val selected = selectedMode == mode
    Column {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.row)
                .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(mode) })
                .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(titleRes), color = colors.text, style = FocTheme.type.body)
                descriptionRes?.let {
                    Text(stringResource(it), color = colors.textTertiary, style = FocTheme.type.caption)
                }
            }
            if (selected) {
                Text("✓", color = colors.accent, style = FocTheme.type.bodyStrong)
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = FocTheme.colors
    Column {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.row)
                .clickable(role = Role.Switch) { onCheckedChange(!checked) }
                .padding(start = Spacing.x2, top = Spacing.x1, bottom = Spacing.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = colors.text, style = FocTheme.type.body)
                Text(description, color = colors.textTertiary, style = FocTheme.type.caption)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                Modifier.padding(horizontal = Spacing.x2),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                    uncheckedThumbColor = colors.outline,
                    uncheckedTrackColor = colors.surface,
                    uncheckedBorderColor = colors.outline,
                ),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun DisplayPreview() {
    FocTheme {
        DisplayContent(
            themeMode = ThemeMode.SYSTEM,
            animations = true,
            onThemeMode = {},
            onAnimations = {},
        )
    }
}
