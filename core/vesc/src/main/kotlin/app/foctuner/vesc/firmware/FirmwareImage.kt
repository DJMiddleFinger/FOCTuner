package app.foctuner.vesc.firmware

import app.foctuner.protocol.Crc16
import java.io.IOException

/** Why a file can't be uploaded. */
class FirmwareFileException(val reason: Reason, message: String) : IOException(message) {
    enum class Reason { NotFirmwareName, Empty, TooLarge, BadHex }
}

enum class HardwareCheck { Match, Mismatch, Unknown }

/**
 * A firmware or bootloader binary to upload, without the size/CRC header the update buffer gets.
 *
 * [hardware] lists the hardware names it is built for: from the server's directory name for downloads,
 * from the `org.vesc.<name>` string the firmware embeds for files (empty if there is none). A [generic]
 * bootloader fits every controller of its hardware type.
 */
class FirmwareImage(
    val name: String,
    val bytes: ByteArray,
    val hardware: List<String>,
    val generic: Boolean = false,
) {
    /** The hardware name the firmware reports, read from the image itself. */
    val embeddedHardware: String? by lazy { embeddedHardware(bytes) }

    fun check(hardwareName: String): HardwareCheck = when {
        generic -> HardwareCheck.Match
        hardware.isEmpty() -> HardwareCheck.Unknown
        hardware.any { it.equals(hardwareName, ignoreCase = true) } -> HardwareCheck.Match
        else -> HardwareCheck.Mismatch
    }

    companion object {
        /** VESC Tool refuses anything larger: no controller has more flash for an application. */
        const val MAX_FILE_SIZE = 8_000_000

        private val EMBEDDED_PREFIX = "org.vesc.".toByteArray(Charsets.US_ASCII)
        private const val MAX_NAME = 40

        /**
         * A firmware file picked by the user: `.bin` or `.hex` (VESC Tool accepts nothing else). A `.bin` that
         * already carries the update header (as saved by some tools) is unwrapped, decompressing it if needed.
         *
         * @throws FirmwareFileException if the file can't be firmware
         */
        fun fromFile(fileName: String, content: ByteArray): FirmwareImage {
            val lower = fileName.lowercase()
            val plain = when {
                content.size > MAX_FILE_SIZE -> throw FirmwareFileException(FirmwareFileException.Reason.TooLarge, "$fileName is ${content.size} bytes")
                lower.endsWith(".hex") -> IntelHex.parse(content.toString(Charsets.US_ASCII))
                lower.endsWith(".bin") -> stripHeader(content)
                else -> throw FirmwareFileException(FirmwareFileException.Reason.NotFirmwareName, "$fileName is not a .bin or .hex file")
            }
            if (plain.isEmpty() || plain.all { it == 0xFF.toByte() }) {
                throw FirmwareFileException(FirmwareFileException.Reason.Empty, "$fileName contains no data")
            }
            return FirmwareImage(fileName, plain, listOfNotNull(embeddedHardware(plain)))
        }

        /** VESC Tool's removeFirmwareHeader: drops a leading size/CRC header whose CRC matches the rest. */
        fun stripHeader(data: ByteArray): ByteArray {
            if (data.size <= UpdateBuffer.HEADER_SIZE) return data
            val size = ((data[0].toLong() and 0xFF) shl 24) or ((data[1].toLong() and 0xFF) shl 16) or
                ((data[2].toLong() and 0xFF) shl 8) or (data[3].toLong() and 0xFF)
            val crc = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            val body = data.copyOfRange(UpdateBuffer.HEADER_SIZE, data.size)
            if (Crc16.compute(body) != crc) return data
            return if (size ushr 24 == UpdateBuffer.COMPRESSED_FLAG.toLong()) Heatshrink.decode(body) else body
        }

        /** The `org.vesc.<HW_NAME>` string every VESC firmware since 3.x contains (its USB/BLE name). */
        fun embeddedHardware(bytes: ByteArray): String? {
            var i = 0
            while (true) {
                i = indexOf(bytes, EMBEDDED_PREFIX, i)
                if (i < 0) return null
                val start = i + EMBEDDED_PREFIX.size
                var end = start
                while (end < bytes.size && end - start <= MAX_NAME && bytes[end] in 0x20..0x7E) end++
                if (end < bytes.size && bytes[end].toInt() == 0 && end > start) {
                    return String(bytes, start, end - start, Charsets.US_ASCII)
                }
                i = start
            }
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
            outer@ for (i in from..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
