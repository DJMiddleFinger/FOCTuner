package app.foctuner.vesc.firmware.archive

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Why the archive could not be read or an image from it was refused. */
class ArchiveException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        /** No connection, a timeout, or an HTTP error. */
        Network,

        /** The server doesn't have the file. */
        NotFound,

        /** The server ignored a Range request; reading on would download a whole bundle. */
        RangeUnsupported,

        /** The bundle's table of contents doesn't make sense. */
        BadBundle,

        /** The image is bigger than any controller can take. */
        TooLarge,

        /** The image is for other hardware than the controller, or doesn't say which. */
        HardwareMismatch,

        /** The image is empty, unreadable or no longer matches what was saved. */
        Damaged,
    }
}

/** One file in a Qt resource bundle: [path] from the root, and where its data starts relative to the data section. */
class RccEntry(val path: String, val flags: Int, val dataOffset: Long) {
    val compressed: Boolean get() = flags and COMPRESSED != 0
    val zstd: Boolean get() = flags and ZSTD != 0

    val name: String get() = path.substringAfterLast('/')

    companion object {
        const val COMPRESSED = 1
        const val DIRECTORY = 2
        const val ZSTD = 4
    }
}

/**
 * The table of contents of a Qt resource bundle (`.rcc`, format 2 or 3), which VESC Tool downloads as its
 * firmware archive. A bundle is a 24-byte header, the data, then the names and the tree of entries; the
 * header says where those are, so the names and tree are the last few kilobytes of a file that can be
 * hundreds of megabytes. Every number is big-endian.
 *
 * - Header: `qres`, version, tree offset, data offset, names offset, (version 3:) flags.
 * - Tree entry, 22 bytes: name offset (u32), flags (u16), then for a directory the child count and the
 *   index of the first child (u32 each), for a file a country and a language (u16 each) and the offset of its
 *   data (u32), then the modification time (u64).
 * - Name: length in characters (u16), a hash (u32), UTF-16 characters.
 * - A file's data is a u32 size and the bytes; with [RccEntry.COMPRESSED] the bytes are Qt's qCompress
 *   format (u32 uncompressed size, then zlib).
 *
 * The index comes over an unauthenticated connection, so the parser checks every offset and bounds how much
 * it walks.
 */
class RccIndex private constructor(
    /** Offset of the data section in the bundle file; entry offsets are relative to it. */
    val dataSection: Long,
    val entries: List<RccEntry>,
) {
    companion object {
        const val HEADER_SIZE = 24
        private const val ENTRY_SIZE = 22
        private const val MAX_ENTRIES = 50_000
        private const val MAX_DEPTH = 8

        /** Names and tree are fetched from here to the end of the file. */
        fun tailOffset(header: ByteArray): Long {
            val h = readHeader(header)
            return minOf(h.tree, h.names)
        }

        /** The most a tail can be before the header is taken to be broken (the real ones are 12 KB and 52 KB). */
        const val MAX_TAIL = 4 * 1024 * 1024

        /**
         * @param header the first [HEADER_SIZE] bytes of the bundle
         * @param tail the bundle from [tailOffset] to its end
         * @throws ArchiveException if either doesn't describe a bundle
         */
        fun parse(header: ByteArray, tail: ByteArray): RccIndex {
            val h = readHeader(header)
            val base = minOf(h.tree, h.names)
            try {
                val buffer = ByteBuffer.wrap(tail).order(ByteOrder.BIG_ENDIAN)
                val names = (h.names - base).toIntExact()
                val tree = (h.tree - base).toIntExact()
                val files = ArrayList<RccEntry>()
                var visited = 0

                fun name(at: Int): String {
                    val start = names + at
                    val length = buffer.getShort(start).toInt() and 0xFFFF
                    val chars = CharArray(length) { buffer.getChar(start + 6 + it * 2) }
                    return String(chars)
                }

                fun walk(index: Int, parent: String, depth: Int) {
                    if (++visited > MAX_ENTRIES || depth > MAX_DEPTH) throw bad("The bundle's tree is too large")
                    val at = tree + index * ENTRY_SIZE
                    val flags = buffer.getShort(at + 4).toInt() and 0xFFFF
                    val label = if (index == 0) "" else name(buffer.getInt(at))
                    if (flags and RccEntry.DIRECTORY != 0) {
                        val count = buffer.getInt(at + 6)
                        val first = buffer.getInt(at + 10)
                        if (count < 0 || first < 0 || count > MAX_ENTRIES) throw bad("Directory with $count entries")
                        val path = if (index == 0) "" else "$parent/$label"
                        for (child in first until first + count) walk(child, path, depth + 1)
                    } else {
                        files += RccEntry("$parent/$label", flags, buffer.getInt(at + 10).toLong() and 0xFFFFFFFFL)
                    }
                }

                walk(0, "", 0)
                return RccIndex(h.data, files)
            } catch (e: IndexOutOfBoundsException) {
                throw bad("The bundle's tree points outside the file", e)
            }
        }

        /** The file's bytes from its data: [raw] is the u32 size prefix followed by the stored bytes. */
        fun sizePrefix(prefix: ByteArray): Long = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL

        /** Qt's qCompress output to the original bytes; zlib verifies its own checksum. */
        fun inflate(stored: ByteArray, limit: Int): ByteArray {
            if (stored.size < 5) throw ArchiveException(ArchiveException.Reason.Damaged, "Compressed data is too short")
            val expected = ByteBuffer.wrap(stored).order(ByteOrder.BIG_ENDIAN).int
            if (expected < 0 || expected > limit) throw ArchiveException(ArchiveException.Reason.TooLarge, "Compressed data expands to $expected bytes")
            val inflater = Inflater()
            try {
                inflater.setInput(stored, 4, stored.size - 4)
                val out = ByteArray(expected)
                var filled = 0
                while (filled < expected && !inflater.finished()) {
                    val n = inflater.inflate(out, filled, expected - filled)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    filled += n
                }
                if (filled != expected || !inflater.finished()) throw ArchiveException(ArchiveException.Reason.Damaged, "Compressed data is shorter than it says")
                return out
            } catch (e: DataFormatException) {
                throw ArchiveException(ArchiveException.Reason.Damaged, "Compressed data is corrupt", e)
            } finally {
                inflater.end()
            }
        }

        private class Header(val tree: Long, val data: Long, val names: Long)

        private fun readHeader(header: ByteArray): Header {
            if (header.size < HEADER_SIZE) throw bad("The bundle header is ${header.size} bytes")
            val b = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            if (b.getInt(0) != 0x71726573) throw bad("Not a Qt resource bundle")
            val version = b.getInt(4)
            if (version != 2 && version != 3) throw bad("Unsupported bundle version $version")
            val tree = b.getInt(8).toLong() and 0xFFFFFFFFL
            val data = b.getInt(12).toLong() and 0xFFFFFFFFL
            val names = b.getInt(16).toLong() and 0xFFFFFFFFL
            return Header(tree, data, names)
        }

        private fun Long.toIntExact(): Int = if (this in 0..MAX_TAIL) toInt() else throw bad("The bundle's tail is $this bytes")

        private fun bad(message: String, cause: Throwable? = null) = ArchiveException(ArchiveException.Reason.BadBundle, message, cause)
    }
}
