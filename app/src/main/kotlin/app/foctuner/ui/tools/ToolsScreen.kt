package app.foctuner.ui.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.PageHeader
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocAnimatedContent
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.theme.push

/** The tools as boxes, grouped by section; opening one shows it full size with a way back. */
@Composable
fun ToolsScreen(context: ToolContext?, modifier: Modifier = Modifier, onOpen: (Tool?) -> Unit = {}) {
    val colors = FocTheme.colors
    var open by rememberSaveable { mutableStateOf<Tool?>(null) }
    LaunchedEffect(open) { onOpen(open) }
    BackHandler(enabled = open != null) { open = null }

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
                        breadcrumb = stringResource(R.string.breadcrumb, stringResource(R.string.nav_tools), stringResource(tool.section.title)),
                    )
                    Hairline()
                    Box(Modifier.weight(1f)) { ToolContent(tool, context) }
                }
            } else {
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .background(colors.background),
                ) {
                    // Two boxes a row on phones, more on wider screens; rows take the height of their tallest box.
                    val columns = maxOf(2, (maxWidth / 176.dp).toInt())
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = Spacing.x2),
                    ) {
                        for (section in ToolSection.entries) {
                            SectionHeader(stringResource(section.title))
                            for (row in Tool.entries.filter { it.section == section }.chunked(columns)) {
                                Row(
                                    Modifier
                                        .height(IntrinsicSize.Min)
                                        .padding(horizontal = Spacing.x2, vertical = Spacing.half),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
                                ) {
                                    row.forEach { entry -> ToolTile(entry, Modifier.weight(1f).fillMaxHeight()) { open = entry } }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A tool as a box: its picture, name and what it does. */
@Composable
private fun ToolTile(tool: Tool, modifier: Modifier, onOpen: () -> Unit) {
    val colors = FocTheme.colors
    val shape = MaterialTheme.shapes.extraLarge
    Column(
        modifier
            .clip(shape)
            .background(colors.surface)
            .border(Dp.Hairline, colors.hairline, shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(Spacing.x2),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        Box(
            Modifier
                .size(Spacing.x6)
                .background(colors.surfaceHigh, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(tool.icon), contentDescription = null, tint = colors.text, modifier = Modifier.size(28.dp))
        }
        Text(stringResource(tool.title), color = colors.text, style = FocTheme.type.bodyStrong)
        Text(stringResource(tool.summary), color = colors.textTertiary, style = FocTheme.type.caption)
    }
}
