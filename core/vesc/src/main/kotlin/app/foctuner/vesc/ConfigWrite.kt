package app.foctuner.vesc

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigCodec
import app.foctuner.config.ConfigDefinition
import app.foctuner.config.ConfigKind
import app.foctuner.config.SignatureMismatchException
import app.foctuner.config.notAppliedIn
import app.foctuner.protocol.CommPacketId
import app.foctuner.protocol.CommPacketId.COMM_SET_APPCONF_NO_STORE
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** A checked write: what the controller stored, and the parameters it didn't take over (hardware limits, a lost write). */
data class ConfigWriteResult(val applied: Config, val notApplied: List<String>)

/**
 * Writes [config] and reads it back, listing what the firmware changed, as VESC Tool does after a motor config write
 * ("Parameters truncated"). Values the firmware clamps to the hardware's limits show up there.
 */
suspend fun VescController.writeConfigChecked(kind: ConfigKind, config: Config): ConfigWriteResult {
    writeConfig(kind, config)
    val applied = readConfig(kind, config)
    return ConfigWriteResult(applied, config.notAppliedIn(applied))
}

/**
 * COMM_SET_APPCONF_NO_STORE: the app config takes effect at once but is not stored, so it lasts until the next reboot
 * or write and doesn't wear the flash. Firmware 6.00 and later.
 */
suspend fun VescController.applyAppConfig(config: Config) {
    val payload = Requests.bare(COMM_SET_APPCONF_NO_STORE) + ConfigCodec.serialize(config)
    session.requestTwice(forwarded(payload), COMM_SET_APPCONF_NO_STORE, session.options.requestTimeout)
}

/** The bundled definition series for firmware [version], or the newest for versions we don't bundle. */
fun bundledSeriesFor(version: String): String =
    version.takeIf { it in BundledDefinitions.versions } ?: BundledDefinitions.versions.last()

/**
 * Reads [kind] with the definition that matches the controller: the series of its firmware [version], or the bundled
 * series whose layout has the signature the controller sent (test builds, forks). [base] supplies the parameters the
 * controller doesn't store, for whichever definition is used.
 *
 * @throws SignatureMismatchException if no bundled layout matches
 */
suspend fun VescController.readMatchingConfig(
    kind: ConfigKind,
    version: String,
    base: (ConfigDefinition) -> Config = Config::defaults,
): Config {
    val definition = BundledDefinitions.load(bundledSeriesFor(version), kind)
    return try {
        readConfig(kind, base(definition))
    } catch (e: SignatureMismatchException) {
        val match = BundledDefinitions.versionWithSignature(kind, e.actual) ?: throw e
        readConfig(kind, base(BundledDefinitions.load(match, kind)))
    }
}

/**
 * Sends [payload] twice back to back, as VESC Tool 7.01 does for config writes so that one packet lost on the way
 * doesn't lose the write. Returns after the first acknowledgement, once the second one has arrived too (or [timeout]
 * passed), so the extra acknowledgement can't be taken for the reply to a later request. Config writes are run one
 * at a time so that two of them can't count each other's acknowledgements.
 */
internal suspend fun VescSession.requestTwice(payload: ByteArray, replyId: CommPacketId, timeout: Duration) = configWrites.withLock {
    coroutineScope {
        val acks = Channel<Unit>(Channel.UNLIMITED)
        val counter = launch(start = CoroutineStart.UNDISPATCHED) {
            incoming.collect { if (it[0].toInt() and 0xFF == replyId.id) acks.send(Unit) }
        }
        try {
            send(payload)
            send(payload)
            withTimeoutOrNull(timeout) { acks.receive() } ?: throw if (connected.value) {
                VescTimeoutException("No reply to $replyId within $timeout")
            } else {
                VescLinkException("Link closed waiting for $replyId")
            }
            withTimeoutOrNull(timeout) { acks.receive() }
        } finally {
            counter.cancel()
        }
    }
}

private val configWrites = Mutex()
