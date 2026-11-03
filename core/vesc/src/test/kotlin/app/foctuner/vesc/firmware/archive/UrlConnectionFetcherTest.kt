package app.foctuner.vesc.firmware.archive

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UrlConnectionFetcherTest {
    private val body = ByteArray(100) { it.toByte() }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/range") { ex ->
            ex.sendResponseHeaders(206, 10)
            ex.responseBody.use { it.write(body, 0, 10) }
        }
        createContext("/whole") { ex ->
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        createContext("/redirect") { ex ->
            ex.responseHeaders.add("Location", "http://example.com/evil.bin")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val fetcher = UrlConnectionFetcher(cleartextHosts = setOf("127.0.0.1"))

    @AfterTest
    fun stop() = server.stop(0)

    private fun fails(reason: ArchiveException.Reason, block: suspend () -> Unit) =
        assertEquals(reason, assertFailsWith<ArchiveException> { runBlocking { block() } }.reason)

    @Test
    fun readsARangeAnsweredWith206() = runBlocking {
        assertContentEquals(body.copyOf(10), fetcher.get("$base/range", "bytes=0-9", maxBytes = 1000))
    }

    @Test
    fun refusesARangeAnsweredWithTheWholeFile() = fails(ArchiveException.Reason.RangeUnsupported) {
        fetcher.get("$base/whole", "bytes=0-9", maxBytes = 1000)
    }

    @Test
    fun doesNotFollowRedirects() = fails(ArchiveException.Reason.Network) {
        fetcher.get("$base/redirect", maxBytes = 1000)
    }

    @Test
    fun refusesABodyLongerThanTheCap() = fails(ArchiveException.Reason.TooLarge) {
        fetcher.get("$base/whole", maxBytes = 50)
    }

    @Test
    fun refusesPlainHttpToOtherHosts() = fails(ArchiveException.Reason.Network) {
        UrlConnectionFetcher().get("$base/whole", maxBytes = 1000)
    }
}
