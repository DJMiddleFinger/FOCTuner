package app.foctuner.sim

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_FILE_LIST
import app.foctuner.protocol.CommPacketId.COMM_FILE_MKDIR
import app.foctuner.protocol.CommPacketId.COMM_FILE_READ
import app.foctuner.protocol.CommPacketId.COMM_FILE_REMOVE
import app.foctuner.protocol.CommPacketId.COMM_FILE_WRITE
import java.util.Locale
import kotlin.math.sin

/**
 * A VESC Express SD card as its commands.c serves it (COMM_FILE_*): listings in pages of under 400 bytes that
 * continue after a given name, 400-byte reads, writes that open the file at offset 0 and accept a repeated chunk,
 * mkdir and recursive remove. Real STM32 firmware has no file system; the simulator answers like an Express on the
 * same link so the file browser can be tried. Starts with a log folder and a note.
 */
internal class FileSystemSim(private val vesc: SimulatedVesc) {
    private val folders = linkedSetOf("", "/logs")
    private val files = linkedMapOf(
        "/logs/2026-10-01_ride.csv" to sampleLog(120),
        "/logs/2026-10-03_ride.csv" to sampleLog(2400),
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

    private fun sampleLog(rows: Int): ByteArray = buildString {
        append("ms_today;input_voltage;current_motor;erpm;duty_cycle\n")
        for (i in 0 until rows) {
            append(36_000_000 + i * 100).append(';')
            append("%.2f".format(Locale.ROOT, 50.4 - i * 0.001)).append(';')
            append("%.2f".format(Locale.ROOT, 12 + 8 * sin(i / 20.0))).append(';')
            append(8000 + i % 500).append(';')
            append("%.3f".format(Locale.ROOT, 0.3 + 0.1 * sin(i / 30.0))).append('\n')
        }
    }.encodeToByteArray()

    private companion object {
        const val PAGE = 400
        const val READ_CHUNK = 400
    }
}
