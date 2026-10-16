package app.foctuner.vesc.firmware

import java.io.IOException

class LzoException(message: String) : IOException(message)

/**
 * LZO1X, the format the firmware unpacks with minilzo's `lzo1x_decompress_safe` when it receives
 * COMM_WRITE_NEW_APP_DATA_LZO.
 *
 * [compress] is a greedy LZO1X-1 style encoder: literal runs and M2/M3/M4 matches of at least 4 bytes,
 * never the short M1 forms. [decompress] follows `lzo1x_decompress_safe` and reads any LZO1X stream.
 */
object Lzo1x {
    private const val MIN_MATCH = 4
    private const val M2_MAX_LEN = 8
    private const val M3_MAX_LEN = 33
    private const val M4_MAX_LEN = 9
    private const val M2_MAX_OFFSET = 0x0800
    private const val M3_MAX_OFFSET = 0x4000
    private const val M4_MAX_OFFSET = 0xBFFF
    private const val HASH_BITS = 12
    private const val MAX_CHAIN = 128

    fun compress(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= src.size) { "range outside the array" }
        val end = offset + length
        val out = Output(length + length / 16 + 64 + 3)
        val head = IntArray(1 shl HASH_BITS) { -1 }
        val previous = IntArray(length)

        fun insert(pos: Int) {
            val h = hash(src, pos)
            previous[pos - offset] = head[h]
            head[h] = pos
        }

        var ip = offset
        var literalStart = offset
        while (ip + MIN_MATCH <= end) {
            var bestLength = 0
            var bestPos = -1
            var candidate = head[hash(src, ip)]
            var chain = MAX_CHAIN
            while (candidate >= 0 && chain-- > 0 && ip - candidate <= M4_MAX_OFFSET) {
                var n = 0
                while (ip + n < end && src[candidate + n] == src[ip + n]) n++
                if (n > bestLength) {
                    bestLength = n
                    bestPos = candidate
                    if (ip + n == end) break
                }
                candidate = previous[candidate - offset]
            }
            insert(ip)
            if (bestLength >= MIN_MATCH) {
                out.literals(src, literalStart, ip)
                out.match(bestLength, ip - bestPos)
                for (p in ip + 1 until ip + bestLength) if (p + MIN_MATCH <= end) insert(p)
                ip += bestLength
                literalStart = ip
            } else {
                ip++
            }
        }
        out.literals(src, literalStart, end)
        out.byte(16 or 1)
        out.byte(0)
        out.byte(0)
        return out.toByteArray()
    }

    /**
     * Unpacks [src], which must decode to at most [maxOutput] bytes and end with the end-of-stream marker.
     *
     * @throws LzoException for a stream that overruns its input or [maxOutput], or refers to data before
     *   its start
     */
    fun decompress(src: ByteArray, maxOutput: Int): ByteArray {
        val input = Input(src)
        val out = ByteArray(maxOutput)
        var op = 0

        fun literals(n: Int) {
            if (op + n > maxOutput) throw LzoException("Output overrun")
            input.copy(out, op, n)
            op += n
        }

        fun match(distance: Int, n: Int) {
            if (distance > op) throw LzoException("Match before the start of the output")
            if (op + n > maxOutput) throw LzoException("Output overrun")
            repeat(n) {
                out[op] = out[op - distance]
                op++
            }
        }

        // Trailing literals of the last match (1..3), 4 after a literal run, 0 when a literal run may follow.
        var state = 0
        if (src.isNotEmpty() && (src[0].toInt() and 0xFF) > 17) {
            val t = input.next() - 17
            literals(t)
            state = if (t < 4) t else 4
        }
        while (true) {
            val t = input.next()
            when {
                t < 16 && state == 0 -> {
                    literals((if (t == 0) 15 + input.extended() else t) + 3)
                    state = 4
                    continue
                }
                t < 16 && state == 4 -> match(1 + M2_MAX_OFFSET + (t ushr 2) + (input.next() shl 2), 3)
                t < 16 -> match(1 + (t ushr 2) + (input.next() shl 2), 2)
                t >= 64 -> match(1 + ((t ushr 2) and 7) + (input.next() shl 3), (t ushr 5) + 1)
                t >= 32 -> {
                    val n = (if (t and 31 == 0) 31 + input.extended() else t and 31) + 2
                    match(1 + input.distance(), n)
                }
                else -> {
                    val n = (if (t and 7 == 0) 7 + input.extended() else t and 7) + 2
                    val distance = ((t and 8) shl 11) + input.distance()
                    if (distance == 0) {
                        if (input.remaining != 0) throw LzoException("Data after the end-of-stream marker")
                        return out.copyOf(op)
                    }
                    match(distance + 0x4000, n)
                }
            }
            state = input.previousByte(2) and 3
            literals(state)
        }
    }

    private fun hash(src: ByteArray, pos: Int): Int {
        val v = (src[pos].toInt() and 0xFF) or
            ((src[pos + 1].toInt() and 0xFF) shl 8) or
            ((src[pos + 2].toInt() and 0xFF) shl 16) or
            ((src[pos + 3].toInt() and 0xFF) shl 24)
        return (v * 0x1824429D) ushr (32 - HASH_BITS)
    }

    private class Output(capacity: Int) {
        private val buf = ByteArray(capacity)
        private var size = 0

        fun byte(v: Int) {
            buf[size++] = v.toByte()
        }

        /** A literal run; one of 1..3 bytes after a match goes into the low bits of that match's distance byte. */
        fun literals(src: ByteArray, from: Int, to: Int) {
            val n = to - from
            if (n == 0) return
            when {
                size == 0 && n <= 238 -> byte(17 + n)
                n <= 3 -> buf[size - 2] = (buf[size - 2].toInt() or n).toByte()
                n <= 18 -> byte(n - 3)
                else -> {
                    byte(0)
                    extended(n - 18)
                }
            }
            src.copyInto(buf, size, from, to)
            size += n
        }

        fun match(length: Int, distance: Int) {
            when {
                length <= M2_MAX_LEN && distance <= M2_MAX_OFFSET -> {
                    val d = distance - 1
                    byte(((length - 1) shl 5) or ((d and 7) shl 2))
                    byte(d ushr 3)
                }
                distance <= M3_MAX_OFFSET -> {
                    val d = distance - 1
                    if (length <= M3_MAX_LEN) {
                        byte(32 or (length - 2))
                    } else {
                        byte(32)
                        extended(length - M3_MAX_LEN)
                    }
                    byte((d and 63) shl 2)
                    byte(d ushr 6)
                }
                else -> {
                    val d = distance - 0x4000
                    val high = (d ushr 11) and 8
                    if (length <= M4_MAX_LEN) {
                        byte(16 or high or (length - 2))
                    } else {
                        byte(16 or high)
                        extended(length - M4_MAX_LEN)
                    }
                    byte((d and 63) shl 2)
                    byte((d ushr 6) and 0xFF)
                }
            }
        }

        private fun extended(count: Int) {
            var rest = count
            while (rest > 255) {
                rest -= 255
                byte(0)
            }
            byte(rest)
        }

        fun toByteArray() = buf.copyOf(size)
    }

    private class Input(private val src: ByteArray) {
        private var ip = 0

        val remaining: Int get() = src.size - ip

        fun next(): Int {
            if (ip >= src.size) throw LzoException("Input overrun")
            return src[ip++].toInt() and 0xFF
        }

        /** Length extension: 255 for each zero byte, plus the first non-zero byte. */
        fun extended(): Int {
            var n = 0
            while (true) {
                val b = next()
                if (b != 0) return n + b
                n += 255
            }
        }

        /** The 14-bit distance of an M3/M4 match; the low two bits of the first byte are the trailing literal count. */
        fun distance(): Int {
            val b0 = next()
            return (b0 ushr 2) + (next() shl 6)
        }

        fun previousByte(back: Int): Int = src[ip - back].toInt() and 0xFF

        fun copy(dst: ByteArray, at: Int, n: Int) {
            if (n > remaining) throw LzoException("Input overrun")
            src.copyInto(dst, at, ip, ip + n)
            ip += n
        }
    }
}
