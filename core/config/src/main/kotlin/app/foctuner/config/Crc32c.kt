package app.foctuner.config

internal object Crc32c {
    private const val REFLECTED_POLY = 0x82F63B78.toInt()

    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor REFLECTED_POLY else c ushr 1 }
        c
    }

    fun compute(data: ByteArray): Long {
        var crc = -1
        for (b in data) crc = table[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        return crc.inv().toLong() and 0xFFFFFFFFL
    }
}
