package app.foctuner.vesc

import app.foctuner.protocol.PacketCodec
import app.foctuner.protocol.PacketDecoder
import app.foctuner.transport.Transport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Relays packets between this connection and [peer] (VESC Tool's TCP server and hub, `vescinterface.cpp`): every
 * packet [peer] sends goes to the attached controller, and every packet the controller sends goes to [peer],
 * replies to this app's own requests included, as VESC Tool does. Returns when [peer] closes; closes [peer] when
 * cancelled. [onPacket] counts traffic, true for packets from [peer].
 */
suspend fun VescClient.bridge(peer: Transport, onPacket: (fromPeer: Boolean) -> Unit = {}) {
    try {
        coroutineScope {
            val toPeer = launch(start = CoroutineStart.UNDISPATCHED) {
                session.incoming.collect { packet ->
                    val frame = PacketCodec.encode(packet)
                    var offset = 0
                    while (offset < frame.size) {
                        val end = minOf(frame.size, offset + peer.maxWriteSize)
                        peer.write(frame.copyOfRange(offset, end))
                        offset = end
                    }
                    onPacket(false)
                }
            }
            val decoder = PacketDecoder()
            peer.received.collect { bytes ->
                for (payload in decoder.feed(bytes)) {
                    session.send(payload)
                    onPacket(true)
                }
            }
            toPeer.cancel()
        }
    } finally {
        withContext(NonCancellable) { peer.close() }
    }
}
