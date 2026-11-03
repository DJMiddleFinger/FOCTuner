package app.foctuner.logging

import android.content.Context
import app.foctuner.logs.LogNames
import app.foctuner.vesc.DownloadSink
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime

/** A log file on this phone, recorded here or downloaded from a controller. */
data class StoredLog(val file: File) {
    val name: String get() = file.name
    val size: Long get() = file.length()
    val modified: Long get() = file.lastModified()
}

object LogStorage {
    private fun root(context: Context) = File(context.filesDir, "logs")

    fun phoneDir(context: Context) = File(root(context), "phone").also { it.mkdirs() }

    /** Downloads from one controller, kept apart by its UUID so two controllers' logs can't collide. */
    fun controllerDir(context: Context, uuid: String) =
        File(root(context), "controller/${uuid.filter { it.isLetterOrDigit() }.ifEmpty { "unknown" }}").also { it.mkdirs() }

    fun newPhoneLog(context: Context): File = File(phoneDir(context), LogNames.phone(LocalDateTime.now()))

    /** Finished `.csv` files in [dir], newest name first. */
    fun logs(dir: File): List<StoredLog> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".csv", ignoreCase = true) }.orEmpty().map(::StoredLog).sortedByDescending { it.name }

    fun partFile(dir: File, name: String) = File(dir, "$name.part")
}

/** Keeps a download in `name.part` until it is whole, so a dropped link leaves something to continue from. */
class PartialFileSink(private val part: File) : DownloadSink {
    override val size: Long get() = part.length()

    override fun head(length: Int): ByteArray = RandomAccessFile(part, "r").use { f -> ByteArray(length).also { f.readFully(it) } }

    override fun append(bytes: ByteArray) {
        part.appendBytes(bytes)
    }

    override fun clear() {
        part.delete()
    }

    fun finish(target: File) {
        target.delete()
        part.renameTo(target)
    }
}
