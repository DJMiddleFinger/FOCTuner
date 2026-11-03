package app.foctuner.ui.about

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.foctuner.BuildConfig
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.theme.ThemePreviews

/** Software bundled with the app, each with the licence text it is shipped under. */
private enum class ThirdParty(@param:StringRes val title: Int, @param:StringRes val detail: Int, val license: String) {
    VescTool(R.string.about_vesc_tool, R.string.about_vesc_tool_detail, "GPL-3.0.txt"),
    LispExamples(R.string.about_lisp_examples, R.string.about_lisp_examples_detail, "VESC-Tool-LispBM.txt"),
    Plex(R.string.about_plex, R.string.about_plex_detail, "IBM-Plex-OFL.txt"),
    AndroidX(R.string.about_androidx, R.string.about_androidx_detail, "Apache-2.0.txt"),
    Osmdroid(R.string.about_osmdroid, R.string.about_osmdroid_detail, "Apache-2.0.txt"),
    Michroma(R.string.about_michroma, R.string.about_michroma_detail, "Michroma-OFL.txt"),
    MaterialIcons(R.string.about_material_icons, R.string.about_material_icons_detail, "Apache-2.0.txt"),
}

/**
 * What VESC Tool's README asks of a rewrite: version and commit, licence and source, credit to VESC Tool with
 * the upstream commit, the not-affiliated note, no warranty, safety, and the licences of what is bundled.
 */
@Composable
fun AboutRoute(modifier: Modifier = Modifier) {
    var license by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = license
    if (shown != null) {
        BackHandler { license = null }
        LicenseText(shown, modifier)
    } else {
        AboutContent(onLicense = { license = it }, modifier)
    }
}

@Composable
private fun AboutContent(onLicense: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = FocTheme.colors
    val uri = LocalUriHandler.current
    val source = stringResource(R.string.about_source_url)
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(Modifier.padding(Spacing.x2), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.app_name), color = colors.text, style = FocTheme.type.title)
            Text(
                if (BuildConfig.GIT_COMMIT.isEmpty()) BuildConfig.VERSION_NAME
                else stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.GIT_COMMIT),
                color = colors.textSecondary,
                style = FocTheme.type.code,
            )
            Text(stringResource(R.string.about_tagline), color = colors.textSecondary, style = FocTheme.type.supporting)
        }
        Hairline()
        Paragraph(R.string.about_copyright)
        LinkRow(stringResource(R.string.about_license_gpl), stringResource(R.string.about_license_gpl_detail)) { onLicense("GPL-3.0.txt") }
        LinkRow(stringResource(R.string.about_source), source) { uri.openUri(source) }

        SectionHeader(stringResource(R.string.about_section_upstream))
        Paragraph(R.string.about_based_on)
        Paragraph(R.string.about_not_affiliated)

        SectionHeader(stringResource(R.string.about_section_warranty))
        Paragraph(R.string.about_no_warranty)

        SectionHeader(stringResource(R.string.about_section_safety))
        Paragraph(R.string.intro_safety_body)

        SectionHeader(stringResource(R.string.about_section_third_party))
        Hairline()
        ThirdParty.entries.forEach { part ->
            LinkRow(stringResource(part.title), stringResource(part.detail)) { onLicense(part.license) }
        }
    }
}

@Composable
private fun Paragraph(@StringRes text: Int) {
    Text(
        stringResource(text),
        Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        color = FocTheme.colors.textSecondary,
        style = FocTheme.type.supporting,
    )
}

@Composable
private fun LinkRow(title: String, detail: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.twoLineRow)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(title, color = FocTheme.colors.text, style = FocTheme.type.bodyStrong)
        Text(detail, color = FocTheme.colors.textTertiary, style = FocTheme.type.supporting)
    }
    Hairline(Modifier.padding(start = Spacing.x2))
}

@ThemePreviews
@Composable
private fun AboutPreview() {
    FocTheme { AboutContent({}) }
}
