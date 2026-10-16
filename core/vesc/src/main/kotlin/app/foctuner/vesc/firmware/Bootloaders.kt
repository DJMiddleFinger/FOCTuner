package app.foctuner.vesc.firmware

import app.foctuner.vesc.FirmwareInfo
import app.foctuner.vesc.HardwareType
import java.security.MessageDigest

/**
 * The bootloaders VESC Tool ships in its repository (`res/bootloaders*` in vedderb/vesc_tool at
 * [SOURCE_COMMIT]), bundled as resources. They are built from vedderb/bldc-bootloader (GPL-3.0). File names
 * list the hardware they fit, separated by `_o_`; `generic` fits the rest of its hardware type.
 */
object Bootloaders {
    const val SOURCE_COMMIT = "f1e15cb1f81203d087599e7fd98ea9c2471a19c6"

    private class Entry(val path: String, val sha256: String) {
        val names: List<String> = path.substringAfterLast('/').removeSuffix(".bin").split("_o_")
        val generic: Boolean get() = names == listOf("generic")
    }

    private val vesc = listOf(
        Entry("bootloaders/40_o_47_o_48_o_410_o_411_o_412_o_DAS_RS.bin", "339a9976176af14cc23389b0fdb64eab24654211ed5b523966cf35b8e0d4a1a5"),
        Entry(
            "bootloaders/60_o_75_300_o_HD60_o_UAVC_OMEGA_o_75_300_R2_o_60_MK3_o_100_250_o_75_300_R3_o_60_MK4_o_60_MK5_o_HD75.bin",
            "d2c014d3b5faf843279d2b77325e128cace6d59ffa294fc0a7d68d96a57b0b33",
        ),
        Entry("bootloaders/generic.bin", "91d4e2e09594b422ec5e1aa976adc75fdfcaac455b59efa31aa304571242df06"),
    )
    private val bms = listOf(Entry("bootloaders_bms/generic.bin", "8508721b4c34699188655ebd268a6ea5e2b8443a4010e68c281bab1fe24129d5"))
    private val stm32g431 = listOf(Entry("bootloaders_custom_module/stm32g431.bin", "baf7809687a2d6e74a0cad0baedf603abe4c97c8aae67987958f21b6bd431ea2"))

    /**
     * The bootloaders for [controller], picked like VESC Tool: the ones naming its hardware, otherwise the
     * generic one. ESP32-based modules (whose UUID ends in four zero bytes) have none.
     */
    fun forController(controller: FirmwareInfo): List<FirmwareImage> {
        val candidates = when (controller.hardwareType) {
            HardwareType.VESC -> vesc
            HardwareType.VESC_BMS -> bms
            HardwareType.CUSTOM_MODULE -> when {
                controller.uuid.endsWith("00000000") -> emptyList()
                controller.hardwareName == "hm1" -> bms
                else -> stm32g431
            }
        }
        val named = candidates.filter { e -> !e.generic && e.names.any { it.equals(controller.hardwareName, ignoreCase = true) } }
        val picked = named.ifEmpty { candidates.filter { it.generic || candidates.size == 1 } }
        return picked.map { load(it, generic = it !in named) }
    }

    private fun load(entry: Entry, generic: Boolean): FirmwareImage {
        val bytes = Bootloaders::class.java.getResourceAsStream(entry.path)?.use { it.readBytes() }
            ?: error("Bundled bootloader ${entry.path} is missing")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == entry.sha256) { "Bundled bootloader ${entry.path} is corrupt" }
        return FirmwareImage(entry.names.joinToString(" & "), bytes, if (generic) emptyList() else entry.names, generic)
    }
}
