package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId
import app.foctuner.protocol.CommPacketId.COMM_FILE_LIST
import app.foctuner.protocol.CommPacketId.COMM_FILE_MKDIR
import app.foctuner.protocol.CommPacketId.COMM_FILE_READ
import app.foctuner.protocol.CommPacketId.COMM_FILE_REMOVE
import app.foctuner.protocol.CommPacketId.COMM_FILE_WRITE
import kotlin.time.Duration.Companion.milliseconds

/*
 * The file system of a VESC Express (SD card or internal flash), COMM_FILE_*. The STM32 firmware has none and
 * ignores these commands. Paths are relative to the device's storage root: "" is the root, "/logs" a folder in it.
 */

/** One entry of a folder. [size] is in bytes, or the number of entries for a folder. */
data class DeviceFile(val name: String, val isDirectory: Boolean, val size: Long)

/** False if the controller doesn't answer a listing of the root, which is the case for everything but a VESC Express. */
suspend fun VescController.hasFileSystem(): Boolean = listPage("", "", tries = 1) != null

/** The folder [path], sorted by name ignoring case like VESC Tool. A missing folder lists as empty. */
suspend fun VescController.listFiles(path: String): List<DeviceFile> {
    val files = ArrayList<DeviceFile>()
    while (true) {
        val (more, page) = listPage(path, files.lastOrNull()?.name ?: "", FILE_TRIES) ?: throw noReply(COMM_FILE_LIST, FILE_TIMEOUT)
        files += page
        if (!more || page.isEmpty()) break
    }
    return files.distinctBy { it.name }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

/**
 * Downloads [path]. A missing file reads as empty, as the firmware can't tell the two apart. [onProgress] gets bytes
 * read and the total; cancel the coroutine to stop.
 */
suspend fun VescController.readFile(path: String, onProgress: (read: Int, total: Int) -> Unit = { _, _ -> }): ByteArray {
    val data = ByteWriter()
    var total: Int
    do {
        val offset = data.length
        val (size, bytes) = readFileChunk(path, offset)
        total = size
        if (bytes.isEmpty() && data.length < total) throw VescException("The controller sent no data at byte $offset of $path")
        data.bytes(bytes)
        onProgress(data.length, total)
    } while (data.length < total)
    return data.toByteArray()
}

/** One reply to COMM_FILE_READ at [offset]: the file's size and up to 400 bytes (more over Wi-Fi) from there. */
internal suspend fun VescController.readFileChunk(path: String, offset: Int): Pair<Int, ByteArray> {
    val request = ByteWriter().u8(COMM_FILE_READ.id).cString(path).i32(offset).toByteArray()
    return fileExchange(request, COMM_FILE_READ) { r ->
        if (r.i32() == offset) r.i32() to r.restBytes() else null
    }
}

/**
 * Creates or replaces [path] with [data], in acknowledged chunks. The folder must exist. [onProgress] gets bytes
 * written and the total; cancelling leaves a partial file behind.
 *
 * @throws VescException if the controller refuses a chunk (no such folder, card full or missing)
 */
suspend fun VescController.writeFile(path: String, data: ByteArray, onProgress: (written: Int, total: Int) -> Unit = { _, _ -> }) {
    val chunkSize = minOf(WRITE_CHUNK_SIZE, MAX_REQUEST - 10 - path.encodeToByteArray().size)
    require(chunkSize > 0) { "Path is too long: $path" }
    var offset = 0
    onProgress(0, data.size)
    // An empty file still takes one write, which creates it.
    do {
        val end = minOf(offset + chunkSize, data.size)
        val request = ByteWriter(end - offset + path.length + 10).u8(COMM_FILE_WRITE.id).cString(path)
            .i32(offset).i32(data.size).bytes(data, offset, end - offset).toByteArray()
        val expected = offset
        val ok = fileExchange(request, COMM_FILE_WRITE) { r -> if (r.i32() == expected) r.u8() != 0 else null }
        if (!ok) throw VescException("The controller could not write $path at byte $offset")
        offset = end
        onProgress(offset, data.size)
    } while (offset < data.size)
}

/** Creates the folder [path]. False if the controller refused, for example because it exists. */
suspend fun VescController.makeDirectory(path: String): Boolean =
    fileExchange(ByteWriter().u8(COMM_FILE_MKDIR.id).cString(path).toByteArray(), COMM_FILE_MKDIR) { it.u8() != 0 }

/** Deletes the file or the folder [path] with everything in it. False if the controller refused. */
suspend fun VescController.removeFile(path: String): Boolean =
    fileExchange(ByteWriter().u8(COMM_FILE_REMOVE.id).cString(path).toByteArray(), COMM_FILE_REMOVE) { it.u8() != 0 }

private suspend fun VescController.listPage(path: String, from: String, tries: Int): Pair<Boolean, List<DeviceFile>>? {
    val request = ByteWriter().u8(COMM_FILE_LIST.id).cString(path).cString(from).toByteArray()
    return retrying(tries) {
        exchange(request, COMM_FILE_LIST, FILE_TIMEOUT) { r ->
            val more = r.u8() != 0
            more to buildList { while (r.remaining > 0) add(DeviceFile(isDirectory = r.u8() != 0, size = r.u32(), name = r.cString())) }
        }
    }
}

private suspend fun <T : Any> VescController.fileExchange(
    request: ByteArray,
    replyId: CommPacketId,
    parse: (ByteReader) -> T?,
): T = retrying(FILE_TRIES) { exchange(request, replyId, FILE_TIMEOUT, parse) } ?: throw noReply(replyId, FILE_TIMEOUT)

/** VESC Tool's chunk; with the path and header the packet stays under the firmware's 512-byte limit. */
private const val WRITE_CHUNK_SIZE = 384
private const val MAX_REQUEST = 500

/** VESC Tool waits 1.5 s and tries 3 more times. */
private const val FILE_TRIES = 4
private val FILE_TIMEOUT = 1500.milliseconds
