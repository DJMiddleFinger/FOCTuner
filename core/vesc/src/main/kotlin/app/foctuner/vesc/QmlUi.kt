package app.foctuner.vesc

import app.foctuner.config.QtCompression
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_GET_QML_UI_APP
import app.foctuner.protocol.CommPacketId.COMM_GET_QML_UI_HW
import app.foctuner.protocol.CommPacketId.COMM_QMLUI_ERASE
import app.foctuner.protocol.CommPacketId.COMM_QMLUI_WRITE
import kotlin.time.Duration.Companion.seconds

/**
 * Where a controller's QML user interface comes from: built into its firmware (the hardware UI, VESC Express and
 * some boards) or installed into the QML flash area by a package (the app UI). [FirmwareInfo.qmlHardware] and
 * [FirmwareInfo.qmlApp] say whether each exists: 0 none, 1 shown as a tab, 2 fullscreen.
 *
 * VESC Tool runs these with Qt Quick and its own object model, which FOCTuner doesn't have, so they are only
 * detected, read and installed here, never run.
 */
enum class QmlUiSource { Hardware, App }

class QmlUiException(message: String) : VescException(message)

/**
 * Downloads QML UI [source] and returns its source text (with the two import lines VESC Tool prepends on upload), or
 * null if none is stored. The hardware UI only answers when the firmware has one.
 */
suspend fun VescController.readQmlUi(source: QmlUiSource, onProgress: (read: Int, total: Int) -> Unit = { _, _ -> }): String? {
    val id = if (source == QmlUiSource.Hardware) COMM_GET_QML_UI_HW else COMM_GET_QML_UI_APP
    val data = readChunked(
        id,
        request = { offset, length -> ByteWriter().u8(id.id).i32(length).i32(offset).toByteArray() },
        parse = { r, offset ->
            val total = r.i32()
            if (r.i32() == offset) total to r.restBytes() else null
        },
        onProgress = onProgress,
    )
    return if (data.isEmpty()) null else QtCompression.uncompress(data).decodeToString()
}

/** Erases the installed (app) QML UI. [size] is how much the next upload needs; STM32 firmware erases it all. */
suspend fun VescController.eraseQmlUi(size: Int = ERASE_ONLY_SIZE) {
    val request = ByteWriter().u8(COMM_QMLUI_ERASE.id).i32(size).toByteArray()
    val ok = exchange(request, COMM_QMLUI_ERASE, ERASE_TIMEOUT) { it.u8() != 0 } ?: throw noReply(COMM_QMLUI_ERASE, ERASE_TIMEOUT)
    if (!ok) throw QmlUiException("Erasing the QML UI failed")
}

/**
 * Stores [qml] as the controller's app QML UI, as a package install does: erases the area, then writes the image in
 * acknowledged chunks. [onProgress] gets bytes written and the total.
 *
 * @throws QmlUiException if it doesn't fit or a write is refused
 */
suspend fun VescController.uploadQmlUi(qml: String, fullscreen: Boolean, onProgress: (written: Int, total: Int) -> Unit = { _, _ -> }) {
    val compressed = QmlUiImage.compress(qml)
    val image = QmlUiImage.image(compressed, fullscreen)
    if (image.size > QmlUiImage.MAX_SIZE) throw QmlUiException("The QML UI takes ${image.size} bytes, the controller has room for ${QmlUiImage.MAX_SIZE}")
    eraseQmlUi(compressed.size + ERASE_MARGIN)
    var offset = 0
    onProgress(0, image.size)
    while (offset < image.size) {
        val chunk = image.copyOfRange(offset, minOf(offset + CHUNK_SIZE, image.size))
        val request = ByteWriter(chunk.size + 5).u8(COMM_QMLUI_WRITE.id).u32(offset.toLong()).bytes(chunk).toByteArray()
        val expected = offset.toLong()
        val ok = retrying(WRITE_TRIES) {
            exchange(request, COMM_QMLUI_WRITE, WRITE_TIMEOUT) { r ->
                val ok = r.u8() != 0
                if (r.u32() == expected) ok else null
            }
        } ?: throw noReply(COMM_QMLUI_WRITE, WRITE_TIMEOUT)
        if (!ok) throw QmlUiException("Writing the QML UI failed at byte $offset")
        offset += chunk.size
        onProgress(offset, image.size)
    }
}

/** The stored form of a QML UI (`CodeLoader::qmlCompress` and `qmlUpload`). */
object QmlUiImage {
    /** VESC Tool refuses images over 120 KiB. */
    const val MAX_SIZE = 120 * 1024

    /** The script with the imports VESC Tool adds, Qt-compressed. */
    fun compress(qml: String): ByteArray =
        QtCompression.compress(("import Vedder.vesc.vescinterface 1.0;import \"qrc:/mobile\";$qml").encodeToByteArray())

    /** Length and CRC header, display flags (1 tab, 2 fullscreen), then the compressed script; the same header as LispBM code. */
    fun image(compressed: ByteArray, fullscreen: Boolean): ByteArray =
        LispCode.image(ByteWriter(compressed.size + 2).u16(if (fullscreen) 2 else 1).bytes(compressed).toByteArray())
}

private const val CHUNK_SIZE = 384
private const val WRITE_TRIES = 5
private const val ERASE_ONLY_SIZE = 16
private const val ERASE_MARGIN = 100
private val ERASE_TIMEOUT = 6.seconds
private val WRITE_TIMEOUT = 1.seconds
