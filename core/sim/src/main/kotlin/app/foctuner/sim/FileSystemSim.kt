package app.foctuner.sim

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_FILE_LIST
import app.foctuner.protocol.CommPacketId.COMM_FILE_MKDIR
import app.foctuner.protocol.CommPacketId.COMM_FILE_READ
import app.foctuner.protocol.CommPacketId.COMM_FILE_REMOVE
import app.foctuner.protocol.CommPacketId.COMM_FILE_WRITE

/**
 * A VESC Express SD card as its commands.c serves it (COMM_FILE_*): listings in pages of under 400 bytes that
 * continue after a given name, 400-byte reads, writes that open the file at offset 0 and accept a repeated chunk,
 * mkdir and recursive remove. Real STM32 firmware has no file system; the simulator answers like an Express on the
 * same link so the file browser can be tried. Starts with the logger's folder holding three rides, and a note.
 */
internal class FileSystemSim(private val vesc: SimulatedVesc) {
    private val folders = linkedSetOf("", LogSim.FOLDER)
    private val files = linkedMapOf(
        "${LogSim.FOLDER}/log_000_26-10-14_17-02-11.csv" to StoredRides.csv(90, 61_331.0, 0.3, 0.92),
        "${LogSim.FOLDER}/log_001_26-10-15_08-31-40.csv" to StoredRides.csv(150, 30_700.0, 1.7, 0.85),
        "${LogSim.FOLDER}/log_002_26-10-15_18-47-05.csv" to StoredRides.csv(240, 67_625.0, 4.1, 0.78),
        "/notes.txt" to "Simulated VESC Express storage.\n".encodeToByteArray(),
    )
    private var writing: String? = null
    private var writtenTo = 0

    fun list(r: ByteReader) {
        val folder = normalize(r.cString())
        val from = r.cString()
        val w = ByteWriter().u8(COMM_FILE_LIST.id).u8(0)
        if (folder in folders) {
            var found = from.isEmpty()
            for ((name, isFolder) in children(folder)) {
                if (!found) {
                    found = name == from
                    continue
                }
                val bytes = name.encodeToByteArray()
                if (w.length + bytes.size >= PAGE) {
                    val page = w.toByteArray().also { it[1] = 1 }
                    vesc.reply(page)
                    return
                }
                val path = "$folder/$name"
                val size = if (isFolder) children(path).size else files.getValue(path).size
                w.u8(if (isFolder) 1 else 0).i32(size).bytes(bytes).u8(0)
            }
        }
        vesc.reply(w.toByteArray())
    }

    fun read(r: ByteReader) {
        val path = normalize(r.cString())
        val offset = r.i32()
        val w = ByteWriter().u8(COMM_FILE_READ.id).i32(offset)
        val data = files[path]
        if (data == null) {
            vesc.reply(w.i32(0).toByteArray())
            return
        }
        val start = offset.coerceIn(0, data.size)
        vesc.reply(w.i32(data.size).bytes(data, start, minOf(READ_CHUNK, data.size - start)).toByteArray())
    }

    fun write(r: ByteReader) {
        val path = normalize(r.cString())
        val offset = r.i32()
        val size = r.i32()
        val chunk = r.restBytes()
        if (offset == 0) {
            writing = path.takeIf { parentOf(it) in folders && it !in folders }
            writtenTo = 0
            writing?.let { files[it] = ByteArray(0) }
        }
        val target = writing
        val ok = when {
            target == null -> false
            writtenTo == offset -> {
                files[target] = files.getValue(target) + chunk
                writtenTo += chunk.size
                true
            }
            // A retry of the chunk that was just written.
            else -> writtenTo - chunk.size == offset
        }
        if (target != null && writtenTo == size) writing = null
        vesc.reply(ByteWriter().u8(COMM_FILE_WRITE.id).i32(offset).u8(if (ok) 1 else 0).toByteArray())
    }

    fun makeDirectory(r: ByteReader) {
        val path = normalize(r.cString())
        val ok = path !in folders && path !in files && parentOf(path) in folders
        if (ok) folders += path
        vesc.reply(byteArrayOf(COMM_FILE_MKDIR.id.toByte(), if (ok) 1 else 0))
    }

    fun remove(r: ByteReader) {
        val path = normalize(r.cString())
        val ok = path.isNotEmpty() && (path in folders || path in files)
        if (ok) {
            files.keys.removeAll { it == path || it.startsWith("$path/") }
            folders.removeAll { it == path || it.startsWith("$path/") }
        }
        vesc.reply(byteArrayOf(COMM_FILE_REMOVE.id.toByte(), if (ok) 1 else 0))
    }

    /** Names directly in [folder] with whether each is a folder, in creation order like an unsorted readdir. */
    private fun children(folder: String): List<Pair<String, Boolean>> =
        folders.filter { it.isNotEmpty() && parentOf(it) == folder }.map { it.substringAfterLast('/') to true } +
            files.keys.filter { parentOf(it) == folder }.map { it.substringAfterLast('/') to false }

    private fun parentOf(path: String) = path.substringBeforeLast('/', "")

    /** "", "/" and "/logs/" style paths to "" and "/logs". */
    private fun normalize(path: String): String {
        val trimmed = path.replace(Regex("/+"), "/").trimEnd('/')
        return if (trimmed.isEmpty() || trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    fun ensureFolder(path: String) {
        folders += path
    }

    fun create(path: String) {
        files[path] = ByteArray(0)
    }

    fun append(path: String, text: String) {
        if (text.isNotEmpty()) files[path] = (files[path] ?: ByteArray(0)) + text.encodeToByteArray()
    }

    /** The highest NNN of the log_NNN_* files in [folder], -1 when there are none. */
    fun highestLogIndex(folder: String): Int =
        files.keys.filter { parentOf(it) == folder }.mapNotNull { Regex("""log_(\d+)""").find(it.substringAfterLast('/'))?.groupValues?.get(1)?.toInt() }.maxOrNull() ?: -1

    private companion object {
        const val PAGE = 400
        const val READ_CHUNK = 400
    }
}
