package app.foctuner.ui.tools.lisp

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.foctuner.R
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.LispCode
import app.foctuner.vesc.LispException
import app.foctuner.vesc.LispProgram
import app.foctuner.vesc.LispStats
import app.foctuner.vesc.LispStreamMode
import app.foctuner.vesc.REPL_SPACING
import app.foctuner.vesc.VescException
import app.foctuner.vesc.VescLinkException
import app.foctuner.vesc.VescTimeoutException
import app.foctuner.vesc.eraseLisp
import app.foctuner.vesc.lispPrints
import app.foctuner.vesc.lispStats
import app.foctuner.vesc.readLisp
import app.foctuner.vesc.sendLispRepl
import app.foctuner.vesc.setLispRunning
import app.foctuner.vesc.streamLisp
import app.foctuner.vesc.uploadLisp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

enum class LineKind { Print, Input, Info, Error }

/** A console line: LispBM output as sent, or one of our own messages ([message] with [args]). */
@Immutable
data class ConsoleLine(val id: Long, val kind: LineKind, val text: String = "", @param:StringRes val message: Int = 0, val args: List<Any> = emptyList())

enum class Transfer { Upload, Read, Stream, Erase }

/** A transfer in progress; [total] is 0 while the size isn't known (erasing). */
@Immutable
data class Progress(val transfer: Transfer, val done: Int, val total: Int)

/** Something that went wrong and needs reading, shown above the panes until dismissed. */
@Immutable
data class LispNotice(@param:StringRes val title: Int, val detail: String? = null, @param:StringRes val hint: Int = 0)

/**
 * LispBM on the selected controller: console and REPL, upload/stream/read/erase with progress, run and stop,
 * and stats polling. Lives as long as the connection; the code being edited lives in [LispWorkspace].
 */
class LispModel(private val context: ToolContext) {
    private val controller get() = context.controller
    private var nextLine = 0L
    private var job: Job? = null
    private val replQueue = Channel<String>(Channel.UNLIMITED)
    private val samples = HashMap<String, ArrayDeque<Pair<TimeSource.Monotonic.ValueTimeMark, Float>>>()

    val console = mutableStateListOf<ConsoleLine>()
    val replHistory = mutableStateListOf<String>()
    var replInput by mutableStateOf("")
    var progress by mutableStateOf<Progress?>(null)
        private set
    var notice by mutableStateOf<LispNotice?>(null)
    var stats by mutableStateOf<LispStats?>(null)
        private set

    /** The last stats request went unanswered: LispBM hasn't been started since boot. */
    var statsUnanswered by mutableStateOf(false)
        private set

    /** Code read from the controller, waiting for the screen to open it. */
    var readBack by mutableStateOf<LispProgram?>(null)

    /** Bindings picked for plotting, with their values over the last [PLOT_WINDOW]. */
    val plotted = mutableStateMapOf<String, List<Float>>()

    init {
        context.scope.launch { controller.lispPrints.collect { add(LineKind.Print, text = it) } }
        context.scope.launch {
            for (line in replQueue) {
                try {
                    controller.sendLispRepl(line)
                } catch (e: VescException) {
                    failed(e)
                }
                delay(REPL_SPACING + REPL_MARGIN)
            }
        }
    }

    val busy: Boolean get() = progress != null

    fun upload(packed: ByteArray, autoRun: Boolean) = transfer(Transfer.Upload) {
        controller.uploadLisp(packed, context.firmware.hardwareType) { done, total -> progress = Progress(Transfer.Upload, done, total) }
        add(LineKind.Info, message = R.string.lisp_line_uploaded, args = listOf(packed.size))
        if (autoRun) start()
    }

    fun stream(code: String, mode: LispStreamMode) = transfer(Transfer.Stream) {
        val bytes = code.encodeToByteArray()
        controller.streamLisp(bytes, mode) { done, total -> progress = Progress(Transfer.Stream, done, total) }
    }

    fun read() = transfer(Transfer.Read) {
        val stored = controller.readLisp { done, total -> progress = Progress(Transfer.Read, done, total) }
        if (stored.isEmpty()) {
            notice = LispNotice(R.string.lisp_nothing_stored)
        } else {
            readBack = LispCode.unpack(stored)
        }
    }

    fun erase() = transfer(Transfer.Erase) {
        controller.eraseLisp()
        add(LineKind.Info, message = R.string.lisp_line_erased)
    }

    fun run() = command { start() }

    fun stop() = command {
        if (controller.setLispRunning(false)) {
            add(LineKind.Info, message = R.string.lisp_line_stopped)
        } else {
            notice = LispNotice(R.string.lisp_stop_failed)
        }
    }

    /** Stops the transfer in progress after the chunk in flight. */
    fun cancel() {
        job?.cancel()
    }

    /** Sends a REPL line. Lines are spaced so the firmware doesn't drop one that follows an expression too soon. */
    fun send(line: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        replHistory.remove(text)
        replHistory.add(0, text)
        while (replHistory.size > MAX_HISTORY) replHistory.removeAt(replHistory.lastIndex)
        add(LineKind.Input, text = text)
        replQueue.trySend(text)
    }

    /** VESC Tool's "run block": short code goes to the REPL, longer code is streamed on top of the running program. */
    fun evaluate(code: String) {
        if (code.length > REPL_BLOCK_LIMIT) stream(code, LispStreamMode.LoadOnTop) else send(code)
    }

    fun clearConsole() {
        console.clear()
    }

    fun togglePlot(name: String) {
        if (plotted.remove(name) == null) plotted[name] = emptyList() else samples.remove(name)
    }

    /**
     * Polls stats at [rate] Hz while called from a running effect. Each request pauses the evaluator briefly
     * (up to 2 s in the firmware while it reads the globals), so polling is off unless asked for, and skipped
     * during transfers.
     */
    suspend fun poll(mode: PollMode, rate: Int) {
        if (mode == PollMode.Off) return
        while (true) {
            val started = TimeSource.Monotonic.markNow()
            if (!busy) refresh(all = mode == PollMode.All)
            delay((1000L / rate.coerceAtLeast(1)).milliseconds - started.elapsedNow())
        }
    }

    suspend fun refresh(all: Boolean = true) {
        try {
            val next = controller.lispStats(all)
            stats = next
            statsUnanswered = false
            record(next)
        } catch (e: VescTimeoutException) {
            statsUnanswered = true
        } catch (e: VescException) {
            failed(e)
        }
    }

    private suspend fun start() {
        if (controller.setLispRunning(true)) {
            add(LineKind.Info, message = R.string.lisp_line_started)
        } else {
            notice = LispNotice(R.string.lisp_start_failed, hint = R.string.lisp_start_failed_hint)
        }
    }

    private fun record(next: LispStats) {
        val now = TimeSource.Monotonic.markNow()
        for (name in plotted.keys.toList()) {
            val value = next.bindings.firstOrNull { it.name == name }?.value ?: continue
            val list = samples.getOrPut(name) { ArrayDeque() }
            list.addLast(now to value)
            while (list.isNotEmpty() && list.first().first.elapsedNow() > PLOT_WINDOW) list.removeFirst()
            plotted[name] = list.map { it.second }
        }
    }

    private fun transfer(kind: Transfer, block: suspend () -> Unit) {
        if (busy) return
        notice = null
        progress = Progress(kind, 0, 0)
        job = context.scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                if (kind == Transfer.Upload) notice = LispNotice(R.string.lisp_upload_cancelled, hint = R.string.lisp_upload_cancelled_hint)
                throw e
            } catch (e: VescException) {
                failed(e)
            } finally {
                progress = null
                job = null
            }
        }
    }

    private fun command(block: suspend () -> Unit) {
        context.scope.launch {
            try {
                block()
            } catch (e: VescException) {
                failed(e)
            }
        }
    }

    private fun failed(e: VescException) {
        notice = when (e) {
            is LispException -> LispNotice(R.string.lisp_failed, e.message)
            is VescTimeoutException -> LispNotice(R.string.lisp_no_reply, hint = R.string.lisp_no_reply_hint)
            is VescLinkException -> LispNotice(R.string.lisp_link_closed)
            else -> LispNotice(R.string.lisp_failed, e.message)
        }
    }

    private fun add(kind: LineKind, text: String = "", @StringRes message: Int = 0, args: List<Any> = emptyList()) {
        console += ConsoleLine(nextLine++, kind, text, message, args)
        if (console.size > MAX_LINES) console.removeRange(0, console.size - MAX_LINES)
    }

    private companion object {
        /** VESC Tool's default for scripting/replMaxLines. */
        const val MAX_LINES = 5000
        const val MAX_HISTORY = 30

        /** VESC Tool streams a "run block" selection longer than this instead of sending it to the REPL. */
        const val REPL_BLOCK_LIMIT = 400
        val REPL_MARGIN = 100.milliseconds

        /** VESC Tool's binding plot shows the last 5 s. */
        val PLOT_WINDOW = 5.seconds
    }
}
