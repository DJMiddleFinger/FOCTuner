package app.foctuner.sim

import app.foctuner.vesc.firmware.archive.ArchiveUrls
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Archive content for demo mode and tests: the layout of the real server, with small images that the
 * simulated controller ([FirmwareSim]) installs. The image names its hardware (`org.vesc.<name>`) and
 * version (`SIMFW <major>.<minor>`) like firmware does, so installing one changes what the controller reports.
 */
object DemoArchive {
    const val RELEASE = "7.01"
    val OLDER = listOf("7.00", "6.06", "6.05")
    const val EXPRESS = "VESC Express T"
    const val BMS = "12s7p"

    /** A firmware image for [hardware] that installs as [version]; [variant] makes the bytes differ between builds. */
    fun image(hardware: String, version: String, variant: Int = 0, size: Int = IMAGE_SIZE): ByteArray {
        val bytes = ByteArray(size) { (((it / 24) * 31 + it % 24) xor variant).toByte() }
        val tag = "org.vesc.$hardware\u0000SIMFW ${version.substringBefore('.')}.${version.substringAfter('.')}\u0000".toByteArray(Charsets.ISO_8859_1)
        tag.copyInto(bytes, 2048)
        return bytes
    }

    fun host(hardware: String = "60_MK6", chunkDelay: Duration = 40.milliseconds, urls: ArchiveUrls = ArchiveUrls()): SimulatedArchiveHost {
        val host = SimulatedArchiveHost(urls)
        host.chunkDelay = chunkDelay
        host.addBundle(
            "res_fw_$RELEASE.rcc",
            RccWriter.build(
                listOf(
                    RccWriter.File("/res/firmwares/$hardware/VESC_default.bin", image(hardware, RELEASE)),
                    RccWriter.File("/res/firmwares/$hardware/VESC_default_no_hw_limits.bin", image(hardware, RELEASE, variant = 1)),
                    RccWriter.File("/res/firmwares/75_300/VESC_default.bin", image("75_300", RELEASE)),
                ),
            ),
        )
        host.addBundle(
            "res_fw.rcc",
            RccWriter.build(
                OLDER.flatMap { version ->
                    listOf(
                        RccWriter.File("/fw_archive/$version/$hardware/VESC_default.bin", image(hardware, version)),
                        RccWriter.File("/fw_archive/$version/75_300/VESC_default.bin", image("75_300", version)),
                    )
                },
            ),
        )
        host.addBundle(
            "res_fw_esp32.rcc",
            RccWriter.build(
                listOf(
                    RccWriter.File("/res/firmwares_esp/esp32c3/$EXPRESS/vesc_express.bin", image(EXPRESS, "6.05", size = 64 * 1024)),
                    RccWriter.File("/res/firmwares_esp/esp32c3/$EXPRESS/bootloader.bin", ByteArray(2048) { 7 }),
                    RccWriter.File("/res/firmwares_esp/esp32c3/$EXPRESS/partition-table.bin", ByteArray(3072) { 0x55 }),
                ),
            ),
        )
        host.addRepositoryFile("res/firmwares_bms/$BMS/vesc_default.bin", image(BMS, "1.00", size = 48 * 1024))
        host.addRepositoryFile("res/firmwares_bms/18s_light/vesc_default.bin", image("18s_light", "1.00", size = 48 * 1024))
        host.addRepositoryFile("res/firmwares/CHANGELOG.md", changelog())
        return host
    }

    private fun changelog(): ByteArray =
        checkNotNull(DemoArchive::class.java.getResourceAsStream("demo-changelog.md")) { "demo-changelog.md is missing" }.use { it.readBytes() }

    private const val IMAGE_SIZE = 160 * 1024
}
