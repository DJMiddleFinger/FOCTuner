package app.foctuner.vesc.firmware

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class HeatshrinkTest {
    @Test
    fun knownVectors() {
        // 1 + 'a' (0x61), padded.
        assertContentEquals(bytes(0xB0, 0x80), Heatshrink.encode("a".toByteArray()))
        // 'a', then 0 + distance-1 = 0 (13 bits) + length-1 = 2 (5 bits), padded.
        assertContentEquals(bytes(0xB0, 0x80, 0x00, 0x20), Heatshrink.encode("aaaa".toByteArray()))
        assertContentEquals("aaaa".toByteArray(), Heatshrink.decode(bytes(0xB0, 0x80, 0x00, 0x20)))
    }

    @Test
    fun referencesBeforeTheStartReadZerosLikeTheBootloader() {
        // 0 + distance-1 = 4 + length-1 = 1, padded: two bytes from the zeroed window.
        assertContentEquals(ByteArray(2), Heatshrink.decode(bytes(0x00, 0x10, 0x20)))
    }

    @Test
    fun roundTrips() {
        val samples = listOf(
            ByteArray(0),
            ByteArray(1) { 5 },
            Random(1).nextBytes(10_000),
            ByteArray(20_000) { 0xFF.toByte() },
            firmwareLike(100_000, seed = 2),
            firmwareLike(30_000, seed = 3) + ByteArray(40_000) { 0xFF.toByte() } + firmwareLike(30_000, seed = 3),
        )
        for (data in samples) assertContentEquals(data, Heatshrink.decode(Heatshrink.encode(data)), "${data.size} bytes")
    }

    @Test
    fun aWholeApplicationAreaFitsTheUpdateBuffer() {
        // A 7.x image spans 512 KB: code, then erased flash.
        val image = firmwareLike(300_000, seed = 4) + ByteArray(524_280 - 300_000) { 0xFF.toByte() }
        val packed = Heatshrink.encode(image)
        assertTrue(packed.size < UpdateBuffer.MAX_IMAGE, "${packed.size}")
        assertContentEquals(image, Heatshrink.decode(packed))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
