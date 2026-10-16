package app.foctuner.ui.tools.lisp

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LispSyntaxTest {
    private val symbols = LispSymbols.parse(File("src/main/assets/lisp/symbols.txt").readText())

    private fun kinds(line: String) = LispLines.tokenize(line, symbols).map { line.substring(it.start, it.end) to it.kind }

    @Test
    fun tokenizesLikeVescToolHighlights() {
        assertEquals(
            listOf(
                "(" to TokenKind.Open, "defun" to TokenKind.Keyword, "(" to TokenKind.Open, ")" to TokenKind.Close,
                "(" to TokenKind.Open, "set-current" to TokenKind.Builtin, "1.5f32" to TokenKind.Number, ")" to TokenKind.Close,
                "\"a \\\" ; b\"" to TokenKind.Text, ")" to TokenKind.Close, "; done (" to TokenKind.Comment,
            ),
            kinds("(defun f () (set-current 1.5f32) \"a \\\" ; b\") ; done ("),
        )
        assertEquals(listOf("0xFF" to TokenKind.Number, "-3" to TokenKind.Number, "10u32" to TokenKind.Number, "nil" to TokenKind.Constant), kinds("0xFF -3 10u32 nil my-var"))
        assertEquals(listOf("{" to TokenKind.Open, "event-can-sid" to TokenKind.Constant, "}" to TokenKind.Close), kinds("{event-can-sid}"))
    }

    @Test
    fun matchesBracketsAcrossLinesSkippingStringsAndComments() {
        val text = "(defun f (x)\n  \"(\" ; )\n  [x])"
        val lines = LispLines.Cache(symbols).split(text)
        assertEquals(0 to text.lastIndex, lines.matchingBracket(0))
        assertEquals(text.lastIndex to 0, lines.matchingBracket(text.length))
        assertEquals(text.indexOf('[') to text.indexOf(']'), lines.matchingBracket(text.indexOf('[')))
        assertNull(lines.matchingBracket(text.indexOf('"') + 1))
        assertNull(LispLines.Cache(symbols).split("(]").matchingBracket(0))
    }

    @Test
    fun cachedLinesGiveTheSameTokensAsAFreshSplit() {
        val cache = LispLines.Cache(symbols)
        val before = "(def a 1)\n(def b \"x\")\n; note"
        cache.split(before)
        val after = before.replace("(def b", "(define b2")
        val cached = cache.split(after)
        val fresh = LispLines.Cache(symbols).split(after)
        for (line in 0 until fresh.count) {
            assertEquals(fresh.tokens(line).map { Triple(it.start, it.end, it.kind) }, cached.tokens(line).map { Triple(it.start, it.end, it.kind) })
        }
        assertEquals(listOf(0, 10, 26), cached.starts.toList())
    }

    @Test
    fun indentsNewLinesLikeTheLineAboveAndDeeperAfterAnOpenBracket() {
        val before = TextFieldValue("  (defun f ()", TextRange(13))
        val typed = TextFieldValue("  (defun f ()\n", TextRange(14))
        assertEquals(TextFieldValue("  (defun f ()\n      ", TextRange(20)), autoIndent(before, typed))
        val closed = TextFieldValue("  (f)", TextRange(5))
        assertEquals(TextFieldValue("  (f)\n  ", TextRange(8)), autoIndent(closed, TextFieldValue("  (f)\n", TextRange(6))))
        val pasted = TextFieldValue("a\nb\n", TextRange(4))
        assertEquals(pasted, autoIndent(TextFieldValue("", TextRange(0)), pasted))
    }

    @Test
    fun togglesCommentsOnSelectedLines() {
        val value = TextFieldValue("(a)\n  (b)\n\n(c)", TextRange(0, 9))
        val commented = toggleComment(value)
        assertEquals("; (a)\n  ; (b)\n\n(c)", commented.text)
        assertEquals("(a)\n  (b)\n\n(c)", toggleComment(commented).text)
    }

    @Test
    fun duplicatesTheCursorLine() {
        val result = duplicateLines(TextFieldValue("(a)\n(b)", TextRange(5)))
        assertEquals("(a)\n(b)\n(b)", result.text)
        assertEquals(TextRange(9), result.selection)
    }

    @Test
    fun completesTheWordBeforeTheCursor() {
        val value = TextFieldValue("(get-rp", TextRange(7))
        assertEquals("get-rp", wordBefore(value))
        assertTrue("get-rpm" in symbols.complete("get-rp", 12))
        assertEquals(TextFieldValue("(get-rpm", TextRange(8)), complete(value, "get-rpm"))
        assertEquals("", wordBefore(TextFieldValue("(get-rpm)", TextRange(4))))
    }

    @Test
    fun findsEveryMatchIgnoringCase() {
        assertEquals(listOf(1..3, 9..11), findAll("(Def a)\n(def b)", "def"))
        assertEquals(emptyList(), findAll("abc", ""))
    }

    @Test
    fun historyUndoesBurstsOfTypingTogether() {
        val history = EditHistory(burstMillis = 1000)
        val a = TextFieldValue("a")
        history.record(TextFieldValue(""), now = 0)
        history.record(a, now = 500)
        history.record(TextFieldValue("ab"), now = 3000)
        val current = TextFieldValue("abc")
        assertEquals("ab", history.undo(current)?.text)
        assertEquals("", history.undo(TextFieldValue("ab"))?.text)
        assertEquals("ab", history.redo(TextFieldValue(""))?.text)
        assertTrue(history.canRedo)
    }
}
