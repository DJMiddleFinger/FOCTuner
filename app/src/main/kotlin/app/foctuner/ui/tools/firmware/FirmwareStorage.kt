package app.foctuner.ui.tools.firmware

import app.foctuner.sim.DemoArchive
import app.foctuner.vesc.firmware.UploadCheckpoint
import app.foctuner.vesc.firmware.archive.FirmwareArchive
import app.foctuner.vesc.firmware.archive.FirmwareCache
import app.foctuner.vesc.firmware.archive.UrlConnectionFetcher
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * What the firmware tool keeps on the phone: downloaded images (so a re-flash works offline) and the
 * checkpoint of an interrupted upload (so it can be continued after the app was closed). Demo mode uses the
 * simulated archive and its own folder.
 */
class FirmwareStorage(root: File, demo: Boolean, hardwareName: String) {
    private val folder = File(root, if (demo) "firmware-demo" else "firmware")
    val cache = FirmwareCache(File(folder, "cache"))
    val archive = FirmwareArchive(if (demo) DemoArchive.host(hardwareName) else UrlConnectionFetcher(), cache)
    private val checkpointFile = File(folder, "checkpoint.properties")

    fun checkpoint(): UploadCheckpoint? = try {
        val p = Properties()
        checkpointFile.inputStream().use(p::load)
        UploadCheckpoint.fromProperties(p)
    } catch (e: IOException) {
        null
    }

    fun saveCheckpoint(checkpoint: UploadCheckpoint?) {
        try {
            if (checkpoint == null) {
                checkpointFile.delete()
            } else {
                checkpointFile.parentFile?.mkdirs()
                checkpointFile.outputStream().use { checkpoint.toProperties().store(it, null) }
            }
        } catch (e: IOException) {
            // Without a saved checkpoint an interrupted upload can only be resumed while the app stays open.
        }
    }
}
