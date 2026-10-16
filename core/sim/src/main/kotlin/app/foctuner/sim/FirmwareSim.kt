package app.foctuner.sim

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_ERASE_BOOTLOADER
import app.foctuner.protocol.CommPacketId.COMM_ERASE_NEW_APP
import app.foctuner.protocol.CommPacketId.COMM_FW_INFO
import app.foctuner.protocol.CommPacketId.COMM_WRITE_NEW_APP_DATA
import app.foctuner.protocol.Crc16
import app.foctuner.vesc.firmware.FirmwareImage
import app.foctuner.vesc.firmware.Heatshrink
import app.foctuner.vesc.firmware.Lzo1x
import app.foctuner.vesc.firmware.LzoException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The firmware update path of `flash_helper.c` and `commands.c`, and the bootloader that installs the result.
 *
 * Flash is modelled as the update buffer (three 128 KB sectors) followed by the bootloader sector, both
 * holding old data until erased; writes can only clear bits, like NOR flash. On COMM_JUMP_TO_BOOTLOADER the
 * controller goes silent: with a valid size/CRC header the image is unpacked and "installed" and the
 * controller answers again after [INSTALL_TIME]; otherwise the bootloader gives up and the old firmware is
 * back after [REJECT_TIME]. With an erased bootloader sector it never answers again.
 *
 * An installed image sets the hardware name from its `org.vesc.<name>` string and the version from a
 * `SIMFW <major>.<minor>` string, when it has them.
 */
internal class FirmwareSim(private val vesc: SimulatedVesc, hardwareName: String, major: Int, minor: Int) {
    var major = major
        private set
    var minor = minor
        private set
    var hardwareName = hardwareName
        private set

    private val flash = ByteArray(BUFFER_SIZE + SECTOR) { (it * 31 + 7).toByte() }
    private var silentUntil = Duration.ZERO
    private var bricked = false

    val answering: Boolean get() = !bricked && vesc.uptime >= silentUntil

    fun eraseNewApp(r: ByteReader) {
        val size = r.u32()
        for (sector in 0 until BUFFER_SIZE / SECTOR) {
            if (size > sector.toLong() * SECTOR) erase(sector) else break
        }
        vesc.reply(byteArrayOf(COMM_ERASE_NEW_APP.id.toByte(), 1))
    }

    fun eraseBootloader() {
        erase(BUFFER_SIZE / SECTOR)
        vesc.reply(byteArrayOf(COMM_ERASE_BOOTLOADER.id.toByte(), 1))
    }

    /** Like the firmware, a stream that fails to unpack writes what came out of it, here nothing. */
    fun write(r: ByteReader, lzo: Boolean) {
        val offset = r.u32()
        val data = if (lzo) {
            val length = r.u16()
            try {
                Lzo1x.decompress(r.restBytes(), length)
            } catch (e: LzoException) {
                ByteArray(0)
            }
        } else {
            r.restBytes()
        }
        val ok = offset + data.size <= flash.size
        if (ok) {
            for (i in data.indices) {
                val at = (offset + i).toInt()
                flash[at] = (flash[at].toInt() and data[i].toInt()).toByte()
            }
        }
        vesc.reply(ByteWriter().u8(COMM_WRITE_NEW_APP_DATA.id).u8(if (ok) 1 else 0).u32(offset).toByteArray())
    }

    fun jump() {
        if ((BUFFER_SIZE until flash.size).all { flash[it] == ERASED }) {
            bricked = true
            return
        }
        val image = verifiedImage()
        if (image == null) {
            silentUntil = vesc.uptime + REJECT_TIME
            return
        }
        FirmwareImage.embeddedHardware(image)?.let { hardwareName = it }
        VERSION.find(image.toString(Charsets.ISO_8859_1))?.let {
            major = it.groupValues[1].toInt()
            minor = it.groupValues[2].toInt()
        }
        silentUntil = vesc.uptime + INSTALL_TIME
    }

    fun info(): ByteArray = ByteWriter().u8(COMM_FW_INFO.id).u8(major).u8(minor).u8(0)
        .cString(GIT_HASH).cString("").toByteArray()

    /** What the bootloader checks before it touches the application: size within the buffer and a matching CRC. */
    private fun verifiedImage(): ByteArray? {
        val r = ByteReader(flash)
        val header = r.u32()
        val crc = r.u16()
        val compressed = header ushr 24 == 0xCCL
        val size = (if (compressed) header and 0xFFFFFF else header).toInt()
        if (size == 0 || size > BUFFER_SIZE - HEADER_SIZE) return null
        val data = flash.copyOfRange(HEADER_SIZE, HEADER_SIZE + size)
        if (Crc16.compute(data) != crc) return null
        return if (compressed) Heatshrink.decode(data) else data
    }

    private fun erase(sector: Int) = flash.fill(ERASED, sector * SECTOR, (sector + 1) * SECTOR)

    private companion object {
        const val SECTOR = 128 * 1024
        const val BUFFER_SIZE = 3 * SECTOR
        const val HEADER_SIZE = 6
        const val ERASED: Byte = 0xFF.toByte()
        const val GIT_HASH = "0123456789abcdef0123456789abcdef01234567"
        val INSTALL_TIME = 8.seconds
        val REJECT_TIME = 2.seconds
        val VERSION = Regex("SIMFW (\\d+)\\.(\\d+)")
    }
}
