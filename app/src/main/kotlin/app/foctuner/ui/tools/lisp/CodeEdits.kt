package app.foctuner.ui.tools.lisp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * When [next] differs from [previous] by one typed newline, indents the new line like the one above it, one
 * level deeper if that line leaves brackets open.
 */
fun autoIndent(previous: TextFieldValue, next: TextFieldValue): TextFieldValue {
    val cursor = next.selection.start
    val typedNewline = next.selection.collapsed && cursor > 0 && next.text[cursor - 1] == '\n' &&
        next.text.length == previous.text.length + 1 - previous.selection.length &&
        next.text.regionMatches(0, previous.text, 0, previous.selection.min)
    if (!typedNewline) return next
    val lineStart = next.text.lastIndexOf('\n', cursor - 2) + 1
    val line = next.text.substring(lineStart, cursor - 1)
    val indent = line.takeWhile { it == ' ' || it == '\t' }
    val open = LispLines.tokenize(line, LispSymbols.Empty).sumOf {
        when (it.kind) {
            TokenKind.Open -> 1
            TokenKind.Close -> -1
            else -> 0
        }
    }
    val insert = indent + if (open > 0) INDENT else ""
    if (insert.isEmpty()) return next
    return TextFieldValue(next.text.substring(0, cursor) + insert + next.text.substring(cursor), TextRange(cursor + insert.length))
}

/** Comments out the selected lines (or the cursor's line) with `;`, or uncomments them if all are commented. */
fun toggleComment(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val first = text.lastIndexOf('\n', value.selection.min - 1) + 1
    val endSearch = if (value.selection.collapsed) value.selection.max else maxOf(value.selection.min, value.selection.max - 1)
    val last = text.indexOf('\n', endSearch).let { if (it < 0) text.length else it }
    val lines = text.substring(first, last).split('\n')
    val commented = lines.filter { it.isNotBlank() }.all { it.trimStart().startsWith(";") }
    val changed = lines.map { line ->
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        when {
            line.isBlank() -> line
            commented -> indent + line.substring(indent.length + 1).removePrefix(" ")
            else -> "$indent; ${line.substring(indent.length)}"
        }
    }.joinToString("\n")
    val newText = text.substring(0, first) + changed + text.substring(last)
    return TextFieldValue(newText, TextRange(first, first + changed.length))
}

/** Every occurrence of [query] in [text], ignoring case. */
fun findAll(text: String, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<IntRange>()
    var i = text.indexOf(query, ignoreCase = true)
    while (i >= 0) {
        out += i until i + query.length
        i = text.indexOf(query, i + query.length, ignoreCase = true)
    }
    return out
}

/** Undo and redo for one document. Edits less than [burstMillis] apart are undone together. */
class EditHistory(private val limit: Int = 50, private val burstMillis: Long = 1000) {
    private val undo = ArrayDeque<TextFieldValue>()
    private val redo = ArrayDeque<TextFieldValue>()
    private var lastEdit: Long? = null
    private var undoSteps by mutableIntStateOf(0)
    private var redoSteps by mutableIntStateOf(0)

    val canUndo: Boolean get() = undoSteps > 0
    val canRedo: Boolean get() = redoSteps > 0

    /** Call before the text changes from [before]; [now] is a monotonic time in milliseconds. */
    fun record(before: TextFieldValue, now: Long) {
        val last = lastEdit
        if (undo.isEmpty() || last == null || now - last > burstMillis) {
            undo.addLast(before)
            if (undo.size > limit) undo.removeFirst()
        }
        lastEdit = now
        redo.clear()
        counted()
    }

    /** Starts a new step, so the next edit isn't merged into the previous one (after a replace, say). */
    fun cut() {
        lastEdit = null
    }

    fun undo(current: TextFieldValue): TextFieldValue? = move(undo, redo, current)

    fun redo(current: TextFieldValue): TextFieldValue? = move(redo, undo, current)

    fun clear() {
        undo.clear()
        redo.clear()
        counted()
    }

    private fun move(from: ArrayDeque<TextFieldValue>, to: ArrayDeque<TextFieldValue>, current: TextFieldValue): TextFieldValue? {
        val value = from.removeLastOrNull() ?: return null
        to.addLast(current)
        lastEdit = null
        counted()
        return value
    }

    private fun counted() {
        undoSteps = undo.size
        redoSteps = redo.size
    }
}

private const val INDENT = "    "
