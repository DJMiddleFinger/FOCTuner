package app.foctuner.sim

import app.foctuner.vesc.LispCode
import app.foctuner.vesc.PackageStore
import app.foctuner.vesc.VescPackage
import app.foctuner.vesc.firmware.archive.ArchiveException
import app.foctuner.vesc.firmware.archive.HttpFetcher
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** A small package store for demo mode, served the way the real one is (one Qt resource bundle). */
object DemoPackages {
    private const val VESC_ONLY = """function isCompatible (fwRxParams) {
        return fwRxParams.hwTypeStr().toLowerCase() == "vesc"
    }"""
    private const val EXPRESS_ONLY = """function isCompatible (fwRxParams) {
        return fwRxParams.hwTypeStr().toLowerCase() == "custom module"
    }"""

    fun bundle(): ByteArray = RccWriter.build(
        listOf(
            file(
                "demo_lights/demo_lights.vescpkg",
                VescPackage(
                    name = "Demo Lights",
                    descriptionMarkdown = "# Demo Lights\n\nBlinks a status print every second and adds no settings. " +
                        "A package for trying the store in demo mode.\n\n- Works on any VESC motor controller\n- LispBM only",
                    lisp = LispCode.pack("(loopwhile t (progn (print \"lights on\") (sleep 1.0)))"),
                    descriptionQml = VESC_ONLY,
                ),
            ),
            file(
                "demo_dash/demo_dash.vescpkg",
                VescPackage(
                    name = "Demo Dash",
                    descriptionMarkdown = "# Demo Dash\n\nA dashboard for a VESC Express display module.",
                    lisp = LispCode.pack("(print \"dash\")"),
                    qml = "import QtQuick 2.7\nItem { }",
                    descriptionQml = EXPRESS_ONLY,
                ),
            ),
            file(
                "lib_demo/demo.vescpkg",
                VescPackage(name = "Demo Library", descriptionMarkdown = "Helpers other packages import.", lisp = LispCode.pack("(defun twice (x) (* 2 x))")),
            ),
        ),
    )

    private fun file(path: String, pkg: VescPackage) = RccWriter.File("/vesc_packages/$path", pkg.pack())

    /** Serves [bundle] at the store's address after a short delay, like a slow network. */
    fun host(): HttpFetcher = object : HttpFetcher {
        private val bytes by lazy { bundle() }

        override suspend fun get(url: String, range: String?, maxBytes: Int, onProgress: (Int) -> Unit): ByteArray {
            if (url != PackageStore.STORE_URL) throw ArchiveException(ArchiveException.Reason.NotFound, "$url was not found")
            for (step in 1..4) {
                delay(150.milliseconds)
                onProgress(bytes.size * step / 4)
            }
            return bytes
        }
    }
}
