package app.foctuner.vesc.firmware

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.Crc16
import app.foctuner.vesc.HardwareType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FirmwareImageTest {
    @Test
    fun readsTheHardwareNameTheFirmwareEmbeds() {
        val image = FirmwareImage.fromFile("custom.bin", testFirmware("60_MK6"))
        assertEquals("60_MK6", image.embeddedHardware)
        assertEquals(HardwareCheck.Match, image.check("60_mk6"))
        assertEquals(HardwareCheck.Mismatch, image.check("75_300"))

        val anonymous = FirmwareImage.fromFile("custom.bin", firmwareLike(4096, seed = 1))
        assertNull(anonymous.embeddedHardware)
        assertEquals(HardwareCheck.Unknown, anonymous.check("60_MK6"))
        assertEquals(HardwareCheck.Match, FirmwareImage("generic", ByteArray(8), emptyList(), generic = true).check("60_MK6"))
    }

    @Test
    fun acceptsOnlyBinAndHexFilesOfFirmwareSize() {
        assertEquals(FirmwareFileException.Reason.NotFirmwareName, assertFailsWith<FirmwareFileException> { FirmwareImage.fromFile("notes.txt", ByteArray(10)) }.reason)
        assertEquals(FirmwareFileException.Reason.TooLarge, assertFailsWith<FirmwareFileException> { FirmwareImage.fromFile("big.bin", ByteArray(8_000_001)) }.reason)
        assertEquals(FirmwareFileException.Reason.Empty, assertFailsWith<FirmwareFileException> { FirmwareImage.fromFile("blank.BIN", ByteArray(64) { 0xFF.toByte() }) }.reason)
    }

    @Test
    fun unwrapsFilesThatAlreadyCarryTheUpdateHeader() {
        val plain = testFirmware("410")
        assertContentEquals(plain, FirmwareImage.fromFile("a.bin", withHeader(plain, plain.size.toLong())).bytes)

        val packed = Heatshrink.encode(plain)
        assertContentEquals(plain, FirmwareImage.fromFile("b.bin", withHeader(packed, (0xCCL shl 24) or packed.size.toLong())).bytes)

        val wrongCrc = withHeader(plain, plain.size.toLong()).also { it[5] = (it[5] + 1).toByte() }
        assertContentEquals(wrongCrc, FirmwareImage.fromFile("c.bin", wrongCrc).bytes)
    }

    @Test
    fun flattensIntelHexWithErasedGaps() {
        val hex = listOf(
            record(4, 0, byteArrayOf(0x08, 0x00)),
            record(0, 0x0000, byteArrayOf(1, 2, 3, 4)),
            record(0, 0x0006, byteArrayOf(0xAA.toByte(), 0xBB.toByte())),
            record(5, 0, byteArrayOf(0x08, 0x00, 0x01, 0xC1.toByte())),
            record(1, 0, ByteArray(0)),
        ).joinToString("\r\n")
        val image = FirmwareImage.fromFile("fw.hex", hex.toByteArray())
        assertContentEquals(byteArrayOf(1, 2, 3, 4, -1, -1, 0xAA.toByte(), 0xBB.toByte()), image.bytes)
    }

    @Test
    fun rejectsBrokenIntelHex() {
        val good = record(0, 0, byteArrayOf(1, 2))
        val badChecksum = good.dropLast(2) + "00"
        for (text in listOf("$badChecksum\n${record(1, 0, ByteArray(0))}", good, "$good\nhello\n${record(1, 0, ByteArray(0))}")) {
            assertEquals(FirmwareFileException.Reason.BadHex, assertFailsWith<FirmwareFileException> { FirmwareImage.fromFile("x.hex", text.toByteArray()) }.reason)
        }
    }

    @Test
    fun theUpdateBufferHeaderIsSizeAndCrc() {
        val small = testFirmware("60_MK6", size = 200_000)
        val buffer = UpdateBuffer.firmware(small)
        val r = ByteReader(buffer)
        assertEquals(small.size.toLong(), r.u32())
        assertEquals(Crc16.compute(small), r.u16())
        assertContentEquals(small, r.restBytes())
    }

    @Test
    fun aFullApplicationAreaImageIsSentCompressed() {
        val image = testFirmware("60_MK6", size = 524_280)
        val r = ByteReader(UpdateBuffer.firmware(image))
        val size = r.u32()
        assertEquals(0xCCL, size ushr 24)
        val crc = r.u16()
        val data = r.restBytes()
        assertEquals(size and 0xFFFFFF, data.size.toLong())
        assertEquals(Crc16.compute(data), crc)
        assertContentEquals(image, Heatshrink.decode(data))

        val incompressible = Random(9).nextBytes(500_000)
        assertEquals(FirmwareFailure.TooLarge, assertFailsWith<FirmwareUpdateException> { UpdateBuffer.firmware(incompressible) }.failure)
    }

    @Test
    fun bootloaderOffsetsFollowVescTool() {
        assertEquals(0x60000, UpdateBuffer.bootloaderOffset(HardwareType.VESC, "60_MK6"))
        assertEquals(0x1E000, UpdateBuffer.bootloaderOffset(HardwareType.VESC_BMS, "18s_light"))
        assertEquals(0x1E000, UpdateBuffer.bootloaderOffset(HardwareType.CUSTOM_MODULE, "hm1"))
        assertEquals(0xE000, UpdateBuffer.bootloaderOffset(HardwareType.CUSTOM_MODULE, "str-dcdc"))
    }

    private fun withHeader(data: ByteArray, size: Long) =
        ByteWriter().u32(size).u16(Crc16.compute(data)).bytes(data).toByteArray()

    private fun record(type: Int, address: Int, data: ByteArray): String {
        val bytes = byteArrayOf(data.size.toByte(), (address ushr 8).toByte(), address.toByte(), type.toByte()) + data
        val checksum = (0x100 - bytes.sumOf { it.toInt() and 0xFF } and 0xFF) and 0xFF
        return ":" + (bytes + checksum.toByte()).joinToString("") { "%02X".format(it) }
    }
}

/**
 * Code-like bytes with the `org.vesc.<hardware>` string a real image has, followed by erased flash up to
 * [size] like a 6.x/7.x image.
 */
internal fun testFirmware(hardware: String, size: Int = 60_000, codeSize: Int = minOf(size, 40_000), extra: String = ""): ByteArray {
    val image = firmwareLike(codeSize, seed = hardware.hashCode()).copyOf(size)
    for (i in codeSize until size) image[i] = 0xFF.toByte()
    val marker = "org.vesc.$hardware\u0000$extra\u0000".toByteArray(Charsets.US_ASCII)
    marker.copyInto(image, codeSize / 2)
    return image
}
