package app.foctuner.ui.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** The list of tools; opening one shows it full size with a way back. */
@Composable
fun ToolsScreen(context: ToolContext?, modifier: Modifier = Modifier) {
    val colors = FocTheme.colors
    var open by rememberSaveable { mutableStateOf<Tool?>(null) }
    BackHandler(enabled = open != null) { open = null }

    Box(
        modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
        ) {
            val tool = open
            if (tool != null && context != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.touchTarget)
                        .padding(start = Spacing.x1, end = Spacing.x2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlainButton("‹ " + stringResource(R.string.tools_back), { open = null })
                    Text(stringResource(tool.title), Modifier.weight(1f), color = colors.textSecondary, style = FocTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Hairline()
                Box(Modifier.weight(1f)) { ToolContent(tool, context) }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(Tool.entries) { index, entry ->
                        if (index == 0 || Tool.entries[index - 1].section != entry.section) {
                            SectionHeader(stringResource(entry.section.title))
                            Hairline()
                        } else {
                            Hairline(Modifier.padding(start = Spacing.x2))
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = Spacing.twoLineRow)
                                .clickable(role = Role.Button) { open = entry }
                                .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                        ) {
                            Text(stringResource(entry.title), color = colors.text, style = FocTheme.type.bodyStrong)
                            Text(stringResource(entry.summary), color = colors.textTertiary, style = FocTheme.type.supporting)
                        }
                    }
                }
            }
        }
    }
}
