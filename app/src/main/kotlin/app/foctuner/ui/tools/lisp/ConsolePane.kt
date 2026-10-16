package app.foctuner.ui.tools.lisp

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import kotlinx.coroutines.launch

/** LispBM's prints and the REPL (VESC Tool's Console/REPL tab). */
@Composable
fun ConsolePane(model: LispModel, modifier: Modifier = Modifier) {
    val colors = FocTheme.colors
    val clipboard = LocalClipboard.current
    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val lines = model.console
    val following by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && following) listState.scrollToItem(lines.lastIndex)
    }

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchTarget)
                .padding(horizontal = Spacing.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlainButton(":help", { model.send(":help") })
            Box(Modifier.weight(1f))
            PlainButton(
                stringResource(R.string.lisp_copy),
                {
                    val text = lines.joinToString("\n") { line ->
                        val body = if (line.message != 0) resources.getString(line.message, *line.args.toTypedArray()) else line.text
                        if (line.kind == LineKind.Input) "› $body" else body
                    }
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("LispBM console", text))) }
                },
                enabled = lines.isNotEmpty(),
            )
            PlainButton(stringResource(R.string.lisp_clear), model::clearConsole, enabled = lines.isNotEmpty())
        }
        Hairline()
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(Spacing.x2),
            ) {
                if (lines.isEmpty()) {
                    item { Text(stringResource(R.string.lisp_console_empty), color = colors.textTertiary, style = FocTheme.type.supporting) }
                }
                items(lines, key = { it.id }) { line ->
                    val text = if (line.message != 0) stringResource(line.message, *line.args.toTypedArray()) else line.text
                    Text(
                        if (line.kind == LineKind.Input) "› $text" else text,
                        Modifier.padding(top = if (line.kind == LineKind.Input) Spacing.x1 else 0.dp),
                        color = when (line.kind) {
                            LineKind.Print -> colors.text
                            LineKind.Input -> colors.textSecondary
                            LineKind.Info -> colors.textTertiary
                            LineKind.Error -> colors.fault
                        },
                        style = FocTheme.type.code,
                    )
                }
            }
        }
        Hairline()
        ReplInput(model)
    }
}

@Composable
private fun ReplInput(model: LispModel) {
    val colors = FocTheme.colors
    var historyOpen by remember { mutableStateOf(false) }
    val send = {
        model.send(model.replInput)
        model.replInput = ""
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .heightIn(min = Spacing.row)
            .padding(start = Spacing.x2, end = Spacing.half),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("›", color = colors.textTertiary, style = FocTheme.type.value)
        BasicTextField(
            value = model.replInput,
            onValueChange = { model.replInput = it },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.x1),
            singleLine = true,
            textStyle = FocTheme.type.value.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { send() }),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (model.replInput.isEmpty()) {
                        Text(stringResource(R.string.lisp_repl_hint), color = colors.textTertiary, style = FocTheme.type.value)
                    }
                    field()
                }
            },
        )
        Box {
            IconButton(onClick = { historyOpen = true }, enabled = model.replHistory.isNotEmpty()) {
                Icon(
                    painterResource(R.drawable.ic_history),
                    contentDescription = stringResource(R.string.lisp_repl_history),
                    tint = if (model.replHistory.isNotEmpty()) colors.textSecondary else colors.hairline,
                    modifier = Modifier.size(22.dp),
                )
            }
            DropdownMenu(
                expanded = historyOpen,
                onDismissRequest = { historyOpen = false },
                containerColor = colors.surfaceHigh,
                shape = MaterialTheme.shapes.small,
            ) {
                model.replHistory.forEach { line ->
                    DropdownMenuItem(
                        text = { Text(line, color = colors.text, style = FocTheme.type.code, maxLines = 2) },
                        onClick = {
                            historyOpen = false
                            model.replInput = line
                        },
                    )
                }
            }
        }
        IconButton(onClick = send, enabled = model.replInput.isNotBlank()) {
            Icon(
                painterResource(R.drawable.ic_enter),
                contentDescription = stringResource(R.string.lisp_repl_send),
                tint = if (model.replInput.isNotBlank()) colors.accent else colors.textTertiary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
