package app.foctuner.vesc.esp

import app.foctuner.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The serial port's modem lines, which put an ESP32 into its ROM bootloader and reset it. */
fun interface SerialLines {
    suspend fun set(dtr: Boolean, rts: Boolean)
}

/** The ESP chips the ROM loader knows, by `CHIP_DETECT_MAGIC_REG` value (esp-serial-flasher `esp_targets.c`). */
enum class EspChip(val label: String, val magic: Set<Long>, internal val spiBase: Long?, val encryptionInBegin: Boolean) {
    Esp32("ESP32", setOf(0x00f01d83L), null, false),
    Esp32S2("ESP32-S2", setOf(0x000007c6L), null, true),
    Esp32C3("ESP32-C3", setOf(0x6921506fL, 0x1b31506fL, 0x4881606FL, 0x4361606FL), 0x60002000L, true),
    Esp32S3("ESP32-S3", setOf(0x00000009L), 0x60002000L, true),
    Esp32C2("ESP32-C2", setOf(0x6f51306fL, 0x7c41a06fL, 0x0C21E06FL), 0x60002000L, true),
    Esp32C6("ESP32-C6", setOf(0x2CE0806FL), 0x60003000L, true),
    ;

    companion object {
        fun ofMagic(value: Long): EspChip? = entries.firstOrNull { value in it.magic }
    }
}

class EspLoaderException(message: String) : IOException(message)

/**
 * Espressif's serial ROM bootloader protocol (esp-serial-flasher, which VESC Tool's ESP programmer uses): SLIP framed
 * commands with little-endian fields. Talks to the ROM directly, without uploading the flasher stub, so no extra
 * binary is needed; writes go in 1 KB blocks and are checked with the ROM's MD5 afterwards. Meant for the VESC
 * Express (ESP32-C3 / S3) on its built-in USB serial port.
 */
class EspLoader(private val link: Transport, private val lines: SerialLines, private val scope: CoroutineScope) {
    private class Response(val value: Long, val data: ByteArray)

    private val packets = Channel<ByteArray>(Channel.UNLIMITED)
    private var reader: Job? = null

    var chip: EspChip? = null
        private set
    var flashSize: Int = DEFAULT_FLASH_SIZE
        private set

    /**
     * Resets the chip into its bootloader ([builtinUsb]: the sequence for the chip's own USB serial port, otherwise
     * the classic DTR/RTS one), syncs, identifies the chip, attaches the SPI flash and reads its size.
     */
    suspend fun connect(builtinUsb: Boolean, tries: Int = 7): EspChip {
        if (reader == null) {
            reader = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val decoder = Slip.Decoder()
                try {
                    link.received.collect { chunk -> decoder.feed(chunk).forEach { packets.trySend(it) } }
                } catch (e: IOException) {
                    packets.close(e)
                }
            }
        }
        enterBootloader(builtinUsb)
        var synced = false
        for (attempt in 1..tries) {
            if (runCatching { command(SYNC, SYNC_DATA, timeout = 100.milliseconds, responses = 8) }.isSuccess) {
                synced = true
                break
            }
            delay(100)
        }
        if (!synced) throw EspLoaderException("The ESP32 didn't answer. Hold its BOOT button while plugging it in, then try again.")
        while (packets.tryReceive().isSuccess) Unit
        val magic = readRegister(CHIP_DETECT_MAGIC_REG)
        val detected = EspChip.ofMagic(magic) ?: throw EspLoaderException("Unknown ESP chip (magic 0x%08X)".format(magic))
        chip = detected
        command(SPI_ATTACH, le32(0) + le32(0))
        flashSize = runCatching { detectFlashSize(detected) }.getOrNull() ?: DEFAULT_FLASH_SIZE
        command(SPI_SET_PARAMS, le32(0) + le32(flashSize.toLong()) + le32(64 * 1024) + le32(4 * 1024) + le32(256) + le32(0xFFFF))
        return detected
    }

    /** Writes [image] at [offset] (4-byte aligned) in 1 KB blocks and checks the MD5 the ROM computes over it. */
    suspend fun flash(image: ByteArray, offset: Long, onProgress: (Int) -> Unit = {}) {
        require(offset % 4 == 0L) { "The offset must be 4-byte aligned" }
        val padded = if (image.size % 4 == 0) image else image + ByteArray(4 - image.size % 4) { 0xFF.toByte() }
        if (offset + padded.size > flashSize) throw EspLoaderException("The image doesn't fit in the ${flashSize / 1024 / 1024} MB flash")
        val blocks = (padded.size + BLOCK - 1) / BLOCK
        begin(offset, padded.size, blocks)
        for (seq in 0 until blocks) {
            val block = ByteArray(BLOCK) { 0xFF.toByte() }
            val from = seq * BLOCK
            padded.copyInto(block, 0, from, minOf(padded.size, from + BLOCK))
            val data = le32(BLOCK.toLong()) + le32(seq.toLong()) + le32(0) + le32(0) + block
            var done = false
            for (attempt in 1..WRITE_TRIES) {
                if (runCatching { command(FLASH_DATA, data, checksum = checksum(block), timeout = 3.seconds) }.isSuccess) {
                    done = true
                    break
                }
            }
            if (!done) throw EspLoaderException("Block ${seq + 1} of $blocks couldn't be written")
            onProgress(minOf(image.size, from + BLOCK))
        }
        val expected = MessageDigest.getInstance("MD5").digest(padded).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val reply = command(SPI_FLASH_MD5, le32(offset) + le32(padded.size.toLong()) + le32(0) + le32(0), timeout = md5Timeout(padded.size))
        val got = reply.data.copyOfRange(0, minOf(32, reply.data.size)).decodeToString()
        if (!got.equals(expected, ignoreCase = true)) throw EspLoaderException("The written image doesn't match (MD5 $got, expected $expected)")
    }

    /** Erases [size] bytes at [offset] the way VESC Tool clears the LispBM and QML areas: a flash begin without data. */
    suspend fun erase(offset: Long, size: Int) = begin(offset, size, (size + BLOCK - 1) / BLOCK)

    /** Leaves the bootloader and runs the firmware (RTS pulse), and stops listening. */
    suspend fun reset() {
        lines.set(dtr = false, rts = false)
        lines.set(dtr = false, rts = true)
        delay(100)
        lines.set(dtr = false, rts = false)
        reader?.cancel()
        reader = null
    }

    suspend fun readRegister(address: Long): Long = command(READ_REG, le32(address)).value

    private suspend fun begin(offset: Long, size: Int, blocks: Int) {
        val c = chip ?: throw EspLoaderException("Not connected")
        val data = le32(size.toLong()) + le32(blocks.toLong()) + le32(BLOCK.toLong()) + le32(offset) + (if (c.encryptionInBegin) le32(0) else ByteArray(0))
        command(FLASH_BEGIN, data, timeout = eraseTimeout(size))
    }

    private suspend fun writeRegister(address: Long, value: Long) {
        command(WRITE_REG, le32(address) + le32(value) + le32(0xFFFFFFFFL) + le32(0))
    }

    /** JEDEC RDID through the SPI peripheral's user command; the third id byte encodes the size. */
    private suspend fun detectFlashSize(chip: EspChip): Int? {
        val base = chip.spiBase ?: return null
        val cmd = base
        val usr = base + 0x18
        val usr2 = base + 0x20
        val misoDlen = base + 0x28
        val w0 = base + 0x58
        val oldUsr = readRegister(usr)
        val oldUsr2 = readRegister(usr2)
        writeRegister(misoDlen, 24 - 1L)
        writeRegister(usr, (1L shl 31) or (1L shl 28))
        writeRegister(usr2, (7L shl 28) or 0x9F)
        writeRegister(w0, 0)
        writeRegister(cmd, 1L shl 18)
        for (i in 0 until 10) if (readRegister(cmd) and (1L shl 18) == 0L) break
        val id = readRegister(w0)
        writeRegister(usr, oldUsr)
        writeRegister(usr2, oldUsr2)
        return SIZES[((id shr 16) and 0xFF).toInt()]
    }

    private suspend fun enterBootloader(builtinUsb: Boolean) {
        if (builtinUsb) {
            lines.set(dtr = false, rts = false)
            delay(100)
            lines.set(dtr = true, rts = false)
            delay(100)
            lines.set(dtr = false, rts = true)
            delay(100)
            lines.set(dtr = false, rts = false)
        } else {
            lines.set(dtr = false, rts = true)
            delay(100)
            lines.set(dtr = true, rts = false)
            delay(100)
            lines.set(dtr = false, rts = false)
        }
    }

    /** Sends command [op]; the response's data comes back without its two status bytes. */
    private suspend fun command(op: Int, data: ByteArray, checksum: Int = 0, timeout: Duration = 1.seconds, responses: Int = 1): Response {
        val packet = byteArrayOf(0, op.toByte()) + le16(data.size) + le32(checksum.toLong()) + data
        link.write(Slip.encode(packet))
        var result = Response(0, ByteArray(0))
        repeat(responses) {
            val reply = withTimeoutOrNull(timeout) {
                var p: ByteArray
                do p = packets.receive() while (p.size < 10 || p[0].toInt() != 1 || (p[1].toInt() and 0xFF) != op)
                p
            } ?: throw EspLoaderException("No answer to command 0x%02X".format(op))
            val size = (reply[2].toInt() and 0xFF) or ((reply[3].toInt() and 0xFF) shl 8)
            val body = reply.copyOfRange(8, minOf(reply.size, 8 + size))
            if (body.size < 2) throw EspLoaderException("Short answer to command 0x%02X".format(op))
            if (body[body.size - 2].toInt() != 0) {
                throw EspLoaderException("The ESP32 refused command 0x%02X (error 0x%02X)".format(op, body.last().toInt() and 0xFF))
            }
            result = Response(le32At(reply, 4), body.copyOfRange(0, body.size - 2))
        }
        return result
    }

    companion object {
        const val BLOCK = 1024
        const val DEFAULT_FLASH_SIZE = 4 * 1024 * 1024
        private const val WRITE_TRIES = 3
        internal const val FLASH_BEGIN = 0x02
        internal const val FLASH_DATA = 0x03
        internal const val SYNC = 0x08
        internal const val WRITE_REG = 0x09
        internal const val READ_REG = 0x0A
        internal const val SPI_SET_PARAMS = 0x0B
        internal const val SPI_ATTACH = 0x0D
        internal const val SPI_FLASH_MD5 = 0x13
        internal const val CHIP_DETECT_MAGIC_REG = 0x40001000L
        internal val SYNC_DATA = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
        private val SIZES = mapOf(
            0x12 to 256 * 1024, 0x13 to 512 * 1024, 0x14 to (1 shl 20), 0x15 to (2 shl 20), 0x16 to (4 shl 20), 0x17 to (8 shl 20),
            0x18 to (16 shl 20), 0x19 to (32 shl 20), 0x32 to 256 * 1024, 0x33 to 512 * 1024, 0x34 to (1 shl 20), 0x35 to (2 shl 20),
            0x36 to (4 shl 20), 0x37 to (8 shl 20), 0x38 to (16 shl 20), 0x39 to (32 shl 20),
        )

        /** VESC Express image offsets VESC Tool uses: bootloader, partition table, app; LispBM and QML areas. */
        const val BOOTLOADER_OFFSET = 0x0L
        const val PARTITION_OFFSET = 0x8000L
        const val APP_OFFSET = 0x20000L
        const val LISP_OFFSET = 0x340000L
        const val LISP_SIZE = 512 * 1024
        const val QML_OFFSET = 0x3C0000L
        const val QML_SIZE = 128 * 1024

        internal fun checksum(data: ByteArray): Int = data.fold(0xEF) { acc, b -> acc xor (b.toInt() and 0xFF) }
        private fun eraseTimeout(bytes: Int) = maxOf(3.seconds, 10.seconds * (bytes / 1_048_576.0 + 1))
        private fun md5Timeout(bytes: Int) = maxOf(3.seconds, 8.seconds * (bytes / 1_048_576.0 + 1))

        internal fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        internal fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        internal fun le32At(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
    }
}

/** SLIP framing as the ESP ROM uses it: 0xC0 delimits, 0xDB 0xDC escapes 0xC0, 0xDB 0xDD escapes 0xDB. */
object Slip {
    fun encode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 8)
        out.write(0xC0)
        for (b in data) {
            when (b.toInt() and 0xFF) {
                0xC0 -> {
                    out.write(0xDB)
                    out.write(0xDC)
                }
                0xDB -> {
                    out.write(0xDB)
                    out.write(0xDD)
                }
                else -> out.write(b.toInt())
            }
        }
        out.write(0xC0)
        return out.toByteArray()
    }

    class Decoder {
        private val current = ByteArrayOutputStream()
        private var inPacket = false
        private var escape = false

        fun feed(chunk: ByteArray): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            for (b in chunk) {
                val v = b.toInt() and 0xFF
                when {
                    v == 0xC0 -> {
                        if (inPacket && current.size() > 0) out += current.toByteArray()
                        current.reset()
                        inPacket = true
                        escape = false
                    }
                    !inPacket -> Unit
                    escape -> {
                        current.write(if (v == 0xDC) 0xC0 else if (v == 0xDD) 0xDB else v)
                        escape = false
                    }
                    v == 0xDB -> escape = true
                    else -> current.write(v)
                }
            }
            return out
        }
    }
}
