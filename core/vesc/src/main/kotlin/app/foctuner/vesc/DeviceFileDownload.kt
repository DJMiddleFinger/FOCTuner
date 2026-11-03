package app.foctuner.vesc

/** Where a download keeps what it has so far, so a dropped link can be picked up again where it stopped. */
interface DownloadSink {
    /** Bytes stored so far. */
    val size: Long

    /** The first [length] stored bytes. */
    fun head(length: Int): ByteArray

    fun append(bytes: ByteArray)

    /** Forgets everything stored. */
    fun clear()
}

/** [resumedAt] is the byte the download continued from; 0 when it started over. */
data class DownloadResult(val resumedAt: Long, val total: Long)

/**
 * Downloads [path] into [sink], continuing after the bytes it already holds. The controller can read from any offset,
 * so a download that was cut off by a dropped link carries on from there, once the first bytes still match: the file
 * is read from its start first (VESC Express firmware serves reads from the file it opened last and only switches when
 * the offset is not the one it expected, so asking for a later offset first could answer from a different file), and
 * the stored start is compared with it. A file that was replaced or shrunk starts over; one that grew, like the log
 * being written right now, continues to its new end. A missing file ends up empty. [onProgress] gets bytes done and the
 * size, which can grow.
 */
suspend fun VescController.downloadFile(
    path: String,
    sink: DownloadSink,
    onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
): DownloadResult {
    val (firstTotal, first) = readFileChunk(path, 0)
    var total = firstTotal
    if (total <= 0) {
        sink.clear()
        onProgress(0, 0)
        return DownloadResult(0, 0)
    }
    val stored = sink.size
    val keep = stored in 1..total.toLong() && sink.head(minOf(stored, first.size.toLong()).toInt()).contentEquals(first.copyOf(minOf(stored, first.size.toLong()).toInt()))
    var offset: Long
    if (keep) {
        offset = stored
    } else {
        sink.clear()
        sink.append(first)
        offset = first.size.toLong()
    }
    val resumedAt = if (keep) stored else 0L
    onProgress(offset, total.toLong())
    while (offset < total) {
        val (size, bytes) = readFileChunk(path, offset.toInt())
        total = size
        if (bytes.isEmpty()) {
            if (offset >= total) break
            throw VescException("The controller sent no data at byte $offset of $path")
        }
        sink.append(bytes)
        offset += bytes.size
        onProgress(offset, total.toLong())
    }
    return DownloadResult(resumedAt, total.toLong())
}

/** Lists the folder [path] on the controller's storage: sub-folders and CSV logs, newest name first. */
suspend fun VescController.listLogFiles(path: String): List<DeviceFile> =
    listFiles(path).filter { it.isDirectory || it.name.endsWith(".csv", ignoreCase = true) }
        .sortedWith(compareByDescending<DeviceFile> { it.isDirectory }.thenByDescending { it.name.lowercase() })

/** Where the VESC Express logger writes its CSV files. */
const val EXPRESS_LOG_FOLDER = "/log_can"
