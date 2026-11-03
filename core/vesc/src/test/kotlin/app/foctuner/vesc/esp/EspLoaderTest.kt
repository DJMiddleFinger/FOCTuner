package app.foctuner.vesc.esp

import app.foctuner.sim.EspRomSim
import app.foctuner.sim.DemoArchive
import app.foctuner.vesc.firmware.archive.FirmwareArchive
import app.foctuner.vesc.firmware.archive.FirmwareCache
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EspLoaderTest {
    @Test
    fun slipRoundTripsEscapes() {
        val data = byteArrayOf(1, 0xC0.toByte(), 2, 0xDB.toByte(), 3)
        val framed = Slip.encode(data)
        assertEquals(listOf(0xC0, 1, 0xDB, 0xDC, 2, 0xDB, 0xDD, 3, 0xC0), framed.map { it.toInt() and 0xFF })
        val decoder = Slip.Decoder()
        assertEquals(0, decoder.feed(framed.copyOfRange(0, 4)).size)
        assertContentEquals(data, decoder.feed(framed.copyOfRange(4, framed.size)).single())
    }

    @Test
    fun flashesAVescExpressImageAndChecksIt() = runTest {
        val rom = EspRomSim()
        val loader = EspLoader(rom, rom, backgroundScope)
        assertEquals(EspChip.Esp32C3, loader.connect(builtinUsb = true))
        assertEquals(4 * 1024 * 1024, loader.flashSize)

        val app = Random(7).nextBytes(5000)
        val progress = mutableListOf<Int>()
        loader.flash(app, EspLoader.APP_OFFSET) { progress += it }
        assertEquals(5, rom.blocksWritten)
        assertEquals(5000, progress.last())
        assertContentEquals(app, rom.flash(EspLoader.APP_OFFSET.toInt(), 5000))

        rom.corruptBlock = 1
        assertTrue(assertFailsWith<EspLoaderException> { loader.flash(app, EspLoader.APP_OFFSET) }.message!!.contains("MD5"))

        loader.erase(EspLoader.LISP_OFFSET, EspLoader.LISP_SIZE)
        loader.reset()
        assertFailsWith<IllegalArgumentException> { loader.flash(app, 2) }
    }

    @Test
    fun expressBuildsComeFromTheArchiveWithAllThreeImages() = runTest {
        val archive = FirmwareArchive(DemoArchive.host(chunkDelay = Duration.ZERO), FirmwareCache(Files.createTempDirectory("fw").toFile()))
        val build = archive.expressBuilds().single()
        assertEquals("esp32c3", build.chip)
        assertEquals(DemoArchive.EXPRESS, build.hardware)
        val (bootloader, partitions, app) = archive.expressImages(build)
        assertEquals(listOf(2048, 3072, 64 * 1024), listOf(bootloader.size, partitions.size, app.size))

        val rom = EspRomSim()
        val loader = EspLoader(rom, rom, backgroundScope)
        loader.connect(builtinUsb = true)
        loader.flash(app, EspLoader.APP_OFFSET)
        assertContentEquals(app, rom.flash(EspLoader.APP_OFFSET.toInt(), app.size))
    }

    @Test
    fun noBootloaderNoSync() = runTest {
        val rom = EspRomSim()
        val loader = EspLoader(rom, { _, _ -> }, backgroundScope)
        assertFailsWith<EspLoaderException> { loader.connect(builtinUsb = true, tries = 2) }
    }
}
