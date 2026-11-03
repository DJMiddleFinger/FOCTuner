package app.foctuner.vesc

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId
import app.foctuner.protocol.CommPacketId.COMM_BM_CONNECT
import app.foctuner.protocol.CommPacketId.COMM_BM_DISCONNECT
import app.foctuner.protocol.CommPacketId.COMM_BM_ERASE_FLASH_ALL
import app.foctuner.protocol.CommPacketId.COMM_BM_MAP_PINS_DEFAULT
import app.foctuner.protocol.CommPacketId.COMM_BM_MAP_PINS_NRF5X
import app.foctuner.protocol.CommPacketId.COMM_BM_MEM_READ
import app.foctuner.protocol.CommPacketId.COMM_BM_REBOOT
import app.foctuner.protocol.CommPacketId.COMM_BM_WRITE_FLASH
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the controller's SWD probe found (`bm_connect`), with where its flash starts. */
enum class SwdTarget(val code: Int, val label: String, val flashStart: Long, val nrf: Boolean) {
    Stm32F40x(1, "STM32F40x", 0x0800_0000, false),
    Nrf51822_128k16k(2, "nRF51822 128K/16K", 0, true),
    Nrf51822_256k16k(3, "nRF51822 256K/16K", 0, true),
    Nrf51822_256k32k(4, "nRF51822 256K/32K", 0, true),
    Nrf52832_256k32k(5, "nRF52832 256K/32K", 0, true),
    Nrf52832_256k64k(6, "nRF52832 256K/64K", 0, true),
    Nrf52832_512k64k(7, "nRF52832 512K/64K", 0, true),
    Nrf52840(8, "nRF52840 1M/256K", 0, true),
    Stm32F30x(9, "STM32F30x", 0x0800_0000, false),
    Stm32L47x(10, "STM32L47x", 0x0800_0000, false),
    Stm32G43(11, "STM32G43", 0x0800_0000, false),
    Stm32G47(12, "STM32G47", 0x0800_0000, false),
    Stm32G49(13, "STM32G49", 0x0800_0000, false),
    ;

    companion object {
        fun of(code: Int): SwdTarget? = entries.firstOrNull { it.code == code }
    }
}

/** A failure the probe reported, with VESC Tool's wording for its code. */
class SwdException(val code: Int, message: String) : VescException(message)

/**
 * The controller as an SWD programmer (firmware built with the Black Magic probe, COMM_BM_*), as VESC Tool's SWD
 * programmer page uses it. Every command runs in the firmware's blocking thread.
 */
class SwdProgrammer(private val controller: VescController) {
    private val session get() = controller.session

    /** Points the probe at the default SWD pins (the SWD header). */
    suspend fun mapDefaultPins(): Boolean = request(COMM_BM_MAP_PINS_DEFAULT, PIN_TIMEOUT) { it.i16() == 1 }

    /** Points the probe at the board's built-in nRF5x module, where the hardware has one wired. */
    suspend fun mapNrf5xPins(): Boolean = request(COMM_BM_MAP_PINS_NRF5X, PIN_TIMEOUT) { it.i16() == 1 }

    /**
     * Connects to the target on the mapped pins.
     *
     * @throws SwdException if nothing answers (-1) or the chip isn't one the probe knows (-2)
     */
    suspend fun connect(): SwdTarget {
        val code = request(COMM_BM_CONNECT, CONNECT_TIMEOUT) { it.i16() }
        return SwdTarget.of(code) ?: throw SwdException(code, if (code == -2) "Could not recognize target" else "Could not connect to target")
    }

    /** Erases the target's whole flash. */
    suspend fun eraseAll() {
        val code = request(COMM_BM_ERASE_FLASH_ALL, ERASE_TIMEOUT) { it.i16() }
        if (code != 1) throw failure(code, erase = true)
    }

    /**
     * Writes [image] at [address] in 400-byte chunks, each tried up to 3 times, read back when [verify] is set.
     * [onProgress] gets the bytes written. Erase first.
     */
    suspend fun write(image: ByteArray, address: Long, verify: Boolean = true, onProgress: (Int) -> Unit = {}) {
        var offset = 0
        while (offset < image.size) {
            val chunk = image.copyOfRange(offset, minOf(image.size, offset + CHUNK))
            val at = address + offset
            var code = TIMED_OUT
            for (attempt in 1..TRIES) {
                code = try {
                    request(COMM_BM_WRITE_FLASH, WRITE_TIMEOUT, ByteWriter().u32(at).bytes(chunk).toByteArray()) { it.i16() }
                } catch (e: VescTimeoutException) {
                    TIMED_OUT
                }
                if (code != TIMED_OUT) break
            }
            if (code != 1) throw failure(code, erase = false)
            if (verify) {
                val back = read(at, chunk.size)
                if (!back.contentEquals(chunk)) throw SwdException(-12, "Verification failed at 0x%08X".format(at))
            }
            offset += chunk.size
            onProgress(offset)
        }
    }

    /** Reads [length] bytes (at most [MAX_READ]) of the target's memory at [address]. */
    suspend fun read(address: Long, length: Int): ByteArray {
        require(length in 1..MAX_READ) { "Read 1 to $MAX_READ bytes" }
        val payload = ByteWriter().u32(address).u16(length).toByteArray()
        return request(COMM_BM_MEM_READ, WRITE_TIMEOUT, payload) { r ->
            val code = r.i16()
            if (code != 1) throw failure(code, erase = false)
            r.restBytes()
        }
    }

    suspend fun reboot(): Boolean = request(COMM_BM_REBOOT, PIN_TIMEOUT) { it.i16() == 1 }

    /** Releases the target and the probe's pins. */
    suspend fun disconnect() = request(COMM_BM_DISCONNECT, PIN_TIMEOUT) { }

    private suspend fun <T> request(id: CommPacketId, timeout: Duration, payload: ByteArray = ByteArray(0), parse: (app.foctuner.protocol.ByteReader) -> T): T =
        session.blocking { session.request(controller.forwarded(byteArrayOf(id.id.toByte()) + payload), id, timeout, parse) }

    private fun failure(code: Int, erase: Boolean) = SwdException(
        code,
        when (code) {
            -1 -> "Not connected to target"
            -2 -> "Could not recognize target"
            -3 -> if (erase) "Erase failed" else "Write failed"
            -10, TIMED_OUT -> "Timed out"
            else -> if (erase) "Erase failed ($code)" else "Write failed ($code)"
        },
    )

    companion object {
        const val CHUNK = 400
        const val MAX_READ = 400
        private const val TRIES = 3
        private const val TIMED_OUT = -20
        private val PIN_TIMEOUT = 1.seconds
        private val CONNECT_TIMEOUT = 5.seconds
        private val ERASE_TIMEOUT = 20.seconds
        private val WRITE_TIMEOUT = 3.seconds
    }
}

/**
 * The data of an Intel HEX file as runs to write over SWD, each at its own address (VESC Tool writes every segment
 * of a hex file separately).
 *
 * @throws app.foctuner.vesc.firmware.FirmwareFileException if [text] isn't a valid hex file
 */
fun swdSegmentsFromHex(text: String): List<Pair<Long, ByteArray>> =
    app.foctuner.vesc.firmware.IntelHex.segments(text).map { it.key to it.value }
