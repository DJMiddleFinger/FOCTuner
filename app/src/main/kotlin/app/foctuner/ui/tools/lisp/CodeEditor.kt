package app.foctuner.ui.tools.lisp

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** One range of the text the editor should scroll to; a new [key] scrolls again to the same range. */
data class Reveal(val range: IntRange, val key: Int)

/**
 * A code field with LispBM highlighting, line numbers and bracket matching. Lines don't wrap; long lines
 * scroll sideways. Only the edited lines are tokenized again, and cursor moves don't restyle the text:
 * the matching bracket and find matches are drawn behind it.
 */
@Composable
fun CodeEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    symbols: LispSymbols,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    matches: List<IntRange> = emptyList(),
    currentMatch: Int = -1,
    reveal: Reveal? = null,
    readOnly: Boolean = false,
) {
    val colors = FocTheme.colors
    val codeColors = rememberCodeColors()
    val style = FocTheme.type.code.copy(fontSize = fontSize, lineHeight = (fontSize.value * LINE_HEIGHT).sp)
    val cache = remember(symbols) { LispLines.Cache(symbols) }
    val lines = remember(value.text, cache) { cache.split(value.text) }
    val highlighting = remember(lines, codeColors) { LispHighlighting(lines, codeColors) }
    val bracket = remember(lines, value.selection) {
        if (value.selection.collapsed) lines.matchingBracket(value.selection.start) else null
    }
    val currentLine = remember(lines, value.selection.start) { lines.lineOf(value.selection.start.coerceAtMost(value.text.length)) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var textViewport by remember { mutableStateOf(IntSize.Zero) }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val density = LocalDensity.current

    LaunchedEffect(reveal) {
        val target = reveal ?: return@LaunchedEffect
        val result = layout ?: return@LaunchedEffect
        val length = result.layoutInput.text.length
        if (length == 0 || target.range.first > length) return@LaunchedEffect
        val box = result.getBoundingBox(target.range.first.coerceAtMost(length - 1))
        val padding = with(density) { Spacing.x1.toPx() }
        scrollToShow(vertical, box.top + padding, box.bottom + padding, textViewport.height.toFloat())
        scrollToShow(horizontal, box.left + padding, box.right + padding, textViewport.width.toFloat())
    }

    BoxWithConstraints(
        modifier
            .background(colors.background)
            .onSizeChanged { textViewport = IntSize(textViewport.width, it.height) },
    ) {
        val viewportHeight = maxHeight
        Row(
            Modifier
                .fillMaxSize()
                .verticalScroll(vertical),
        ) {
            LineNumbers(lines.count, currentLine, style, colors.textTertiary, colors.text)
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .onSizeChanged { textViewport = IntSize(it.width, textViewport.height) },
            ) {
                val viewportWidth = maxWidth
                Row(Modifier.horizontalScroll(horizontal)) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        readOnly = readOnly,
                        textStyle = style.copy(color = colors.text),
                        cursorBrush = SolidColor(colors.accent),
                        visualTransformation = highlighting,
                        onTextLayout = { layout = it },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                        ),
                        modifier = Modifier
                            .widthIn(min = viewportWidth)
                            .heightIn(min = viewportHeight)
                            .drawBehindText(layout, bracket, matches, currentMatch, codeColors)
                            .padding(Spacing.x1),
                    )
                }
            }
        }
    }
}

@Composable
private fun LineNumbers(count: Int, current: Int, style: TextStyle, color: Color, currentColor: Color) {
    val numbers = remember(count) { (1..count).joinToString("\n") }
    val text = remember(numbers, current) {
        val start = if (current == 0) 0 else numbers.indexOfNth('\n', current) + 1
        val end = numbers.indexOf('\n', start).let { if (it < 0) numbers.length else it }
        AnnotatedString(numbers, listOf(AnnotatedString.Range(SpanStyle(color = currentColor), start, end)))
    }
    Text(
        text,
        Modifier
            .background(FocTheme.colors.surface)
            .padding(Spacing.x1),
        color = color,
        style = style,
        textAlign = TextAlign.End,
        softWrap = false,
    )
}

/** Find matches and the matching bracket pair, drawn under the text so they don't restyle it. */
private fun Modifier.drawBehindText(
    layout: TextLayoutResult?,
    bracket: Pair<Int, Int>?,
    matches: List<IntRange>,
    currentMatch: Int,
    colors: CodeColors,
): Modifier = drawBehind {
    val result = layout ?: return@drawBehind
    val length = result.layoutInput.text.length
    val origin = Offset(Spacing.x1.toPx(), Spacing.x1.toPx())
    val corner = CornerRadius(2.dp.toPx())
    for ((index, range) in matches.withIndex()) {
        if (index >= MAX_DRAWN_MATCHES) break
        if (range.last >= length) continue
        val box = rangeBox(result, range)
        if (index == currentMatch) {
            drawRoundRect(colors.currentMatch, box.topLeft + origin, box.size, corner, style = Stroke(1.5.dp.toPx()))
        } else {
            drawRoundRect(colors.match, box.topLeft + origin, box.size, corner)
        }
    }
    bracket?.toList()?.forEach { offset ->
        if (offset < length) {
            val box = result.getBoundingBox(offset)
            drawRoundRect(colors.bracket, box.topLeft + origin, box.size, corner, style = Stroke(1.dp.toPx()))
        }
    }
}

/** The box around [range], or around its first character if it spans lines. */
private fun rangeBox(result: TextLayoutResult, range: IntRange): Rect {
    val first = result.getBoundingBox(range.first)
    val last = result.getBoundingBox(range.last)
    if (first.top != last.top) return first
    return Rect(first.topLeft, Size(last.right - first.left, first.height))
}

private suspend fun scrollToShow(state: ScrollState, start: Float, end: Float, viewport: Float) {
    val margin = viewport / 4
    val target = when {
        start < state.value -> start - margin
        end > state.value + viewport -> end - viewport + margin
        else -> return
    }
    state.animateScrollTo(target.toInt().coerceIn(0, state.maxValue))
}

private fun String.indexOfNth(char: Char, n: Int): Int {
    var index = -1
    repeat(n) { index = indexOf(char, index + 1) }
    return index
}

/** Line height as a multiple of the font size (the code style is 13/20). */
private const val LINE_HEIGHT = 20f / 13f

private const val MAX_DRAWN_MATCHES = 1000
