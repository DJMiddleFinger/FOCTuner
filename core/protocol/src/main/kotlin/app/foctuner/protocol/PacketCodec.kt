package app.foctuner.protocol

/**
 * VESC packet framing, ported from the firmware's comm/packet.c.
 *
 * Wire format: `[start][length (1-3 bytes, big endian)][payload][crc16 hi][crc16 lo][0x03]`
 * where the start byte is 2, 3 or 4 for a 1, 2 or 3 byte length. The CRC covers the payload only.
 * The shortest length encoding is mandatory (a payload of 255 bytes or less always uses start byte 2).
 */
object PacketCodec {
    const val END_BYTE = 3

    /**
     * Largest payload we accept or send. The firmware default is 512, but links such as WiFi, BLE and USB
     * carry bigger packets (files, scripts), and the host should accept whatever the device sends.
     */
    const val MAX_PAYLOAD = 1 shl 20

    fun encode(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "VESC packets cannot be empty" }
        require(payload.size <= MAX_PAYLOAD) { "payload too long: ${payload.size}" }

        val lengthBytes = when {
            payload.size <= 0xFF -> 1
            payload.size <= 0xFFFF -> 2
            else -> 3
        }
        val out = ByteArray(1 + lengthBytes + payload.size + 3)
        out[0] = (lengthBytes + 1).toByte()
        for (i in 0 until lengthBytes) {
            out[1 + i] = (payload.size shr (8 * (lengthBytes - 1 - i))).toByte()
        }
        payload.copyInto(out, 1 + lengthBytes)
        val crc = Crc16.compute(payload)
        val crcAt = 1 + lengthBytes + payload.size
        out[crcAt] = (crc shr 8).toByte()
        out[crcAt + 1] = crc.toByte()
        out[crcAt + 2] = END_BYTE.toByte()
        return out
    }
}

/**
 * Incremental decoder: feed it whatever chunks the transport delivers and it returns the complete,
 * CRC-valid payloads. Garbage and corrupt frames are skipped one byte at a time, like the firmware does,
 * so the stream resynchronizes on its own. Not thread-safe.
 *
 * A half-received packet stays buffered until more bytes arrive; callers should [reset] after a
 * link-level timeout so a stale partial frame can't swallow the start of the next one.
 */
class PacketDecoder(private val maxPayload: Int = PacketCodec.MAX_PAYLOAD) {
    private var buf = ByteArray(1024)
    private var start = 0
    private var end = 0
    private var badCrcFrameLength = 0

    /** Bytes buffered but not yet part of a completed packet. */
    val pendingBytes: Int get() = end - start

    fun reset() {
        start = 0
        end = 0
    }

    fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset): List<ByteArray> {
        append(chunk, offset, length)
        var packets: ArrayList<ByteArray>? = null
        while (start < end) {
            val consumed = tryDecode { payload -> (packets ?: ArrayList<ByteArray>().also { packets = it }).add(payload) }
            when {
                consumed > 0 -> start += consumed
                consumed == NEED_MORE -> break
                // Start, length and stop byte all lined up but the CRC didn't: the frame is corrupt, not
                // misaligned. Skip all of it, otherwise its trailing 0x03 reads as the start of a 16-bit
                // length frame and swallows the next packet (the firmware's 512 byte limit hides this).
                consumed == BAD_CRC -> start += badCrcFrameLength
                else -> start++
            }
        }
        if (start == end) reset()
        return packets ?: emptyList()
    }

    /**
     * Returns bytes consumed on success, [NEED_MORE] if the frame is incomplete, [BAD_CRC] (with
     * [badCrcFrameLength] set) if only the checksum is wrong, and [INVALID] otherwise.
     */
    private inline fun tryDecode(onPacket: (ByteArray) -> Unit): Int {
        val available = end - start
        val startByte = buf[start].toInt()
        if (startByte !in 2..4) return INVALID

        val dataStart = startByte
        if (available < dataStart) return NEED_MORE

        var len = 0
        for (i in 1 until dataStart) len = (len shl 8) or (buf[start + i].toInt() and 0xFF)
        // Reject non-minimal length encodings, same as the firmware.
        when (startByte) {
            2 -> if (len < 1) return INVALID
            3 -> if (len < 255) return INVALID
            4 -> if (len < 65535) return INVALID
        }
        if (len > maxPayload) return INVALID

        val total = dataStart + len + 3
        if (available < total) return NEED_MORE
        if (buf[start + dataStart + len + 2].toInt() != PacketCodec.END_BYTE) return INVALID

        val crcRx = ((buf[start + dataStart + len].toInt() and 0xFF) shl 8) or
            (buf[start + dataStart + len + 1].toInt() and 0xFF)
        if (Crc16.compute(buf, start + dataStart, len) != crcRx) {
            badCrcFrameLength = total
            return BAD_CRC
        }

        onPacket(buf.copyOfRange(start + dataStart, start + dataStart + len))
        return total
    }

    private fun append(chunk: ByteArray, offset: Int, length: Int) {
        if (end + length > buf.size) {
            val pending = end - start
            val needed = pending + length
            val target = if (needed > buf.size) maxOf(needed, buf.size * 2) else buf.size
            val next = if (target == buf.size) buf else ByteArray(target)
            System.arraycopy(buf, start, next, 0, pending)
            buf = next
            start = 0
            end = pending
        }
        System.arraycopy(chunk, offset, buf, end, length)
        end += length
    }

    private companion object {
        const val NEED_MORE = -2
        const val INVALID = -1
        const val BAD_CRC = -3
    }
}
