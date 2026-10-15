package app.foctuner.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PacketCodecTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun crcMatchesXmodemCheckValue() {
        assertEquals(0x31C3, Crc16.compute("123456789".encodeToByteArray()))
    }

    // These two frames are what VESC Tool sends for COMM_FW_VERSION and COMM_GET_VALUES.
    @Test
    fun encodesKnownRequests() {
        assertContentEquals(bytes(2, 1, 0, 0, 0, 3), PacketCodec.encode(bytes(0)))
        assertContentEquals(bytes(2, 1, 4, 0x40, 0x84, 3), PacketCodec.encode(bytes(4)))
    }

    @Test
    fun lengthEncodingUsesShortestForm() {
        val cases = mapOf(1 to 2, 255 to 2, 256 to 3, 65535 to 3, 65536 to 4)
        for ((size, startByte) in cases) {
            val frame = PacketCodec.encode(ByteArray(size) { 7 })
            assertEquals(startByte, frame[0].toInt(), "start byte for payload of $size")
            assertEquals(startByte + size + 3, frame.size)
        }
    }

    @Test
    fun roundTripsAcrossLengthBoundaries() {
        val rnd = Random(1)
        for (size in listOf(1, 2, 254, 255, 256, 257, 65535, 65536, 70000)) {
            val payload = rnd.nextBytes(size)
            val decoded = PacketDecoder().feed(PacketCodec.encode(payload))
            assertEquals(1, decoded.size, "size $size")
            assertContentEquals(payload, decoded[0])
        }
    }

    @Test
    fun reassemblesPacketsFedOneByteAtATime() {
        val payloads = listOf(bytes(4), ByteArray(300) { it.toByte() }, bytes(30, 1, 2))
        val stream = payloads.map(PacketCodec::encode).reduce { a, b -> a + b }
        val decoder = PacketDecoder()
        val out = stream.flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals(payloads.size, out.size)
        payloads.zip(out).forEach { (want, got) -> assertContentEquals(want, got) }
        assertEquals(0, decoder.pendingBytes)
    }

    @Test
    fun returnsSeveralPacketsFromOneChunk() {
        val chunk = PacketCodec.encode(bytes(1)) + PacketCodec.encode(bytes(2)) + PacketCodec.encode(bytes(3))
        assertEquals(listOf(1, 2, 3), PacketDecoder().feed(chunk).map { it[0].toInt() })
    }

    // Like the firmware, a plausible start byte makes the decoder wait for the frame it announces, so these
    // streams avoid stray 2/3/4 bytes except where a frame is deliberately malformed.
    @Test
    fun resynchronizesAfterGarbage() {
        // Leading junk, then a false frame (start byte 2, length 1) whose stop byte is 9.
        val noise = bytes(0, 9, 200, 255, 77, 2, 1, 0, 0, 0, 9)
        val decoded = PacketDecoder().feed(noise + PacketCodec.encode(bytes(4, 5)))
        assertEquals(1, decoded.size)
        assertContentEquals(bytes(4, 5), decoded[0])
    }

    @Test
    fun dropsCorruptFramesButKeepsTheNextOne() {
        // Corrupt payload: the frame's trailing 0x03 must not be taken for a 16-bit length start byte.
        val badCrc = PacketCodec.encode(bytes(10, 20, 30, 40, 50)).also { it[3] = (it[3] + 1).toByte() }
        // Bad stop byte: structure can't be trusted, so we step through it byte by byte. The CRC of this
        // payload (0x6408) contains no 2/3/4 byte, so none of those steps can look like a frame start.
        val badEnd = PacketCodec.encode(bytes(11, 22, 33, 44, 55)).also { it[it.size - 1] = 9 }
        val good = PacketCodec.encode(bytes(8, 8, 8, 8, 8))
        val decoded = PacketDecoder().feed(badCrc + badEnd + good)
        assertEquals(1, decoded.size)
        assertContentEquals(bytes(8, 8, 8, 8, 8), decoded[0])
    }

    @Test
    fun waitsForAnnouncedLengthEvenIfLaterBytesLookLikeAFrame() {
        // A payload may legitimately contain framed data (tunneled packets), so the decoder must not
        // resync inside a frame that is still arriving.
        val inner = PacketCodec.encode(bytes(4))
        val outer = PacketCodec.encode(inner)
        val decoder = PacketDecoder()
        assertTrue(decoder.feed(outer.copyOf(outer.size - 3)).isEmpty())
        val decoded = decoder.feed(outer.copyOfRange(outer.size - 3, outer.size))
        assertEquals(1, decoded.size)
        assertContentEquals(inner, decoded[0])
    }

    @Test
    fun rejectsNonMinimalLengthEncoding() {
        // 10 byte payload framed with a 2 byte length: valid CRC and end byte, but not how the firmware sends it.
        val payload = ByteArray(10) { it.toByte() }
        val crc = Crc16.compute(payload)
        val frame = bytes(3, 0, 10) + payload + bytes(crc shr 8, crc and 0xFF, 3)
        assertTrue(PacketDecoder().feed(frame).isEmpty())
    }

    @Test
    fun resetDiscardsPartialFrame() {
        val frame = PacketCodec.encode(bytes(4, 4, 4))
        val decoder = PacketDecoder()
        assertTrue(decoder.feed(frame.copyOf(frame.size - 2)).isEmpty())
        assertTrue(decoder.pendingBytes > 0)
        decoder.reset()
        assertEquals(0, decoder.pendingBytes)
        assertEquals(1, decoder.feed(frame).size)
    }

    @Test
    fun ignoresPacketsLongerThanTheConfiguredLimit() {
        val decoder = PacketDecoder(maxPayload = 16)
        assertTrue(decoder.feed(PacketCodec.encode(ByteArray(17))).isEmpty())
        assertEquals(1, decoder.feed(PacketCodec.encode(ByteArray(16) { 1 })).size)
    }

    @Test
    fun encodeRejectsEmptyPayload() {
        assertFailsWith<IllegalArgumentException> { PacketCodec.encode(ByteArray(0)) }
    }

    @Test
    fun commandIdsMatchFirmware() {
        assertEquals(4, CommPacketId.COMM_GET_VALUES.id)
        assertEquals(30, CommPacketId.COMM_ALIVE.id)
        assertEquals(34, CommPacketId.COMM_FORWARD_CAN.id)
        assertEquals(62, CommPacketId.COMM_PING_CAN.id)
        assertEquals(156, CommPacketId.COMM_SHUTDOWN.id)
        assertEquals(CommPacketId.COMM_FW_VERSION, CommPacketId.fromId(0))
        assertEquals(null, CommPacketId.fromId(153)) // reserved upstream
        assertEquals(null, CommPacketId.fromId(9999))
    }
}
