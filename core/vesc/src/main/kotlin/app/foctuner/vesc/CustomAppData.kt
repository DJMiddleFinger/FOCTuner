package app.foctuner.vesc

import app.foctuner.protocol.CommPacketId.COMM_CUSTOM_APP_DATA
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * COMM_CUSTOM_APP_DATA payloads (without the command id) the controller sends: what LispBM's `send-data` and native
 * packages send to their user interface. Like [lispPrints] they carry no sender, so with several controllers on CAN
 * this has the data of every one that sends to this connection. Slow collectors lose the oldest packets.
 */
val VescController.customAppData: Flow<ByteArray>
    get() = session.incoming
        .filter { it[0].toInt() and 0xFF == COMM_CUSTOM_APP_DATA.id }
        .map { it.copyOfRange(1, it.size) }

/** Sends [data] to the controller's package (LispBM receives it with `recv` or an event handler). */
suspend fun VescController.sendCustomAppData(data: ByteArray) {
    session.send(forwarded(byteArrayOf(COMM_CUSTOM_APP_DATA.id.toByte()) + data))
}
