package app.foctuner.vesc

import app.foctuner.sim.RccWriter
import app.foctuner.vesc.PackageCompatibility.Verdict
import app.foctuner.vesc.firmware.archive.ArchiveException
import app.foctuner.vesc.firmware.archive.HttpFetcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PackageStoreTest {
    private val vesc = FirmwareInfo(6, 5, "60_MK6", "", hardwareType = HardwareType.VESC)
    private val express = FirmwareInfo(6, 5, "VESC Express T", "", hardwareType = HardwareType.CUSTOM_MODULE)
    private val vbms32 = FirmwareInfo(6, 5, "VBMS32", "", hardwareType = HardwareType.CUSTOM_MODULE, firmwareName = "micro")

    // From vesc_pkg: refloat, balance, vbms32_micro and a version-pinned one, as their pkgdesc.qml has them.
    private val refloat = """
        Item {
            function isCompatible (fwRxParams) {
                if (fwRxParams.hwTypeStr().toLowerCase() != "vesc") {
                    return false;
                }
                return true;
            }
        }
    """
    private val vbmsMicro = """
        Item {
            property string pkgName: "VBMS32 Micro"
            function isCompatible (fwRxParams) {
                var hwName = fwRxParams.hw.toLowerCase();
                var fwName = fwRxParams.fwName.toLowerCase();
                var hwType = fwRxParams.hwTypeStr().toLowerCase();		
                // The classic VESC BMS does not support packages at all
                if (hwType == "vesc bms") {
                    return false
                }
                if (hwType != "custom module") {
                    return false
                }
                var res = false
                if (hwName == "vbms32" && (fwName == "micro" || fwName == "")) {
                    res = true
                }
                return res
            }
        }
    """
    private val pinned = """
        function isCompatible(fwRxParams) {
            var major = fwRxParams.major;
            var minor = fwRxParams.minor;
            var hwName = fwRxParams.hw;
            if (major != 7 || minor != 0) {
                return false;
            }
            if (hwName != "410" && hwName != "60" && hwName != "60_MK5") {
                return false;
            } else {
                return true;
            }
        }
    """

    @Test
    fun runsTheCompatibilityChecksPackagesShipWith() {
        assertEquals(Verdict.Compatible, PackageCompatibility.check(refloat, vesc))
        assertEquals(Verdict.Incompatible, PackageCompatibility.check(refloat, express))
        assertEquals(Verdict.Compatible, PackageCompatibility.check(vbmsMicro, vbms32))
        assertEquals(Verdict.Incompatible, PackageCompatibility.check(vbmsMicro, vbms32.copy(firmwareName = "harmony32")))
        assertEquals(Verdict.Incompatible, PackageCompatibility.check(vbmsMicro, vesc))
        assertEquals(Verdict.Incompatible, PackageCompatibility.check(pinned, vesc))
        assertEquals(Verdict.Compatible, PackageCompatibility.check(pinned, FirmwareInfo(7, 0, "60_MK5", "")))
        assertEquals(Verdict.Incompatible, PackageCompatibility.check(pinned, FirmwareInfo(7, 0, "75_300", "")))
    }

    @Test
    fun anythingOutsideTheKnownSubsetIsUnknown() {
        assertEquals(Verdict.Unknown, PackageCompatibility.check("", vesc))
        assertEquals(Verdict.Unknown, PackageCompatibility.check("Item { }", vesc))
        assertEquals(Verdict.Unknown, PackageCompatibility.check("function isCompatible(fwRxParams) { return VescIf.isPortConnected() }", vesc))
        assertEquals(Verdict.Unknown, PackageCompatibility.check("function isCompatible(fwRxParams) { for (;;) {} }", vesc))
        assertEquals(Verdict.Unknown, PackageCompatibility.check("function isCompatible(fwRxParams) { return fwRxParams.major + 1 }", vesc))
        assertEquals(Verdict.Unknown, PackageCompatibility.check("function isCompatible(fwRxParams) { var a = 1 }", vesc))
        // An untaken branch may use names the evaluator doesn't know.
        assertEquals(
            Verdict.Compatible,
            PackageCompatibility.check("function isCompatible(fwRxParams) { if (false) { return x.y() } return true }", vesc),
        )
    }

    @Test
    fun readsTheStoreBundleAndKeepsIt() = runTest {
        fun pkg(name: String, qml: String) = VescPackage(name, lisp = byteArrayOf(1, 2, 3), descriptionQml = qml).pack()
        val bundle = RccWriter.build(
            listOf(
                RccWriter.File("/vesc_packages/refloat/refloat.vescpkg", pkg("Refloat", refloat)),
                RccWriter.File("/vesc_packages/vbms32_micro/vbms32_micro.vescpkg", pkg("VBMS32 Micro", vbmsMicro), compress = true),
                RccWriter.File("/vesc_packages/lib_files/files.vescpkg", pkg("files", "")),
                RccWriter.File("/vesc_packages/refloat/README.md", "not a package".toByteArray()),
                RccWriter.File("/vesc_packages/broken/broken.vescpkg", "junk".toByteArray()),
            ),
        )
        var served: ByteArray? = bundle
        val host = object : HttpFetcher {
            override suspend fun get(url: String, range: String?, maxBytes: Int, onProgress: (Int) -> Unit): ByteArray {
                assertEquals(PackageStore.STORE_URL, url)
                return served ?: throw ArchiveException(ArchiveException.Reason.Network, "offline")
            }
        }
        val file = File(Files.createTempDirectory("store").toFile(), "vesc_pkg_all.rcc")
        val store = PackageStore(host, file)
        assertNull(store.cached())

        val packages = store.refresh()
        assertEquals(listOf("files", "Refloat", "VBMS32 Micro"), packages.map { it.pkg.name })
        assertEquals(listOf(true, false, false), packages.map { it.isLibrary })
        assertEquals(Verdict.Compatible, packages[1].compatibility(vesc))
        assertEquals(Verdict.Incompatible, packages[2].compatibility(vesc))

        served = null
        assertFailsWith<ArchiveException> { store.refresh() }
        assertEquals(3, store.cached()?.size)
        assertFailsWith<ArchiveException> { PackageStore.parse(ByteArray(10)) }
    }
}
