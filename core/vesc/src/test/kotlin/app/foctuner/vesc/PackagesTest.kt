package app.foctuner.vesc

import app.foctuner.config.Config
import app.foctuner.config.ParamType
import app.foctuner.config.QtCompression
import app.foctuner.config.sections
import app.foctuner.protocol.ByteWriter
import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.random.Random
import kotlin.test.assertTrue

class PackagesTest {
    private fun TestScope.client(chunkSize: Int = Int.MAX_VALUE) = VescClient(SimulatedVesc(testTimeSource, chunkSize = chunkSize), backgroundScope)

    @Test
    fun downloadsTheCustomConfigDefinitionOverBleAndEditsTheConfig() = runTest {
        val client = client(chunkSize = 20)
        assertEquals(1, client.firmwareInfo().customConfigCount)
        val progress = mutableListOf<Int>()

        val definition = parseCustomConfigDefinition(client.readCustomConfigDefinition(0) { read, _ -> progress += read })

        assertEquals("Demo Package", definition.customConfigName)
        assertTrue(progress.size > 2 && progress.zipWithNext().all { (a, b) -> b > a })
        assertEquals(listOf("Ride", "Lights", "About"), definition.sections().single().subgroups.map { it.name })
        assertEquals(ParamType.UNDEFINED, definition.param("hw_name").type)

        val stored = client.readCustomConfig(0, Config.defaults(definition))
        assertEquals(25.0, stored.double("top_speed"))
        assertEquals("FOCTuner", stored.text("rider_name"))

        client.writeCustomConfig(0, stored.with("top_speed", 32.5).with("tilt_angle", -1.25).with("rider_name", "Sam"))
        val back = client.readCustomConfig(0, stored)
        assertEquals(32.5, back.double("top_speed"))
        assertEquals(-1.25, back.double("tilt_angle"))
        assertEquals("Sam", back.text("rider_name"))

        assertEquals(25.0, client.readDefaultCustomConfig(0, back).double("top_speed"))
    }

    @Test
    fun customConfigsOfTheSecondMotorGoThroughCanAndOtherIndicesDontAnswer() = runTest {
        val client = client()
        val definition = parseCustomConfigDefinition(client.can(1).readCustomConfigDefinition(0))
        assertEquals(60L, client.can(1).readCustomConfig(0, Config.defaults(definition)).int("brightness"))
        assertFailsWith<VescTimeoutException> { client.readCustomConfigDefinition(1) }
    }

    @Test
    fun packageRoundTripsAndSkipsUnknownFields() {
        val lisp = LispCode.pack("(def x 1)")
        val pkg = VescPackage("Demo", "<p>Hi</p>", "Hi", lisp, "Item {}", qmlFullscreen = true, descriptionQml = "Item {}")

        val back = VescPackage.parse(pkg.pack())
        assertEquals("Demo", back.name)
        assertEquals("Hi", back.descriptionMarkdown)
        assertContentEquals(lisp, back.lisp)
        assertEquals("Item {}", back.qml)
        assertTrue(back.qmlFullscreen)

        val withExtra = ByteWriter().cString("VESC Packet").cString("future").i32(3).bytes(byteArrayOf(1, 2, 3))
            .cString("name").i32(4).bytes("Next".encodeToByteArray()).toByteArray()
        val parsed = VescPackage.parse(QtCompression.compress(withExtra))
        assertEquals("Next", parsed.name)
        assertNull(parsed.lisp)
        assertNull(parsed.qml)

        assertFailsWith<PackageException> { VescPackage.parse("not a package".encodeToByteArray()) }
        assertFailsWith<PackageException> { VescPackage.parse(QtCompression.compress(ByteWriter().cString("VESC Packet").cString("name").i32(40).toByteArray())) }
    }

    @Test
    fun installsAndUninstallsAPackage() = runTest {
        val client = client(chunkSize = 20)
        val qml = "import QtQuick 2.15\nItem { property string tabTitle: \"Demo\" }"
        val code = "(def vt-mode 2)\n" + (1..80).joinToString("\n") { "(def pad-$it $it)" }
        val pkg = VescPackage("Demo", lisp = LispCode.pack(code), qml = qml)
        val steps = mutableListOf<PackageInstallStep>()

        client.installPackage(pkg) { step, _, _ -> if (steps.lastOrNull() != step) steps += step }

        assertEquals(PackageInstallStep.entries.toList(), steps)
        assertEquals(1, client.firmwareInfo().qmlApp)
        assertTrue(checkNotNull(client.readQmlUi(QmlUiSource.App)).endsWith(qml))
        assertEquals(code, LispCode.unpack(client.readLisp()).code)

        client.uninstallPackage()
        assertEquals(0, client.firmwareInfo().qmlApp)
        assertNull(client.readQmlUi(QmlUiSource.App))
        assertEquals(0, client.readLisp().size)
    }

    @Test
    fun aPackageWithoutQmlErasesTheInstalledUi() = runTest {
        val client = client()
        client.uploadQmlUi("Item {}", fullscreen = true)
        assertEquals(2, client.firmwareInfo().qmlApp)

        client.installPackage(VescPackage("Lisp only", lisp = LispCode.pack("(def a 1)")))

        assertEquals(0, client.firmwareInfo().qmlApp)
        assertFailsWith<QmlUiException> { client.uploadQmlUi(randomText(300_000), fullscreen = false) }
    }

    @Test
    fun browsesWritesReadsAndRemovesFiles() = runTest {
        val client = client(chunkSize = 20)
        assertTrue(client.hasFileSystem())

        val root = client.listFiles("")
        assertEquals(listOf("logs", "notes.txt"), root.map { it.name })
        assertTrue(root.first().isDirectory)
        assertEquals(2L, root.first().size)

        assertTrue(client.makeDirectory("/test"))
        assertFalse(client.makeDirectory("/test"))
        val data = ByteArray(1000) { (it * 31).toByte() }
        val progress = mutableListOf<Int>()
        client.writeFile("/test/data.bin", data) { written, _ -> progress += written }
        assertEquals(listOf(0, 384, 768, 1000), progress)
        assertEquals(DeviceFile("data.bin", isDirectory = false, size = 1000), client.listFiles("/test").single())
        assertContentEquals(data, client.readFile("/test/data.bin"))

        client.writeFile("/test/empty.txt", ByteArray(0))
        assertEquals(0L, client.listFiles("/test").first { it.name == "empty.txt" }.size)
        assertFailsWith<VescException> { client.writeFile("/missing/x.txt", data) }

        assertTrue(client.removeFile("/test"))
        assertEquals(listOf("logs", "notes.txt"), client.listFiles("").map { it.name })
        assertEquals(0, client.readFile("/test/data.bin").size)
    }

    @Test
    fun listsLongFoldersPageByPage() = runTest {
        val client = client()
        assertTrue(client.makeDirectory("/many"))
        val names = (1..40).map { "file-with-a-longish-name-%02d.csv".format(it) }
        for (name in names.shuffled(Random(1))) client.writeFile("/many/$name", byteArrayOf(1))

        assertEquals(names, client.listFiles("/many").map { it.name })
    }

    @Test
    fun customAppDataFlowsBothWays() = runTest {
        val client = client()
        val received = async(start = CoroutineStart.UNDISPATCHED) { client.customAppData.first() }
        client.sendCustomAppData(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), received.await())
    }

    /** Letters that don't compress below the QML size limit. */
    private fun randomText(length: Int): String = Random(2).let { random -> String(CharArray(length) { 'a' + random.nextInt(26) }) }
}
