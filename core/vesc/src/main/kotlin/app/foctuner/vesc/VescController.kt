package app.foctuner.vesc

import app.foctuner.protocol.CommPacketId.COMM_FW_VERSION
import app.foctuner.protocol.CommPacketId.COMM_GET_VALUES
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * One controller: the one the transport is attached to, or one reached over CAN through it
 * ([VescClient.can]). Both behave the same; CAN requests are wrapped in COMM_FORWARD_CAN.
 *
 * Requests fail with [VescTimeoutException] if no reply comes (always the case for a CAN id with no
 * device) and with [VescLinkException] once the link is down.
 */
open class VescController internal constructor(
    internal val session: VescSession,
    private val canId: Int?,
) {
    val motor = MotorControl(session, canId)

    suspend fun firmwareInfo(): FirmwareInfo =
        session.request(Requests.forwarded(canId, Requests.firmwareInfo), COMM_FW_VERSION) { FirmwareInfo.parse(it) }

    suspend fun values(): RealtimeValues =
        session.request(Requests.forwarded(canId, Requests.values), COMM_GET_VALUES) { RealtimeValues.parse(it) }

    /**
     * Polls [values] every [period] (start to start, so a slow reply shortens the wait rather than the rate).
     * A request that times out is skipped; the flow only fails if the link goes down.
     */
    fun realtime(period: Duration): Flow<RealtimeValues> = flow {
        while (true) {
            val started = TimeSource.Monotonic.markNow()
            try {
                emit(values())
            } catch (e: VescTimeoutException) {
                // Next poll tries again.
            }
            delay(period - started.elapsedNow())
        }
    }

    /**
     * Runs a firmware terminal command and returns what it printed (without the echo of the command).
     * Output has no end marker, so this returns once nothing has been printed for
     * [VescClientOptions.terminalQuietPeriod]. The echo must arrive within [VescClientOptions.requestTimeout]
     * or this throws [VescTimeoutException].
     */
    suspend fun terminal(command: String): String = session.terminal(canId, command)
}
