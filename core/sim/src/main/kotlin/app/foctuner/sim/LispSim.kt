package app.foctuner.sim

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_LISP_ERASE_CODE
import app.foctuner.protocol.CommPacketId.COMM_LISP_GET_STATS
import app.foctuner.protocol.CommPacketId.COMM_LISP_PRINT
import app.foctuner.protocol.CommPacketId.COMM_LISP_READ_CODE
import app.foctuner.protocol.CommPacketId.COMM_LISP_SET_RUNNING
import app.foctuner.protocol.CommPacketId.COMM_LISP_STREAM_CODE
import app.foctuner.protocol.CommPacketId.COMM_LISP_WRITE_CODE
import app.foctuner.protocol.Crc16
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * LispBM as the firmware's commands.c, flash_helper.c and lispif.c handle it: a 128 KiB code area that is only
 * used when its length and CRC header check out, an evaluator that is started (with or without the stored
 * code) and paused, stats, the REPL and streaming. Code isn't really run: [LispEval] understands numbers,
 * arithmetic, `def` and `print`, enough for bindings and prints to look like a running script.
 * One instance serves both motors of the board, which share one firmware.
 */
internal class LispSim(private val vesc: SimulatedVesc) {
    private val flash = ByteArray(CODE_AREA) { ERASED }
    private val eval = LispEval(::print, vesc::reply)
    private var running = false
    private var paused = false
    private var lastReplEval: Duration? = null
    private var stream: ByteWriter? = null
    private var streamOffsetLast = -1
    private var streamResultLast = -1

    fun erase() {
        stop()
        flash.fill(ERASED)
        vesc.reply(byteArrayOf(COMM_LISP_ERASE_CODE.id.toByte(), 1))
    }

    fun write(r: ByteReader) {
        val offset = r.u32()
        val data = r.restBytes()
        val ok = offset + data.size <= CODE_AREA
        if (ok) {
            // Flash programming can only clear bits.
            for (i in data.indices) flash[offset.toInt() + i] = (flash[offset.toInt() + i].toInt() and data[i].toInt()).toByte()
        }
        vesc.reply(ByteWriter().u8(COMM_LISP_WRITE_CODE.id).u8(if (ok) 1 else 0).u32(offset).toByteArray())
    }

    fun read(r: ByteReader) {
        val length = r.i32()
        val offset = r.i32()
        val code = storedCode()
        val w = ByteWriter().u8(COMM_LISP_READ_CODE.id)
        if (code == null) {
            vesc.reply(w.i32(0).i32(0).toByteArray())
            return
        }
        if (length + offset > code.size || length > MAX_READ || length < 0 || offset < 0) return
        vesc.reply(w.i32(code.size).i32(offset).bytes(code, offset, length).toByteArray())
    }

    fun setRunning(r: ByteReader) {
        val ok = if (r.u8() != 0) {
            restart(loadCode = true)
        } else {
            if (running) paused = true
            running
        }
        vesc.reply(byteArrayOf(COMM_LISP_SET_RUNNING.id.toByte(), if (ok) 1 else 0))
    }

    /** Like the firmware, no reply at all while the evaluator thread doesn't exist. */
    fun stats(r: ByteReader) {
        if (!running) return
        val all = r.remaining == 0 || r.u8() != 0
        val seconds = vesc.uptime.toDouble(DurationUnit.SECONDS)
        val cpu = if (paused) 0.0 else 2.4 + 0.6 * sin(seconds * 0.7) + 0.05 * eval.globals.size
        val w = ByteWriter().u8(COMM_LISP_GET_STATS.id)
            .f16(cpu.toFloat(), 100f)
            .f16(if (eval.collections > 0) 6f + 0.6f * eval.globals.size else 0f, 100f)
            .f16(3.5f + (storedCode()?.size ?: 0) / 2000f, 100f)
            .f16(0f, 100f)
            .u8(0)
        for ((name, value) in eval.globals) {
            if (w.length > STATS_BINDING_LIMIT) break
            if (value !is Number) continue
            if (all || name.startsWith("vt", ignoreCase = true)) w.cString(name).f32Auto(value.toFloat())
        }
        vesc.reply(w.toByteArray())
    }

    fun repl(r: ByteReader) {
        val now = vesc.uptime
        if (lastReplEval?.let { now - it <= REPL_SPACING } == true) return
        if (!running) restart(loadCode = false)
        val line = r.cString()
        when {
            line.isEmpty() -> print(">")
            line.startsWith(":help") -> HELP.forEach(::print)
            line.startsWith(":info") -> info().forEach(::print)
            line.startsWith(":env") -> eval.globals.forEach { (name, value) -> print("  ($name . ${LispEval.show(value)})") }
            line.startsWith(":reset") -> print(if (restart(loadCode = storedCode() != null)) "Reset OK\n\n" else "Reset Failed\n\n")
            line.startsWith(":pause") -> {
                paused = true
                print("Evaluator paused\n")
            }
            line.startsWith(":continue") -> paused = false
            else -> {
                lastReplEval = now
                eval.evaluate(line)?.let { print("> $it") }
            }
        }
    }

    fun stream(r: ByteReader) {
        val offset = r.i32()
        val total = r.i32()
        val mode = r.i8()
        val chunk = r.restBytes()
        if (offset == 0) {
            when {
                !running -> restart(loadCode = mode == 2)
                mode == 1 -> restart(loadCode = false)
                mode == 2 -> restart(loadCode = true)
            }
        }
        if (offset == streamOffsetLast) return streamReply(offset, streamResultLast)
        streamOffsetLast = offset
        if (!running) {
            streamOffsetLast = -1
            return streamReply(offset, -1)
        }
        if (offset == 0) stream = ByteWriter()
        val buffer = stream ?: return streamReply(offset, -15)
        buffer.bytes(chunk)
        if (offset + chunk.size == total) {
            stream = null
            streamOffsetLast = -1
            print("Stream done, starting...")
            eval.run(buffer.toByteArray().decodeToString())
        }
        streamReply(offset, 0)
    }

    private fun streamReply(offset: Int, result: Int) {
        streamResultLast = result
        vesc.reply(ByteWriter().u8(COMM_LISP_STREAM_CODE.id).i32(offset).i16(result).toByteArray())
    }

    private fun restart(loadCode: Boolean): Boolean {
        val code = storedCode()
        if (loadCode && code == null) return false
        running = true
        paused = false
        stream = null
        eval.reset()
        if (loadCode && code != null) {
            val text = code.copyOfRange(0, code.indexOf(0).let { if (it < 0) code.size else it }).decodeToString()
            print("Parsing ${text.length} characters")
            eval.run(text)
        }
        return true
    }

    private fun stop() {
        running = false
        paused = false
        eval.reset()
    }

    /** The code after the header and flags, or null when the area is erased or the CRC doesn't match. */
    private fun storedCode(): ByteArray? {
        val r = ByteReader(flash)
        val length = r.u32()
        val crc = r.u16()
        if (length > CODE_AREA - HEADER_SIZE) return null
        if (Crc16.compute(flash, FLAGS_OFFSET, length.toInt() + 2) != crc) return null
        return flash.copyOfRange(HEADER_SIZE, HEADER_SIZE + length.toInt())
    }

    private fun info(): List<String> {
        val code = storedCode()?.size ?: 0
        return listOf(
            "--(LISP HEAP)--\n",
            "Heap size: 23432 Bytes\n",
            "Used cons cells: ${120 + eval.globals.size * 8}\n",
            "GC counter: ${eval.collections}\n",
            "Global env cells: ${eval.globals.size}\n",
            "--(Flash)--\n",
            "Code+Import: $code\n",
        )
    }

    private fun print(line: String) {
        vesc.reply(byteArrayOf(COMM_LISP_PRINT.id.toByte()) + line.toByteArray(Charsets.ISO_8859_1))
    }

    private companion object {
        const val CODE_AREA = 128 * 1024
        const val ERASED: Byte = -1
        const val HEADER_SIZE = 8
        const val FLAGS_OFFSET = 6
        const val MAX_READ = 502
        const val STATS_BINDING_LIMIT = 300
        val REPL_SPACING = 500.milliseconds

        val HELP = listOf(
            "== Special Commands ==",
            ":help\n  Print this help text",
            ":info\n  Print info about memory usage, allocated arrays and garbage collection",
            ":env\n  Print current environment and variables",
            ":reset\n  Reset LBM",
            ":pause\n  Pause LBM",
            ":continue\n  Continue running LBM",
            " ",
            "Anything else will be evaluated as an expression in LBM.",
            " ",
        )
    }
}

