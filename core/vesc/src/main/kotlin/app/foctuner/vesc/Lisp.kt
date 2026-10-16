package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId
import app.foctuner.protocol.CommPacketId.COMM_LISP_ERASE_CODE
import app.foctuner.protocol.CommPacketId.COMM_LISP_GET_STATS
import app.foctuner.protocol.CommPacketId.COMM_LISP_PRINT
import app.foctuner.protocol.CommPacketId.COMM_LISP_READ_CODE
import app.foctuner.protocol.CommPacketId.COMM_LISP_REPL_CMD
import app.foctuner.protocol.CommPacketId.COMM_LISP_SET_RUNNING
import app.foctuner.protocol.CommPacketId.COMM_LISP_STREAM_CODE
import app.foctuner.protocol.CommPacketId.COMM_LISP_WRITE_CODE
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * COMM_LISP_GET_STATS. Percentages: [cpuUse] of the evaluator thread since the previous request, [heapUse] of
 * cons cells after the last garbage collection (0 until the first one), [memoryUse] of array and symbol memory.
 * [bindings] are the global variables that hold numbers; the firmware stops adding them after about 300 bytes.
 */
data class LispStats(val cpuUse: Float, val heapUse: Float, val memoryUse: Float, val bindings: List<LispBinding>) {
    companion object {
        fun parse(r: ByteReader): LispStats {
            val cpu = r.f16(100f)
            val heap = r.f16(100f)
            val memory = r.f16(100f)
            r.f16(100f) // stack use, always 0
            r.cString() // result of the last context, always empty
            val bindings = buildList { while (r.remaining > 0) add(LispBinding(r.cString(), r.f32Auto())) }
            return LispStats(cpu, heap, memory, bindings)
        }
    }
}

data class LispBinding(val name: String, val value: Float)

/** What happens to the running program when streaming starts (VESC Tool's "After Stream" choices). */
enum class LispStreamMode {
    /** Evaluate the streamed code on top of what is running. */
    LoadOnTop,

    /** Restart LispBM empty, then evaluate the streamed code. */
    ResetAndLoad,

    /** Restart LispBM with the code stored in flash, then evaluate the streamed code on top. */
    ReloadFlashAndLoad,
}

/** The firmware refused a LispBM command; [message] says which and, where known, why. */
class LispException(message: String) : VescException(message)

/**
 * Lines printed by LispBM (`print`, REPL results and errors), one COMM_LISP_PRINT each, without the trailing newline.
 * Prints carry no sender: with several controllers on CAN, this has the prints of every controller whose
 * LispBM last heard from this connection.
 */
val VescController.lispPrints: Flow<String>
    get() = session.incoming
        .filter { it[0].toInt() and 0xFF == COMM_LISP_PRINT.id }
        .map { String(it, 1, it.size - 1, Charsets.ISO_8859_1) }

/**
 * Stops LispBM and erases the stored code, so nothing runs on the next boot. Erasing a flash sector takes
 * seconds on an STM32. [size] is how much the next upload needs; STM32 firmware always erases the whole area.
 */
suspend fun VescController.eraseLisp(size: Int = ERASE_ONLY_SIZE) {
    val request = ByteWriter().u8(COMM_LISP_ERASE_CODE.id).i32(size).toByteArray()
    val ok = exchange(request, COMM_LISP_ERASE_CODE, ERASE_TIMEOUT) { it.u8() != 0 }
        ?: throw noLispReply(COMM_LISP_ERASE_CODE, ERASE_TIMEOUT)
    if (!ok) throw LispException("Erasing the LispBM code failed")
}

/**
 * Stores [packed] code (see [LispCode.pack]) in flash: erases the code area, then writes it in chunks, each
 * acknowledged before the next is sent and retried a few times like VESC Tool does. The code starts on the
 * next boot or [setLispRunning]. [onProgress] gets bytes written and the total.
 *
 * @throws LispException if the code doesn't fit or a write is refused
 */
suspend fun VescController.uploadLisp(
    packed: ByteArray,
    hardwareType: HardwareType = HardwareType.VESC,
    onProgress: (written: Int, total: Int) -> Unit = { _, _ -> },
) {
    val image = LispCode.image(packed)
    val max = LispCode.maxImageSize(hardwareType)
    if (image.size > max) throw LispException("The code takes ${image.size} bytes, the controller has room for $max")
    eraseLisp(packed.size + ERASE_MARGIN)
    var offset = 0
    onProgress(0, image.size)
    while (offset < image.size) {
        val chunk = image.copyOfRange(offset, minOf(offset + CHUNK_SIZE, image.size))
        val request = ByteWriter(chunk.size + 5).u8(COMM_LISP_WRITE_CODE.id).u32(offset.toLong()).bytes(chunk).toByteArray()
        val expected = offset.toLong()
        val ok = retrying(WRITE_TRIES) {
            exchange(request, COMM_LISP_WRITE_CODE, WRITE_TIMEOUT) { r ->
                val ok = r.u8() != 0
                if (r.u32() == expected) ok else null
            }
        } ?: throw noLispReply(COMM_LISP_WRITE_CODE, WRITE_TIMEOUT)
        if (!ok) throw LispException("Writing the LispBM code failed at byte $offset")
        offset += chunk.size
        onProgress(offset, image.size)
    }
}

/**
 * Reads the stored code back as the controller holds it (code and imports; [LispCode.unpack] splits them).
 * Empty if nothing valid is stored. [onProgress] gets bytes read and the total.
 */
suspend fun VescController.readLisp(onProgress: (read: Int, total: Int) -> Unit = { _, _ -> }): ByteArray {
    // A zero-length probe learns the size; the firmware ignores a request that reaches past the end of the code.
    val total = readChunk(0, 0)?.first ?: throw noLispReply(COMM_LISP_READ_CODE, READ_TIMEOUT)
    val data = ByteWriter(total)
    onProgress(0, total)
    while (data.length < total) {
        val size = minOf(READ_CHUNK_SIZE, total - data.length)
        val (_, chunk) = readChunk(data.length, size) ?: throw noLispReply(COMM_LISP_READ_CODE, READ_TIMEOUT)
        if (chunk.isEmpty()) throw LispException("The controller sent no code at byte ${data.length}")
        data.bytes(chunk)
        onProgress(data.length, total)
    }
    return data.toByteArray()
}

/**
 * Starts LispBM with the stored code (restarting it if it runs) or pauses it. Returns false if the firmware
 * refused: there is no valid code to start, or LispBM isn't running and so can't be paused.
 */
suspend fun VescController.setLispRunning(running: Boolean): Boolean {
    val request = byteArrayOf(COMM_LISP_SET_RUNNING.id.toByte(), if (running) 1 else 0)
    return exchange(request, COMM_LISP_SET_RUNNING, RUN_TIMEOUT) { it.u8() != 0 }
        ?: throw noLispReply(COMM_LISP_SET_RUNNING, RUN_TIMEOUT)
}

/**
 * Reads CPU, memory and global numeric bindings; without [allBindings], only bindings whose names start with
 * "vt". The firmware doesn't answer before LispBM has been started once, so this times out then.
 */
suspend fun VescController.lispStats(allBindings: Boolean = true): LispStats {
    val request = byteArrayOf(COMM_LISP_GET_STATS.id.toByte(), if (allBindings) 1 else 0)
    return exchange(request, COMM_LISP_GET_STATS, STATS_TIMEOUT) { LispStats.parse(it) }
        ?: throw noLispReply(COMM_LISP_GET_STATS, STATS_TIMEOUT)
}

/**
 * Sends a line to the LispBM REPL. Output arrives on [lispPrints]: the value of an expression that finishes
 * within 0.5 s as "> value", errors, and the output of commands such as `:help`, `:info` or `:env`. The firmware
 * starts LispBM (without the stored code) if it isn't running. It drops a line that arrives within
 * [REPL_SPACING] of the previous expression, so callers sending several must space them.
 */
suspend fun VescController.sendLispRepl(line: String) {
    session.send(forwarded(ByteWriter().u8(COMM_LISP_REPL_CMD.id).cString(line).toByteArray()))
}

/**
 * Evaluates [code] without storing it: faster than an upload and gone after a reboot. Imports don't work.
 * The firmware evaluates the program as it arrives and prints "Stream done, starting..." at the end.
 *
 * @throws LispException with the firmware's result code if it refuses a chunk
 */
suspend fun VescController.streamLisp(
    code: ByteArray,
    mode: LispStreamMode = LispStreamMode.LoadOnTop,
    onProgress: (sent: Int, total: Int) -> Unit = { _, _ -> },
) {
    var offset = 0
    onProgress(0, code.size)
    while (offset < code.size) {
        val chunk = code.copyOfRange(offset, minOf(offset + CHUNK_SIZE, code.size))
        val request = ByteWriter(chunk.size + 10).u8(COMM_LISP_STREAM_CODE.id)
            .i32(offset).i32(code.size).i8(mode.ordinal).bytes(chunk).toByteArray()
        val expected = offset
        val result = exchange(request, COMM_LISP_STREAM_CODE, STREAM_TIMEOUT) { r ->
            if (r.i32() == expected) r.i16() else null
        } ?: throw noLispReply(COMM_LISP_STREAM_CODE, STREAM_TIMEOUT)
        if (result != 0) throw LispException("Streaming failed: ${streamFailure(result)}")
        offset += chunk.size
        onProgress(offset, code.size)
    }
}

/** The firmware doesn't evaluate REPL lines that arrive this soon after the previous expression. */
val REPL_SPACING: Duration = 500.milliseconds

private suspend fun VescController.readChunk(offset: Int, size: Int): Pair<Int, ByteArray>? {
    val request = ByteWriter().u8(COMM_LISP_READ_CODE.id).i32(size).i32(offset).toByteArray()
    return retrying(READ_TRIES) {
        exchange(request, COMM_LISP_READ_CODE, READ_TIMEOUT) { r ->
            val total = r.i32()
            if (r.i32() == offset) total to r.restBytes() else null
        }
    }
}

/**
 * Sends [payload] and returns the first reply with [replyId] that [parse] accepts (returns non-null for), or
 * null after [timeout]. Replies to these commands carry the offset they belong to, so a late reply to an
 * earlier attempt is skipped instead of being taken for the current one.
 */
private suspend fun <T : Any> VescController.exchange(
    payload: ByteArray,
    replyId: CommPacketId,
    timeout: Duration,
    parse: (ByteReader) -> T?,
): T? = coroutineScope {
    val reply = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeoutOrNull(timeout) {
            session.incoming.mapNotNull { packet ->
                if (packet[0].toInt() and 0xFF != replyId.id) return@mapNotNull null
                try {
                    parse(ByteReader(packet, 1))
                } catch (e: IndexOutOfBoundsException) {
                    null
                }
            }.first()
        }
    }
    session.send(forwarded(payload))
    reply.await()
}

private inline fun <T : Any> retrying(tries: Int, attempt: () -> T?): T? {
    repeat(tries) { attempt()?.let { return it } }
    return null
}

private fun VescController.noLispReply(id: CommPacketId, timeout: Duration): VescException =
    if (session.connected.value) VescTimeoutException("No reply to $id within $timeout") else VescLinkException("Link closed waiting for $id")

private fun streamFailure(result: Int): String = when (result) {
    -1 -> "LispBM is not running"
    -2 -> "the previous stream did not close"
    -3 -> "LispBM could not be paused"
    -4 -> "evaluation could not start"
    -5 -> "LispBM stopped reading"
    -6 -> "the reader closed early, probably a syntax error"
    -15 -> "the stream was interrupted; start again"
    else -> "result $result"
}

/** Code and stream chunk; with the header the packet stays under the firmware's 512-byte limit, CAN forwarding included. */
private const val CHUNK_SIZE = 384

/** Read chunk as VESC Tool uses; the firmware refuses more than 502. */
private const val READ_CHUNK_SIZE = 400

/** VESC Tool erases 16 bytes for the Erase button and code size plus 100 before an upload. */
private const val ERASE_ONLY_SIZE = 16
private const val ERASE_MARGIN = 100

private const val WRITE_TRIES = 5
private const val READ_TRIES = 5

/** VESC Tool's waits: 8 s for the erase, 1 s per written chunk, 1.5 s per read chunk, 4 s per streamed chunk. */
private val ERASE_TIMEOUT = 8.seconds
private val WRITE_TIMEOUT = 1.seconds
private val READ_TIMEOUT = 1500.milliseconds
private val STREAM_TIMEOUT = 4.seconds

/** Pausing waits up to 2 s for the evaluator; stats pause it too. */
private val RUN_TIMEOUT = 4.seconds
private val STATS_TIMEOUT = 3.seconds
