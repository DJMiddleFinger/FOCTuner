package app.foctuner.vesc.firmware.archive

import app.foctuner.protocol.Crc16
import app.foctuner.vesc.firmware.sha256
import java.io.File
import java.io.IOException
import java.util.Properties

/** An image saved on the phone, so a re-flash works without a connection. */
class CachedImage internal constructor(
    val entry: ArchiveEntry,
    val size: Int,
    val crc16: Int,
    val sha256: String,
    val savedAt: Long,
    private val file: File,
) {
    /** The saved bytes, or null if the file is gone or no longer matches the size, CRC and SHA-256 recorded when it was saved. */
    fun read(): ByteArray? {
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return null
        }
        return bytes.takeIf { it.size == size && Crc16.compute(it) == crc16 && sha256(it) == sha256 }
    }
}

/**
 * Downloaded images and the bundle indexes, in [root]. Writes go to a temporary file that is renamed when
 * complete, so a download that is cancelled or killed leaves nothing behind. Each image has a `.meta` file
 * with its size, CRC16 and SHA-256, checked again on every read: a damaged copy is treated as missing.
 */
class FirmwareCache(private val root: File) {
    private val images = File(root, "images")
    private val indexes = File(root, "indexes")
    private val texts = File(root, "texts")

    fun store(entry: ArchiveEntry, bytes: ByteArray): CachedImage {
        val file = File(images, entry.key)
        val meta = Properties().apply {
            setProperty("origin", entry.origin.name)
            entry.version?.let { setProperty("version", it) }
            setProperty("hardware", entry.hardware.joinToString("\n"))
            setProperty("file", entry.file)
            entry.bundle?.let { setProperty("bundle", it) }
            setProperty("path", entry.path)
            setProperty("dataOffset", entry.dataOffset.toString())
            setProperty("flags", entry.flags.toString())
            setProperty("size", bytes.size.toString())
            setProperty("crc16", Crc16.compute(bytes).toString())
            setProperty("sha256", sha256(bytes))
            setProperty("savedAt", System.currentTimeMillis().toString())
        }
        writeAtomically(file, bytes)
        writeAtomically(metaFile(file), java.io.ByteArrayOutputStream().also { meta.store(it, null) }.toByteArray())
        return checkNotNull(read(file)) { "Saved image can't be read back" }
    }

    /** The saved image's record; [CachedImage.read] is what checks the bytes. */
    fun find(entry: ArchiveEntry): CachedImage? = read(File(images, entry.key))

    /** Everything saved whose hardware list includes [hardwareName]. */
    fun saved(hardwareName: String, origin: Set<ArchiveOrigin>? = null): List<CachedImage> =
        images.walkTopDown().filter { it.isFile && it.name.endsWith(META) }
            .mapNotNull { read(File(it.path.removeSuffix(META))) }
            .filter { image -> image.entry.hardware.any { it.equals(hardwareName, true) } && (origin == null || image.entry.origin in origin) }
            .filter { it.read() != null }
            .toList()

    fun remove(entry: ArchiveEntry) {
        val file = File(images, entry.key)
        file.delete()
        metaFile(file).delete()
    }

    /** Header and tail of a bundle, as fetched. */
    fun index(bundle: String): Pair<ByteArray, ByteArray>? {
        val bytes = try {
            File(indexes, bundle).readBytes()
        } catch (e: IOException) {
            return null
        }
        if (bytes.size < RccIndex.HEADER_SIZE) return null
        return bytes.copyOfRange(0, RccIndex.HEADER_SIZE) to bytes.copyOfRange(RccIndex.HEADER_SIZE, bytes.size)
    }

    fun storeIndex(bundle: String, header: ByteArray, tail: ByteArray) = writeAtomically(File(indexes, bundle), header + tail)

    fun text(name: String): String? = try {
        File(texts, name).readText()
    } catch (e: IOException) {
        null
    }

    fun storeText(name: String, text: String) = writeAtomically(File(texts, name), text.toByteArray())

    /** Bytes used by saved images. */
    fun size(): Long = images.walkTopDown().filter { it.isFile && !it.name.endsWith(META) }.sumOf { it.length() }

    private fun read(file: File): CachedImage? {
        val meta = Properties()
        try {
            metaFile(file).inputStream().use(meta::load)
        } catch (e: IOException) {
            return null
        }
        if (!file.isFile) return null
        return try {
            val entry = ArchiveEntry(
                origin = ArchiveOrigin.valueOf(meta.getProperty("origin")),
                version = meta.getProperty("version"),
                hardware = meta.getProperty("hardware").split("\n"),
                file = meta.getProperty("file"),
                bundle = meta.getProperty("bundle"),
                path = meta.getProperty("path"),
                dataOffset = meta.getProperty("dataOffset").toLong(),
                flags = meta.getProperty("flags").toInt(),
            )
            CachedImage(entry, meta.getProperty("size").toInt(), meta.getProperty("crc16").toInt(), meta.getProperty("sha256"), meta.getProperty("savedAt").toLong(), file)
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun metaFile(file: File) = File(file.path + META)

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.part")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("Could not save ${target.name}")
            }
        } finally {
            temp.delete()
        }
    }

    private companion object {
        const val META = ".meta"
    }
}
