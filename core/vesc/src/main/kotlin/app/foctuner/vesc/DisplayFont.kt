package app.foctuner.vesc

import kotlin.math.abs

/**
 * Bitmap fonts for LispBM display drivers in the format VESC Tool's display tool exports (`pagedisplaytool.cpp`):
 * four header bytes (glyph width, glyph height, glyph count, bits per pixel), then each glyph's pixels row by row.
 * With 1 bit per pixel a lit pixel sets bit `i % 8` of byte `i / 8`; with 2 bits each pixel is the nearest of
 * the grays 0x00, 0x55, 0xAA, 0xFF, four pixels per byte from the low bits up. Glyphs are the 95 printable ASCII
 * characters from space, or the 10 digits.
 */
object DisplayFont {
    /** The characters a font holds. */
    fun characters(digitsOnly: Boolean): List<Char> = if (digitsOnly) ('0'..'9').toList() else (' '..'~').toList()

    /**
     * [glyphs] holds each character's pixels as gray levels 0..255, row by row, [width] × [height] of them.
     *
     * @throws IllegalArgumentException for sizes the header can't hold or glyphs of the wrong size
     */
    fun encode(width: Int, height: Int, glyphs: List<IntArray>, bitsPerPixel: Int): ByteArray {
        require(width in 1..255 && height in 1..255) { "Glyphs are 1 to 255 pixels on each side" }
        require(glyphs.size in 1..255) { "1 to 255 glyphs" }
        require(bitsPerPixel == 1 || bitsPerPixel == 2) { "1 or 2 bits per pixel" }
        val pixels = width * height
        require(glyphs.all { it.size == pixels }) { "Every glyph needs $pixels pixels" }
        val bytesPerGlyph = (pixels * bitsPerPixel + 7) / 8
        val out = ByteArray(4 + bytesPerGlyph * glyphs.size)
        out[0] = width.toByte()
        out[1] = height.toByte()
        out[2] = glyphs.size.toByte()
        out[3] = bitsPerPixel.toByte()
        glyphs.forEachIndexed { g, gray ->
            val base = 4 + bytesPerGlyph * g
            for (i in 0 until pixels) {
                if (bitsPerPixel == 1) {
                    if (gray[i] != 0) out[base + i / 8] = (out[base + i / 8].toInt() or (1 shl (i % 8))).toByte()
                } else {
                    val level = PALETTE.indices.minBy { abs(gray[i] - PALETTE[it]) }
                    out[base + i / 4] = (out[base + i / 4].toInt() or (level shl ((i % 4) * 2))).toByte()
                }
            }
        }
        return out
    }

    private val PALETTE = intArrayOf(0x00, 0x55, 0xAA, 0xFF)
}
