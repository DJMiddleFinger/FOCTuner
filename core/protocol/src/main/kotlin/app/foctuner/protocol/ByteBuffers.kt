package app.foctuner.protocol

/*
 * Big-endian read/write helpers matching the firmware's util/buffer.c. Scaled floats are plain integers
 * on the wire (value * scale), truncated toward zero, exactly like the C casts.
 */

/** Smallest magnitude the firmware's float32_auto format keeps; anything smaller is sent as 0. */
private const val FLOAT32_AUTO_MIN = 1.5e-38f

class ByteWriter(initialCapacity: Int = 64) {
    private var buf = ByteArray(initialCapacity)
    private var size = 0

    val length: Int get() = size

    fun toByteArray(): ByteArray = buf.copyOf(size)

    fun u8(v: Int) = apply { ensure(1); buf[size++] = v.toByte() }
    fun i8(v: Int) = u8(v)
    fun u16(v: Int) = apply { ensure(2); put(v, 2) }
    fun i16(v: Int) = u16(v)
    fun u32(v: Long) = apply { ensure(4); put(v.toInt(), 4) }
    fun i32(v: Int) = apply { ensure(4); put(v, 4) }
    fun i64(v: Long) = apply { i32((v shr 32).toInt()); i32(v.toInt()) }

    /** `buffer_append_float16`: value * scale as int16. */
    fun f16(v: Float, scale: Float) = i16((v * scale).toInt())

    /** `buffer_append_float32`: value * scale as int32. */
    fun f32(v: Float, scale: Float) = i32((v * scale).toInt())

    /**
     * `buffer_append_float32_auto`. For every normal float the firmware's frexp/ldexp scheme produces the
     * float's own IEEE-754 bits, so we write those. Values below 1.5e-38 become 0 as in the firmware;
     * NaN and infinity are undefined there and are written as 0.
     */
    fun f32Auto(v: Float) = u32(
        if (!v.isFinite() || kotlin.math.abs(v) < FLOAT32_AUTO_MIN) 0L else v.toRawBits().toLong() and 0xFFFFFFFFL,
    )

    /** `buffer_append_float64_auto`: a float plus the float-sized error of the rounding. */
    fun f64Auto(v: Double) = apply {
        val n = v.toFloat()
        f32Auto(n)
        f32Auto((v - n.toDouble()).toFloat())
    }

    fun bytes(src: ByteArray, offset: Int = 0, length: Int = src.size - offset) = apply {
        ensure(length)
        src.copyInto(buf, size, offset, offset + length)
        size += length
    }

    /** NUL-terminated ASCII/UTF-8 string, as used by terminal and file commands. */
    fun cString(s: String) = apply { bytes(s.encodeToByteArray()); u8(0) }

    private fun put(v: Int, n: Int) {
        for (i in n - 1 downTo 0) buf[size++] = (v shr (8 * i)).toByte()
    }

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }
}

class ByteReader(private val data: ByteArray, var position: Int = 0) {
    val remaining: Int get() = data.size - position

    fun u8(): Int = data[position++].toInt() and 0xFF
    fun i8(): Int = data[position++].toInt()
    fun u16(): Int = (u8() shl 8) or u8()
    fun i16(): Int = u16().toShort().toInt()
    fun i32(): Int = (u16() shl 16) or u16()
    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL
    fun i64(): Long = (i32().toLong() shl 32) or u32()

    fun f16(scale: Float): Float = i16().toFloat() / scale
    fun f32(scale: Float): Float = i32().toFloat() / scale

    /** `buffer_get_float32_auto`, ported literally (including its non-IEEE reading of subnormal patterns). */
    fun f32Auto(): Float {
        val res = u32().toInt()
        var e = (res ushr 23) and 0xFF
        val sigI = res and 0x7FFFFF
        var sig = 0.0f
        if (e != 0 || sigI != 0) {
            sig = (sigI.toDouble() / (8388608.0 * 2.0) + 0.5).toFloat()
            e -= 126
        }
        if (res < 0) sig = -sig
        return Math.scalb(sig, e)
    }

    fun f64Auto(): Double {
        val n = f32Auto().toDouble()
        val err = f32Auto().toDouble()
        return n + err
    }

    fun bytes(n: Int): ByteArray = data.copyOfRange(position, position + n).also { position += n }

    fun restBytes(): ByteArray = bytes(remaining)

    /** Reads a NUL-terminated string; a missing terminator reads to the end of the data. */
    fun cString(): String {
        var end = position
        while (end < data.size && data[end].toInt() != 0) end++
        val s = data.decodeToString(position, end)
        position = minOf(end + 1, data.size)
        return s
    }
}
