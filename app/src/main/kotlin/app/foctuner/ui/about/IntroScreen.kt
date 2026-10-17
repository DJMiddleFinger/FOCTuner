package app.foctuner.ui.about

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.theme.StressPreview
import app.foctuner.ui.theme.ThemePreviews

/**
 * Shown before anything else until accepted, and again whenever [app.foctuner.prefs.INTRO_VERSION] is raised:
 * what the app does, the safety basics, no warranty, not affiliated (VESC Tool's startup wizard, own words).
 */
@Composable
fun IntroScreen(onAccept: () -> Unit, onQuit: () -> Unit) {
    val colors = FocTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp)) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.intro_title),
                    Modifier.padding(start = Spacing.x2, end = Spacing.x2, top = Spacing.x3, bottom = Spacing.x1),
                    color = colors.text,
                    style = FocTheme.type.title,
                )
                Part(R.string.intro_section_purpose, R.string.intro_purpose_body)
                Part(R.string.about_section_safety, R.string.intro_safety_body)
                Part(R.string.about_section_warranty, R.string.intro_warranty_body)
                Part(R.string.intro_section_affiliation, R.string.about_not_affiliated)
            }
            Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                horizontalArrangement = Arrangement.spacedBy(Spacing.x1, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlainButton(stringResource(R.string.intro_quit), onQuit)
                OutlineButton(stringResource(R.string.intro_accept), onAccept)
            }
        }
    }
}

@Composable
private fun Part(@StringRes title: Int, @StringRes body: Int) {
    SectionHeader(stringResource(title))
    Text(
        stringResource(body),
        Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        color = FocTheme.colors.textSecondary,
        style = FocTheme.type.supporting,
    )
}

@ThemePreviews
@Composable
private fun IntroPreview() {
    FocTheme { IntroScreen({}, {}) }
}

@StressPreview
@Composable
private fun IntroStressPreview() {
    FocTheme { IntroScreen({}, {}) }
}
