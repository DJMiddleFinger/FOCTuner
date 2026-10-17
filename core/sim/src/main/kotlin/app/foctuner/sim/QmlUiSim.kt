package app.foctuner.sim

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_GET_QML_UI_APP
import app.foctuner.protocol.CommPacketId.COMM_QMLUI_ERASE
import app.foctuner.protocol.CommPacketId.COMM_QMLUI_WRITE
import app.foctuner.protocol.Crc16

/**
 * The QML flash area packages install their user interface into (flash_helper.c `CODE_IND_QML`): 128 KiB used only
 * when its length and CRC header check out. The board has no hardware QML UI, so COMM_GET_QML_UI_HW is ignored.
 */
internal class QmlUiSim(private val vesc: SimulatedVesc) {
    private val flash = ByteArray(AREA) { ERASED }

    /** What COMM_FW_VERSION reports for the app UI: the stored display flags, 0 when nothing valid is stored. */
    val flags: Int
        get() = if (stored() == null) 0 else ByteReader(flash, FLAGS_OFFSET).u16()

    fun erase() {
        flash.fill(ERASED)
        vesc.reply(byteArrayOf(COMM_QMLUI_ERASE.id.toByte(), 1))
    }

    fun write(r: ByteReader) {
        val offset = r.u32()
        val data = r.restBytes()
        val ok = offset + data.size <= AREA
        if (ok) {
            for (i in data.indices) flash[offset.toInt() + i] = (flash[offset.toInt() + i].toInt() and data[i].toInt()).toByte()
        }
        vesc.reply(ByteWriter().u8(COMM_QMLUI_WRITE.id).u8(if (ok) 1 else 0).u32(offset).toByteArray())
    }

    fun read(r: ByteReader) {
        val length = r.i32()
        val offset = r.i32()
        val data = stored()
        val w = ByteWriter().u8(COMM_GET_QML_UI_APP.id)
        if (data == null) {
            vesc.reply(w.i32(0).i32(0).toByteArray())
            return
        }
        if (length < 0 || offset < 0 || length + offset > data.size || length > MAX_READ) return
        vesc.reply(w.i32(data.size).i32(offset).bytes(data, offset, length).toByteArray())
    }

    /** The compressed script after the header and flags, or null when the area is erased or the CRC doesn't match. */
    private fun stored(): ByteArray? {
        val r = ByteReader(flash)
        val length = r.u32()
        val crc = r.u16()
        if (length > AREA - DATA_OFFSET) return null
        if (Crc16.compute(flash, FLAGS_OFFSET, length.toInt() + 2) != crc) return null
        return flash.copyOfRange(DATA_OFFSET, DATA_OFFSET + length.toInt())
    }

    private companion object {
        const val AREA = 128 * 1024
        const val FLAGS_OFFSET = 6
        const val DATA_OFFSET = 8
        const val MAX_READ = 502
        const val ERASED = 0xFF.toByte()
    }
}
