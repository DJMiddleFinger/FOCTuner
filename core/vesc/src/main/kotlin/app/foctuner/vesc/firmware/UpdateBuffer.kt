package app.foctuner.vesc.firmware

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.Crc16
import app.foctuner.vesc.HardwareType

/**
 * The flash area the firmware writes uploads to (`flash_helper.c`): three 128 KB sectors for the new
 * application, followed by the bootloader sector. The bootloader copies the new application over the old
 * one only if the CRC16 in its 6-byte header (u32 size, u16 CRC) matches the data.
 */
internal object UpdateBuffer {
    const val HEADER_SIZE = 6

    /** Set in the top byte of the header size when the data is heatshrink-compressed. */
    const val COMPRESSED_FLAG = 0xCC

    /** VESC Tool's limit for an image in the update buffer (3 * 128 KB, less what the bootloader reserves). */
    const val MAX_IMAGE = 393_208

    /** Larger images are taken to be for an ESP32 and are sent as they are. */
    const val MAX_COMPRESSIBLE = 700_000

    /** Erased flash; VESC Tool skips chunks that only contain this. */
    const val ERASED: Byte = 0xFF.toByte()

    /**
     * The update buffer contents for [image]: header plus data, heatshrink-compressed when the image only
     * fits that way (every 6.x/7.x STM32 image, which spans the whole 512 KB application area).
     *
     * @throws FirmwareUpdateException if it doesn't fit
     */
    fun firmware(image: ByteArray): ByteArray {
        val compress = image.size > MAX_IMAGE && image.size < MAX_COMPRESSIBLE
        val data = if (compress) Heatshrink.encode(image) else image
        if (compress && data.size > MAX_IMAGE) {
            throw FirmwareUpdateException(FirmwareFailure.TooLarge, "Image is ${data.size} bytes after compression, more than $MAX_IMAGE")
        }
        if (data.size > FirmwareImage.MAX_FILE_SIZE) {
            throw FirmwareUpdateException(FirmwareFailure.TooLarge, "Image is ${data.size} bytes")
        }
        val size = if (compress) (COMPRESSED_FLAG.toLong() shl 24) or data.size.toLong() else data.size.toLong()
        return ByteWriter(data.size + HEADER_SIZE).u32(size).u16(Crc16.compute(data)).bytes(data).toByteArray()
    }

    /** Where a bootloader goes, as an offset from the start of the update buffer (VESC Tool's fwUpload). */
    fun bootloaderOffset(type: HardwareType, hardwareName: String): Int = when {
        type == HardwareType.VESC -> 3 * 128 * 1024
        type == HardwareType.VESC_BMS || hardwareName == "hm1" -> 0x0803E000 - 0x08020000
        else -> 0x0801E000 - 0x08010000
    }
}
