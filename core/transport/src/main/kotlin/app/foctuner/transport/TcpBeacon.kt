package app.foctuner.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * VESC Express (and VESC Tool's TCP server) broadcast `name::ip::port` once a second on UDP port 65109.
 * Beacons repeat, so the same endpoint shows up many times; deduplicate on the receiving side.
 */
object TcpBeacon {
    const val UDP_PORT = 65109

    /** Listens until the collector is cancelled. Fails if the port cannot be bound. */
    fun listen(udpPort: Int = UDP_PORT): Flow<LinkDevice> = callbackFlow {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(udpPort))
        }
        launch(Dispatchers.IO) {
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                while (true) {
                    packet.length = buffer.size
                    socket.receive(packet)
                    parse(String(buffer, 0, packet.length, Charsets.UTF_8))?.let { trySend(it) }
                }
            } catch (e: SocketException) {
                close(e.takeUnless { socket.isClosed })
            }
        }
        awaitClose { socket.close() }
    }.flowOn(Dispatchers.IO)

    internal fun parse(datagram: String): LinkDevice? {
        val parts = datagram.trimEnd('\u0000', '\n', '\r').split("::")
        if (parts.size != 3) return null
        val port = parts[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (parts[1].isBlank()) return null
        return LinkDevice.tcp(parts[1], port, parts[0])
    }
}
