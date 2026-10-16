package app.foctuner.ui.tools.lisp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** The find bar's state, hoisted so a keyboard shortcut can open it. */
@Stable
class FindState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var replacing by mutableStateOf(false)
    var index by mutableIntStateOf(-1)
    var reveal by mutableStateOf<Reveal?>(null)
        private set
    private var revealKey = 0

    fun show(range: IntRange) {
        reveal = Reveal(range, revealKey++)
    }
}

@Composable
fun EditorPane(
    workspace: LispWorkspace,
    find: FindState,
    busy: Boolean,
    onUpload: () -> Unit,
    onStream: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onClose: (LispDocument) -> Unit,
    modifier: Modifier = Modifier,
) {
    val document = workspace.current
    val value = document.value
    val matches = remember(value.text, find.query, find.open) { if (find.open) findAll(value.text, find.query) else emptyList() }
    val suggestions = remember(value, workspace.symbols) {
        val word = wordBefore(value)
        if (word.length >= MIN_COMPLETION) workspace.symbols.complete(word, MAX_SUGGESTIONS) else emptyList()
    }

    fun goTo(index: Int) {
        if (matches.isEmpty()) return
        val i = ((index % matches.size) + matches.size) % matches.size
        find.index = i
        val match = matches[i]
        workspace.edit(document, value.copy(selection = TextRange(match.first, match.last + 1)))
        find.show(match)
    }

    Column(modifier) {
        DocumentTabs(workspace, onClose)
        Hairline()
        if (find.open) {
            FindBar(
                find = find,
                count = matches.size,
                onNext = { goTo(find.index + 1) },
                onPrevious = { goTo(find.index - 1) },
                onReplace = {
                    val match = matches.getOrNull(find.index) ?: return@FindBar
                    val text = value.text.replaceRange(match, find.replacement)
                    workspace.replace(document, TextFieldValue(text, TextRange(match.first + find.replacement.length)))
                },
                onReplaceAll = {
                    if (matches.isEmpty()) return@FindBar
                    val text = buildString {
                        var last = 0
                        for (m in matches) {
                            append(value.text, last, m.first)
                            append(find.replacement)
                            last = m.last + 1
                        }
                        append(value.text, last, value.text.length)
                    }
                    workspace.replace(document, TextFieldValue(text, TextRange(0)))
                    find.index = -1
                },
                onClose = { find.open = false },
            )
            Hairline()
        }
        CodeEditor(
            value = value,
            onValueChange = { workspace.edit(document, it) },
            symbols = workspace.symbols,
            fontSize = workspace.fontSize.sp,
            matches = matches,
            currentMatch = find.index,
            reveal = find.reveal,
            modifier = Modifier.weight(1f),
        )
        Hairline()
        if (suggestions.isNotEmpty()) {
            LazyRow(
                Modifier
                    .fillMaxWidth()
                    .background(FocTheme.colors.surface),
                contentPadding = PaddingValues(horizontal = Spacing.x1, vertical = Spacing.half),
                horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
            ) {
                items(suggestions) { word ->
                    Chip(word) { workspace.edit(document, complete(document.value, word)) }
                }
            }
            Hairline()
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchTarget)
                .padding(horizontal = Spacing.x1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PlainButton(stringResource(R.string.lisp_upload), onUpload, enabled = !busy)
            PlainButton(stringResource(R.string.lisp_stream), onStream, enabled = !busy)
            PlainButton(stringResource(R.string.lisp_run), onRun, enabled = !busy)
            PlainButton(stringResource(R.string.lisp_stop), onStop, enabled = !busy)
        }
    }
}

@Composable
private fun DocumentTabs(workspace: LispWorkspace, onClose: (LispDocument) -> Unit) {
    val colors = FocTheme.colors
    val untitled = stringResource(R.string.lisp_untitled)
    LazyRow(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(workspace.documents, key = { it.id }) { document ->
            val selected = document === workspace.current
            Row(
                Modifier
                    .heightIn(min = Spacing.touchTarget)
                    .semantics { this.selected = selected }
                    .clickable(role = Role.Tab) { workspace.select(document) }
                    .padding(start = Spacing.x2, end = if (selected) 0.dp else Spacing.x2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        (document.name ?: untitled) + if (document.dirty) " •" else "",
                        Modifier.padding(vertical = Spacing.x1),
                        color = if (selected) colors.text else colors.textSecondary,
                        style = FocTheme.type.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        Modifier
                            .height(2.dp)
                            .fillMaxWidth()
                            .background(if (selected) colors.accent else colors.background),
                    )
                }
                if (selected) PlainButton("×", { onClose(document) })
            }
        }
        item {
            PlainButton("+ " + stringResource(R.string.lisp_new_tab), { workspace.newDocument() }, Modifier.padding(horizontal = Spacing.x1))
        }
    }
}

@Composable
private fun FindBar(
    find: FindState,
    count: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = FocTheme.colors
    Column(Modifier.background(colors.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchTarget)
                .padding(start = Spacing.x2, end = Spacing.half),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallField(find.query, { find.query = it; find.index = -1 }, stringResource(R.string.lisp_find_hint), Modifier.weight(1f), onDone = onNext)
            Text(
                if (count == 0) stringResource(R.string.lisp_find_none) else stringResource(R.string.lisp_find_count, find.index + 1, count),
                Modifier.padding(horizontal = Spacing.x1),
                color = colors.textTertiary,
                style = FocTheme.type.caption,
            )
            PlainButton("‹", onPrevious, enabled = count > 0)
            PlainButton("›", onNext, enabled = count > 0)
            PlainButton(stringResource(if (find.replacing) R.string.lisp_find_less else R.string.lisp_find_replace), { find.replacing = !find.replacing })
            PlainButton("×", onClose)
        }
        if (find.replacing) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.touchTarget)
                    .padding(start = Spacing.x2, end = Spacing.half),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallField(find.replacement, { find.replacement = it }, stringResource(R.string.lisp_replace_hint), Modifier.weight(1f), onDone = onReplace)
                PlainButton(stringResource(R.string.lisp_replace), onReplace, enabled = find.index in 0 until count)
                PlainButton(stringResource(R.string.lisp_replace_all), onReplaceAll, enabled = count > 0)
            }
        }
    }
}

@Composable
fun SmallField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    val colors = FocTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        singleLine = true,
        textStyle = FocTheme.type.code.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, color = colors.textTertiary, style = FocTheme.type.code)
                field()
            }
        },
    )
}

@Composable
fun Chip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = FocTheme.colors.background,
        contentColor = FocTheme.colors.text,
        border = BorderStroke(1.dp, FocTheme.colors.outline),
    ) {
        Text(
            label,
            Modifier
                .heightIn(min = 32.dp)
                .padding(horizontal = Spacing.x2 - Spacing.half, vertical = Spacing.half + 2.dp),
            style = FocTheme.type.code,
        )
    }
}

private const val MIN_COMPLETION = 2
private const val MAX_SUGGESTIONS = 12
