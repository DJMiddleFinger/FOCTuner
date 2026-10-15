package app.foctuner.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ByteBuffersTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun integersAreBigEndian() {
        val b = ByteWriter().u8(0xAB).i16(-2).u16(0x1234).i32(-3).u32(0xFFFFFFFEL).i64(-5L).toByteArray()
        assertContentEquals(
            bytes(0xAB, 0xFF, 0xFE, 0x12, 0x34, 0xFF, 0xFF, 0xFF, 0xFD, 0xFF, 0xFF, 0xFF, 0xFE) +
                bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFB),
            b,
        )
        val r = ByteReader(b)
        assertEquals(0xAB, r.u8())
        assertEquals(-2, r.i16())
        assertEquals(0x1234, r.u16())
        assertEquals(-3, r.i32())
        assertEquals(0xFFFFFFFEL, r.u32())
        assertEquals(-5L, r.i64())
        assertEquals(0, r.remaining)
    }

    @Test
    fun scaledFloatsTruncateTowardZero() {
        val b = ByteWriter().f16(12.349f, 10f).f16(-12.349f, 10f).f32(25.4321f, 1000f).toByteArray()
        val r = ByteReader(b)
        assertEquals(12.3f, r.f16(10f))
        assertEquals(-12.3f, r.f16(10f))
        assertEquals(25.432f, r.f32(1000f))
    }

    @Test
    fun float32AutoKnownValues() {
        assertContentEquals(bytes(0x3F, 0x80, 0, 0), ByteWriter().f32Auto(1.0f).toByteArray())
        assertContentEquals(bytes(0xC0, 0, 0, 0), ByteWriter().f32Auto(-2.0f).toByteArray())
        assertContentEquals(bytes(0, 0, 0, 0), ByteWriter().f32Auto(0f).toByteArray())
        // Below the firmware's cut-off and non-finite values are sent as zero.
        assertContentEquals(bytes(0, 0, 0, 0), ByteWriter().f32Auto(1e-39f).toByteArray())
        assertContentEquals(bytes(0, 0, 0, 0), ByteWriter().f32Auto(Float.NaN).toByteArray())
    }

    @Test
    fun float32AutoRoundTrips() {
        val rnd = Random(7)
        val values = List(2000) {
            val v = Math.scalb(rnd.nextFloat() + 0.5f, rnd.nextInt(-100, 100))
            if (rnd.nextBoolean()) -v else v
        } + listOf(0f, 1f, -1f, 3.14159f, 12345.678f, Float.MAX_VALUE, -Float.MAX_VALUE, 2e-38f)
        for (v in values) {
            val back = ByteReader(ByteWriter().f32Auto(v).toByteArray()).f32Auto()
            assertEquals(v, back, "value $v")
        }
    }

    // Firmware reads an exponent of 0 with a non-zero mantissa as (0.5 + m/2^24) * 2^-126, not an IEEE subnormal.
    @Test
    fun float32AutoDecodesSubnormalPatternLikeFirmware() {
        val v = ByteReader(bytes(0, 0, 0, 1)).f32Auto()
        assertEquals(Math.scalb(0.5f + 1f / 16777216f, -126), v)
    }

    @Test
    fun float64AutoKeepsMoreThanFloatPrecision() {
        val x = 1234567.123456789
        val back = ByteReader(ByteWriter().f64Auto(x).toByteArray()).f64Auto()
        assertEquals(x, back, 1e-6)
    }

    @Test
    fun cStringsAreNulTerminated() {
        val b = ByteWriter().cString("hi").cString("").bytes(bytes(9)).toByteArray()
        assertContentEquals(bytes('h'.code, 'i'.code, 0, 0, 9), b)
        val r = ByteReader(b)
        assertEquals("hi", r.cString())
        assertEquals("", r.cString())
        assertContentEquals(bytes(9), r.restBytes())
    }
}
