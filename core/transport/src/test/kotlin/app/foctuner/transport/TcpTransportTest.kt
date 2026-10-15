package app.foctuner.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TcpTransportTest {
    private fun withServer(block: suspend (server: ServerSocket, transport: TcpTransport, peer: Socket) -> Unit) =
        runBlocking {
            withTimeout(10_000) {
                ServerSocket(0).use { server ->
                    val transport = TcpTransport.connect("127.0.0.1", server.localPort)
                    val peer = withContext(Dispatchers.IO) { server.accept() }
                    try {
                        block(server, transport, peer)
                    } finally {
                        transport.close()
                        peer.close()
                    }
                }
            }
        }

    @Test
    fun `delivers what the peer sends`() = withServer { _, transport, peer ->
        peer.getOutputStream().apply { write(byteArrayOf(2, 1, 0)); flush() }

        assertContentEquals(byteArrayOf(2, 1, 0), transport.received.first())
    }

    @Test
    fun `writes reach the peer`() = withServer { _, transport, peer ->
        transport.write(byteArrayOf(2, 1, 0, 0, 0, 3))

        val got = withContext(Dispatchers.IO) { peer.getInputStream().readNBytes(6) }
        assertContentEquals(byteArrayOf(2, 1, 0, 0, 0, 3), got)
    }

    @Test
    fun `bytes sent before anyone collects are kept in order`() = withServer { _, transport, peer ->
        val data = ByteArray(200_000) { it.toByte() }
        peer.getOutputStream().apply { write(data); flush() }
        peer.shutdownOutput()

        val all = transport.received.toList().fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        assertContentEquals(data, all)
    }

    @Test
    fun `received completes when the peer closes`() = withServer { _, transport, peer ->
        peer.getOutputStream().apply { write(byteArrayOf(7)); flush() }
        peer.close()

        assertEquals(listOf(7), transport.received.toList().flatMap { it.toList() }.map { it.toInt() })
    }

    @Test
    fun `received fails when the connection is reset`() = withServer { _, transport, peer ->
        peer.setSoLinger(true, 0)
        peer.close()

        assertFailsWith<IOException> { transport.received.toList() }
    }

    @Test
    fun `close completes received without an error and rejects writes`() = withServer { _, transport, _ ->
        transport.close()

        assertEquals(emptyList(), transport.received.toList())
        assertFailsWith<IOException> { transport.write(byteArrayOf(1)) }
    }

    @Test
    fun `closing twice is harmless`() = withServer { _, transport, _ ->
        transport.close()
        transport.close()
    }

    @Test
    fun `connecting to a closed port fails`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }

        assertFailsWith<IOException> { TcpTransport.connect("127.0.0.1", port, timeoutMs = 2_000) }
        Unit
    }
}
