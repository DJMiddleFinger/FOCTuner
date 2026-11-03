package app.foctuner.vesc

import app.foctuner.vesc.firmware.archive.ArchiveException
import app.foctuner.vesc.firmware.archive.HttpFetcher
import app.foctuner.vesc.firmware.archive.RccIndex
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** A package from the store, with the folder it came from in vesc_pkg. */
class StorePackage(
    val path: String,
    val pkg: VescPackage,
    /** Libraries (`lib_*` folders) are imported by other packages' code, not installed on their own. */
    val isLibrary: Boolean,
) {
    fun compatibility(info: FirmwareInfo): PackageCompatibility.Verdict = PackageCompatibility.check(pkg.descriptionQml, info)
}

/**
 * VESC Tool's package store: the bundle `vesc_pkg_all.rcc` from the VESC Project server (`CodeLoader::
 * downloadPackageArchive`), a Qt resource file with every package of vesc_pkg under `/vesc_packages/<folder>/`.
 * It is about 1 MB, so it is fetched whole and kept in [cacheFile] for offline use. The server only offers plain
 * HTTP; a package is code that runs on the controller, so the app says where it came from before installing.
 */
class PackageStore(
    private val fetcher: HttpFetcher,
    private val cacheFile: File,
    private val url: String = STORE_URL,
) {
    /** Downloads the store, keeps it and returns its packages sorted by name. */
    suspend fun refresh(onProgress: (Int) -> Unit = {}): List<StorePackage> {
        val bytes = fetcher.get(url, maxBytes = MAX_BYTES, onProgress = onProgress)
        val packages = parse(bytes)
        try {
            cacheFile.parentFile?.mkdirs()
            val temp = File(cacheFile.path + ".tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(cacheFile)) temp.delete()
        } catch (e: IOException) {
            // Works for this session; downloaded again next time.
        }
        return packages
    }

    /** The packages of the last download, or null if there is none (or it is damaged). */
    fun cached(): List<StorePackage>? = try {
        if (cacheFile.isFile) parse(cacheFile.readBytes()) else null
    } catch (e: IOException) {
        null
    }

    /** Last time the kept store was downloaded, epoch millis, or null. */
    fun cachedAt(): Long? = cacheFile.takeIf { it.isFile }?.lastModified()

    companion object {
        const val STORE_URL = "http://home.vedder.se/vesc_pkg/vesc_pkg_all.rcc"
        private const val MAX_BYTES = 32 * 1024 * 1024
        private const val MAX_PACKAGE = 16 * 1024 * 1024

        /** @throws ArchiveException if [bundle] isn't a resource bundle */
        fun parse(bundle: ByteArray): List<StorePackage> {
            if (bundle.size < RccIndex.HEADER_SIZE) throw ArchiveException(ArchiveException.Reason.BadBundle, "The package store is empty")
            val header = bundle.copyOfRange(0, RccIndex.HEADER_SIZE)
            val tailFrom = RccIndex.tailOffset(header)
            if (tailFrom !in 0..bundle.size.toLong()) throw ArchiveException(ArchiveException.Reason.BadBundle, "The package store is cut off")
            val index = RccIndex.parse(header, bundle.copyOfRange(tailFrom.toInt(), bundle.size))
            return index.entries
                .filter { it.path.startsWith("/vesc_packages/") && it.path.endsWith(".vescpkg", ignoreCase = true) }
                .mapNotNull { entry ->
                    val at = index.dataSection + entry.dataOffset
                    if (at < 0 || at + 4 > bundle.size) return@mapNotNull null
                    val size = ByteBuffer.wrap(bundle, at.toInt(), 4).int
                    if (size < 0 || at + 4 + size > bundle.size) return@mapNotNull null
                    var data = bundle.copyOfRange(at.toInt() + 4, at.toInt() + 4 + size)
                    if (entry.zstd) return@mapNotNull null
                    if (entry.compressed) data = RccIndex.inflate(data, MAX_PACKAGE)
                    val pkg = try {
                        VescPackage.parse(data)
                    } catch (e: PackageException) {
                        return@mapNotNull null
                    }
                    val folder = entry.path.removePrefix("/vesc_packages/").substringBefore('/')
                    StorePackage(entry.path, pkg, isLibrary = folder.startsWith("lib_"))
                }
                .sortedBy { it.pkg.name.lowercase() }
        }
    }
}
