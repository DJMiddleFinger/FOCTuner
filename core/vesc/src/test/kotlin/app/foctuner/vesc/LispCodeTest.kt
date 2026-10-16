package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.Crc16
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LispCodeTest {
    @Test
    fun packsLikeVescTool() {
        // Laid out by hand from codeloader.cpp lispPackImports for one 3-byte import tagged "pic".
        val packed = LispCode.pack("(import \"a.bin\" 'pic)\n") { byteArrayOf(1, 2, 3) }
        val code = "(import \"a.bin\" 'pic)\n".encodeToByteArray()
        val r = ByteReader(packed)
        assertEquals(0, r.u16())
        assertContentEquals(code, r.bytes(code.size))
        assertEquals(0, r.u8())
        assertEquals(1, r.i16())
        assertEquals("pic", r.cString())
        val offset = r.i32()
        assertEquals(0, offset % 4)
        assertEquals(4, r.i32())
        assertContentEquals(byteArrayOf(1, 2, 3, 0), packed.copyOfRange(offset + 2, offset + 6))
        assertEquals(offset + 6, packed.size)
    }

    @Test
    fun codeWithoutImportsStillCarriesAnEmptyTable() {
        val packed = LispCode.pack("(+ 1 2)")
        assertEquals(2 + 7 + 1 + 2, packed.size)
        assertEquals(0, ByteReader(packed, 10).i16())
    }

    @Test
    fun imageHeaderHasLengthWithoutFlagsAndCrcWithThem() {
        val packed = LispCode.pack("(def a 1)")
        val r = ByteReader(LispCode.image(packed))
        assertEquals((packed.size - 2).toLong(), r.u32())
        assertEquals(Crc16.compute(packed), r.u16())
    }

    @Test
    fun unpackReturnsCodeAndImportsFromPackedAndStoredForms() {
        val code = "(import \"x/one.bin\" 'one)\n  (import \"two.lbm\" 'two) ; comment\n(print one)"
        val packed = LispCode.pack(code) { if (it.tag == "one") byteArrayOf(9, 8, 7, 6, 5) else "(def b 2)".encodeToByteArray() }
        for (stored in listOf(packed, packed.copyOfRange(2, packed.size))) {
            val program = LispCode.unpack(stored)
            assertEquals(code, program.code)
            assertEquals(listOf("one", "two"), program.imports.keys.toList())
            assertContentEquals(byteArrayOf(9, 8, 7, 6, 5), program.imports["one"])
            assertEquals("(def b 2)", program.imports["two"]?.decodeToString())
        }
    }

    @Test
    fun findsImportsTheWayVescToolDoes() {
        val imports = LispCode.imports("( import \"pkg@a.vescpkg\" 'lib)\n(import \"b c.bin\"  'tag-b) ; x\n(def x \"(import\")")
        assertEquals(listOf(LispImport(1, "pkg@a.vescpkg", "lib"), LispImport(2, "b c.bin", "tag-b")), imports)
        assertFailsWith<LispImportException> { LispCode.imports("(import 'nopath)") }
        assertFailsWith<LispImportException> { LispCode.imports("(import \"a.bin\")") }
        assertFailsWith<LispImportException> { LispCode.pack("(import \"a.bin\" 'a)") }
    }

    @Test
    fun reduceDropsCommentsAndIndentationButNotSemicolonsInStrings() {
        val reduced = LispCode.reduce("; header\n(defun f (x)\n    (print \"a;b\") ; note\n\t\n  )\n")
        assertEquals("(defun f (x)\n(print \"a;b\")\n)\n", reduced)
    }

    @Test
    fun reduceAppliesToLispImportsOnly() {
        val code = "(import \"lib.lisp\" 'lib)\n(import \"raw.bin\" 'raw)"
        val files = mapOf("lib" to "  (def a 1) ; c".encodeToByteArray(), "raw" to "  ; keep".encodeToByteArray())
        val program = LispCode.unpack(LispCode.pack(code, reduce = true) { files.getValue(it.tag) })
        assertEquals("(def a 1)\n", program.imports["lib"]?.decodeToString())
        assertEquals("  ; keep", program.imports["raw"]?.decodeToString())
    }
}
