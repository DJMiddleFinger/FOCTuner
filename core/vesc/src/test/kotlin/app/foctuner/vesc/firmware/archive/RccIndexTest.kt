package app.foctuner.vesc.firmware.archive

import app.foctuner.sim.RccWriter
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RccIndexTest {
    @Test
    fun readsTheIndexOfTheRealReleaseBundle() {
        val header = RealBundles.header(RealBundles.RELEASE)
        assertEquals(114_293_936L, RccIndex.tailOffset(header))

        val index = RealBundles.index(RealBundles.RELEASE)
        assertEquals(24L, index.dataSection)
        assertEquals(218, index.entries.size)
        val mk6 = index.entries.first { it.path == "/res/firmwares/60_MK6/VESC_default.bin" }
        assertEquals(59_768_376L, mk6.dataOffset)
        assertEquals("VESC_default.bin", mk6.name)
        assertEquals(false, mk6.compressed)
        assertTrue(index.entries.any { it.path == "/res/firmwares/Duet XS60/VESC_default.bin" }, "names with spaces")
    }

    @Test
    fun readsTheIndexOfTheRealArchiveBundle() {
        val index = RealBundles.index(RealBundles.ARCHIVE)
        assertEquals(1441, index.entries.size)
        val versions = index.entries.map { it.path.split('/')[2] }.toSortedSet()
        assertEquals(23, versions.size)
        assertTrue(listOf("3.55", "4.02", "6.05", "7.00").all { it in versions })
        assertNotNull(index.entries.firstOrNull { it.path == "/fw_archive/6.05/60_MK6/VESC_default.bin" })
    }

    @Test
    fun readsTheIndexOfTheRealVescExpressBundle() {
        val index = RealBundles.index(RealBundles.EXPRESS)
        assertEquals(99, index.entries.size)
        assertTrue(index.entries.any { it.path == "/res/firmwares_esp/esp32c3/VESC Express T/vesc_express.bin" })
        assertTrue(index.entries.first { it.name == "partition-table.bin" }.compressed)
    }

    @Test
    fun roundTripsABundleWithCompressedAndPlainFiles() {
        val plain = ByteArray(5000) { (it % 251).toByte() }
        val packed = ByteArray(5000) { (it / 50).toByte() }
        val bundle = RccWriter.build(
            listOf(
                RccWriter.File("/res/firmwares/A/VESC_default.bin", plain),
                RccWriter.File("/res/firmwares/A_o_B/VESC_default.bin", packed, compress = true),
            ),
        )
        val header = bundle.copyOfRange(0, 24)
        val index = RccIndex.parse(header, bundle.copyOfRange(RccIndex.tailOffset(header).toInt(), bundle.size))
        assertEquals(listOf("/res/firmwares/A/VESC_default.bin", "/res/firmwares/A_o_B/VESC_default.bin"), index.entries.map { it.path }.sorted())

        val second = index.entries.first { it.compressed }
        val at = (index.dataSection + second.dataOffset).toInt()
        val size = RccIndex.sizePrefix(bundle.copyOfRange(at, at + 4)).toInt()
        assertContentEquals(packed, RccIndex.inflate(bundle.copyOfRange(at + 4, at + 4 + size), limit = 10_000))
    }

    @Test
    fun refusesCompressedDataThatLiesAboutItsSize() {
        val bundle = RccWriter.build(listOf(RccWriter.File("/a/b.bin", ByteArray(100) { 1 }, compress = true)))
        val header = bundle.copyOfRange(0, 24)
        val index = RccIndex.parse(header, bundle.copyOfRange(RccIndex.tailOffset(header).toInt(), bundle.size))
        val at = (index.dataSection + index.entries.single().dataOffset).toInt()
        val stored = bundle.copyOfRange(at + 4, at + 4 + RccIndex.sizePrefix(bundle.copyOfRange(at, at + 4)).toInt())

        val oversized = stored.copyOf().also { ByteBuffer.wrap(it).putInt(1_000_000) }
        assertEquals(ArchiveException.Reason.TooLarge, assertFailsWith<ArchiveException> { RccIndex.inflate(oversized, limit = 4096) }.reason)
        val short = stored.copyOf().also { ByteBuffer.wrap(it).putInt(500) }
        assertEquals(ArchiveException.Reason.Damaged, assertFailsWith<ArchiveException> { RccIndex.inflate(short, limit = 4096) }.reason)
        val corrupt = stored.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals(ArchiveException.Reason.Damaged, assertFailsWith<ArchiveException> { RccIndex.inflate(corrupt, limit = 4096) }.reason)
    }

    @Test
    fun refusesIndexesThatDontDescribeABundle() {
        val good = RealBundles.header(RealBundles.RELEASE)
        val tail = RealBundles.tail(RealBundles.RELEASE)
        fun reason(header: ByteArray, tail: ByteArray) = assertFailsWith<ArchiveException> { RccIndex.parse(header, tail) }.reason

        assertEquals(ArchiveException.Reason.BadBundle, reason(good.copyOf().also { it[0] = 'x'.code.toByte() }, tail))
        assertEquals(ArchiveException.Reason.BadBundle, reason(good.copyOf().also { it[7] = 9 }, tail))
        assertEquals(ArchiveException.Reason.BadBundle, reason(good.copyOfRange(0, 10), tail))
        assertEquals(ArchiveException.Reason.BadBundle, reason(good, tail.copyOfRange(0, 3000)), "tree cut off")
        assertEquals(ArchiveException.Reason.BadBundle, reason(good.copyOf().also { ByteBuffer.wrap(it).putInt(8, 0x7FFFFFF0) }, tail), "tree offset far outside")
    }

    @Test
    fun aTreeThatLoopsOrFansOutIsStoppedNotWalkedForever() {
        val tail = ByteBuffer.allocate(22 + 6).putInt(0).putShort(2).putInt(1).putInt(0).array()
        val header = ByteBuffer.allocate(24).putInt(0x71726573).putInt(3).putInt(0).putInt(24).putInt(22).putInt(0).array()
        assertEquals(ArchiveException.Reason.BadBundle, assertFailsWith<ArchiveException> { RccIndex.parse(header, tail) }.reason)
    }
}
