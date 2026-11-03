package app.foctuner.sim

import app.foctuner.transport.Transport
import app.foctuner.vesc.esp.SerialLines
import app.foctuner.vesc.esp.Slip
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException
import java.security.MessageDigest

/**
 * An ESP32-C3's ROM bootloader on its USB serial port, for tests and demo mode: it answers only after the reset
 * lines put it in download mode, then SYNC, READ_REG / WRITE_REG (the chip magic and the SPI peripheral, which reads a
 * 4 MB flash id), SPI_ATTACH, SPI_SET_PARAMS, FLASH_BEGIN (erases), FLASH_DATA (checksum checked, written at its
 * sequence number), SPI_FLASH_MD5 (32 hex characters, as the ROM sends it). [corruptBlock] flips a byte of one
 * written block, to test the MD5 check.
 */
class EspRomSim : Transport, SerialLines {
    private val out = Channel<ByteArray>(Channel.UNLIMITED)
    private val decoder = Slip.Decoder()
    private val flash = ByteArray(4 * 1024 * 1024) { 0xFF.toByte() }
    private val registers = HashMap<Long, Long>()
    private var downloadMode = false
    private var lastRts = false
    private var beginOffset = 0L
    private var blockSize = 0
    var corruptBlock: Int? = null
    var blocksWritten = 0
        private set

    override val maxWriteSize: Int = 64
    override val received: Flow<ByteArray> = out.receiveAsFlow()

    fun flash(offset: Int, length: Int): ByteArray = flash.copyOfRange(offset, offset + length)

    override suspend fun set(dtr: Boolean, rts: Boolean) {
        // RTS released after being held enters download mode (both the classic and the USB serial sequences end so).
        if (lastRts && !rts) downloadMode = true
        lastRts = rts
    }

    override suspend fun write(bytes: ByteArray) {
        for (packet in decoder.feed(bytes)) if (downloadMode) handle(packet)
    }

    override suspend fun close() {
        out.close()
    }

    private fun handle(p: ByteArray) {
        if (p.size < 8 || p[0].toInt() != 0) return
        val op = p[1].toInt() and 0xFF
        val data = p.copyOfRange(8, p.size)
        fun u32(at: Int): Long = (0..3).fold(0L) { acc, i -> acc or ((data[at + i].toLong() and 0xFF) shl (8 * i)) }
        when (op) {
            SYNC -> repeat(8) { reply(op) }
            READ_REG -> reply(op, value = read(u32(0)))
            WRITE_REG -> {
                registers[u32(0)] = u32(4)
                if (u32(0) == SPI_CMD && u32(4) and (1L shl 18) != 0L) registers[SPI_W0] = 0x1640EF // Winbond, size id 0x16
                reply(op)
            }
            SPI_ATTACH, SPI_SET_PARAMS -> reply(op)
            FLASH_BEGIN -> {
                val size = u32(0).toInt()
                blockSize = u32(8).toInt()
                beginOffset = u32(12)
                flash.fill(0xFF.toByte(), beginOffset.toInt(), (beginOffset + size).toInt().coerceAtMost(flash.size))
                reply(op)
            }
            FLASH_DATA -> {
                val length = u32(0).toInt()
                val seq = u32(4).toInt()
                val block = data.copyOfRange(16, 16 + length)
                val checksum = (p[4].toInt() and 0xFF)
                if (block.fold(0xEF) { acc, b -> acc xor (b.toInt() and 0xFF) } != checksum) {
                    reply(op, failed = true)
                    return
                }
                if (seq == corruptBlock) block[0] = (block[0].toInt() xor 0xFF).toByte()
                block.copyInto(flash, (beginOffset + seq.toLong() * blockSize).toInt())
                blocksWritten++
                reply(op)
            }
            SPI_FLASH_MD5 -> {
                val at = u32(0).toInt()
                val size = u32(4).toInt()
                val md5 = MessageDigest.getInstance("MD5").digest(flash.copyOfRange(at, at + size)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                reply(op, payload = md5.toByteArray())
            }
            else -> reply(op, failed = true)
        }
    }

    private fun read(address: Long): Long = when (address) {
        MAGIC_REG -> 0x1b31506fL
        else -> registers[address] ?: 0L
    }.also { if (address == SPI_CMD) registers[SPI_CMD] = 0 }

    private fun reply(op: Int, value: Long = 0, payload: ByteArray = ByteArray(0), failed: Boolean = false) {
        val body = payload + byteArrayOf(if (failed) 1 else 0, if (failed) 0x05 else 0)
        val header = byteArrayOf(1, op.toByte(), body.size.toByte(), (body.size shr 8).toByte()) +
            byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
        if (!out.trySend(Slip.encode(header + body)).isSuccess) throw IOException("closed")
    }

    private companion object {
        const val SYNC = 0x08
        const val WRITE_REG = 0x09
        const val READ_REG = 0x0A
        const val SPI_SET_PARAMS = 0x0B
        const val SPI_ATTACH = 0x0D
        const val FLASH_BEGIN = 0x02
        const val FLASH_DATA = 0x03
        const val SPI_FLASH_MD5 = 0x13
        const val MAGIC_REG = 0x40001000L
        const val SPI_CMD = 0x60002000L
        const val SPI_W0 = 0x60002058L
    }
}
