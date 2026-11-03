package app.foctuner.vesc.firmware.archive

import app.foctuner.vesc.HardwareType

/** Where VESC Tool's own downloads come from. */
data class ArchiveUrls(
    /** Directory with the `res_fw*.rcc` bundles. Plain HTTP: the certificate of this host doesn't cover its name. */
    val archive: String = "http://$ARCHIVE_HOST/vesc_fw_archive/",
    /** The VESC Tool repository, for BMS firmware and the changelog. */
    val repository: String = "https://raw.githubusercontent.com/vedderb/vesc_tool/master/",
    /** The same repository's directory listings. */
    val repositoryListing: String = "https://api.github.com/repos/vedderb/vesc_tool/contents/",
) {
    companion object {
        const val ARCHIVE_HOST = "home.vedder.se"
    }
}

/** The controller an image is for. */
data class ArchiveTarget(val hardwareName: String, val type: HardwareType)

enum class ArchiveOrigin {
    /** The bundle that ships with the newest VESC Tool, `res_fw_<version>.rcc`. */
    Release,

    /** `res_fw.rcc`, every version since 3.55. */
    Archive,

    /** A file in the VESC Tool repository (BMS firmware). */
    Repository,

    /** VESC Express builds, `res_fw_esp32.rcc`. */
    Express,
}

/**
 * One installable image the archive offers. [hardware] is every hardware name the server's directory
 * stands for (`410_o_411_o_412` is three). [bundle], [path] and [dataOffset] say where to read it from.
 */
data class ArchiveEntry(
    val origin: ArchiveOrigin,
    /** Firmware version such as `7.01`; null for builds that don't carry one (BMS, VESC Express). */
    val version: String?,
    val hardware: List<String>,
    /** File name, e.g. `VESC_default.bin`; the build variant. */
    val file: String,
    val bundle: String?,
    val path: String,
    val dataOffset: Long,
    val flags: Int,
) {
    /** The ordinary build; the other files of a hardware are variants (no hardware limits, a different PWM frequency, ...). */
    val standard: Boolean get() = file.equals("VESC_default.bin", true) || file.equals("vesc_express.bin", true)

    /** Builds that remove the hardware's protective limits. */
    val withoutLimits: Boolean get() = file.contains("no_hw_limits", ignoreCase = true)

    /** Names the image on disk and in the list. Unique per version, hardware and file. */
    val key: String get() = listOfNotNull(origin.name.lowercase(), version, hardware.joinToString("_o_"), file).joinToString("/") { sanitize(it) }

    val displayName: String get() = listOfNotNull(version, hardware.joinToString(" & "), file).joinToString(" ")

    companion object {
        fun sanitize(part: String): String = part.replace(Regex("[^A-Za-z0-9._+-]"), "_").trimStart('.').ifEmpty { "_" }
    }
}

/** What [FirmwareArchive.listing] found for a controller. */
data class ArchiveListing(
    /** Newest version first; within a version the standard build first. */
    val entries: List<ArchiveEntry>,
    /** The server couldn't be reached; these are the images saved on the phone. */
    val offline: Boolean = false,
    /** Some sources answered and some didn't, so older versions may be missing. */
    val incomplete: Boolean = false,
)

/** An image that passed the checks. */
class ArchiveImage(
    val entry: ArchiveEntry,
    val image: app.foctuner.vesc.firmware.FirmwareImage,
    val crc16: Int,
    val sha256: String,
    val fromCache: Boolean,
)

sealed interface ArchiveDownload {
    /** [total] is unknown until the size of the file has been read. */
    data class Progress(val received: Long, val total: Long?) : ArchiveDownload
    data class Done(val image: ArchiveImage) : ArchiveDownload
}

/** A VESC Express build for one board ([hardware]) and chip directory (`esp32c3`, `esp32s3`, ...). */
data class ExpressBuild(val chip: String, val hardware: String, internal val entries: Map<String, RccEntry>) {
    companion object {
        /** In flashing order: bootloader at 0x0, partition table at 0x8000, app at 0x20000. */
        val FILES = listOf("bootloader.bin", "partition-table.bin", "vesc_express.bin")
    }
}
