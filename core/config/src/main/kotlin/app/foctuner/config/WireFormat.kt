package app.foctuner.config

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Per-parameter encodings, following VESC Tool's `VByteArray` rather than the firmware's `buffer.c`: scaled
 * doubles are rounded half away from zero in double precision where the firmware truncates a float, and
 * `float32_auto` takes a double.
 */
internal object WireFormat {
    private const val FLOAT32_AUTO_MIN = 1.5e-38

    /** Why [value] cannot be put on the wire for [p], or null when it can. */
    fun problem(p: ParamDefinition, value: ParamValue): String? {
        when (p.type) {
            ParamType.DOUBLE -> {
                val d = (value as? ParamValue.DoubleValue)?.value ?: return "not a number"
                if (!d.isFinite()) return "$d is not finite"
                return when (p.wire) {
                    WireType.DOUBLE16 -> scaledProblem(d, p, Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong(), "double16")
                    WireType.DOUBLE32 -> scaledProblem(d, p, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), "double32")
                    else -> if (d.toFloat().isFinite()) null else "$d is too large for float32"
                }
            }
            ParamType.INT -> {
                val v = (value as? ParamValue.IntValue)?.value ?: return "not an integer"
                val (min, max) = intRange(p.wire)
                return if (v in min..max) null else "$v does not fit ${p.wire.name.lowercase()} ($min..$max)"
            }
            ParamType.ENUM, ParamType.BOOL -> return int8Problem(value, -128, 127)
            ParamType.BITFIELD -> return int8Problem(value, 0, 255)
            ParamType.STRING -> {
                val s = (value as? ParamValue.TextValue)?.value ?: return "not a string"
                return if ('\u0000' in s) "contains a NUL character" else null
            }
            ParamType.UNDEFINED -> return "has no wire encoding"
        }
    }

    fun write(p: ParamDefinition, value: ParamValue, w: ByteWriter) {
        when (p.type) {
            ParamType.DOUBLE -> {
                val d = (value as ParamValue.DoubleValue).value
                when (p.wire) {
                    WireType.DOUBLE16 -> w.i16(scaled(d, p).toInt())
                    WireType.DOUBLE32 -> w.i32(scaled(d, p).toInt())
                    else -> w.f32Auto(if (abs(d) < FLOAT32_AUTO_MIN) 0f else d.toFloat())
                }
            }
            ParamType.INT -> {
                val v = (value as ParamValue.IntValue).value
                when (p.wire) {
                    WireType.UINT8 -> w.u8(v.toInt())
                    WireType.INT8 -> w.i8(v.toInt())
                    WireType.UINT16 -> w.u16(v.toInt())
                    WireType.INT16 -> w.i16(v.toInt())
                    WireType.UINT32 -> w.u32(v)
                    else -> w.i32(v.toInt())
                }
            }
            ParamType.ENUM, ParamType.BOOL, ParamType.BITFIELD -> w.i8((value as ParamValue.IntValue).value.toInt())
            ParamType.STRING -> w.cString((value as ParamValue.TextValue).value)
            ParamType.UNDEFINED -> throw IllegalArgumentException("${p.name} has no wire encoding")
        }
    }

    /** The bytes [write] produces for [value], or null when [problem] rejects it. */
    fun encode(p: ParamDefinition, value: ParamValue): ByteArray? {
        if (problem(p, value) != null) return null
        return ByteWriter(8).also { write(p, value, it) }.toByteArray()
    }

    fun read(p: ParamDefinition, r: ByteReader): ParamValue {
        if (p.type == ParamType.STRING) return ParamValue.TextValue(readString(p, r))
        need(p, r, width(p))
        return when (p.type) {
            ParamType.DOUBLE -> ParamValue.DoubleValue(
                when (p.wire) {
                    WireType.DOUBLE16 -> r.i16().toDouble() / p.wireScale
                    WireType.DOUBLE32 -> r.i32().toDouble() / p.wireScale
                    else -> r.f32Auto().toDouble()
                },
            )
            ParamType.INT -> ParamValue.IntValue(
                when (p.wire) {
                    WireType.UINT8 -> r.u8().toLong()
                    WireType.INT8 -> r.i8().toLong()
                    WireType.UINT16 -> r.u16().toLong()
                    WireType.INT16 -> r.i16().toLong()
                    WireType.UINT32 -> r.u32()
                    else -> r.i32().toLong()
                },
            )
            ParamType.BITFIELD -> ParamValue.IntValue(r.u8().toLong())
            else -> ParamValue.IntValue(r.i8().toLong())
        }
    }

    private fun readString(p: ParamDefinition, r: ByteReader): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            need(p, r, 1)
            val b = r.u8()
            if (b == 0) return bytes.toString(Charsets.UTF_8)
            bytes.write(b)
        }
    }

    private fun need(p: ParamDefinition, r: ByteReader, bytes: Int) {
        if (r.remaining < bytes) throw PayloadException("payload ends inside ${p.name}")
    }

    private fun width(p: ParamDefinition): Int = when (p.type) {
        ParamType.DOUBLE -> if (p.wire == WireType.DOUBLE16) 2 else 4
        ParamType.INT -> when (p.wire) {
            WireType.UINT8, WireType.INT8 -> 1
            WireType.UINT16, WireType.INT16 -> 2
            else -> 4
        }
        else -> 1
    }

    private fun intRange(wire: WireType): Pair<Long, Long> = when (wire) {
        WireType.UINT8 -> 0L to 255L
        WireType.INT8 -> -128L to 127L
        WireType.UINT16 -> 0L to 65535L
        WireType.INT16 -> -32768L to 32767L
        WireType.UINT32 -> 0L to 0xFFFFFFFFL
        else -> Int.MIN_VALUE.toLong() to Int.MAX_VALUE.toLong()
    }

    private fun int8Problem(value: ParamValue, min: Long, max: Long): String? {
        val v = (value as? ParamValue.IntValue)?.value ?: return "not an integer"
        return if (v in min..max) null else "$v does not fit one byte ($min..$max)"
    }

    private fun scaledProblem(d: Double, p: ParamDefinition, min: Long, max: Long, name: String): String? {
        val scaled = scaled(d, p)
        return if (scaled >= min && scaled <= max) null else "$d times ${p.wireScale} does not fit $name ($min..$max)"
    }

    private fun scaled(d: Double, p: ParamDefinition): Double {
        val x = d * p.wireScale
        return if (x < 0.0) ceil(x - 0.5) else floor(x + 0.5)
    }
}
