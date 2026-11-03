package app.foctuner.ui.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.PageHeader
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.Tile
import app.foctuner.ui.components.TileGrid
import app.foctuner.ui.theme.FocAnimatedContent
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.theme.push

/** The Start page: the tools as boxes in VESC Tool's groups; opening one shows it full size with a way back. */
@Composable
fun ToolsScreen(context: ToolContext?, modifier: Modifier = Modifier, onOpen: (Tool?) -> Unit = {}) {
    val colors = FocTheme.colors
    var open by rememberSaveable { mutableStateOf<Tool?>(null) }
    LaunchedEffect(open) { onOpen(open) }
    BackHandler(enabled = open != null) { open = null }
    // Outside the page transition, so coming back from a tool returns to the same place in the list.
    val listScroll = rememberScrollState()

    Box(
        modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        FocAnimatedContent(
            target = open.takeIf { context != null },
            label = "tool",
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            transition = { push(forward = targetState != null) },
        ) { tool ->
            if (tool != null && context != null) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(colors.background),
                ) {
                    PageHeader(
                        stringResource(tool.title),
                        onBack = { open = null },
                        breadcrumb = stringResource(R.string.breadcrumb, stringResource(R.string.nav_start), stringResource(tool.section.title)),
                    )
                    Hairline()
                    Box(Modifier.weight(1f)) { ToolContent(tool, context) { open = it } }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(colors.background)
                        .verticalScroll(listScroll)
                        .padding(bottom = Spacing.x2),
                ) {
                    for (section in ToolSection.entries - ToolSection.Tab) {
                        SectionHeader(stringResource(section.title))
                        TileGrid(Tool.entries.filter { it.section == section }, minTileWidth = 104.dp) { entry, tileModifier ->
                            Tile(entry.icon, stringResource(entry.title), { open = entry }, tileModifier, compact = true)
                        }
                    }
                }
            }
        }
    }
}
