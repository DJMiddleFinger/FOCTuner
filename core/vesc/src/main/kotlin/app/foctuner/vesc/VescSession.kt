package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.CommPacketId
import app.foctuner.protocol.CommPacketId.COMM_PING_CAN
import app.foctuner.protocol.CommPacketId.COMM_PRINT
import app.foctuner.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * Everything one connection shares: the packet link, the request/reply bookkeeping and the motor keepalive.
 * [VescController] views (the attached controller and CAN controllers behind it) are thin wrappers.
 *
 * Replies carry no sender, so a reply can't be matched to a CAN controller. Requests that expect the same
 * reply id are therefore run one at a time, and the first packet with that id after the request is its reply.
 *
 * The session owns [transport]: it is closed by [close] and when [parent] is cancelled.
 */
internal class VescSession(
    private val transport: Transport,
    parent: CoroutineScope,
    val options: VescClientOptions,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val link = PacketLink(transport, options.frameIdleTimeout)

    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()
    private val replyLocks = ConcurrentHashMap<Int, Mutex>()

    /** The firmware runs terminal and CAN-ping commands in one thread and discards a new one while busy. */
    private val blockingCommandLock = Mutex()

    private val _prints = MutableSharedFlow<String>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val prints: SharedFlow<String> = _prints.asSharedFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Every packet that arrives (id byte first), for features that receive streams or unsolicited data such as
     * LispBM prints or sampled data. Subscribe before sending the request that starts the stream.
     */
    val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    private val _connected = MutableStateFlow(true)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** CAN ids (null for the attached controller) that have been given a motor command and not released. */
    private val activeMotors = MutableStateFlow<Set<Int?>>(emptySet())

    /**
     * Held for every change to [activeMotors], [leases] and [stopLockout], so a lease that runs out can't
     * release a command that replaced it a moment earlier.
     */
    private val motorLock = Mutex()
    private val leases = HashMap<Int?, Job>()
    private var stopLockout: Job? = null

    private val guardian: Job

    init {
        scope.launch { readLoop() }
        scope.launch { keepAlive() }
        // Started immediately so that cancelling the scope right away still releases and closes.
        guardian = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { shutDown() }
            }
        }
    }

    suspend fun <T> request(
        payload: ByteArray,
        replyId: CommPacketId,
        timeout: Duration = options.requestTimeout,
        parse: (ByteReader) -> T,
    ): T = replyLocks.computeIfAbsent(replyId.id) { Mutex() }.withLock {
        val reply = CompletableDeferred<ByteArray>()
        waiting[replyId.id] = reply
        try {
            send(payload)
            val packet = withTimeoutOrNull(timeout) { reply.await() }
                ?: throw noReply("$replyId within $timeout")
            try {
                parse(ByteReader(packet, 1))
            } catch (e: IndexOutOfBoundsException) {
                throw VescException("Malformed $replyId reply (${packet.size} bytes)", e)
            }
        } finally {
            waiting.remove(replyId.id)
        }
    }

    suspend fun send(payload: ByteArray) {
        if (!_connected.value) throw VescLinkException("Link closed")
        link.send(payload)
    }

    /**
     * Runs [block] while no other command that the firmware handles in its blocking thread (terminal, CAN ping,
     * motor detection, ...) is in flight; the firmware silently drops such a command while another one runs.
     */
    suspend fun <T> blocking(block: suspend () -> T): T = blockingCommandLock.withLock { block() }

    suspend fun pingCan(): List<Int> = blocking {
        request(Requests.pingCan, COMM_PING_CAN, options.pingCanTimeout) { r -> List(r.remaining) { r.u8() } }
    }

    /**
     * Runs a terminal command and returns what it printed, without the echo of the command itself.
     * The reply is a series of COMM_PRINT packets with no end marker; see [VescClientOptions.terminalQuietPeriod].
     */
    suspend fun terminal(canId: Int?, command: String): String = blocking {
        coroutineScope {
            val lines = Channel<String>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { prints.collect { lines.send(it) } }
            try {
                send(Requests.forwarded(canId, Requests.terminal(command)))
                val echo = withTimeoutOrNull(options.requestTimeout) { lines.receive() }
                    ?: throw noReply("terminal command '$command' within ${options.requestTimeout}")
                val output = mutableListOf<String>()
                if (!echo.startsWith("-> ")) output += echo
                while (true) {
                    output += withTimeoutOrNull(options.terminalQuietPeriod) { lines.receive() } ?: break
                }
                output.joinToString("\n") { it.trimEnd('\n') }.trimEnd()
            } finally {
                collector.cancel()
            }
        }
    }

    /** Sends [command] and keeps it alive until released, or until [holdFor] passes without a newer command. */
    suspend fun motorCommand(canId: Int?, command: ByteArray, holdFor: Duration? = null) = motorLock.withLock {
        if (!_connected.value) throw VescLinkException("Link closed")
        if (stopLockout?.isActive == true) throw VescException("Motor commands are blocked after an emergency stop")
        leases.remove(canId)?.cancel()
        activeMotors.update { it + canId }
        send(Requests.forwarded(canId, command))
        if (holdFor != null) leases[canId] = scope.launch { delay(holdFor); expireLease(canId) }
    }

    suspend fun releaseMotor(canId: Int?) = motorLock.withLock {
        leases.remove(canId)?.cancel()
        activeMotors.update { it - canId }
        send(Requests.forwarded(canId, Requests.release))
    }

    /**
     * Releases every motor this session drives and the attached controller's, then sends COMM_MOTOR_ESTOP to
     * each of them. Our own motor commands are refused for [duration] too, which also covers firmware that
     * doesn't know COMM_MOTOR_ESTOP. Every send is attempted before a failure is reported.
     */
    suspend fun emergencyStop(duration: Duration) = motorLock.withLock {
        if (!_connected.value) throw VescLinkException("Link closed")
        stopLockout = scope.launch { delay(duration) }
        leases.values.forEach { it.cancel() }
        leases.clear()
        val targets = activeMotors.value + null
        activeMotors.value = emptySet()
        var failure: IOException? = null
        for (canId in targets) {
            for (payload in listOf(Requests.release, Requests.emergencyStop(duration))) {
                try {
                    send(Requests.forwarded(canId, payload))
                } catch (e: IOException) {
                    failure = failure ?: e
                }
            }
        }
        if (failure != null) throw VescLinkException("Emergency stop may not have reached every controller", failure)
    }

    private suspend fun expireLease(canId: Int?) = motorLock.withLock {
        leases.remove(canId)
        activeMotors.update { it - canId }
        trySend(Requests.forwarded(canId, Requests.release))
    }

    /** Releases every active motor, then closes the transport. */
    suspend fun close() {
        guardian.cancelAndJoin()
        scope.cancel()
    }

    private fun noReply(what: String): VescException =
        if (_connected.value) VescTimeoutException("No reply to $what") else VescLinkException("Link closed waiting for $what")

    private suspend fun readLoop() {
        val failure = try {
            link.receive(::dispatch)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e
        }
        markClosed(failure)
    }

    private fun dispatch(packet: ByteArray) {
        _incoming.tryEmit(packet)
        val id = packet[0].toInt() and 0xFF
        if (id == COMM_PRINT.id) {
            _prints.tryEmit(String(packet, 1, packet.size - 1, Charsets.ISO_8859_1))
        } else {
            waiting[id]?.complete(packet)
        }
    }

    /**
     * Sends COMM_ALIVE to every controller with an active motor command, which keeps the firmware's command
     * timeout (1 s by default) from releasing it. Idle while nothing is active. The keepalive never repeats
     * a command, so a command can't outlive its [releaseMotor] by more than the packet in flight.
     */
    private suspend fun keepAlive() {
        while (true) {
            activeMotors.first { it.isNotEmpty() }
            delay(options.keepaliveInterval)
            for (canId in activeMotors.value) trySend(Requests.forwarded(canId, Requests.alive))
        }
    }

    private suspend fun trySend(payload: ByteArray) = quietly { send(payload) }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A missed keepalive or release is covered by the next tick and by the firmware's own timeout,
            // and a transport that fails to close is going away either way.
        }
    }

    private suspend fun shutDown() {
        withTimeoutOrNull(options.shutdownTimeout) {
            for (canId in activeMotors.value) trySend(Requests.forwarded(canId, Requests.release))
        }
        withTimeoutOrNull(options.shutdownTimeout) { quietly { transport.close() } }
        markClosed(null)
    }

    private fun markClosed(cause: Throwable?) {
        if (!_connected.compareAndSet(expect = true, update = false)) return
        activeMotors.value = emptySet()
        val failure = VescLinkException("Link closed", cause)
        waiting.values.forEach { it.completeExceptionally(failure) }
    }
}
