package app.foctuner.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A VESC reached over TCP: a VESC Express on WiFi, or the TCP server in VESC Tool. The stream carries
 * packets exactly as on UART, with no handshake.
 */
class TcpTransport private constructor(socket: Socket) :
    Transport by StreamTransport(socket.getInputStream(), socket.getOutputStream(), socket::close, MAX_WRITE_SIZE) {

    companion object {
        /** Port used by VESC Express, VESC Tool's TCP server and its UDP beacon. */
        const val DEFAULT_PORT = 65102

        private const val MAX_WRITE_SIZE = 64 * 1024

        /** @throws IOException when the host is unknown or does not accept the connection within [timeoutMs]. */
        suspend fun connect(host: String, port: Int = DEFAULT_PORT, timeoutMs: Int = 5_000): TcpTransport =
            withContext(Dispatchers.IO) {
                val socket = Socket()
                try {
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    TcpTransport(socket)
                } catch (e: IOException) {
                    socket.close()
                    throw e
                }
            }
    }
}
