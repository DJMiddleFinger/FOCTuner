package app.foctuner.vesc

import app.foctuner.config.Config
import app.foctuner.config.ConfigCodec
import app.foctuner.config.ConfigKind
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
    /** Null for the attached controller. */
    internal val canId: Int?,
) {
    /** [payload] wrapped for this controller: unchanged for the attached one, in COMM_FORWARD_CAN otherwise. */
    internal fun forwarded(payload: ByteArray): ByteArray = Requests.forwarded(canId, payload)

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
     * Reads the motor or app config. Parameters the controller doesn't store (VESC Tool's motor description
     * fields) keep their value from [base], whose definition must match the controller's layout.
     *
     * @throws app.foctuner.config.SignatureMismatchException if the controller uses a different layout
     */
    suspend fun readConfig(kind: ConfigKind, base: Config): Config = readConfig(kind, base, defaults = false)

    /** The firmware's default config for this hardware, with the controller's current calibration offsets. */
    suspend fun readDefaultConfig(kind: ConfigKind, base: Config): Config = readConfig(kind, base, defaults = true)

    /**
     * Stores [config] on the controller (in flash, so it survives a reboot). The firmware may adjust values to
     * hardware limits, so read it back to see what was applied. A controller with a different layout ignores
     * the write and this times out.
     */
    suspend fun writeConfig(kind: ConfigKind, config: Config) {
        val id = Requests.writeConfigId(kind)
        val payload = Requests.bare(id) + ConfigCodec.serialize(config)
        session.request(Requests.forwarded(canId, payload), id, session.options.configWriteTimeout) { }
    }

    private suspend fun readConfig(kind: ConfigKind, base: Config, defaults: Boolean): Config {
        val id = Requests.readConfigId(kind, defaults)
        return session.request(Requests.forwarded(canId, Requests.bare(id)), id) { r ->
            ConfigCodec.deserialize(base.definition, r.restBytes(), base)
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
