package app.foctuner.vesc

import app.foctuner.sim.SimulatedVesc
import app.foctuner.transport.TcpBeacon
import app.foctuner.transport.TcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BridgeTest {
    @Test
    fun desktopToolReachesTheControllerThroughThePhone() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val phone = VescClient(SimulatedVesc(), scope)
            val server = ServerSocket(0)
            val fromPeer = AtomicInteger()
            val bridging = scope.launch {
                val peer = TcpTransport.accept(server)
                phone.bridge(peer) { if (it) fromPeer.incrementAndGet() }
            }
            val desktop = VescClient(TcpTransport.connect("127.0.0.1", server.localPort), scope)
            withTimeout(5_000) {
                assertEquals("60_MK6", desktop.firmwareInfo().hardwareName)
                assertEquals("60_MK6", desktop.can(1).firmwareInfo().hardwareName)
                assertEquals("60_MK6", phone.firmwareInfo().hardwareName) // the phone keeps working alongside
            }
            assertTrue(fromPeer.get() >= 2)
            desktop.close()
            withTimeout(5_000) { bridging.join() }
            server.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun hubLoginAndBeaconFollowVescTool() = runBlocking {
        val hub = ServerSocket(0)
        val login = async(Dispatchers.IO) {
            hub.accept().use { BufferedReader(InputStreamReader(it.getInputStream())).readLine() }
        }
        val phone = TcpTransport.registerWithHub("127.0.0.1", "board1", "secret", hub.localPort)
        assertEquals("VESC:board1:secret", withTimeout(5_000) { login.await() })
        phone.close()
        hub.close()
        assertFailsWith<IllegalArgumentException> { TcpTransport.registerWithHub("127.0.0.1", "ab", "x", 1) }
        assertFailsWith<IllegalArgumentException> { TcpTransport.registerWithHub("127.0.0.1", "abc", "x:y", 1) }
        assertEquals("60_MK6::192.168.1.20::65102", TcpBeacon.format("60_MK6", "192.168.1.20", 65102))
    }
}
