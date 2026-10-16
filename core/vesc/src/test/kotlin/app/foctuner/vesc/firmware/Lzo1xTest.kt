package app.foctuner.vesc.firmware

import org.anarres.lzo.LzoCompressor1x_1
import org.anarres.lzo.LzoCompressor1x_999
import org.anarres.lzo.LzoDecompressor1x_safe
import org.anarres.lzo.LzoTransformer
import org.anarres.lzo.lzo_uintp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** lzo-java is a translation of liblzo, so its safe decompressor stands in for the firmware's minilzo. */
class Lzo1xTest {
    private val samples: Map<String, ByteArray> = buildMap {
        for (n in 0..6) put("$n bytes", ByteArray(n) { (it * 7).toByte() })
        put("zeros", ByteArray(384))
        put("erased flash", ByteArray(384) { 0xFF.toByte() })
        put("random", Random(1).nextBytes(384))
        put("text", "VESC firmware update chunk, VESC firmware update chunk, mostly repeated words ".repeat(5).toByteArray())
        put("firmware-like", firmwareLike(384, seed = 2))
        put("long literal run then match", Random(3).nextBytes(300) + ByteArray(84) { 0x42 })
        put("very long match", ByteArray(5) { it.toByte() } + ByteArray(1000) { 9 })
        put("M3 distances", firmwareLike(20_000, seed = 4).let { it + it.copyOfRange(0, 3000) })
        put("M4 distances", Random(5).nextBytes(40_000).let { it + it.copyOfRange(100, 6000) })
        put("large firmware-like", firmwareLike(65_536, seed = 6))
    }

    @Test
    fun knownVector() {
        val stream = byteArrayOf(18, 'a'.code.toByte(), 39, 0, 0, 17, 0, 0)
        assertContentEquals(stream, Lzo1x.compress("aaaaaaaaaa".toByteArray()))
        assertContentEquals("aaaaaaaaaa".toByteArray(), referenceDecompress(stream, 10))
    }

    @Test
    fun liblzoReadsWhatWeWrite() {
        for ((name, data) in samples) {
            val packed = Lzo1x.compress(data)
            assertContentEquals(data, referenceDecompress(packed, data.size), name)
            assertContentEquals(data, Lzo1x.decompress(packed, data.size), name)
        }
    }

    @Test
    fun weReadWhatLiblzoWrites() {
        for ((name, data) in samples) {
            assertContentEquals(data, Lzo1x.decompress(referenceCompress(data, optimal = false), data.size), "$name, LZO1X-1")
            // LZO1X-999 also uses the short M1 matches.
            assertContentEquals(data, Lzo1x.decompress(referenceCompress(data, optimal = true), data.size), "$name, LZO1X-999")
        }
    }

    @Test
    fun compressesAboutAsWellAsLzo1x1() {
        for ((name, data) in samples) {
            if (data.size < 384) continue
            val ours = Lzo1x.compress(data).size
            val reference = referenceCompress(data, optimal = false).size
            assertTrue(ours <= reference * 11 / 10 + 8, "$name: $ours bytes, LZO1X-1 $reference")
        }
        assertTrue(Lzo1x.compress(ByteArray(384)).size < 16)
    }

    @Test
    fun rejectsBrokenStreams() {
        val data = firmwareLike(384, seed = 8)
        val packed = Lzo1x.compress(data)
        assertFailsWith<LzoException> { Lzo1x.decompress(packed.copyOf(packed.size - 4), data.size) }
        assertFailsWith<LzoException> { Lzo1x.decompress(packed, data.size - 1) }
        assertFailsWith<LzoException> { Lzo1x.decompress(packed + 0.toByte(), data.size) }
        // One literal, then a 3-byte match 5 back.
        assertFailsWith<LzoException> { Lzo1x.decompress(byteArrayOf(18, 1, (2 shl 5 or (4 shl 2)).toByte(), 0, 17, 0, 0), 16) }
    }

    private fun referenceDecompress(packed: ByteArray, size: Int): ByteArray {
        val out = ByteArray(size)
        val length = lzo_uintp(size)
        val result = LzoDecompressor1x_safe().decompress(packed, 0, packed.size, out, 0, length)
        assertEquals(LzoTransformer.LZO_E_OK, result, "liblzo result")
        assertEquals(size, length.value)
        return out
    }

    private fun referenceCompress(data: ByteArray, optimal: Boolean): ByteArray {
        val out = ByteArray(data.size + data.size / 16 + 64 + 3)
        val length = lzo_uintp(out.size)
        val compressor = if (optimal) LzoCompressor1x_999(9) else LzoCompressor1x_1()
        assertEquals(LzoTransformer.LZO_E_OK, compressor.compress(data, 0, data.size, out, 0, length))
        return out.copyOf(length.value)
    }
}

/** Something like Thumb code: 16-bit words from a small vocabulary with runs and repeats. */
internal fun firmwareLike(size: Int, seed: Int): ByteArray {
    val random = Random(seed)
    val words = IntArray(48) { random.nextInt(0x10000) }
    val out = ByteArray(size)
    var i = 0
    while (i < size) {
        val w = if (random.nextInt(6) == 0) random.nextInt(0x10000) else words[random.nextInt(words.size)]
        out[i++] = w.toByte()
        if (i < size) out[i++] = (w ushr 8).toByte()
    }
    return out
}
