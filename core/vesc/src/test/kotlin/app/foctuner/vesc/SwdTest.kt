package app.foctuner.vesc

import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SwdTest {
    @Test
    fun programsAnNrfOverSwd() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val swd = SwdProgrammer(VescClient(sim, backgroundScope))
        assertEquals(true, swd.mapDefaultPins())
        assertEquals(false, swd.mapNrf5xPins())
        val target = swd.connect()
        assertEquals(SwdTarget.Nrf52832_512k64k, target)

        val image = Random(1).nextBytes(1000)
        swd.eraseAll()
        val progress = mutableListOf<Int>()
        swd.write(image, target.flashStart + 0x1000, verify = true) { progress += it }
        assertEquals(listOf(400, 800, 1000), progress)
        assertContentEquals(image, sim.swdFlash(0x1000, 1000))
        assertContentEquals(image.copyOfRange(0, 16), swd.read(0x1000, 16))

        // Writing without an erase can only clear bits, so the read-back no longer matches.
        assertFailsWith<SwdException> { swd.write(ByteArray(400) { 0x7F }, 0x1000, verify = true) }
        assertEquals(true, swd.reboot())
        swd.disconnect()
        assertEquals(-1, assertFailsWith<SwdException> { swd.eraseAll() }.code)
    }

    @Test
    fun noTargetIsReported() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        sim.setSwdTargetPresent(false)
        val e = assertFailsWith<SwdException> { SwdProgrammer(VescClient(sim, backgroundScope)).connect() }
        assertEquals(-1, e.code)
    }

    @Test
    fun hexSegmentsKeepTheirAddresses() {
        val hex = ":020000040001F9\n:04000000DEADBEEFC4\n:04100000CAFEBABEAC\n:00000001FF\n"
        val segments = swdSegmentsFromHex(hex)
        assertEquals(listOf(0x10000L, 0x11000L), segments.map { it.first })
        assertContentEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), segments[0].second)
    }
}
