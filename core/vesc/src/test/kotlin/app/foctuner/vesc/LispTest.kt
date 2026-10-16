package app.foctuner.vesc

import app.foctuner.protocol.CommPacketId.COMM_LISP_WRITE_CODE
import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LispTest {
    private val program = """
        (import "logo.bin" 'logo)
        (def vt-speed 12.5)
        (def counter 3)
        (print "hello from lisp")
        (defun main () (loopwhile t (sleep 1)))
    """.trimIndent() + "\n" + (1..120).joinToString("\n") { "(def padding-$it $it) ; keeps the upload past one chunk" }

    private val logo = ByteArray(700) { (it * 7).toByte() }

    private fun TestScope.client(transport: SimulatedVesc = SimulatedVesc(testTimeSource)) = VescClient(transport, backgroundScope)

    private fun TestScope.prints(controller: VescController): Channel<String> {
        val lines = Channel<String>(Channel.UNLIMITED)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { controller.lispPrints.collect { lines.send(it) } }
        return lines
    }

    private suspend fun Channel<String>.next() = withTimeout(5.seconds) { receive() }

    @Test
    fun uploadsInChunksOverBleAndReadsTheSameCodeAndImportsBack() = runTest {
        val transport = RecordingTransport(SimulatedVesc(testTimeSource, chunkSize = 20))
        val client = VescClient(transport, backgroundScope)
        val packed = LispCode.pack(program) { logo }
        val progress = mutableListOf<Int>()

        client.uploadLisp(packed) { written, total -> progress += written; assertEquals(packed.size + 6, total) }

        val writes = transport.packets.filter { it[0].toInt() and 0xFF == COMM_LISP_WRITE_CODE.id }
        assertTrue(writes.size > 1 && writes.all { it.size <= 1 + 4 + 384 })
        assertEquals(packed.size + 6, progress.last())

        val stored = client.readLisp()
        assertContentEquals(packed.copyOfRange(2, packed.size), stored)
        val back = LispCode.unpack(stored)
        assertEquals(program, back.code)
        assertContentEquals(logo, back.imports["logo"])
    }

    @Test
    fun eraseLeavesNothingToReadOrRun() = runTest {
        val client = client()
        client.uploadLisp(LispCode.pack("(def a 1)"))
        assertTrue(client.readLisp().isNotEmpty())

        client.eraseLisp()

        assertEquals(0, client.readLisp().size)
        assertFalse(client.setLispRunning(true))
    }

    @Test
    fun tinyCodeReadsBack() = runTest {
        val client = client()
        client.uploadLisp(LispCode.pack("1"))
        assertEquals("1", LispCode.unpack(client.readLisp()).code)
    }

    @Test
    fun refusesCodeLargerThanTheCodeArea() = runTest {
        val client = client()
        assertFailsWith<LispException> { client.uploadLisp(ByteArray(128 * 1024)) }
    }

    @Test
    fun runPrintsAndReportsBindingsAndStopPauses() = runTest {
        val client = client()
        val lines = prints(client)
        client.uploadLisp(LispCode.pack(program) { logo })

        assertTrue(client.setLispRunning(true))
        assertTrue(lines.next().startsWith("Parsing "))
        assertEquals("hello from lisp", lines.next())

        val stats = client.lispStats()
        assertTrue(stats.cpuUse > 0f && stats.memoryUse > 0f)
        assertEquals(LispBinding("vt-speed", 12.5f), stats.bindings[0])
        assertEquals(LispBinding("counter", 3f), stats.bindings[1])
        assertEquals(listOf("vt-speed"), client.lispStats(allBindings = false).bindings.map { it.name })

        assertTrue(client.setLispRunning(false))
        assertEquals(0f, client.lispStats().cpuUse)
    }

    @Test
    fun statsGoUnansweredUntilLispBmRuns() = runTest {
        val client = client()
        assertFailsWith<VescTimeoutException> { client.lispStats() }
        assertFalse(client.setLispRunning(false))
    }

    @Test
    fun replEvaluatesExpressionsAndRunsCommands() = runTest {
        val client = client()
        val lines = prints(client)

        client.sendLispRepl("(def speed (* 2 21))")
        assertEquals("> speed", lines.next())
        delay(REPL_SPACING * 1.2)
        client.sendLispRepl("(+ speed 0.5)")
        assertEquals("> 42.5f32", lines.next())
        delay(REPL_SPACING * 1.2)
        client.sendLispRepl(":help")
        assertEquals("== Special Commands ==", lines.next())

        assertEquals(LispBinding("speed", 42f), client.lispStats().bindings.single())
    }

    @Test
    fun replLinesSentTooSoonAfterAnExpressionAreDropped() = runTest {
        val client = client()
        val lines = prints(client)
        client.sendLispRepl("(+ 1 2)")
        assertEquals("> 3", lines.next())
        client.sendLispRepl("(+ 2 2)")
        delay(REPL_SPACING * 1.2)
        client.sendLispRepl("(+ 3 3)")
        assertEquals("> 6", lines.next())
    }

    @Test
    fun streamsCodeWithoutStoringIt() = runTest {
        val client = client()
        val lines = prints(client)
        val code = (1..20).joinToString("\n") { "(def s$it $it) ; long enough to need several chunks" }.encodeToByteArray()
        var sent = 0

        client.streamLisp(code, LispStreamMode.ResetAndLoad) { s, _ -> sent = s }

        assertEquals(code.size, sent)
        assertEquals("Stream done, starting...", lines.next())
        assertEquals(20, client.lispStats().bindings.size)
        assertEquals(0, client.readLisp().size)
    }

    @Test
    fun theSecondMotorOfADualBoardSharesOneLispBm() = runTest {
        val client = client()
        client.can(1).uploadLisp(LispCode.pack("(def shared 7)"))
        assertTrue(client.setLispRunning(true))
        assertEquals(LispBinding("shared", 7f), client.can(1).lispStats().bindings.single())
    }

    @Test
    fun aCanIdWithoutAControllerTimesOut() = runTest {
        val client = client()
        assertFailsWith<VescTimeoutException> { client.can(9).eraseLisp() }
    }
}
