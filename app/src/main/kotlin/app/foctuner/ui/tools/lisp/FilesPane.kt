package app.foctuner.ui.tools.lisp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** Recent files and VESC Tool's bundled examples, with one filter for both (VESC Tool's Recent and Examples tabs). */
@Composable
fun FilesPane(
    workspace: LispWorkspace,
    onOpenFile: () -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
    onOpenExample: (String) -> Unit,
    onClearRecent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocTheme.colors
    var filter by rememberSaveable { mutableStateOf("") }
    val recent = workspace.recent.filter { it.name.contains(filter, ignoreCase = true) }
    val examples = workspace.examples.filter { it.contains(filter, ignoreCase = true) }

    LazyColumn(modifier) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(Spacing.x2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                OutlineButton(stringResource(R.string.lisp_open_file), onOpenFile)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .heightIn(min = Spacing.touchTarget)
                    .padding(start = Spacing.x2, end = Spacing.half),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallField(filter, { filter = it }, stringResource(R.string.lisp_filter_hint), Modifier.weight(1f))
                if (filter.isNotEmpty()) PlainButton("×", { filter = "" })
            }
            Hairline()
            SectionHeader(stringResource(R.string.lisp_recent)) {
                PlainButton(stringResource(R.string.lisp_clear), onClearRecent, enabled = workspace.recent.isNotEmpty())
            }
            if (recent.isEmpty()) {
                Text(
                    stringResource(if (workspace.recent.isEmpty()) R.string.lisp_recent_empty else R.string.lisp_filter_nothing),
                    Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                    color = colors.textTertiary,
                    style = FocTheme.type.supporting,
                )
            }
        }
        items(recent, key = { "recent:" + it.uri }) { file ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.twoLineRow)
                    .clickable(role = Role.Button) { onOpenRecent(file) }
                    .padding(start = Spacing.x2, end = Spacing.half),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(file.name, color = colors.text, style = FocTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(file.uri.authority.orEmpty(), color = colors.textTertiary, style = FocTheme.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PlainButton(stringResource(R.string.lisp_remove), { workspace.forget(file) })
            }
            Hairline(Modifier.padding(start = Spacing.x2))
        }
        item {
            SectionHeader(stringResource(R.string.lisp_examples, workspace.examples.size))
            Text(
                stringResource(R.string.lisp_examples_note),
                Modifier.padding(horizontal = Spacing.x2),
                color = colors.textTertiary,
                style = FocTheme.type.caption,
            )
            if (examples.isEmpty() && workspace.examples.isNotEmpty()) {
                Text(
                    stringResource(R.string.lisp_filter_nothing),
                    Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                    color = colors.textTertiary,
                    style = FocTheme.type.supporting,
                )
            }
        }
        items(examples, key = { "example:$it" }) { name ->
            Text(
                name,
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.touchTarget)
                    .clickable(role = Role.Button) { onOpenExample(name) }
                    .padding(horizontal = Spacing.x2, vertical = Spacing.x1 + Spacing.half),
                color = colors.text,
                style = FocTheme.type.code,
            )
            Hairline(Modifier.padding(start = Spacing.x2))
        }
    }
}
