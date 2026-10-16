package app.foctuner.ui.tools.lisp

enum class TokenKind { Keyword, Builtin, Constant, Number, Text, Comment, Open, Close }

/** [start] and [end] are offsets in the token's line. */
class Token(val start: Int, val end: Int, val kind: TokenKind)

/**
 * The names VESC Tool's editor highlights (assets/lisp/symbols.txt, from its lisp.xml): special forms,
 * constants (`nil`, `t`, type and event names), and the built-in and extension functions.
 */
class LispSymbols(private val kinds: Map<String, TokenKind>) {
    fun kindOf(name: String): TokenKind? = kinds[name]

    /** Names starting with [prefix], shortest first. */
    fun complete(prefix: String, limit: Int): List<String> =
        kinds.keys.filter { it.length > prefix.length && it.startsWith(prefix) }.sortedWith(compareBy({ it.length }, { it })).take(limit)

    companion object {
        val Empty = LispSymbols(emptyMap())

        fun parse(text: String): LispSymbols {
            val kinds = HashMap<String, TokenKind>()
            var kind: TokenKind? = null
            for (line in text.lineSequence()) {
                val name = line.trim()
                when {
                    name.isEmpty() || name.startsWith("#") -> Unit
                    name.startsWith("[") -> kind = when (name) {
                        "[Keyword]" -> TokenKind.Keyword
                        "[Label]", "[Global]" -> TokenKind.Constant
                        else -> TokenKind.Builtin
                    }
                    else -> kind?.let { kinds.putIfAbsent(name, it) }
                }
            }
            return LispSymbols(kinds)
        }
    }
}

/**
 * The document split into lines with their tokens. Strings and comments end at the end of their line, as in
 * VESC Tool's highlighter, so lines are tokenized independently and unchanged lines come from [cache].
 */
class LispLines private constructor(val text: String, val starts: IntArray, private val tokens: Array<List<Token>>) {
    val count: Int get() = starts.size

    /** Tokens of [line], offsets relative to [starts]`[line]`. */
    fun tokens(line: Int): List<Token> = tokens[line]

    fun lineOf(offset: Int): Int {
        val i = starts.binarySearch(offset)
        return if (i >= 0) i else -i - 2
    }

    /**
     * The offset of the bracket matching the one just before or at [cursor], skipping brackets in strings and
     * comments; null when there is no bracket there or it is unbalanced.
     */
    fun matchingBracket(cursor: Int): Pair<Int, Int>? {
        val bracket = bracketAt(cursor - 1) ?: bracketAt(cursor) ?: return null
        val (line, index) = bracket
        val token = tokens[line][index]
        val forward = token.kind == TokenKind.Open
        var depth = 0
        var l = line
        var i = index
        while (l in 0 until count) {
            val list = tokens[l]
            while (i in list.indices) {
                val t = list[i]
                if (t.kind == TokenKind.Open || t.kind == TokenKind.Close) {
                    depth += if ((t.kind == TokenKind.Open) == forward) 1 else -1
                    if (depth == 0) {
                        val from = starts[line] + token.start
                        val to = starts[l] + t.start
                        return if ("${text[from]}${text[to]}" in PAIRS) from to to else null
                    }
                }
                i += if (forward) 1 else -1
            }
            l += if (forward) 1 else -1
            i = if (forward) 0 else tokens.getOrNull(l)?.lastIndex ?: 0
        }
        return null
    }

    private fun bracketAt(offset: Int): Pair<Int, Int>? {
        if (offset !in text.indices) return null
        val line = lineOf(offset)
        val column = offset - starts[line]
        val index = tokens[line].indexOfFirst { it.start == column && (it.kind == TokenKind.Open || it.kind == TokenKind.Close) }
        return if (index >= 0) line to index else null
    }

    /** Tokens of each line keyed by the line's text, reused across edits. Not thread-safe. */
    class Cache(private val symbols: LispSymbols) {
        private var lines = HashMap<String, List<Token>>()

        fun split(text: String): LispLines {
            val starts = ArrayList<Int>(text.length / 32 + 1)
            starts += 0
            for (i in text.indices) if (text[i] == '\n') starts += i + 1
            val next = HashMap<String, List<Token>>(starts.size * 2)
            val tokens = Array(starts.size) { line ->
                val start = starts[line]
                val end = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length
                val content = text.substring(start, end)
                val lineTokens = next[content] ?: lines[content] ?: tokenize(content, symbols)
                next[content] = lineTokens
                lineTokens
            }
            lines = next
            return LispLines(text, starts.toIntArray(), tokens)
        }
    }

    companion object {
        /** The tokens of one line, offsets relative to its start. Plain symbols and whitespace get none. */
        fun tokenize(line: String, symbols: LispSymbols): List<Token> {
            val out = ArrayList<Token>()
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == ';' -> {
                        out += Token(i, line.length, TokenKind.Comment)
                        i = line.length
                    }
                    c == '"' -> {
                        var j = i + 1
                        while (j < line.length && line[j] != '"') j += if (line[j] == '\\') 2 else 1
                        val end = minOf(j + 1, line.length)
                        out += Token(i, end, TokenKind.Text)
                        i = end
                    }
                    c in "([{" -> out += Token(i, ++i, TokenKind.Open)
                    c in ")]}" -> out += Token(i, ++i, TokenKind.Close)
                    c.isWhitespace() || c in "'`,@" -> i++
                    else -> {
                        val start = i
                        while (i < line.length && !line[i].isWhitespace() && line[i] !in DELIMITERS) i++
                        val word = line.substring(start, i)
                        val kind = if (NUMBER.matches(word)) TokenKind.Number else symbols.kindOf(word)
                        if (kind != null) out += Token(start, i, kind)
                    }
                }
            }
            return out
        }

        private const val DELIMITERS = "()[]{}\";'`,"
        private val PAIRS = setOf("()", ")(", "[]", "][", "{}", "}{")

        /** Integers (decimal, hex, binary), decimals and exponents, with LispBM's type suffixes (5u32, 1.0f64, 3b). */
        private val NUMBER = Regex("[+-]?(0x[0-9a-fA-F]+|0b[01]+|\\d+\\.?\\d*([eE][+-]?\\d+)?|\\.\\d+([eE][+-]?\\d+)?)(u|i|b|u32|i32|u64|i64|f32|f64)?")
    }
}
