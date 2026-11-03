package app.foctuner.vesc

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_NRF_START_PAIRING
import app.foctuner.protocol.CommPacketId.COMM_SET_BLE_NAME
import app.foctuner.protocol.CommPacketId.COMM_SET_BLE_PIN
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Whether the controller's nRF module takes a new Bluetooth name and PIN ([FirmwareInfo.nrfFlags] bits 0 and 1). */
val FirmwareInfo.bleModuleConfigurable: Boolean get() = nrfFlags and 0b11 == 0b11

/** The Bluetooth name as VESC Tool sends it: at most 25 characters; under 2 means "back to the module's default". */
fun bleNameOrDefault(name: String): String = name.trim().take(BLE_NAME_MAX)

/**
 * The PIN as VESC Tool sends it: digits only, left-padded with zeros to 6 digits; empty turns the PIN off.
 *
 * @throws IllegalArgumentException if [pin] has anything but digits or more than 6 of them
 */
fun blePin(pin: String): String {
    val digits = pin.trim()
    require(digits.all { it in '0'..'9' }) { "The PIN can only have digits" }
    require(digits.length <= BLE_PIN_LENGTH) { "The PIN has at most $BLE_PIN_LENGTH digits" }
    return if (digits.isEmpty()) "" else digits.padStart(BLE_PIN_LENGTH, '0')
}

/**
 * COMM_SET_BLE_NAME: the firmware passes it to the nRF module, which restarts with the new name; a Bluetooth link
 * to this controller drops. There is no reply. [name] goes through [bleNameOrDefault].
 */
suspend fun VescController.setBleName(name: String) {
    session.send(forwarded(ByteWriter().u8(COMM_SET_BLE_NAME.id).cString(bleNameOrDefault(name)).toByteArray()))
}

/** COMM_SET_BLE_PIN, like [setBleName]; [pin] goes through [blePin]. */
suspend fun VescController.setBlePin(pin: String) {
    session.send(forwarded(ByteWriter().u8(COMM_SET_BLE_PIN.id).cString(blePin(pin)).toByteArray()))
}

enum class NrfPairingResult {
    /** A VESC Remote paired; the controller stored its address. */
    Paired,

    /** The window ran out before a remote asked to pair. */
    TimedOut,

    /** The controller didn't say it started: no nRF radio, or firmware without remote pairing. */
    NotSupported,

    /** It started but never said how it ended. VESC Tool reads this as hardware that can't pair a remote. */
    NoAnswer,
}

/**
 * COMM_NRF_START_PAIRING: the controller listens for a VESC Remote for [window] (VESC Tool offers 1 to 30 s,
 * 10 s by default). The firmware replies "started" at once and "paired" or "failed" when the window ends; this
 * waits for both, plus [grace] for a late answer. [onStarted] runs when the controller says it is listening.
 * Replies through CAN carry no sender, so pair one controller at a time.
 */
suspend fun VescController.pairNrfRemote(
    window: Duration = 10.seconds,
    grace: Duration = 2.seconds,
    onStarted: () -> Unit = {},
): NrfPairingResult = coroutineScope {
    require(window in 1.seconds..30.seconds) { "The pairing window must be within 1 to 30 s" }
    val replies = async(start = CoroutineStart.UNDISPATCHED) {
        session.incoming
            .filter { it.size >= 2 && (it[0].toInt() and 0xFF) == COMM_NRF_START_PAIRING.id }
            .map { it[1].toInt() }
            .first { it != NRF_PAIR_STARTED }
    }
    val started = async(start = CoroutineStart.UNDISPATCHED) {
        session.incoming.filter { it.size >= 2 && (it[0].toInt() and 0xFF) == COMM_NRF_START_PAIRING.id && it[1].toInt() == NRF_PAIR_STARTED }
            .take(1).toList()
    }
    session.send(forwarded(ByteWriter().u8(COMM_NRF_START_PAIRING.id).i32(window.inWholeMilliseconds.toInt()).toByteArray()))
    if (withTimeoutOrNull(session.options.requestTimeout) { started.await() } == null) {
        replies.cancel()
        started.cancel()
        return@coroutineScope NrfPairingResult.NotSupported
    }
    onStarted()
    when (withTimeoutOrNull(window + grace) { replies.await() }) {
        NRF_PAIR_OK -> NrfPairingResult.Paired
        NRF_PAIR_FAIL -> NrfPairingResult.TimedOut
        else -> {
            replies.cancel()
            NrfPairingResult.NoAnswer
        }
    }
}

private const val BLE_NAME_MAX = 25
private const val BLE_PIN_LENGTH = 6
private const val NRF_PAIR_STARTED = 0
private const val NRF_PAIR_OK = 1
private const val NRF_PAIR_FAIL = 2
