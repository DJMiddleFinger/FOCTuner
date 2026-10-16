package app.foctuner.vesc.firmware

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId
import app.foctuner.protocol.CommPacketId.COMM_ERASE_BOOTLOADER
import app.foctuner.protocol.CommPacketId.COMM_ERASE_NEW_APP
import app.foctuner.protocol.CommPacketId.COMM_ERASE_NEW_APP_ALL_CAN
import app.foctuner.protocol.CommPacketId.COMM_FORWARD_CAN
import app.foctuner.protocol.CommPacketId.COMM_FW_VERSION
import app.foctuner.protocol.CommPacketId.COMM_JUMP_TO_BOOTLOADER
import app.foctuner.protocol.CommPacketId.COMM_JUMP_TO_BOOTLOADER_ALL_CAN
import app.foctuner.protocol.CommPacketId.COMM_PING_CAN
import app.foctuner.protocol.CommPacketId.COMM_WRITE_NEW_APP_DATA
import app.foctuner.protocol.CommPacketId.COMM_WRITE_NEW_APP_DATA_ALL_CAN
import app.foctuner.protocol.CommPacketId.COMM_WRITE_NEW_APP_DATA_ALL_CAN_LZO
import app.foctuner.protocol.CommPacketId.COMM_WRITE_NEW_APP_DATA_LZO
import app.foctuner.protocol.PacketCodec
import app.foctuner.protocol.PacketDecoder
import app.foctuner.sim.SimulatedVesc
import app.foctuner.transport.Transport
import app.foctuner.vesc.RecordingTransport
import app.foctuner.vesc.VescClient
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FirmwareUpdateTest {
    @Test
    fun installsFirmwareAndReportsTheNewVersion() = runTest {
        val link = RecordingTransport(SimulatedVesc(testTimeSource))
        val client = VescClient(link, backgroundScope)
        val image = image("60_MK6", compressibleFirmware("60_MK6", "7.02"))

        val progress = client.updateFirmware(image).toList()

        assertEquals(FirmwareProgress.Preparing, progress.first())
        assertTrue(FirmwareProgress.Erasing(FlashRegion.Firmware) in progress)
        assertTrue(FirmwareProgress.Installing in progress)
        val done = assertIs<FirmwareProgress.Restarted>(progress.last())
        assertEquals("7.02", done.firmware.version)
        assertEquals(1, link.sent(COMM_JUMP_TO_BOOTLOADER))

        // What arrived in the update buffer is the header plus image, chunk by chunk, compressed where it helped.
        val expected = UpdateBuffer.firmware(image.bytes)
        val written = writtenBuffer(link, expected.size)
        assertContentEquals(expected, written)
        assertTrue(link.sentPackets(COMM_WRITE_NEW_APP_DATA_LZO).isNotEmpty(), "LZO used")
        assertEquals(expected.size.toLong(), ByteReader(link.sentPackets(COMM_ERASE_NEW_APP).single(), 1).u32())
    }

    @Test
    fun sendsAWholeApplicationAreaCompressedOverBleSizedWrites() = runTest {
        val link = RecordingTransport(SimulatedVesc(testTimeSource, chunkSize = 20))
        val client = VescClient(link, backgroundScope)
        val image = image("60_MK6", testFirmware("60_MK6", size = 524_280, extra = "SIMFW 7.03"))

        val done = assertIs<FirmwareProgress.Restarted>(client.updateFirmware(image).toList().last())

        assertEquals("7.03", done.firmware.version)
        assertTrue(link.writes.all { it.size <= 20 })
        assertTrue(link.sentPackets(COMM_WRITE_NEW_APP_DATA).all { it.size <= 1 + 4 + 384 })
        assertTrue(link.sentPackets(COMM_WRITE_NEW_APP_DATA_LZO).isEmpty(), "heatshrink data is sent as it is")
        assertEquals(0xCC, link.sentPackets(COMM_WRITE_NEW_APP_DATA).first()[5].toInt() and 0xFF)
    }

    @Test
    fun refusesFirmwareForOtherHardwareUnlessOverridden() = runTest {
        val link = RecordingTransport(SimulatedVesc(testTimeSource))
        val client = VescClient(link, backgroundScope)
        val wrong = FirmwareImage.fromFile("75_300.bin", compressibleFirmware("75_300", "7.02"))
        val anonymous = FirmwareImage.fromFile("mystery.bin", firmwareLike(20_000, seed = 3))

        for (image in listOf(wrong, anonymous)) {
            val e = assertFailsWith<FirmwareUpdateException> { client.updateFirmware(image).toList() }
            assertEquals(FirmwareFailure.HardwareMismatch, e.failure)
        }
        assertEquals(0, link.sent(COMM_ERASE_NEW_APP))
        assertEquals("60_MK6", client.firmwareInfo().hardwareName)

        val done = assertIs<FirmwareProgress.Restarted>(client.updateFirmware(wrong, allowHardwareMismatch = true).toList().last())
        assertEquals("75_300", done.firmware.hardwareName)
    }

    @Test
    fun cancellingBeforeTheJumpLeavesTheOldFirmware() = runTest {
        val link = RecordingTransport(SimulatedVesc(testTimeSource, chunkSize = 20))
        val client = VescClient(link, backgroundScope)
        val image = image("60_MK6", compressibleFirmware("60_MK6", "7.02"))

        client.updateFirmware(image).first { it is FirmwareProgress.Writing && it.region == FlashRegion.Firmware && it.written >= 3 * 384 }

        assertEquals(0, link.sent(COMM_JUMP_TO_BOOTLOADER))
        assertTrue(link.sent(COMM_WRITE_NEW_APP_DATA) + link.sent(COMM_WRITE_NEW_APP_DATA_LZO) <= 4)
        assertEquals("7.01", client.firmwareInfo().version)
    }

    @Test
    fun writesTheBootloaderFirst() = runTest {
        val link = RecordingTransport(SimulatedVesc(testTimeSource))
        val client = VescClient(link, backgroundScope)
        val bootloader = FirmwareImage("generic.bin", firmwareLike(5_280, seed = 4), emptyList(), generic = true)

        val progress = client.updateFirmware(image("60_MK6", compressibleFirmware("60_MK6", "7.02")), bootloader).toList()

        assertEquals(FirmwareProgress.Erasing(FlashRegion.Bootloader), progress[1])
        assertTrue(progress.indexOf(FirmwareProgress.Erasing(FlashRegion.Firmware)) > progress.indexOfLast { it is FirmwareProgress.Writing && it.region == FlashRegion.Bootloader })
        assertIs<FirmwareProgress.Restarted>(progress.last())
        val firstFirmwareWrite = link.packets.indexOfFirst { it.id == COMM_ERASE_NEW_APP }
        val bootloaderWrites = link.packets.take(firstFirmwareWrite).filter { it.id == COMM_WRITE_NEW_APP_DATA || it.id == COMM_WRITE_NEW_APP_DATA_LZO }
        assertTrue(bootloaderWrites.isNotEmpty() && bootloaderWrites.all { ByteReader(it, 1).u32() >= 0x60000 })
        assertEquals(1, link.sent(COMM_ERASE_BOOTLOADER))
    }

    @Test
    fun aBootloaderAloneIsNotInstalled() = runTest {
        val link = RecordingTransport(SimulatedVesc(testTimeSource))
        val client = VescClient(link, backgroundScope)
        val bootloader = FirmwareImage("generic.bin", firmwareLike(5_280, seed = 5), emptyList(), generic = true)

        assertEquals(FirmwareProgress.BootloaderWritten, client.updateFirmware(null, bootloader).toList().last())
        assertEquals(0, link.sent(COMM_JUMP_TO_BOOTLOADER))
    }

    @Test
    fun retriesAChunkWhoseReplyWasLost() = runTest {
        var dropped = 0
        val link = RecordingTransport(Tampering(SimulatedVesc(testTimeSource), dropReply = { it.id == COMM_WRITE_NEW_APP_DATA && dropped++ == 2 }))
        val client = VescClient(link, backgroundScope)

        assertIs<FirmwareProgress.Restarted>(client.updateFirmware(image("60_MK6", compressibleFirmware("60_MK6", "7.02"))).toList().last())

        val offsets = (link.sentPackets(COMM_WRITE_NEW_APP_DATA) + link.sentPackets(COMM_WRITE_NEW_APP_DATA_LZO)).map { ByteReader(it, 1).u32() }
        assertEquals(1, offsets.size - offsets.toSet().size)
    }

    @Test
    fun theBootloaderRejectsACorruptedUpload() = runTest {
        var tampered = false
        val corrupt: (ByteArray) -> ByteArray = { packet ->
            if (!tampered && packet.id == COMM_WRITE_NEW_APP_DATA && ByteReader(packet, 1).u32() > 0) {
                tampered = true
                packet.copyOf().also { it[20] = (it[20].toInt() xor 0x40).toByte() }
            } else {
                packet
            }
        }
        val client = VescClient(Tampering(SimulatedVesc(testTimeSource), rewrite = corrupt), backgroundScope)
        val image = image("60_MK6", testFirmware("60_MK6", extra = "SIMFW 7.02"))

        val done = assertIs<FirmwareProgress.Restarted>(client.updateFirmware(image).toList().last())

        assertTrue(tampered)
        assertEquals("7.01", done.firmware.version)
    }

    @Test
    fun updatesACanControllerThroughForwarding() = runTest {
        val link = RecordingTransport(SimulatedVesc(testTimeSource))
        val client = VescClient(link, backgroundScope)

        assertIs<FirmwareProgress.Restarted>(client.can(1).updateFirmware(image("60_MK6", compressibleFirmware("60_MK6", "7.02"))).toList().last())

        assertEquals(0, link.sent(COMM_ERASE_NEW_APP))
        assertTrue(link.packets.filter { it.id == COMM_FORWARD_CAN }.any { it[1].toInt() == 1 && it[2].toInt() == COMM_ERASE_NEW_APP.id })
    }

    @Test
    fun updatesEveryControllerOnTheBus() = runTest {
        val link = RecordingTransport(SimulatedVesc(testTimeSource))
        val client = VescClient(link, backgroundScope)

        val done = assertIs<FirmwareProgress.Restarted>(client.updateFirmware(image("60_MK6", compressibleFirmware("60_MK6", "7.02")), allCan = true).toList().last())

        assertEquals(1, link.sent(COMM_ERASE_NEW_APP_ALL_CAN))
        assertEquals(1, link.sent(COMM_JUMP_TO_BOOTLOADER_ALL_CAN))
        assertTrue(link.sent(COMM_WRITE_NEW_APP_DATA_ALL_CAN) + link.sent(COMM_WRITE_NEW_APP_DATA_ALL_CAN_LZO) > 0)
        assertEquals(0, link.sent(COMM_WRITE_NEW_APP_DATA) + link.sent(COMM_WRITE_NEW_APP_DATA_LZO))
        assertEquals("7.02", done.canDevices.getValue(1).version)
    }

    @Test
    fun refusesToUpdateTheBusWhenHardwareDiffers() = runTest {
        val bus = MixedBus()
        val client = VescClient(bus, backgroundScope)

        val e = assertFailsWith<FirmwareUpdateException> {
            client.updateFirmware(image("60_MK6", compressibleFirmware("60_MK6", "7.02")), allCan = true).toList()
        }

        assertEquals(FirmwareFailure.CanBusMismatch, e.failure)
        assertTrue(bus.seen.none { it.id == COMM_ERASE_NEW_APP_ALL_CAN || it.id == COMM_ERASE_NEW_APP })
    }

    @Test
    fun readsTheBuild() = runTest {
        val build = VescClient(SimulatedVesc(testTimeSource), backgroundScope).firmwareBuild()
        assertEquals(7, build.major)
        assertEquals(40, build.gitHash.length)
    }

    private fun image(hardware: String, bytes: ByteArray) = FirmwareImage("$hardware.bin", bytes, listOf(hardware))

    /** The update buffer reassembled from the write commands (unwritten bytes read as erased). */
    private fun writtenBuffer(link: RecordingTransport, size: Int): ByteArray {
        val buffer = ByteArray(size) { 0xFF.toByte() }
        for (packet in link.packets) {
            val r = ByteReader(packet, 1)
            val (offset, data) = when (packet.id) {
                COMM_WRITE_NEW_APP_DATA -> r.u32().toInt() to r.restBytes()
                COMM_WRITE_NEW_APP_DATA_LZO -> r.u32().toInt() to r.u16().let { Lzo1x.decompress(r.restBytes(), it) }
                else -> continue
            }
            data.copyInto(buffer, offset)
        }
        return buffer
    }
}

private val ByteArray.id: CommPacketId? get() = CommPacketId.fromId(this[0].toInt() and 0xFF)

private fun RecordingTransport.sent(id: CommPacketId) = sent(id.id).size

private fun RecordingTransport.sentPackets(id: CommPacketId) = sent(id.id)

/** Code-like bytes that LZO can shrink, with the markers the simulator reads. */
private fun compressibleFirmware(hardware: String, version: String): ByteArray {
    val code = (0 until 900).joinToString("") { "ldr r${it % 8}, [pc, #${it % 64}]; bl 0x0800${(it * 37) % 1000}; " }
    return "$code org.vesc.$hardware\u0000SIMFW $version\u0000$code".toByteArray(Charsets.US_ASCII)
}

/**
 * Passes everything through [inner] (which must send whole frames, i.e. no chunk size), except that
 * [rewrite] may change a packet the client sends and [dropReply] may swallow one the controller sends.
 */
private class Tampering(
    private val inner: Transport,
    private val rewrite: (ByteArray) -> ByteArray = { it },
    private val dropReply: (ByteArray) -> Boolean = { false },
) : Transport by inner {
    private val decoder = PacketDecoder()

    override val received: Flow<ByteArray> = inner.received.mapNotNull { frame ->
        val packet = PacketDecoder().feed(frame).singleOrNull()
        if (packet != null && dropReply(packet)) null else frame
    }

    override suspend fun write(bytes: ByteArray) {
        for (packet in decoder.feed(bytes)) inner.write(PacketCodec.encode(rewrite(packet)))
    }
}

/** A bus whose CAN device 5 is other hardware: answers COMM_FW_VERSION, COMM_PING_CAN and forwarded versions. */
private class MixedBus : Transport {
    private val decoder = PacketDecoder()
    private val out = Channel<ByteArray>(Channel.UNLIMITED)
    val seen = mutableListOf<ByteArray>()
    override val maxWriteSize = 512

    override val received: Flow<ByteArray> get() = out.receiveAsFlow()

    override suspend fun write(bytes: ByteArray) {
        for (packet in decoder.feed(bytes)) {
            seen += packet
            when (packet.id) {
                COMM_FW_VERSION -> reply(version("60_MK6"))
                COMM_PING_CAN -> reply(byteArrayOf(COMM_PING_CAN.id.toByte(), 5))
                COMM_FORWARD_CAN -> if (packet[1].toInt() == 5 && packet[2].toInt() == COMM_FW_VERSION.id) reply(version("75_300"))
                else -> Unit
            }
        }
    }

    override suspend fun close() {
        out.close()
    }

    private fun reply(payload: ByteArray) {
        out.trySend(PacketCodec.encode(payload))
    }

    private fun version(hardware: String) = ByteWriter().u8(COMM_FW_VERSION.id).u8(7).u8(1).cString(hardware)
        .bytes(ByteArray(12)).u8(0).u8(0).u8(0).toByteArray()
}
