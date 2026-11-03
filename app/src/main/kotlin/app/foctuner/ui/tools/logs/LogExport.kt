package app.foctuner.ui.tools.logs

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.foctuner.R
import app.foctuner.logging.StoredLog
import app.foctuner.logs.GpsTrack
import app.foctuner.logs.Gpx
import app.foctuner.logs.LogKeys
import app.foctuner.logs.LogNames
import app.foctuner.logs.LogTable
import java.io.File
import java.io.IOException
import java.time.ZoneId

/** Gets a log out of the app: the share sheet, or a file the user picks with the system's document picker. */
internal object LogExport {
    /** The track of [table] as GPX, with the time of every point when the file name gives the date; null without a track. */
    fun gpx(log: StoredLog, table: LogTable, track: GpsTrack?): String? {
        if (track == null) return null
        val start = LogNames.start(log.name)?.let { LogNames.instant(it, table.column(LogKeys.TIME)?.firstOrNull(), ZoneId.systemDefault()) }
        return buildString { Gpx.write(log.name.substringBeforeLast('.'), table, track, start, this) }
    }

    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
        chooser(context, uri, mime)
    }

    fun shareText(context: Context, name: String, text: String, mime: String) {
        val file = File(File(context.cacheDir, "exports").also { it.mkdirs() }, name)
        file.writeText(text)
        share(context, file, mime)
    }

    /** Writes [bytes] to the document the user chose; a picker that was dismissed never gets here. */
    fun save(context: Context, uri: Uri, bytes: ByteArray) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        } catch (e: IOException) {
            // The provider refused the write; the user sees no file at the place they chose.
        }
    }

    private fun chooser(context: Context, uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.logging_share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
