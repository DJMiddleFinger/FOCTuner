package app.foctuner.vesc

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.ConfigKind
import app.foctuner.config.ConfigXmlParser
import app.foctuner.vesc.firmware.archive.ArchiveException
import app.foctuner.vesc.firmware.archive.HttpFetcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteDefinitionsTest {
    /** 7.01's motor definitions minus one serialized parameter: a layout no bundled series has. */
    private val futureXml = javaClass.getResourceAsStream("/config/7.01/parameters_mcconf.xml")!!.readBytes().decodeToString()
        .replaceFirst(Regex("""\s*<ser>l_watt_min</ser>"""), "")
    private val futureSignature = ConfigXmlParser.parse(futureXml).signature

    private class Host(private val files: Map<String, String>) : HttpFetcher {
        val requested = mutableListOf<String>()
        override suspend fun get(url: String, range: String?, maxBytes: Int, onProgress: (Int) -> Unit): ByteArray {
            requested += url
            return files[url]?.toByteArray() ?: throw ArchiveException(ArchiveException.Reason.NotFound, "$url was not found")
        }
    }

    @Test
    fun fetchesChecksAndKeepsDefinitionsForUnbundledFirmware() = runTest {
        val dir = Files.createTempDirectory("defs").toFile()
        val url = "${RemoteDefinitions.VESC_TOOL_CONFIG_URL}/9.10/parameters_mcconf.xml"
        val host = Host(mapOf(url to futureXml))
        val remote = RemoteDefinitions(host, dir)
        assertTrue(futureSignature != BundledDefinitions.load("7.01", ConfigKind.MCCONF).signature)

        assertEquals(RemoteDefinitions.Outcome.WrongLayout, remote.fetch("9.10", ConfigKind.MCCONF, 1234L).outcome)
        assertEquals(RemoteDefinitions.Outcome.NotPublished, remote.fetch("9.11", ConfigKind.MCCONF, futureSignature).outcome)

        val found = remote.fetch("9.10", ConfigKind.MCCONF, futureSignature)
        assertEquals(RemoteDefinitions.Outcome.Found, found.outcome)
        assertEquals("9.10", found.version)
        assertEquals(futureSignature, BundledDefinitions.load("9.10", ConfigKind.MCCONF).signature)
        assertEquals("9.10", BundledDefinitions.versionWithSignature(ConfigKind.MCCONF, futureSignature))
        assertTrue(File(dir, "9.10/parameters_mcconf.xml").isFile)

        // Already known: no second download.
        val before = host.requested.size
        assertEquals("9.10", remote.fetch("9.10", ConfigKind.MCCONF, futureSignature).version)
        assertEquals(before, host.requested.size)
        // A bundled layout is found without the network at all.
        val bundled = BundledDefinitions.load("6.02", ConfigKind.APPCONF).signature
        assertEquals("6.02", remote.fetch("6.02", ConfigKind.APPCONF, bundled).version)
        assertEquals(before, host.requested.size)
    }

    @Test
    fun restoresKeptDefinitionsAndDropsDamagedOnes() {
        val dir = Files.createTempDirectory("defs").toFile()
        File(dir, "9.20").mkdirs()
        File(dir, "9.20/parameters_mcconf.xml").writeText(futureXml)
        File(dir, "9.21").mkdirs()
        File(dir, "9.21/parameters_mcconf.xml").writeText("<broken")
        RemoteDefinitions(Host(emptyMap()), dir).restore()
        assertTrue(BundledDefinitions.has("9.20", ConfigKind.MCCONF))
        assertFalse(BundledDefinitions.has("9.21", ConfigKind.MCCONF))
        assertFalse(File(dir, "9.21/parameters_mcconf.xml").exists())
    }
}
