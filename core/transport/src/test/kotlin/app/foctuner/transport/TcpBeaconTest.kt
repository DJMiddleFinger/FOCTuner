package app.foctuner.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TcpBeaconTest {
    @Test
    fun `parses the firmware beacon including the trailing NUL`() {
        assertEquals(
            LinkDevice.tcp("192.168.4.1", 65102, "VESC Express"),
            TcpBeacon.parse("VESC Express::192.168.4.1::65102\u0000"),
        )
    }

    @Test
    fun `rejects malformed beacons`() {
        assertNull(TcpBeacon.parse("no separators"))
        assertNull(TcpBeacon.parse("a::b"))
        assertNull(TcpBeacon.parse("a::10.0.0.2::notaport"))
        assertNull(TcpBeacon.parse("a::10.0.0.2::70000"))
        assertNull(TcpBeacon.parse("a::::65102"))
    }

    @Test
    fun `listen emits endpoints from datagrams`() = runBlocking {
        val port = DatagramSocket(0).use { it.localPort }
        withTimeout(10_000) {
            val sender = launch(Dispatchers.IO) {
                DatagramSocket().use { socket ->
                    val bytes = "esc::10.0.0.7::65102".toByteArray()
                    while (true) {
                        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
                        delay(50)
                    }
                }
            }
            assertEquals(LinkDevice.tcp("10.0.0.7", 65102, "esc"), TcpBeacon.listen(port).first())
            sender.cancel()
        }
    }
}
