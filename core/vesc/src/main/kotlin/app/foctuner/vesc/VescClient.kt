package app.foctuner.vesc

import app.foctuner.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A connection to one VESC over [transport]; as a [VescController] it talks to the attached controller.
 *
 * The client owns the transport and runs its coroutines in [scope]. [close] and cancelling [scope] both
 * release any motor with an active command and then close the transport.
 */
class VescClient private constructor(session: VescSession) : VescController(session, null) {
    constructor(
        transport: Transport,
        scope: CoroutineScope,
        options: VescClientOptions = VescClientOptions(),
    ) : this(VescSession(transport, scope, options))

    /** False once the link has closed or failed. */
    val connected: StateFlow<Boolean> get() = session.connected

    /**
     * Every line the controller prints (COMM_PRINT), including the output of [terminal] commands and prints
     * from CAN controllers. Slow collectors lose the oldest lines.
     */
    val prints: SharedFlow<String> get() = session.prints

    /** CAN ids that answer on the controller's bus, not including the controller itself. */
    suspend fun pingCan(): List<Int> = session.pingCan()

    /** The controller with CAN id [controllerId] (0..254), reached through the attached one. */
    fun can(controllerId: Int): VescController {
        require(controllerId in 0..254) { "CAN id must be within 0..254, was $controllerId" }
        return VescController(session, controllerId)
    }

    suspend fun close() = session.close()
}
