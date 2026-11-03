package app.foctuner.vesc.firmware.archive

import app.foctuner.protocol.Crc16
import app.foctuner.vesc.HardwareType
import app.foctuner.vesc.firmware.FirmwareFileException
import app.foctuner.vesc.firmware.FirmwareImage
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/**
 * The firmware VESC Tool downloads on demand: the VESC Project archive (Qt resource bundles over HTTP, see
 * [RccIndex]) for motor controllers and VESC Express, and the VESC Tool repository (HTTPS) for BMS firmware
 * and the changelog. Bundles are read with Range requests, so listing a hardware's versions costs a few
 * kilobytes and an image costs its own size, not the bundle's.
 *
 * Nothing is trusted because of where it came from. Every image is checked against the controller before it
 * is returned ([verify]) and saved in [cache] with its size, CRC16 and SHA-256, which are checked again
 * whenever it is read back.
 */
class FirmwareArchive(
    private val http: HttpFetcher,
    private val cache: FirmwareCache,
    private val urls: ArchiveUrls = ArchiveUrls(),
) {
    /**
     * Every version offered for [target], newest first. When the server can't be reached the listing is the
     * images saved on the phone ([ArchiveListing.offline]); if only some sources fail, what the others have
     * is returned with [ArchiveListing.incomplete] set.
     */
    suspend fun listing(target: ArchiveTarget): ArchiveListing {
        var incomplete = false
        val found = try {
            when (target.type) {
                HardwareType.VESC -> vescEntries(target) { incomplete = true }
                HardwareType.VESC_BMS -> bmsEntries(target)
                HardwareType.CUSTOM_MODULE -> expressEntries(target)
            }
        } catch (e: ArchiveException) {
            return ArchiveListing(saved(target), offline = true)
        }
        return ArchiveListing(sorted(found), incomplete = incomplete)
    }

    /** What the phone has for [target], newest first, for when there is no connection. */
    fun saved(target: ArchiveTarget): List<ArchiveEntry> =
        sorted(cache.saved(target.hardwareName, originsOf(target.type)).map { it.entry })

    fun isSaved(entry: ArchiveEntry): Boolean = cache.find(entry) != null

    /**
     * Downloads [entry] (or takes the saved copy), runs the checks and saves it. Cancelling the collector
     * closes the connection and saves nothing.
     *
     * @throws ArchiveException if the image can't be fetched or is refused
     */
    fun download(entry: ArchiveEntry, target: ArchiveTarget): Flow<ArchiveDownload> = channelFlow {
        cache.find(entry)?.read()?.let { saved ->
            val image = verify(entry, target, saved)
            send(ArchiveDownload.Done(ArchiveImage(entry, image, Crc16.compute(saved), sha256(saved), fromCache = true)))
            return@channelFlow
        }
        send(ArchiveDownload.Progress(0, null))
        val (located, dataSection) = locate(entry)
        val bytes = fetch(located, dataSection) { received, total -> trySend(ArchiveDownload.Progress(received, total)) }
        val image = verify(located, target, bytes)
        cache.store(located, bytes)
        send(ArchiveDownload.Done(ArchiveImage(located, image, Crc16.compute(bytes), sha256(bytes), fromCache = false)))
    }.flowOn(Dispatchers.IO)

    /** The changelog from the VESC Tool repository, or the last copy fetched if it can't be reached; null if there is neither. */
    suspend fun changelog(): String? = try {
        val text = http.get(urls.repository + CHANGELOG, null, MAX_TEXT).toString(Charsets.UTF_8)
        cache.storeText(CHANGELOG_CACHE, text)
        text
    } catch (e: ArchiveException) {
        cache.text(CHANGELOG_CACHE)
    }

    private fun sorted(entries: List<ArchiveEntry>): List<ArchiveEntry> = entries
        .distinctBy { it.key }
        .sortedWith(NEWEST_FIRST.thenByDescending { it.standard }.thenBy { it.withoutLimits }.thenBy { it.file.lowercase() })

    private suspend fun vescEntries(target: ArchiveTarget, onIncomplete: () -> Unit): List<ArchiveEntry> = supervisorScope {
        val names = bundleNames()
        val releases = names.mapNotNull { name -> RELEASE.matchEntire(name)?.let { name to it.groupValues[1] } }
        val sources = releases.map { (name, version) ->
            async { bundleEntries(name) { parts, rcc -> releaseEntry(name, version, parts, rcc, target) } }
        } + if (ARCHIVE_BUNDLE in names) {
            listOf(async { bundleEntries(ARCHIVE_BUNDLE) { parts, rcc -> archiveEntry(parts, rcc, target) } })
        } else {
            emptyList()
        }
        val results = sources.map { settle(it) }
        if (results.all { it == null }) throw ArchiveException(ArchiveException.Reason.Network, "No firmware bundle could be read")
        if (results.any { it == null }) onIncomplete()
        results.filterNotNull().flatten()
    }

    private suspend fun settle(source: Deferred<List<ArchiveEntry>>): List<ArchiveEntry>? = try {
        source.await()
    } catch (e: ArchiveException) {
        null
    }

    private suspend fun bundleEntries(bundle: String, transform: (List<String>, RccEntry) -> ArchiveEntry?): List<ArchiveEntry> =
        bundleIndex(bundle).entries.mapNotNull { transform(it.path.split('/').drop(1), it) }

    private fun releaseEntry(bundle: String, version: String, parts: List<String>, rcc: RccEntry, target: ArchiveTarget): ArchiveEntry? {
        if (parts.size != 4 || parts[0] != "res" || parts[1] != "firmwares" || !parts[3].endsWith(".bin", true)) return null
        return entryFor(ArchiveOrigin.Release, version, bundle, parts[2], rcc, target)
    }

    private fun archiveEntry(parts: List<String>, rcc: RccEntry, target: ArchiveTarget): ArchiveEntry? {
        if (parts.size != 4 || parts[0] != "fw_archive" || !parts[3].endsWith(".bin", true)) return null
        return entryFor(ArchiveOrigin.Archive, parts[1], ARCHIVE_BUNDLE, parts[2], rcc, target)
    }

    private fun entryFor(origin: ArchiveOrigin, version: String, bundle: String, directory: String, rcc: RccEntry, target: ArchiveTarget): ArchiveEntry? {
        val hardware = directory.split("_o_")
        if (hardware.none { it.equals(target.hardwareName, true) }) return null
        return ArchiveEntry(origin, version, hardware, rcc.name, bundle, rcc.path, rcc.dataOffset, rcc.flags)
    }

    private suspend fun expressEntries(target: ArchiveTarget): List<ArchiveEntry> {
        val index = bundleIndex(EXPRESS_BUNDLE)
        for (chip in EXPRESS_CHIPS) {
            val match = index.entries.firstOrNull { entry ->
                val parts = entry.path.split('/').drop(1)
                parts.size == 5 && parts[0] == "res" && parts[1] == "firmwares_esp" && parts[2] == chip &&
                    parts[3].equals(target.hardwareName, true) && parts[4].equals(EXPRESS_IMAGE, true)
            } ?: continue
            val directory = match.path.split('/')[4]
            return listOf(ArchiveEntry(ArchiveOrigin.Express, null, listOf(directory), match.name, EXPRESS_BUNDLE, match.path, match.dataOffset, match.flags))
        }
        return emptyList()
    }

    private suspend fun bmsEntries(target: ArchiveTarget): List<ArchiveEntry> {
        val base = "res/firmwares_bms"
        val listed = try {
            listDirectory(base).filter { it.directory }.map { it.name }
        } catch (e: ArchiveException) {
            emptyList()
        }
        val directories = listed.ifEmpty { listOf(target.hardwareName) }
            .filter { dir -> dir.split("_o_").any { it.equals(target.hardwareName, true) } }
        return directories.flatMap { dir ->
            val files = try {
                listDirectory("$base/$dir").filter { !it.directory && it.name.endsWith(".bin", true) }.map { it.name }
            } catch (e: ArchiveException) {
                emptyList()
            }.ifEmpty { listOf(BMS_IMAGE) }
            files.map { ArchiveEntry(ArchiveOrigin.Repository, null, dir.split("_o_"), it, null, "$base/$dir/$it", 0, 0) }
        }
    }

    private class Listed(val name: String, val directory: Boolean)

    private suspend fun listDirectory(path: String): List<Listed> {
        val json = http.get(urls.repositoryListing + path, null, MAX_TEXT).toString(Charsets.UTF_8)
        return LISTING_ITEM.findAll(json).map { Listed(it.groupValues[1], it.groupValues[2] == "dir") }.toList()
    }

    private suspend fun bundleNames(): List<String> {
        val html = http.get(urls.archive, null, MAX_TEXT).toString(Charsets.UTF_8)
        return BUNDLE_LINK.findAll(html).map { it.groupValues[1] }.distinct().toList()
    }

    /** The bundle's table of contents: the 24-byte header is always fetched fresh, the tail only when the header changed. */
    private suspend fun bundleIndex(bundle: String): RccIndex {
        val url = urls.archive + bundle
        val header = http.get(url, "bytes=0-${RccIndex.HEADER_SIZE - 1}", RccIndex.HEADER_SIZE)
        val from = RccIndex.tailOffset(header)
        val saved = cache.index(bundle)
        val tail = if (saved != null && saved.first.contentEquals(header)) {
            saved.second
        } else {
            http.get(url, "bytes=$from-", RccIndex.MAX_TAIL).also { cache.storeIndex(bundle, header, it) }
        }
        return RccIndex.parse(header, tail)
    }

    /** The entry as the bundle lists it now, with the bundle's data section; the listing it came from may be older than a rebuilt bundle. */
    private suspend fun locate(entry: ArchiveEntry): Pair<ArchiveEntry, Long> {
        val bundle = entry.bundle ?: return entry to 0
        val index = bundleIndex(bundle)
        val fresh = index.entries.firstOrNull { it.path == entry.path }
            ?: throw ArchiveException(ArchiveException.Reason.NotFound, "${entry.path} is no longer in $bundle")
        return entry.copy(dataOffset = fresh.dataOffset, flags = fresh.flags) to index.dataSection
    }

    private suspend fun fetch(entry: ArchiveEntry, dataSection: Long, onProgress: (Long, Long?) -> Unit): ByteArray {
        val bundle = entry.bundle
        if (bundle == null) {
            return http.get(urls.repository + entry.path, null, FirmwareImage.MAX_FILE_SIZE) { onProgress(it.toLong(), null) }
        }
        val url = urls.archive + bundle
        val start = dataSection + entry.dataOffset
        val prefix = http.get(url, "bytes=$start-${start + 3}", 4)
        if (prefix.size != 4) throw ArchiveException(ArchiveException.Reason.BadBundle, "The size of ${entry.file} could not be read")
        val size = RccIndex.sizePrefix(prefix)
        if (size <= 0 || size > FirmwareImage.MAX_FILE_SIZE) {
            throw ArchiveException(ArchiveException.Reason.TooLarge, "${entry.file} claims to be $size bytes")
        }
        if (entry.flags and RccEntry.ZSTD != 0) throw ArchiveException(ArchiveException.Reason.BadBundle, "${entry.file} uses a compression this app can't read")
        val stored = http.get(url, "bytes=${start + 4}-${start + 3 + size}", size.toInt()) { onProgress(it.toLong(), size) }
        if (stored.size.toLong() != size) throw ArchiveException(ArchiveException.Reason.Network, "${entry.file} ended after ${stored.size} of $size bytes")
        return if (entry.flags and RccEntry.COMPRESSED != 0) RccIndex.inflate(stored, FirmwareImage.MAX_FILE_SIZE) else stored
    }

    companion object {
        private const val CHANGELOG = "res/firmwares/CHANGELOG.md"
        private const val CHANGELOG_CACHE = "firmware-changelog.md"
        private const val ARCHIVE_BUNDLE = "res_fw.rcc"
        private const val EXPRESS_BUNDLE = "res_fw_esp32.rcc"
        private const val EXPRESS_IMAGE = "vesc_express.bin"
        private const val BMS_IMAGE = "vesc_default.bin"
        private val EXPRESS_CHIPS = listOf("esp32c3", "esp32s3", "esp32")
        private const val MAX_TEXT = 2 * 1024 * 1024
        private val RELEASE = Regex("""res_fw_(\d+(?:\.\d+)+)\.rcc""")
        private val BUNDLE_LINK = Regex("""href="(res_fw(?:_\d+(?:\.\d+)+)?\.rcc)"""")
        private val LISTING_ITEM = Regex(""""name"\s*:\s*"([^"]+)"[^{}]*?"type"\s*:\s*"(dir|file)"""")

        private val NEWEST_FIRST = Comparator<ArchiveEntry> { a, b ->
            val x = a.version
            val y = b.version
            when {
                x == null && y == null -> 0
                x == null -> 1
                y == null -> -1
                else -> Changelog.compareVersions(y, x)
            }
        }

        private fun originsOf(type: HardwareType): Set<ArchiveOrigin> = when (type) {
            HardwareType.VESC -> setOf(ArchiveOrigin.Release, ArchiveOrigin.Archive)
            HardwareType.VESC_BMS -> setOf(ArchiveOrigin.Repository)
            HardwareType.CUSTOM_MODULE -> setOf(ArchiveOrigin.Express)
        }

        /**
         * The pre-flash checks for an image from the archive; the controller's hardware must be one of the
         * names the server filed it under, and the image itself must agree:
         * - size within what any controller takes, not blank (same as a file picked by hand);
         * - motor controller firmware names its hardware (`org.vesc.<name>`), and the name must be one of the
         *   directory's, which also catches a wrong offset into a bundle; BMS and VESC Express images carry no
         *   such name, but if one is present it must match too.
         *
         * @throws ArchiveException with [ArchiveException.Reason.HardwareMismatch], [ArchiveException.Reason.TooLarge]
         *   or [ArchiveException.Reason.Damaged]
         */
        fun verify(entry: ArchiveEntry, target: ArchiveTarget, bytes: ByteArray): FirmwareImage {
            if (entry.hardware.none { it.equals(target.hardwareName, true) }) {
                throw ArchiveException(
                    ArchiveException.Reason.HardwareMismatch,
                    "${entry.displayName} is for ${entry.hardware.joinToString()}, the controller is ${target.hardwareName}",
                )
            }
            val plain = try {
                FirmwareImage.fromFile(entry.file, bytes).bytes
            } catch (e: FirmwareFileException) {
                val reason = if (e.reason == FirmwareFileException.Reason.TooLarge) ArchiveException.Reason.TooLarge else ArchiveException.Reason.Damaged
                throw ArchiveException(reason, e.message.orEmpty(), e)
            }
            val embedded = FirmwareImage.embeddedHardware(plain)
            if (embedded == null && target.type == HardwareType.VESC) {
                throw ArchiveException(ArchiveException.Reason.Damaged, "${entry.displayName} doesn't name its hardware, so it can't be the file it should be")
            }
            if (embedded != null && entry.hardware.none { it.equals(embedded, true) }) {
                throw ArchiveException(
                    ArchiveException.Reason.HardwareMismatch,
                    "${entry.displayName} contains firmware for $embedded, not ${entry.hardware.joinToString()}",
                )
            }
            return FirmwareImage(entry.displayName, plain, entry.hardware)
        }
    }
}
