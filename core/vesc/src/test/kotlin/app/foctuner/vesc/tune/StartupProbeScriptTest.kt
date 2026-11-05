package app.foctuner.vesc.tune

import app.foctuner.vesc.LispCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The probe can't run here, so these check what can go wrong without running it: forms that don't close, calls to
 * functions the firmware doesn't have (typos, or extensions newer than 6.05), calls with the wrong number of arguments,
 * and a packet layout that drifted from [ProbeCodec].
 */
class StartupProbeScriptTest {
    private val script = StartupProbe.script
    private val forms = Reader(script).readAll()

    /** LispBM special forms and the VESC extensions the probe may use, all present in firmware 6.05. */
    private val builtins = setOf(
        "def", "defun", "if", "cond", "let", "progn", "loopwhile", "and", "or", "not", "eq", "=", "<", ">", "+", "-", "*", "/",
        "ix", "trap", "car", "cdr", "recv", "spawn", "sleep", "systime", "secs-since",
        "bufcreate", "buflen", "free", "bufset-u8", "bufset-u16", "bufset-i16", "bufset-f32", "bufget-u8", "bufget-u16", "bufget-f32",
        "send-data", "conf-set", "conf-get", "conf-measure-res", "select-motor", "set-current", "set-brake", "app-disable-output",
        "get-rpm", "get-rpm-faster", "get-iq", "get-id", "get-iq-set", "get-vd", "get-vq", "phase-observer", "phase-motor",
        "get-duty", "get-est-lambda", "get-est-res", "get-fault", "get-vin", "get-temp-fet", "get-temp-mot",
        "event-register-handler", "event-enable",
    )

    /** Names loopwhile binds around its body on firmware where it is a macro; locals with these names would be shadowed. */
    private val loopwhileNames = setOf("res", "break", "loop", "brk")

    @Test
    fun formsCloseAndOnlyKnownFunctionsAreCalled() {
        val functions = forms.filterIsInstance<List<*>>().filter { it.firstOrNull() == Sym("defun") }
            .associate { (it[1] as Sym).name to (it[2] as List<*>).size }
        val globals = forms.filterIsInstance<List<*>>().filter { it.firstOrNull() == Sym("def") }.map { (it[1] as Sym).name }.toSet()
        val problems = mutableListOf<String>()
        fun walk(node: Any?, locals: Set<String>) {
            if (node !is List<*> || node.isEmpty()) return
            val head = node[0]
            if (head !is Sym) {
                node.forEach { walk(it, locals) }
                return
            }
            when (head.name) {
                "quote" -> return
                "defun" -> {
                    val params = (node[2] as List<*>).map { (it as Sym).name }.toSet()
                    params.intersect(loopwhileNames).forEach { problems += "parameter $it of ${(node[1] as Sym).name} would be shadowed by loopwhile" }
                    node.drop(3).forEach { walk(it, params) }
                }
                "let" -> {
                    val bindings = node[1] as List<*>
                    val names = bindings.map { ((it as List<*>)[0] as Sym).name }.toSet()
                    names.intersect(loopwhileNames).forEach { problems += "local $it would be shadowed by loopwhile" }
                    bindings.forEach { walk((it as List<*>)[1], locals) }
                    node.drop(2).forEach { walk(it, locals + names) }
                }
                "recv" -> node.drop(1).forEach { clause -> (clause as List<*>).drop(1).forEach { walk(it, locals + "d") } }
                "cond" -> node.drop(1).forEach { clause -> (clause as List<*>).forEach { if (it is List<*>) walk(it, locals) } }
                "spawn" -> {
                    val closure = (node[2] as Sym).name
                    val arity = functions[closure] ?: run { problems += "spawn of unknown $closure"; 0 }
                    if (node.size - 3 != arity) problems += "spawn $closure with ${node.size - 3} arguments, it takes $arity"
                    node.drop(3).forEach { walk(it, locals) }
                }
                else -> {
                    val name = head.name
                    val arity = functions[name]
                    when {
                        arity != null -> if (node.size - 1 != arity) problems += "$name called with ${node.size - 1} arguments, it takes $arity"
                        name in builtins -> Unit
                        name in globals || name in locals -> problems += "$name is a variable, not a function"
                        else -> problems += "unknown function $name"
                    }
                    node.drop(1).forEach { walk(it, locals) }
                }
            }
        }
        forms.forEach { walk(it, emptySet()) }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun theReducedScriptKeepsEveryForm() {
        // The app streams the reduced script; comments must not swallow code.
        assertEquals(forms, Reader(LispCode.reduce(script)).readAll())
    }

    @Test
    fun parametersAreInTheEnumsOrder() {
        val list = forms.filterIsInstance<List<*>>().first { it.getOrNull(1) == Sym("ft-params") }[2] as Quoted
        val names = (list.form as List<*>).map { (it as Sym).name }
        assertEquals(ProbeParam.entries.map { it.configName.replace('_', '-') }, names)
    }

    @Test
    fun sampleLayoutMatchesTheCodec() {
        for (field in ProbeCodec.Field.entries) {
            val kind = if (field.signed) "i16" else "u16"
            val offset = if (field.offset == 0) "off" else "(+ off ${field.offset})"
            val expected = "(bufset-$kind ft-pkt $offset (ft-$kind ${field.lisp}))"
            assertTrue(expected in script, "missing $expected")
        }
        assertEquals(ProbeCodec.SAMPLE_SIZE, ProbeCodec.Field.entries.size * 2)
        assertTrue("(ft-sample (+ ${ProbeCodec.DATA_HEADER} (* ft-k ${ProbeCodec.SAMPLE_SIZE})))" in script)
        assertTrue("(def ft-pkt (bufcreate ${ProbeCodec.DATA_HEADER + ProbeCodec.SAMPLES_PER_PACKET * ProbeCodec.SAMPLE_SIZE}))" in script)
        assertTrue("(if (= ft-k ${ProbeCodec.SAMPLES_PER_PACKET})" in script)
    }

    @Test
    fun packetTypesAndSizesMatchTheCodec() {
        val end = ProbeCodec.end(TrialEnd(1, TrialOutcome.REACHED, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertTrue("(def ft-end (bufcreate ${end.size}))" in script)
        for ((type, call) in listOf(
            ProbeCodec.HELLO_REPLY to "(ft-head b ${ProbeCodec.HELLO_REPLY})",
            ProbeCodec.PARAMS_REPLY to "(ft-head b ${ProbeCodec.PARAMS_REPLY})",
            ProbeCodec.DATA to "(ft-head ft-pkt ${ProbeCodec.DATA})",
            ProbeCodec.END to "(ft-head ft-end ${ProbeCodec.END})",
            ProbeCodec.ERROR to "(ft-head b ${ProbeCodec.ERROR})",
            ProbeCodec.BYE to "(ft-head b ${ProbeCodec.BYE})",
            ProbeCodec.RESISTANCE to "(ft-head b ${ProbeCodec.RESISTANCE})",
            ProbeCodec.RELEASED to "(ft-head b ${ProbeCodec.RELEASED})",
            ProbeCodec.GAVE_UP to "(ft-head b ${ProbeCodec.GAVE_UP})",
        )) {
            assertTrue(call in script, "reply type $type: missing $call")
        }
        for ((command, handler) in listOf(
            ProbeCodec.HELLO to "ft-hello", ProbeCodec.SET to "ft-set", ProbeCodec.GET to "ft-get", ProbeCodec.TRIAL to "ft-start-trial",
            ProbeCodec.QUIT to "ft-quit", ProbeCodec.MEASURE_RESISTANCE to "ft-start-res",
        )) {
            assertTrue("((= cmd $command) ($handler" in script, "command $command should go to $handler")
        }
        assertTrue("((= cmd ${ProbeCodec.STOP}) (def ft-stop t))" in script)
        assertTrue("((= cmd ${ProbeCodec.PING}) nil)" in script)
        assertTrue("(def ft-probe ${ProbeCodec.VERSION})" in script)
    }

    @Test
    fun theDeadMansSwitchMatchesTheApp() {
        // It saves every parameter, 5 bytes each, and waits as long as the app expects before it gives up.
        assertTrue("(def ft-nparams ${ProbeParam.entries.size})" in script)
        assertTrue("(def ft-orig (bufcreate ${ProbeParam.entries.size * 5}))" in script)
        assertTrue("(def ft-quiet ${ProbeCodec.QUIET_SECONDS})" in script)
        // Every command resets the quiet timer, and the probe ignores commands once it gave up.
        assertTrue("(if (and ft-alive (> (buflen d) 3)" in script)
        assertTrue("(progn\n        (def ft-seen (systime))\n        (cond" in script)
        // A start or measurement that ends restarts the timer before it frees the probe.
        assertEquals(2, Regex("\\(def ft-seen \\(systime\\)\\)\\s*\\(def ft-busy nil\\)").findAll(script).count())
        assertTrue("(if ft-saved nil (ft-save))" in script)
        assertTrue("(spawn 256 ft-watch)" in script)
    }

    @Test
    fun trialArgumentsAreReadWhereTheCodecWritesThem() {
        val spec = TrialSpec(0, 1.0, 1.0, kotlin.time.Duration.parse("1s"), kotlin.time.Duration.parse("4ms"), 1.0, 1.0, kotlin.time.Duration.parse("250ms"), 1.0, 1.0, 1.0)
        assertEquals(39, ProbeCodec.trial(spec).size)
        val reads = "(bufget-u16 d 4) (bufget-f32 d 6) (bufget-f32 d 10) (bufget-u16 d 14) (bufget-u8 d 16)\n" +
            "        (bufget-f32 d 17) (bufget-f32 d 21) (bufget-u16 d 25) (bufget-f32 d 27) (bufget-f32 d 31) (bufget-f32 d 35)"
        assertTrue(reads in script)
    }

    // A small s-expression reader: lists, quotes, numbers and symbols, comments skipped.

    private data class Sym(val name: String)
    private data class Quoted(val form: Any)

    private class Reader(private val text: String) {
        private var i = 0

        fun readAll(): List<Any> = buildList {
            while (true) {
                skip()
                if (i >= text.length) break
                add(read())
            }
        }

        private fun read(): Any {
            skip()
            check(i < text.length) { "unexpected end" }
            return when (text[i]) {
                '(' -> list()
                ')' -> error("unbalanced ) at $i")
                '\'' -> {
                    i++
                    Quoted(read())
                }
                else -> {
                    val start = i
                    while (i < text.length && !text[i].isWhitespace() && text[i] !in "();'") i++
                    val token = text.substring(start, i)
                    token.toDoubleOrNull() ?: Sym(token)
                }
            }
        }

        private fun list(): List<Any> {
            i++
            val items = mutableListOf<Any>()
            while (true) {
                skip()
                check(i < text.length) { "a form doesn't close" }
                if (text[i] == ')') {
                    i++
                    return items
                }
                items += read()
            }
        }

        private fun skip() {
            while (i < text.length) {
                when {
                    text[i].isWhitespace() -> i++
                    text[i] == ';' -> while (i < text.length && text[i] != '\n') i++
                    else -> return
                }
            }
        }
    }
}
