package app.foctuner.vesc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DisplayFontTest {
    @Test
    fun oneBitGlyphsPackLeastSignificantBitFirst() {
        // 3×3, a diagonal: pixels 0, 4 and 8 lit.
        val diagonal = intArrayOf(255, 0, 0, 0, 255, 0, 0, 0, 255)
        val font = DisplayFont.encode(3, 3, listOf(diagonal, IntArray(9)), bitsPerPixel = 1)
        assertContentEquals(byteArrayOf(3, 3, 2, 1, 0x11, 0x01, 0, 0), font)
    }

    @Test
    fun twoBitGlyphsUseTheNearestGray() {
        val gray = intArrayOf(0, 90, 160, 250, 40)
        val font = DisplayFont.encode(5, 1, listOf(gray), bitsPerPixel = 2)
        // levels 0, 1, 2, 3 in the first byte (low bits first), then level 0 (40 is nearest 0x00)
        assertContentEquals(byteArrayOf(5, 1, 1, 2, 0b11100100.toByte(), 0), font)
    }

    @Test
    fun characterSetsAndLimits() {
        assertEquals(95, DisplayFont.characters(false).size)
        assertEquals('~', DisplayFont.characters(false).last())
        assertEquals(10, DisplayFont.characters(true).size)
        assertFailsWith<IllegalArgumentException> { DisplayFont.encode(0, 3, listOf(IntArray(0)), 1) }
        assertFailsWith<IllegalArgumentException> { DisplayFont.encode(2, 2, listOf(IntArray(3)), 1) }
    }
}
