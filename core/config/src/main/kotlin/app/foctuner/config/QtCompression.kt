package app.foctuner.config

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.ZipException

/**
 * Qt's `qCompress` / `qUncompress` format, in which VESC Tool and the firmware keep custom config definitions, QML
 * user interfaces and packages: the uncompressed length as a big-endian uint32, then a zlib stream.
 */
object QtCompression {
    /** Larger than anything a controller or package holds; a bigger claim is treated as a corrupt file. */
    private const val MAX_SIZE = 64 * 1024 * 1024

    fun compress(data: ByteArray, level: Int = 9): ByteArray {
        val deflater = Deflater(level)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            out.write(ByteWriter(4).u32(data.size.toLong()).toByteArray())
            val buffer = ByteArray(8192)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /**
     * Like `qUncompress`, the stated length is only a size hint; the zlib stream decides.
     *
     * @throws ZipException if [data] is not in this format
     */
    fun uncompress(data: ByteArray): ByteArray {
        if (data.size < 4) throw ZipException("Compressed data is shorter than its 4-byte length")
        val stated = ByteReader(data).u32()
        if (stated > MAX_SIZE) throw ZipException("Compressed data claims $stated bytes")
        val inflater = Inflater()
        try {
            inflater.setInput(data, 4, data.size - 4)
            val out = ByteArrayOutputStream(stated.toInt())
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw ZipException("Compressed data is cut off")
                out.write(buffer, 0, n)
                if (out.size() > MAX_SIZE) throw ZipException("Compressed data expands past $MAX_SIZE bytes")
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw ZipException(e.message ?: "Compressed data is corrupt")
        } finally {
            inflater.end()
        }
    }
}
