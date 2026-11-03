package app.foctuner.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
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

        /** VESC Tool's TCP hub (`tcphub.cpp`, veschub.vedder.se) listens here. */
        const val HUB_PORT = 65101

        /** Waits for the next client of [server] (blocking accept on the IO dispatcher; closing [server] ends it). */
        suspend fun accept(server: ServerSocket): TcpTransport = withContext(Dispatchers.IO) {
            val socket = server.accept()
            socket.tcpNoDelay = true
            TcpTransport(socket)
        }

        /**
         * Registers with a VESC Tool TCP hub as a controller: connects and sends `VESC:<id>:<password>\n`. The hub
         * then relays whatever a VESC Tool that logs in with the same id and password sends. The hub protocol is
         * cleartext, password included. The hub doesn't confirm the login; a wrong one only shows on the other side.
         */
        suspend fun registerWithHub(host: String, id: String, password: String, port: Int = HUB_PORT, timeoutMs: Int = 5_000): TcpTransport {
            require(id.trim().length >= 3) { "The hub needs an id of at least 3 characters" }
            require(':' !in id && ':' !in password && '\n' !in id && '\n' !in password) { "Id and password can't contain ':' or line breaks" }
            val transport = connect(host, port, timeoutMs)
            try {
                transport.write("VESC:${id.trim()}:$password\n".toByteArray(Charsets.UTF_8))
            } catch (e: IOException) {
                transport.close()
                throw e
            }
            return transport
        }

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
