package app.foctuner.protocol

/**
 * CRC-16/XMODEM (poly 0x1021, init 0), the checksum VESC packets use.
 * Ported from `crc16()` in the firmware's util/crc.c.
 */
object Crc16 {
    private val table = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }
        c and 0xFFFF
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = table[((crc shr 8) xor data[i].toInt()) and 0xFF] xor ((crc shl 8) and 0xFFFF)
        }
        return crc
    }
}
