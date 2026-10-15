package app.foctuner.config

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigCodecTest {
    private val mcconf = BundledDefinitions.load("7.01", ConfigKind.MCCONF)
    private val appconf = BundledDefinitions.load("7.01", ConfigKind.APPCONF)

    private fun param(name: String, type: Int, vTx: Int? = null, scale: Int? = null) =
        "<$name><type>$type</type>" +
            (vTx?.let { "<vTx>$it</vTx>" } ?: "") +
            (scale?.let { "<vTxDoubleScale>$it</vTxDoubleScale>" } ?: "") +
            "</$name>"

    private val allTypes = ConfigXmlParser.parse(
        "<ConfigParams><Params>" +
            param("d16", 1, 7, 10) + param("d16n", 1, 7, 10) + param("d32", 1, 8, 1000) +
            param("auto1", 1, 9) + param("auto2", 1, 9) +
            param("u8", 2, 1) + param("i8", 2, 2) + param("u16", 2, 3) + param("i16", 2, 4) +
            param("u32", 2, 5) + param("i32", 2, 6) +
            param("en", 4) + param("bo", 5) + param("bf", 6) + param("s1", 3) + param("s2", 3) +
            param("unsent", 2, 1) +
            "</Params><SerOrder>" +
            listOf("d16", "d16n", "d32", "auto1", "auto2", "u8", "i8", "u16", "i16", "u32", "i32", "en", "bo", "bf", "s1", "s2")
                .joinToString("") { "<ser>$it</ser>" } +
            "</SerOrder></ConfigParams>",
    )

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun u32Hex(v: Long) = "%08x".format(v)

    @Test
    fun everyWireTypeEncodesToKnownBytes() {
        val config = Config.defaults(allTypes)
            .with("d16", 0.25) // 2.5 rounds away from zero; the firmware's float cast would truncate to 2
            .with("d16n", -0.25)
            .with("d32", 1.2345)
            .with("auto1", 1.0)
            .with("auto2", -2.5)
            .with("u8", 200L).with("i8", -3L).with("u16", 65535L).with("i16", -2L)
            .with("u32", 4_000_000_000L).with("i32", -5L)
            .with("en", 2L).with("bo", true).with("bf", 200L)
            .with("s1", "ab").with("s2", "é")
            .with("unsent", 77L)

        val bytes = ConfigCodec.serialize(config)

        assertEquals(
            u32Hex(allTypes.signature) + "0003" + "fffd" + "000004d3" + "3f800000" + "c0200000" +
                "c8" + "fd" + "ffff" + "fffe" + "ee6b2800" + "fffffffb" + "02" + "01" + "c8" + "616200" + "c3a900",
            hex(bytes),
        )

        val back = ConfigCodec.deserialize(allTypes, bytes)
        assertEquals(0.3, back.double("d16"))
        assertEquals(-0.3, back.double("d16n"))
        assertEquals(1.235, back.double("d32"))
        assertEquals(1.0, back.double("auto1"))
        assertEquals(-2.5, back.double("auto2"))
        assertEquals(4_000_000_000L, back.int("u32"))
        assertEquals(-3L, back.int("i8"))
        assertEquals(200L, back.int("bf"))
        assertTrue(back.bool("bo"))
        assertEquals("é", back.text("s2"))
        assertEquals(0L, back.int("unsent"))
    }

    @Test
    fun enumsAreSignedAndBitfieldsUnsignedWhenRead() {
        val payload = ByteWriter().u32(allTypes.signature).also { w ->
            w.u16(0).u16(0).u32(0).u32(0).u32(0) // d16 d16n d32 auto1 auto2
            w.u8(0).u8(0).u16(0).u16(0).u32(0L).u32(0L) // u8 i8 u16 i16 u32 i32
            w.u8(0xFF).u8(0).u8(0xFF).u8(0).u8(0) // en bo bf s1 s2
        }.toByteArray()
        val config = ConfigCodec.deserialize(allTypes, payload)
        assertEquals(-1L, config.int("en"))
        assertEquals(255L, config.int("bf"))
    }

    @Test
    fun defaultsRoundTripByteForByte() {
        for (def in listOf(mcconf, appconf)) {
            val bytes = ConfigCodec.serialize(Config.defaults(def))
            assertEquals(def.signature, ByteReader(bytes).u32())

            val back = ConfigCodec.deserialize(def, bytes)
            assertContentEquals(bytes, ConfigCodec.serialize(back))
        }
    }

    @Test
    fun editedValuesRoundTrip() {
        val config = Config.defaults(mcconf)
            .with("l_current_max", 75.5)
            .with("l_min_vin", 8.3)
            .with("pwm_mode", 2L)
            .with("foc_hfi_start_samples", 40L)
        val back = ConfigCodec.deserialize(mcconf, ConfigCodec.serialize(config))
        assertEquals(75.5, back.double("l_current_max"))
        assertEquals(8.3, back.double("l_min_vin"))
        assertEquals(2L, back.int("pwm_mode"))
        assertEquals(40L, back.int("foc_hfi_start_samples"))
    }

    @Test
    fun unserializedParametersKeepTheirBaseValue() {
        val base = Config.defaults(mcconf).with("motor_brand", "Acme").with("l_current_max", 10.0)
        val back = ConfigCodec.deserialize(mcconf, ConfigCodec.serialize(Config.defaults(mcconf)), base)
        assertEquals("Acme", back.text("motor_brand"))
        assertEquals(60.0, back.double("l_current_max"))
    }

    @Test
    fun rejectsPayloadsMadeForAnotherLayout() {
        val mc = ConfigCodec.serialize(Config.defaults(mcconf))

        val wrongDefinition = assertFailsWith<SignatureMismatchException> { ConfigCodec.deserialize(appconf, mc) }
        assertEquals(appconf.signature, wrongDefinition.expected)
        assertEquals(mcconf.signature, wrongDefinition.actual)

        val flipped = mc.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertFailsWith<SignatureMismatchException> { ConfigCodec.deserialize(mcconf, flipped) }
        assertEquals(mcconf.signature xor 1, ConfigCodec.signatureOf(flipped))
    }

    @Test
    fun rejectsTruncatedAndOversizedPayloads() {
        val bytes = ConfigCodec.serialize(Config.defaults(mcconf))
        assertFailsWith<PayloadException> { ConfigCodec.deserialize(mcconf, bytes.copyOf(bytes.size - 1)) }
        assertFailsWith<PayloadException> { ConfigCodec.deserialize(mcconf, bytes + 0) }
        assertFailsWith<PayloadException> { ConfigCodec.deserialize(mcconf, bytes.copyOf(3)) }
        assertFailsWith<PayloadException> { ConfigCodec.deserialize(mcconf, ByteArray(0)) }

        val unterminated = ConfigCodec.serialize(Config.defaults(allTypes).with("s2", "x")).dropLast(1).toByteArray()
        assertFailsWith<PayloadException> { ConfigCodec.deserialize(allTypes, unterminated) }
    }

    @Test
    fun refusesToSerializeValuesThatDoNotFit() {
        fun fails(name: String, value: ParamValue, def: ConfigDefinition = allTypes): String {
            val e = assertFailsWith<IllegalArgumentException> {
                ConfigCodec.serialize(Config.defaults(def).with(name, value))
            }
            val message = e.message.orEmpty()
            assertTrue(message.startsWith("$name: "), message)
            return message
        }
        fun long(v: Long) = ParamValue.IntValue(v)

        fails("u8", long(256))
        fails("u8", long(-1))
        fails("i8", long(128))
        fails("u16", long(65536))
        fails("i16", long(40000))
        fails("u32", long(-1))
        fails("u32", long(4_294_967_296L))
        fails("i32", long(2_147_483_648L))
        fails("en", long(128))
        fails("bf", long(256))
        fails("s1", ParamValue.TextValue("a\u0000b"))
        fails("d16", ParamValue.DoubleValue(3277.0)) // 32770 does not fit int16
        fails("d16", ParamValue.DoubleValue(Double.NaN))
        fails("d32", ParamValue.DoubleValue(3_000_000.0))
        fails("auto1", ParamValue.DoubleValue(Double.POSITIVE_INFINITY))
        fails("auto1", ParamValue.DoubleValue(1e300))
        fails("l_in_current_map_start", ParamValue.DoubleValue(4.0), mcconf)
    }

    @Test
    fun float32AutoMatchesVescToolsAlgorithm() {
        val def = allTypes
        val rng = Random(20261015)
        val samples = List(2000) { rng.nextDouble(-1.0, 1.0) * 10.0.pow(rng.nextInt(-37, 38)) } +
            listOf(0.0, -0.0, 1.0, 1e-39, 1.4e-38, 1.5e-38, -1.5e-38, 1.6e-38, 3.0e38, Float.MAX_VALUE.toDouble(), 0.1)

        for (v in samples) {
            val bytes = ConfigCodec.serialize(Config.defaults(def).with("auto1", v)).copyOfRange(12, 16)
            assertEquals(u32Hex(vescDouble32Auto(v)), hex(bytes), "value $v")

            val back = ByteReader(bytes).f32Auto().toDouble()
            val flushed = if (abs(v) < 1.5e-38) 0.0 else v.toFloat().toDouble()
            assertEquals(flushed, back, "value $v")
        }
    }

    /** `VByteArray::vbAppendDouble32Auto` with `frexpf` spelled out. */
    private fun vescDouble32Auto(number: Double): Long {
        val f = (if (abs(number) < 1.5e-38) 0.0 else number).toFloat()
        var e = 0
        var fr = 0f
        if (f != 0f) {
            e = Math.getExponent(f) + 1
            fr = Math.scalb(f, -e)
        }
        var frS = 0L
        if (abs(fr) >= 0.5f) {
            frS = ((abs(fr) - 0.5f) * 2.0f * 8388608.0f).toLong()
            e += 126
        }
        var res = ((e and 0xFF).toLong() shl 23) or (frS and 0x7FFFFF)
        if (fr < 0) res = res or (1L shl 31)
        return res
    }
}
