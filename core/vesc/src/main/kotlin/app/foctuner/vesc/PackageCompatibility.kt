package app.foctuner.vesc

/**
 * Runs a package's `isCompatible(fwRxParams)` from its `pkgdesc.qml` without a QML engine. Every package in
 * vesc_pkg uses the same small part of JavaScript: `var` and plain assignments, `if` / `else`, `return`, string and
 * number literals, `==` `!=` `===` `!==` `&&` `||` `!` and parentheses, and these reads of the controller:
 * `fwRxParams.hw`, `.fwName`, `.major`, `.minor`, `.hwTypeStr()`, with `.toLowerCase()` / `.toUpperCase()` on strings.
 * Anything else (another call, a loop, arithmetic) gives [Verdict.Unknown], so a package is never hidden on a guess.
 */
object PackageCompatibility {
    enum class Verdict { Compatible, Incompatible, Unknown }

    fun check(descriptionQml: String, info: FirmwareInfo): Verdict {
        if (descriptionQml.isBlank()) return Verdict.Unknown
        val body = functionBody(descriptionQml) ?: return Verdict.Unknown
        return try {
            val result = Interpreter(Lexer(body).tokens(), info).run()
            when (result) {
                true -> Verdict.Compatible
                false -> Verdict.Incompatible
                else -> Verdict.Unknown
            }
        } catch (e: Unsupported) {
            Verdict.Unknown
        }
    }

    /** VESC Tool's `FW_RX_PARAMS::hwTypeStr`. */
    fun hardwareTypeName(type: HardwareType): String = when (type) {
        HardwareType.VESC -> "VESC"
        HardwareType.VESC_BMS -> "VESC BMS"
        HardwareType.CUSTOM_MODULE -> "Custom Module"
    }

    private class Unsupported(message: String) : Exception(message)

    /** The text between the braces of `function isCompatible (fwRxParams) { ... }`, with nested braces matched. */
    private fun functionBody(qml: String): String? {
        val start = Regex("""function\s+isCompatible\s*\(\s*fwRxParams\s*\)\s*\{""").find(qml) ?: return null
        var depth = 1
        var i = start.range.last + 1
        var inString: Char? = null
        while (i < qml.length) {
            val c = qml[i]
            when {
                inString != null -> if (c == '\\') i++ else if (c == inString) inString = null
                c == '"' || c == '\'' -> inString = c
                c == '/' && qml.getOrNull(i + 1) == '/' -> while (i < qml.length && qml[i] != '\n') i++
                c == '{' -> depth++
                c == '}' -> if (--depth == 0) return qml.substring(start.range.last + 1, i)
            }
            i++
        }
        return null
    }

    private sealed interface Token {
        data class Word(val text: String) : Token
        data class Str(val text: String) : Token
        data class Num(val value: Double) : Token
        data class Sym(val text: String) : Token
    }

    private class Lexer(private val s: String) {
        fun tokens(): List<Token> {
            val out = ArrayList<Token>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && s.getOrNull(i + 1) == '/' -> while (i < s.length && s[i] != '\n') i++
                    c == '/' && s.getOrNull(i + 1) == '*' -> {
                        val end = s.indexOf("*/", i + 2)
                        if (end < 0) throw Unsupported("open comment")
                        i = end + 2
                    }
                    c.isLetter() || c == '_' || c == '$' -> {
                        val start = i
                        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) i++
                        out += Token.Word(s.substring(start, i))
                    }
                    c.isDigit() -> {
                        val start = i
                        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                        out += Token.Num(s.substring(start, i).toDoubleOrNull() ?: throw Unsupported("number"))
                    }
                    c == '"' || c == '\'' -> {
                        val text = StringBuilder()
                        i++
                        while (i < s.length && s[i] != c) {
                            if (s[i] == '\\') {
                                i++
                                text.append(
                                    when (s.getOrNull(i)) {
                                        'n' -> '\n'
                                        't' -> '\t'
                                        null -> throw Unsupported("string")
                                        else -> s[i]
                                    },
                                )
                            } else {
                                text.append(s[i])
                            }
                            i++
                        }
                        if (i >= s.length) throw Unsupported("open string")
                        i++
                        out += Token.Str(text.toString())
                    }
                    else -> {
                        val three = s.regionMatches(i, "===", 0, 3) || s.regionMatches(i, "!==", 0, 3)
                        val two = listOf("==", "!=", "&&", "||").any { s.regionMatches(i, it, 0, 2) }
                        val n = if (three) 3 else if (two) 2 else 1
                        val sym = s.substring(i, i + n)
                        if (n == 1 && sym !in SINGLE) throw Unsupported("symbol $sym")
                        out += Token.Sym(sym)
                        i += n
                    }
                }
            }
            return out
        }

        private companion object {
            val SINGLE = setOf("(", ")", "{", "}", ";", "=", ".", "!")
        }
    }

    /** Statement interpreter; [run] gives the returned value, or null if the body ends without returning. */
    private class Interpreter(private val t: List<Token>, private val info: FirmwareInfo) {
        private var p = 0
        private val vars = HashMap<String, Any>()
        private var steps = 0

        /** Set while walking a branch that isn't taken: tokens are consumed, nothing is evaluated. */
        private var dry = false

        private class Return(val value: Any) : Throwable(null, null, false, false)

        fun run(): Any? = try {
            while (p < t.size) statement(execute = true)
            null
        } catch (r: Return) {
            r.value
        }

        private fun statement(execute: Boolean) {
            if (++steps > MAX_STEPS) throw Unsupported("too long")
            val wasDry = dry
            dry = !execute
            try {
                statementBody(execute)
            } finally {
                dry = wasDry
            }
        }

        private fun statementBody(execute: Boolean) {
            when {
                sym(";") -> Unit
                sym("{") -> {
                    while (!sym("}")) {
                        if (p >= t.size) throw Unsupported("open block")
                        statement(execute)
                    }
                }
                word("var") || word("let") || word("const") -> {
                    val name = name()
                    val value = if (sym("=")) expression() else Unit
                    if (execute) vars[name] = value
                    sym(";")
                }
                word("if") -> {
                    expect("(")
                    val condition = expression()
                    expect(")")
                    val taken = execute && truthy(condition)
                    statement(taken)
                    if (word("else")) statement(execute && !taken)
                }
                word("return") -> {
                    val value = if (peekSym(";") || peekSym("}")) Unit else expression()
                    sym(";")
                    if (execute) throw Return(value)
                }
                peekAssignment() -> {
                    val name = name()
                    expect("=")
                    val value = expression()
                    if (execute) {
                        if (name !in vars) throw Unsupported("assignment to $name")
                        vars[name] = value
                    }
                    sym(";")
                }
                else -> {
                    expression() // e.g. console.log(...) is refused inside
                    sym(";")
                }
            }
        }

        private fun expression(): Any = or()

        private fun or(): Any {
            var left = and()
            while (sym("||")) {
                val right = and()
                left = if (truthy(left)) left else right
            }
            return left
        }

        private fun and(): Any {
            var left = equality()
            while (sym("&&")) {
                val right = equality()
                left = if (!truthy(left)) left else right
            }
            return left
        }

        private fun equality(): Any {
            var left = unary()
            while (true) {
                val op = listOf("===", "!==", "==", "!=").firstOrNull { sym(it) } ?: return left
                val right = unary()
                val same = equal(left, right)
                left = if (op.startsWith("!")) !same else same
            }
        }

        private fun unary(): Any = if (sym("!")) !truthy(unary()) else postfix()

        private fun postfix(): Any {
            var value = primary()
            while (sym(".")) {
                val member = name()
                value = if (sym("(")) {
                    expect(")")
                    call(value, member)
                } else {
                    property(value, member)
                }
            }
            return value
        }

        private fun primary(): Any {
            val token = t.getOrNull(p++) ?: throw Unsupported("end of input")
            return when (token) {
                is Token.Str -> token.text
                is Token.Num -> token.value
                is Token.Word -> when (token.text) {
                    "true" -> true
                    "false" -> false
                    "fwRxParams" -> Params
                    else -> vars[token.text] ?: if (dry) Unit else throw Unsupported("unknown name ${token.text}")
                }
                is Token.Sym -> if (token.text == "(") {
                    val value = expression()
                    expect(")")
                    value
                } else {
                    throw Unsupported("unexpected ${token.text}")
                }
            }
        }

        private fun property(target: Any, member: String): Any = when {
            dry -> Unit
            target === Params && member == "hw" -> info.hardwareName
            target === Params && member == "fwName" -> info.firmwareName
            target === Params && member == "major" -> info.major.toDouble()
            target === Params && member == "minor" -> info.minor.toDouble()
            target is String && member == "length" -> target.length.toDouble()
            else -> throw Unsupported("property $member")
        }

        private fun call(target: Any, member: String): Any = when {
            dry -> Unit
            target === Params && member == "hwTypeStr" -> hardwareTypeName(info.hardwareType)
            target is String && member == "toLowerCase" -> target.lowercase()
            target is String && member == "toUpperCase" -> target.uppercase()
            else -> throw Unsupported("call $member")
        }

        private fun equal(a: Any, b: Any): Boolean = when {
            dry -> false
            a is String && b is String -> a == b
            a is Double && b is Double -> a == b
            a is Boolean && b is Boolean -> a == b
            else -> throw Unsupported("comparing ${a::class.simpleName} with ${b::class.simpleName}")
        }

        private fun truthy(v: Any): Boolean = when (v) {
            Unit -> if (dry) false else throw Unsupported("undefined")
            is Boolean -> v
            is String -> v.isNotEmpty()
            is Double -> v != 0.0
            else -> throw Unsupported("truth of $v")
        }

        private fun peekAssignment() = t.getOrNull(p) is Token.Word && (t.getOrNull(p + 1) as? Token.Sym)?.text == "="
        private fun peekSym(s: String) = (t.getOrNull(p) as? Token.Sym)?.text == s
        private fun sym(s: String): Boolean = peekSym(s).also { if (it) p++ }
        private fun word(w: String): Boolean = ((t.getOrNull(p) as? Token.Word)?.text == w).also { if (it) p++ }
        private fun name(): String = (t.getOrNull(p++) as? Token.Word)?.text ?: throw Unsupported("expected a name")
        private fun expect(s: String) {
            if (!sym(s)) throw Unsupported("expected $s")
        }

        private object Params

        private companion object {
            const val MAX_STEPS = 10_000
        }
    }
}
