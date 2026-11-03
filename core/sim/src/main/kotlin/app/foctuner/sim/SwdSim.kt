package app.foctuner.sim

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId

/**
 * The controller's Black Magic SWD probe with an nRF52832 (512K/64K) on the SWD header: connect, erase, write, read,
 * reboot, disconnect, as `commands.c` answers them. Flash starts erased (0xFF); a write needs a connected target and
 * can only clear bits, like NOR flash. Without [connected] probe pins nothing answers the connect.
 */
internal class SwdSim(private val vesc: SimulatedVesc) {
    private val flash = ByteArray(FLASH_SIZE) { 0xFF.toByte() }
    private var connected = false
    var targetPresent = true
    var writes = 0
        private set

    fun handle(id: CommPacketId, r: ByteReader) {
        val w = ByteWriter().u8(id.id)
        when (id) {
            CommPacketId.COMM_BM_MAP_PINS_DEFAULT -> vesc.reply(w.i16(1).toByteArray())
            CommPacketId.COMM_BM_MAP_PINS_NRF5X -> vesc.reply(w.i16(0).toByteArray())
            CommPacketId.COMM_BM_CONNECT -> {
                connected = targetPresent
                vesc.reply(w.i16(if (targetPresent) NRF52832_512K else -1).toByteArray())
            }
            CommPacketId.COMM_BM_ERASE_FLASH_ALL -> {
                if (connected) flash.fill(0xFF.toByte())
                vesc.reply(w.i16(if (connected) 1 else -1).toByteArray())
            }
            CommPacketId.COMM_BM_WRITE_FLASH -> {
                val address = r.u32()
                val data = r.restBytes()
                val ok = connected && address >= 0 && address + data.size <= FLASH_SIZE
                if (ok) {
                    writes++
                    data.forEachIndexed { i, b -> flash[(address + i).toInt()] = (flash[(address + i).toInt()].toInt() and b.toInt()).toByte() }
                }
                vesc.reply(w.i16(if (!connected) -1 else if (ok) 1 else -3).toByteArray())
            }
            CommPacketId.COMM_BM_MEM_READ -> {
                val address = r.u32()
                val length = r.u16().coerceAtMost(MAX_READ)
                if (!connected || address + length > FLASH_SIZE) {
                    vesc.reply(w.i16(-1).toByteArray())
                } else {
                    vesc.reply(w.i16(1).bytes(flash, address.toInt(), length).toByteArray())
                }
            }
            CommPacketId.COMM_BM_REBOOT -> vesc.reply(w.i16(if (connected) 1 else -1).toByteArray())
            CommPacketId.COMM_BM_DISCONNECT -> {
                connected = false
                vesc.reply(w.toByteArray())
            }
            else -> Unit
        }
    }

    fun read(address: Int, length: Int): ByteArray = flash.copyOfRange(address, address + length)

    private companion object {
        const val FLASH_SIZE = 512 * 1024
        const val MAX_READ = 400
        const val NRF52832_512K = 7
    }
}
