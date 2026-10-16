package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.Crc16

/** `(import "path" 'tag)`: the file at [path] is stored next to the code and read by the script as [tag]. */
data class LispImport(val line: Int, val path: String, val tag: String)

/** The code could not be packed: an import line is malformed or its file can't be read. */
class LispImportException(message: String) : Exception(message)

/** Code read back from a controller, with the files it imported. */
class LispProgram(val code: String, val imports: Map<String, ByteArray>)

/**
 * The format VESC Tool stores LispBM code in (codeloader.cpp `lispPackImports`, `lispUpload`,
 * `lispUnpackImports`), so code written by either tool reads back in the other.
 *
 * Packed: u16 flags (0), the code, a 0 byte, i16 import count, then per import its tag (0-terminated), i32 offset
 * and i32 length, then the imported files, each padded with a 0 byte and starting on a 4-byte boundary.
 * Offsets count from just after the flags, which is where the firmware's code pointer starts.
 *
 * In flash the packed code is preceded by u32 length (excluding the flags) and the CRC16 of flags and code;
 * the firmware ignores the stored code when the CRC doesn't match.
 */
object LispCode {
    /**
     * What a code area holds: 128 KiB on STM32 controllers, partitions of several MiB on ESP32 devices.
     * Includes the 6-byte header.
     */
    fun maxImageSize(hardwareType: HardwareType): Int =
        if (hardwareType == HardwareType.VESC) 128 * 1024 - 6 else 8 * 1024 * 1024 - 6

    /**
     * Every import line in [code]. Like VESC Tool, an import must start its line (after spaces) and the path is
     * everything between the first and the last quote.
     *
     * @throws LispImportException for an import line without a quoted path or a tag
     */
    fun imports(code: String): List<LispImport> = code.split('\n').mapIndexedNotNull { index, raw ->
        var line = raw.trimStart(' ')
        while (line.startsWith("( ")) line = line.removeRange(1, 2)
        if (!line.startsWith("(import ", ignoreCase = true)) return@mapIndexedNotNull null
        val start = line.indexOf('"')
        val end = line.lastIndexOf('"')
        val valid = start in 1 until end
        val path = if (valid) line.substring(start + 1, end) else ""
        val tag = if (valid) line.substring(end + 1).filterNot { it in "\r )'" }.substringBefore(';') else ""
        if (path.isEmpty() || tag.isEmpty()) throw LispImportException("Line ${index + 1}: invalid import")
        LispImport(index + 1, path, tag)
    }

    /** Removes comments, indentation, trailing blanks and empty lines, as VESC Tool's "Reduce" option does. */
    fun reduce(code: String): String = buildString {
        for (raw in code.split('\n')) {
            var inString = false
            var comment = -1
            for ((i, c) in raw.withIndex()) {
                if (c == '"') {
                    inString = !inString
                } else if (c == ';' && !inString) {
                    comment = i
                    break
                }
            }
            val line = (if (comment >= 0) raw.substring(0, comment) else raw).trim(' ', '\t')
            if (line.isNotEmpty()) append(line).append('\n')
        }
    }

    /**
     * Packs [code] with the files it imports. [read] returns an import's file content; it is called once per
     * import, in order. With [reduce], the code and imports ending in .lbm or .lisp are [reduce]d.
     *
     * @throws LispImportException if an import line is malformed or [read] throws it
     */
    fun pack(code: String, reduce: Boolean = false, read: (LispImport) -> ByteArray = { noImports(it) }): ByteArray {
        val files = imports(code).map { import ->
            var data = read(import)
            val isLisp = import.path.endsWith(".lbm", ignoreCase = true) || import.path.endsWith(".lisp", ignoreCase = true)
            if (reduce && isLisp) data = reduce(data.decodeToString()).encodeToByteArray()
            import.tag.encodeToByteArray() to data + 0
        }
        val w = ByteWriter(code.length + 64).u16(0)
        val text = (if (reduce) reduce(code) else code).encodeToByteArray()
        w.bytes(text)
        if (text.isEmpty() || text.last() != 0.toByte()) w.u8(0)
        w.i16(files.size)

        var offset = w.length + files.sumOf { it.first.size + 9 } - FLAGS_SIZE
        for ((tag, data) in files) {
            offset = align(offset)
            w.bytes(tag).u8(0).i32(offset).i32(data.size)
            offset += data.size
        }
        for ((_, data) in files) {
            repeat(align(w.length - FLAGS_SIZE) - (w.length - FLAGS_SIZE)) { w.u8(0) }
            w.bytes(data)
        }
        return w.toByteArray()
    }

    /** [packed] as it is written to flash: length and CRC header first. */
    fun image(packed: ByteArray): ByteArray =
        ByteWriter(packed.size + 6).u32((packed.size - FLAGS_SIZE).toLong()).u16(Crc16.compute(packed)).bytes(packed).toByteArray()

    /** Splits stored code (as [VescController.readLisp] returns it, or a packed buffer) into code and imports. */
    fun unpack(stored: ByteArray): LispProgram {
        val data = if (stored.size > 2 && stored[0] == 0.toByte() && stored[1] == 0.toByte()) stored.copyOfRange(2, stored.size) else stored
        val end = data.indexOf(0)
        if (end < 0) return LispProgram(data.decodeToString(), emptyMap())
        val imports = LinkedHashMap<String, ByteArray>()
        val r = ByteReader(data, end + 1)
        if (r.remaining > 3) {
            val count = r.i16()
            if (count in 1 until 500) {
                repeat(count) {
                    val tag = r.cString()
                    val offset = r.i32().coerceIn(0, data.size)
                    val length = (r.i32() - 1).coerceIn(0, data.size - offset)
                    imports[tag] = data.copyOfRange(offset, offset + length)
                }
            }
        }
        return LispProgram(data.copyOfRange(0, end).decodeToString(), imports)
    }

    private fun align(offset: Int) = (offset + 3) and 3.inv()

    private fun noImports(import: LispImport): Nothing =
        throw LispImportException("Line ${import.line}: can't read ${import.path}")

    private const val FLAGS_SIZE = 2
}
