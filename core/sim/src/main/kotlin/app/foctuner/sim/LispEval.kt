package app.foctuner.sim

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId
import app.foctuner.protocol.CommPacketId.COMM_PLOT_ADD_GRAPH
import app.foctuner.protocol.CommPacketId.COMM_PLOT_DATA
import app.foctuner.protocol.CommPacketId.COMM_PLOT_INIT
import app.foctuner.protocol.CommPacketId.COMM_PLOT_SET_GRAPH

/**
 * A very small subset of LispBM for the simulator: integer and float literals, strings, global bindings
 * (`def`, `define`, `setq`), arithmetic and comparison, `progn` (and `{ }`), `print` and the plot extensions,
 * which [send] as the firmware's COMM_PLOT_* packets. Anything else is an error in the REPL and skipped in
 * programs, where real scripts mostly call firmware extensions.
 */
internal class LispEval(private val print: (String) -> Unit, private val send: (ByteArray) -> Unit) {
    val globals = LinkedHashMap<String, Any>()

    /** Garbage collections so far; the firmware reports heap use as 0 until the first. */
    var collections = 0
        private set

    fun reset() {
        globals.clear()
        collections = 0
    }

    /** Evaluates every top-level form of [program], skipping the ones this subset can't handle. */
    fun run(program: String) {
        val forms = try {
            Reader(program).readAll()
        } catch (e: EvalError) {
            print("*** Error: ${e.message}")
            return
        }
        for (form in forms) {
            try {
                eval(form)
            } catch (e: EvalError) {
                // An extension or special form the simulator doesn't know; the rest of the program still runs.
            }
        }
        collections++
    }

    /** The printed value of the REPL [line], or an error message. */
    fun evaluate(line: String): String? = try {
        val forms = Reader(line).readAll()
        var value: Any = NIL
        for (form in forms) value = eval(form)
        collections++
        show(value)
    } catch (e: EvalError) {
        "*** Error: ${e.message}"
    }

    private fun eval(form: Any): Any = when (form) {
        is Symbol -> when (form.name) {
            "nil" -> NIL
            "t", "true" -> T
            else -> globals[form.name] ?: throw EvalError("variable_not_bound ${form.name}")
        }
        is List<*> -> apply(form)
        is Quoted -> form.form
        else -> form
    }

    private fun apply(form: List<*>): Any {
        if (form.isEmpty()) return NIL
        val head = (form[0] as? Symbol)?.name ?: throw EvalError("eval_error")
        val args = form.drop(1).map { it ?: NIL }
        return when (head) {
            "def", "define", "setq", "set" -> {
                val name = when (val target = args.getOrNull(0)) {
                    is Symbol -> target.name
                    is Quoted -> (target.form as? Symbol)?.name
                    else -> null
                } ?: throw EvalError("type_error")
                val value = eval(args.getOrNull(1) ?: NIL)
                globals[name] = value
                Symbol(name)
            }
            "progn" -> args.fold(NIL as Any) { _, it -> eval(it) }
            "print" -> {
                print(args.joinToString("") { arg -> eval(arg).let { it as? String ?: show(it) } })
                T
            }
            "quote" -> args.firstOrNull() ?: NIL
            "plot-init" -> plot(COMM_PLOT_INIT) { w -> args.take(2).forEach { w.cString(eval(it) as? String ?: throw EvalError("type_error")) } }
            "plot-add-graph" -> plot(COMM_PLOT_ADD_GRAPH) { w -> w.cString(eval(args.firstOrNull() ?: NIL) as? String ?: throw EvalError("type_error")) }
            "plot-set-graph" -> plot(COMM_PLOT_SET_GRAPH) { w -> w.u8(number(args.firstOrNull()).toInt()) }
            "plot-send-points" -> plot(COMM_PLOT_DATA) { w -> w.f32Auto(number(args.getOrNull(0)).toFloat()).f32Auto(number(args.getOrNull(1)).toFloat()) }
            "+", "-", "*", "/" -> arithmetic(head, args.map(::eval))
            "=", "<", ">", "<=", ">=" -> compare(head, args.map(::eval))
            else -> throw EvalError("variable_not_bound $head")
        }
    }

    private fun plot(id: CommPacketId, body: (ByteWriter) -> Unit): Any {
        val w = ByteWriter().u8(id.id)
        body(w)
        send(w.toByteArray())
        return T
    }

    private fun number(form: Any?): Number = eval(form ?: NIL) as? Number ?: throw EvalError("type_error")

    private fun arithmetic(op: String, values: List<Any>): Any {
        val numbers = values.map { it as? Number ?: throw EvalError("type_error") }
        if (numbers.isEmpty()) return if (op == "*") 1 else 0
        if (numbers.any { it is Double }) {
            val d = numbers.map { it.toDouble() }
            return when (op) {
                "+" -> d.sum()
                "-" -> if (d.size == 1) -d[0] else d.drop(1).fold(d[0]) { a, b -> a - b }
                "*" -> d.fold(1.0) { a, b -> a * b }
                else -> d.drop(1).fold(d[0]) { a, b -> a / b }
            }
        }
        val l = numbers.map { it.toLong() }
        return when (op) {
            "+" -> l.sum()
            "-" -> if (l.size == 1) -l[0] else l.drop(1).fold(l[0]) { a, b -> a - b }
            "*" -> l.fold(1L) { a, b -> a * b }
            else -> l.drop(1).fold(l[0]) { a, b -> if (b == 0L) throw EvalError("division_by_zero") else a / b }
        }
    }

    private fun compare(op: String, values: List<Any>): Any {
        val d = values.map { (it as? Number)?.toDouble() ?: throw EvalError("type_error") }
        val holds = d.zipWithNext().all { (a, b) ->
            when (op) {
                "=" -> a == b
                "<" -> a < b
                ">" -> a > b
                "<=" -> a <= b
                else -> a >= b
            }
        }
        return if (holds) T else NIL
    }

    private class Reader(private val text: String) {
        private var i = 0

        fun readAll(): List<Any> = buildList {
            while (true) {
                skipBlank()
                if (i >= text.length) break
                add(read())
            }
        }

        private fun read(): Any {
            skipBlank()
            if (i >= text.length) throw EvalError("parse_error")
            return when (val c = text[i]) {
                '(', '[' -> list(if (c == '(') ')' else ']', progn = false)
                '{' -> list('}', progn = true)
                ')', ']', '}' -> throw EvalError("parse_error")
                '\'' -> {
                    i++
                    Quoted(read())
                }
                '"' -> string()
                else -> atom()
            }
        }

        private fun list(close: Char, progn: Boolean): List<Any> {
            i++
            val items = mutableListOf<Any>()
            if (progn) items += Symbol("progn")
            while (true) {
                skipBlank()
                if (i >= text.length) throw EvalError("parse_error")
                if (text[i] == close) {
                    i++
                    return items
                }
                items += read()
            }
        }

        private fun string(): String {
            val out = StringBuilder()
            i++
            while (i < text.length && text[i] != '"') {
                if (text[i] == '\\' && i + 1 < text.length) i++
                out.append(text[i++])
            }
            if (i >= text.length) throw EvalError("parse_error")
            i++
            return out.toString()
        }

        private fun atom(): Any {
            val start = i
            while (i < text.length && !text[i].isWhitespace() && text[i] !in "()[]{}\"';") i++
            val token = text.substring(start, i)
            token.toLongOrNull()?.let { return it }
            if (token.contains('.') || token.endsWith("f32")) token.removeSuffix("f32").toDoubleOrNull()?.let { return it }
            return Symbol(token)
        }

        private fun skipBlank() {
            while (i < text.length) {
                when {
                    text[i].isWhitespace() -> i++
                    text[i] == ';' -> while (i < text.length && text[i] != '\n') i++
                    else -> return
                }
            }
        }
    }

    private data class Symbol(val name: String)
    private data class Quoted(val form: Any)
    private class EvalError(message: String) : Exception(message)

    companion object {
        private val NIL = Symbol("nil")
        private val T = Symbol("t")

        /** Roughly how LispBM prints values: floats with an f32 suffix, symbols by name, strings quoted. */
        fun show(value: Any): String = when (value) {
            is Double -> "${value}f32"
            is Number -> value.toString()
            is String -> "\"$value\""
            is Symbol -> value.name
            is Quoted -> show(value.form)
            is List<*> -> value.joinToString(" ", "(", ")") { show(it ?: NIL) }
            else -> value.toString()
        }
    }
}
