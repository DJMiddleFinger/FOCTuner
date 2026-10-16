package app.foctuner.ui.tools.lisp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.measureTime

/**
 * What the editor does per keystroke on a 3000-line file, on the JVM: split and tokenize (cached lines reused)
 * and build the styled text. Text layout runs on the device and isn't covered. Medians are written to
 * build/reports/lisp-editor-timing.txt.
 */
class LispEditorTimingTest {
    private val symbols = LispSymbols.parse(File("src/main/assets/lisp/symbols.txt").readText())
    private val colors = CodeColors(
        keyword = SpanStyle(color = Color.White),
        builtin = SpanStyle(color = Color.Blue),
        constant = SpanStyle(color = Color.Magenta),
        text = SpanStyle(color = Color.Yellow),
        comment = SpanStyle(color = Color.Gray),
        bracket = Color.Cyan,
        match = Color.DarkGray,
        currentMatch = Color.Cyan,
    )

    private val document: String = run {
        val examples = File("src/main/assets/lisp/examples").listFiles().orEmpty().sortedBy { it.name }.map { it.readText() }
        val lines = ArrayList<String>()
        while (lines.size < LINES) examples.forEach { lines += it.lines() }
        lines.take(LINES).joinToString("\n")
    }

    @Test
    fun aKeystrokeInAThreeThousandLineFileStaysCheap() {
        assertEquals(LINES, document.lines().size)
        val middle = document.length / 2

        val cold = median {
            val lines = LispLines.Cache(symbols).split(document)
            LispHighlighting(lines, colors).filter(AnnotatedString(document))
        }
        val cache = LispLines.Cache(symbols)
        cache.split(document)
        var edits = 0
        val keystroke = median {
            val text = document.substring(0, middle) + ('a' + edits++ % 26) + document.substring(middle)
            val lines = cache.split(text)
            LispHighlighting(lines, colors).filter(AnnotatedString(text))
        }
        val lines = cache.split(document)
        val cursorMove = median { lines.matchingBracket(middle) }

        File("build/reports").mkdirs()
        File("build/reports/lisp-editor-timing.txt").writeText(
            "lines=$LINES chars=${document.length}\ncold=$cold\nkeystroke=$keystroke\nbracket=$cursorMove\n",
        )
        assertTrue(keystroke < cold, "keystroke $keystroke should reuse cached lines (cold $cold)")
    }

    private fun median(block: () -> Unit): Duration {
        repeat(WARMUP) { block() }
        return List(RUNS) { measureTime(block) }.sorted()[RUNS / 2]
    }

    private companion object {
        const val LINES = 3000
        const val WARMUP = 20
        const val RUNS = 41
    }
}
