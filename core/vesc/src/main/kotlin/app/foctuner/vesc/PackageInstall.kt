package app.foctuner.vesc

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

enum class PackageInstallStep { ErasingQml, WritingQml, ErasingLisp, WritingLisp, StartingLisp }

/**
 * Installs [pkg] the way VESC Tool does (`CodeLoader::installVescPackage`): its QML UI replaces the stored one, or the
 * stored one is erased if the package has none; its LispBM code replaces the stored program and is started, or the
 * program is erased. Both are flash writes: if the link drops half way the controller is left without a program
 * until the package is installed again. Read [firmwareInfo] again afterwards, since the package may add custom
 * configs or a QML UI. [onProgress] gets the step and its bytes done and total (0 and 0 for erasing and starting).
 *
 * @throws LispException or [QmlUiException] if the controller refuses a step, [PackageException] if the code doesn't start
 */
suspend fun VescController.installPackage(
    pkg: VescPackage,
    onProgress: (step: PackageInstallStep, done: Int, total: Int) -> Unit = { _, _, _ -> },
) {
    val info = firmwareInfo()
    val qml = pkg.qml
    if (qml != null) {
        onProgress(PackageInstallStep.ErasingQml, 0, 0)
        uploadQmlUi(qml, pkg.qmlFullscreen) { done, total -> onProgress(PackageInstallStep.WritingQml, done, total) }
    } else if (info.qmlApp > 0) {
        onProgress(PackageInstallStep.ErasingQml, 0, 0)
        eraseQmlUi()
    }
    onProgress(PackageInstallStep.ErasingLisp, 0, 0)
    val lisp = pkg.lisp
    if (lisp != null) {
        uploadLisp(lisp, info.hardwareType) { done, total -> onProgress(PackageInstallStep.WritingLisp, done, total) }
        onProgress(PackageInstallStep.StartingLisp, 0, 0)
        if (!setLispRunning(true)) throw PackageException("The package was written but its LispBM code did not start")
    } else {
        eraseLisp()
    }
    // VESC Tool waits this long before reading the firmware info again, so the package can register its config.
    delay(SETTLE_TIME)
}

/**
 * Removes whatever package is installed: erases the LispBM program and the app QML UI (VESC Tool's "Uninstall
 * Current"). Both are attempted; the first failure is thrown afterwards.
 */
suspend fun VescController.uninstallPackage() {
    var failure: VescException? = null
    for (erase in listOf<suspend () -> Unit>({ eraseLisp() }, { eraseQmlUi() })) {
        try {
            erase()
        } catch (e: VescException) {
            failure = failure ?: e
        }
    }
    failure?.let { throw it }
    delay(SETTLE_TIME)
}

private val SETTLE_TIME = 500.milliseconds
