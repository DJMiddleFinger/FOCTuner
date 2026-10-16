package app.foctuner.vesc.firmware

/**
 * Heatshrink (LZSS), as configured in the VESC bootloader: 13 window bits, 5 lookahead bits. Firmware
 * images too large for the 384 KB update buffer are sent compressed and the bootloader unpacks them.
 *
 * The stream is MSB-first bits: `1` + 8-bit literal, or `0` + (distance - 1) in 13 bits + (length - 1) in
 * 5 bits, zero-padded to a whole byte. Padding can never complete an instruction, so it decodes to nothing.
 */
object Heatshrink {
    const val WINDOW_BITS = 13
    const val LOOKAHEAD_BITS = 5
    private const val WINDOW = 1 shl WINDOW_BITS
    private const val MAX_MATCH = 1 shl LOOKAHEAD_BITS

    /** A back-reference costs 19 bits, so it only pays off from 3 bytes (27 bits as literals). */
    private const val MIN_MATCH = 3
    private const val HASH_BITS = 14
    private const val MAX_CHAIN = 256

    fun encode(data: ByteArray): ByteArray {
        val bits = BitWriter(data.size + data.size / 8 + 1)
        val head = IntArray(1 shl HASH_BITS) { -1 }
        val previous = IntArray(data.size)

        fun insert(pos: Int) {
            if (pos + MIN_MATCH > data.size) return
            val h = hash(data, pos)
            previous[pos] = head[h]
            head[h] = pos
        }

        var ip = 0
        while (ip < data.size) {
            var bestLength = 0
            var bestPos = -1
            if (ip + MIN_MATCH <= data.size) {
                val limit = minOf(MAX_MATCH, data.size - ip)
                var candidate = head[hash(data, ip)]
                var chain = MAX_CHAIN
                while (candidate >= 0 && chain-- > 0 && ip - candidate <= WINDOW) {
                    var n = 0
                    while (n < limit && data[candidate + n] == data[ip + n]) n++
                    if (n > bestLength) {
                        bestLength = n
                        bestPos = candidate
                        if (n == limit) break
                    }
                    candidate = previous[candidate]
                }
            }
            if (bestLength >= MIN_MATCH) {
                bits.write(0, 1)
                bits.write(ip - bestPos - 1, WINDOW_BITS)
                bits.write(bestLength - 1, LOOKAHEAD_BITS)
                repeat(bestLength) { insert(ip + it) }
                ip += bestLength
            } else {
                bits.write(1, 1)
                bits.write(data[ip].toInt() and 0xFF, 8)
                insert(ip)
                ip++
            }
        }
        return bits.toByteArray()
    }

    /**
     * Unpacks [data] like the bootloader's decoder, whose window starts out zeroed (a reference before the
     * start reads zeros). Stops at the first instruction the remaining bits can't complete.
     */
    fun decode(data: ByteArray): ByteArray {
        val bits = BitReader(data)
        var out = ByteArray(data.size * 2 + 16)
        var size = 0

        fun push(b: Byte) {
            if (size == out.size) out = out.copyOf(out.size * 2)
            out[size++] = b
        }

        while (true) {
            if (bits.remaining < 1) break
            if (bits.read(1) == 1) {
                if (bits.remaining < 8) break
                push(bits.read(8).toByte())
            } else {
                if (bits.remaining < WINDOW_BITS + LOOKAHEAD_BITS) break
                val distance = bits.read(WINDOW_BITS) + 1
                val length = bits.read(LOOKAHEAD_BITS) + 1
                repeat(length) { push(if (size >= distance) out[size - distance] else 0) }
            }
        }
        return out.copyOf(size)
    }

    private fun hash(data: ByteArray, pos: Int): Int {
        val v = (data[pos].toInt() and 0xFF) or
            ((data[pos + 1].toInt() and 0xFF) shl 8) or
            ((data[pos + 2].toInt() and 0xFF) shl 16)
        return (v * 0x1E35A7BD) ushr (32 - HASH_BITS)
    }

    private class BitWriter(capacity: Int) {
        private var buf = ByteArray(maxOf(capacity, 16))
        private var bitCount = 0L

        fun write(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                val index = (bitCount ushr 3).toInt()
                if (index == buf.size) buf = buf.copyOf(buf.size * 2)
                if ((value ushr i) and 1 != 0) buf[index] = (buf[index].toInt() or (0x80 ushr (bitCount and 7).toInt())).toByte()
                bitCount++
            }
        }

        fun toByteArray(): ByteArray = buf.copyOf(((bitCount + 7) ushr 3).toInt())
    }

    private class BitReader(private val data: ByteArray) {
        private var bitPos = 0L

        val remaining: Long get() = data.size * 8L - bitPos

        fun read(count: Int): Int {
            var v = 0
            repeat(count) {
                val b = data[(bitPos ushr 3).toInt()].toInt()
                v = (v shl 1) or ((b ushr (7 - (bitPos and 7).toInt())) and 1)
                bitPos++
            }
            return v
        }
    }
}
