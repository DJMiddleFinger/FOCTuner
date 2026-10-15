package app.foctuner.ui.terminal

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.foctuner.ui.theme.StressPreview
import app.foctuner.ui.theme.ThemePreviews
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(
    state: TerminalState,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocTheme.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.lastIndex)
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.row)
                .padding(start = Spacing.x2, end = Spacing.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.waiting) stringResource(R.string.terminal_waiting) else "",
                Modifier.weight(1f),
                color = colors.accent,
                style = FocTheme.type.caption,
            )
            PlainButton(
                stringResource(R.string.terminal_copy),
                onClick = {
                    val text = state.lines.joinToString("\n") { it.display() }
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("FOCTuner terminal", text))) }
                },
                enabled = state.lines.isNotEmpty(),
            )
            PlainButton(stringResource(R.string.terminal_clear), onClear, enabled = state.lines.isNotEmpty())
        }
        Hairline()
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(Spacing.x2),
            ) {
                if (state.lines.isEmpty()) {
                    item {
                        Text(stringResource(R.string.terminal_empty), color = colors.textTertiary, style = FocTheme.type.supporting)
                    }
                }
                items(state.lines, key = { it.id }) { line ->
                    Text(
                        line.display(),
                        color = when (line.kind) {
                            LineKind.Command -> colors.textSecondary
                            LineKind.Output -> colors.text
                            LineKind.Error -> colors.fault
                        },
                        style = FocTheme.type.code,
                        modifier = Modifier.padding(top = if (line.kind == LineKind.Command) Spacing.x1 else 0.dp),
                    )
                }
            }
        }
        Hairline()
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Spacing.x2),
            horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
        ) {
            items(state.quickCommands) { command -> CommandChip(command) { onSend(command) } }
        }
        InputRow(state, onInputChange, onSend)
    }
}

private fun TerminalLine.display() = if (kind == LineKind.Command) "› $text" else text

@Composable
private fun CommandChip(command: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = FocTheme.colors.background,
        contentColor = FocTheme.colors.text,
        border = BorderStroke(1.dp, FocTheme.colors.outline),
    ) {
        Text(
            command,
            Modifier.padding(horizontal = Spacing.x2 - Spacing.half, vertical = Spacing.half + 2.dp),
            style = FocTheme.type.code,
        )
    }
}

@Composable
private fun InputRow(state: TerminalState, onInputChange: (String) -> Unit, onSend: (String) -> Unit) {
    val colors = FocTheme.colors
    var historyOpen by remember { mutableStateOf(false) }
    val send = {
        val command = state.input.trim()
        if (command.isNotEmpty()) onSend(command)
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
            value = state.input,
            onValueChange = onInputChange,
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
                    if (state.input.isEmpty()) {
                        Text(stringResource(R.string.terminal_input_hint), color = colors.textTertiary, style = FocTheme.type.value)
                    }
                    field()
                }
            },
        )
        Box {
            IconButton(onClick = { historyOpen = true }, enabled = state.history.isNotEmpty()) {
                Icon(
                    painterResource(R.drawable.ic_history),
                    contentDescription = stringResource(R.string.terminal_history),
                    tint = if (state.history.isNotEmpty()) colors.textSecondary else colors.hairline,
                    modifier = Modifier.size(22.dp),
                )
            }
            DropdownMenu(
                expanded = historyOpen,
                onDismissRequest = { historyOpen = false },
                containerColor = colors.surfaceHigh,
                shape = MaterialTheme.shapes.small,
            ) {
                state.history.forEach { command ->
                    DropdownMenuItem(
                        text = { Text(command, color = colors.text, style = FocTheme.type.value) },
                        onClick = {
                            historyOpen = false
                            onInputChange(command)
                        },
                    )
                }
            }
        }
        IconButton(onClick = send, enabled = state.input.isNotBlank()) {
            Icon(
                painterResource(R.drawable.ic_enter),
                contentDescription = stringResource(R.string.terminal_send),
                tint = if (state.input.isNotBlank()) colors.accent else colors.textTertiary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private val previewLines = listOf(
    TerminalLine(1, "faults", LineKind.Command),
    TerminalLine(2, "No faults registered since startup", LineKind.Output),
    TerminalLine(3, "hw_status", LineKind.Command),
    TerminalLine(4, "Firmware: 6.05\nHardware: 60_MK6\nUUID: 3A 00 1F 00 0E 51 34 37 33 36 39 31", LineKind.Output),
    TerminalLine(5, "Phase filters : Enabled\nPermanent NRF found: No", LineKind.Output),
    TerminalLine(6, "foc_state", LineKind.Command),
    TerminalLine(7, "No reply within 2 s", LineKind.Error),
)

@ThemePreviews
@Composable
private fun TerminalPreview() {
    FocTheme {
        TerminalScreen(
            TerminalState(lines = previewLines, input = "can_d", history = listOf("hw_status", "faults"), waiting = true),
            {}, {}, {},
        )
    }
}

@StressPreview
@Composable
private fun TerminalEmptyPreview() {
    FocTheme { TerminalScreen(TerminalState(), {}, {}, {}) }
}
