package app.foctuner.vesc.firmware

import java.io.ByteArrayOutputStream

/**
 * Intel HEX to a flat image, as VESC Tool's HexFile does it: data from the lowest address on, gaps filled
 * with 0xFF (erased flash). Record types 00, 01, 02, 03, 04 and 05; every line's checksum is verified.
 */
internal object IntelHex {
    private const val MAX_IMAGE = FirmwareImage.MAX_FILE_SIZE

    fun parse(text: String): ByteArray {
        val segments = sortedMapOf<Long, ByteArray>()
        var base = 0L
        var currentStart = -1L
        var current = ByteArrayOutputStream()
        var endSeen = false

        fun closeSegment() {
            if (current.size() > 0) segments[currentStart] = current.toByteArray()
            current = ByteArrayOutputStream()
        }

        for ((index, raw) in text.lineSequence().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val lineNo = index + 1
            if (endSeen) throw FirmwareFileException(FirmwareFileException.Reason.BadHex, "Data after the end-of-file record (line $lineNo)")
            if (line[0] != ':' || line.length % 2 != 1) throw FirmwareFileException(FirmwareFileException.Reason.BadHex, "Line $lineNo is not a hex record")
            val bytes = ByteArray(line.length / 2) { i ->
                val hi = Character.digit(line[1 + 2 * i], 16)
                val lo = Character.digit(line[2 + 2 * i], 16)
                if (hi < 0 || lo < 0) throw FirmwareFileException(FirmwareFileException.Reason.BadHex, "Line $lineNo has a non-hex character")
                ((hi shl 4) or lo).toByte()
            }
            if (bytes.size < 5) throw FirmwareFileException(FirmwareFileException.Reason.BadHex, "Line $lineNo is too short")
            val length = bytes[0].toInt() and 0xFF
            if (bytes.size != 5 + length) throw FirmwareFileException(FirmwareFileException.Reason.BadHex, "Line $lineNo has the wrong length")
            if (bytes.sumOf { it.toInt() and 0xFF } and 0xFF != 0) {
                throw FirmwareFileException(FirmwareFileException.Reason.BadHex, "Line $lineNo has a bad checksum")
            }
            val address = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
            val data = bytes.copyOfRange(4, 4 + length)
            when (bytes[3].toInt()) {
                0 -> {
                    val absolute = base + address
                    if (absolute != currentStart + current.size()) {
                        closeSegment()
                        currentStart = absolute
                    }
                    current.write(data)
                }
                1 -> endSeen = true
                2, 4 -> {
                    if (length != 2) throw FirmwareFileException(FirmwareFileException.Reason.BadHex, "Line $lineNo has a bad address record")
                    val value = ((data[0].toLong() and 0xFF) shl 8) or (data[1].toLong() and 0xFF)
                    base = if (bytes[3].toInt() == 2) value shl 4 else value shl 16
                }
                3, 5 -> Unit
                else -> throw FirmwareFileException(FirmwareFileException.Reason.BadHex, "Line $lineNo has an unknown record type")
            }
        }
        if (!endSeen) throw FirmwareFileException(FirmwareFileException.Reason.BadHex, "The file has no end-of-file record")
        closeSegment()
        if (segments.isEmpty()) throw FirmwareFileException(FirmwareFileException.Reason.Empty, "The file contains no data")

        val start = segments.firstKey()
        val (lastStart, lastData) = segments.entries.last().let { it.key to it.value }
        val size = lastStart + lastData.size - start
        if (size > MAX_IMAGE) throw FirmwareFileException(FirmwareFileException.Reason.TooLarge, "The data spans $size bytes")
        val image = ByteArray(size.toInt()) { 0xFF.toByte() }
        var end = start
        for ((at, data) in segments) {
            if (at < end) throw FirmwareFileException(FirmwareFileException.Reason.BadHex, "Overlapping data at 0x${at.toString(16)}")
            data.copyInto(image, (at - start).toInt())
            end = at + data.size
        }
        return image
    }
}
