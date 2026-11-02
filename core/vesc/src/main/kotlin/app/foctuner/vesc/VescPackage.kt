package app.foctuner.vesc

import app.foctuner.config.QtCompression
import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import java.util.zip.ZipException

class PackageException(message: String, cause: Throwable? = null) : VescException(message, cause)

/**
 * A `.vescpkg` file (`CodeLoader::packVescPackage` / `unpackVescPackage`): Qt-compressed, the marker "VESC Packet",
 * then named fields, each `str key, i32 length, bytes`. Unknown fields are skipped by their length, as VESC Tool does.
 */
class VescPackage(
    val name: String,
    /** HTML; VESC Tool renders the Markdown description into it when building the package. */
    val description: String = "",
    val descriptionMarkdown: String = "",
    /** LispBM code and its imports as [LispCode.pack] lays them out; null if the package has no code. */
    val lisp: ByteArray? = null,
    val qml: String? = null,
    val qmlFullscreen: Boolean = false,
    /**
     * The package's `pkgdesc.qml`: a QML item whose `isCompatible(fwRxParams)` function VESC Tool runs to decide
     * whether the package suits the connected controller.
     */
    val descriptionQml: String = "",
) {
    fun pack(): ByteArray {
        val w = ByteWriter(4096).cString(MAGIC)
        fun field(key: String, data: ByteArray) {
            w.cString(key).i32(data.size).bytes(data)
        }
        if (name.isNotEmpty()) field("name", name.encodeToByteArray())
        if (description.isNotEmpty()) field("description", description.encodeToByteArray())
        if (descriptionMarkdown.isNotEmpty()) field("description_md", descriptionMarkdown.encodeToByteArray())
        lisp?.takeIf { it.isNotEmpty() }?.let { field("lispData", it) }
        qml?.takeIf { it.isNotEmpty() }?.let { field("qmlFile", it.encodeToByteArray()) }
        if (descriptionQml.isNotEmpty()) field("pkgDescQml", descriptionQml.encodeToByteArray())
        field("qmlIsFullscreen", byteArrayOf(if (qmlFullscreen) 1 else 0))
        return QtCompression.compress(w.toByteArray())
    }

    companion object {
        // "Packet" is VESC Tool's typo, kept for compatibility.
        private const val MAGIC = "VESC Packet"

        /** @throws PackageException if [data] isn't a package or is cut off */
        fun parse(data: ByteArray): VescPackage {
            val raw = try {
                QtCompression.uncompress(data)
            } catch (e: ZipException) {
                throw PackageException("Not a VESC package: ${e.message}", e)
            }
            val r = ByteReader(raw)
            if (r.cString() != MAGIC) throw PackageException("Not a VESC package")
            val fields = HashMap<String, ByteArray>()
            try {
                while (r.remaining > 0) {
                    val key = r.cString()
                    if (key.isEmpty()) break
                    val length = r.i32()
                    if (length < 0 || length > r.remaining) throw PackageException("The package is cut off in its \"$key\" field")
                    fields[key] = r.bytes(length)
                }
            } catch (e: IndexOutOfBoundsException) {
                throw PackageException("The package is cut off", e)
            }
            val known = listOf("name", "description", "description_md", "lispData", "qmlFile", "pkgDescQml")
            if (known.none { it in fields }) throw PackageException("The package is empty")
            fun text(key: String) = fields[key]?.decodeToString() ?: ""
            return VescPackage(
                name = text("name"),
                description = text("description"),
                descriptionMarkdown = text("description_md"),
                lisp = fields["lispData"]?.takeIf { it.isNotEmpty() },
                qml = fields["qmlFile"]?.decodeToString()?.takeIf { it.isNotEmpty() },
                qmlFullscreen = fields["qmlIsFullscreen"]?.firstOrNull()?.toInt() == 1,
                descriptionQml = text("pkgDescQml"),
            )
        }
    }
}
