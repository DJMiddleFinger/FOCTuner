package app.foctuner.vesc.firmware

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId
import app.foctuner.protocol.CommPacketId.COMM_ERASE_BOOTLOADER
import app.foctuner.protocol.CommPacketId.COMM_ERASE_BOOTLOADER_ALL_CAN
import app.foctuner.protocol.CommPacketId.COMM_ERASE_BOOTLOADER_ALL_CAN_HW
import app.foctuner.protocol.CommPacketId.COMM_ERASE_NEW_APP
import app.foctuner.protocol.CommPacketId.COMM_ERASE_NEW_APP_ALL_CAN
import app.foctuner.protocol.CommPacketId.COMM_ERASE_NEW_APP_ALL_CAN_HW
import app.foctuner.protocol.CommPacketId.COMM_JUMP_TO_BOOTLOADER
import app.foctuner.protocol.CommPacketId.COMM_JUMP_TO_BOOTLOADER_ALL_CAN
import app.foctuner.protocol.CommPacketId.COMM_JUMP_TO_BOOTLOADER_ALL_CAN_HW
import app.foctuner.protocol.CommPacketId.COMM_WRITE_NEW_APP_DATA
import app.foctuner.protocol.CommPacketId.COMM_WRITE_NEW_APP_DATA_ALL_CAN
import app.foctuner.protocol.CommPacketId.COMM_WRITE_NEW_APP_DATA_ALL_CAN_HW
import app.foctuner.protocol.CommPacketId.COMM_WRITE_NEW_APP_DATA_ALL_CAN_LZO
import app.foctuner.protocol.CommPacketId.COMM_WRITE_NEW_APP_DATA_LZO
import app.foctuner.vesc.FirmwareInfo
import app.foctuner.vesc.HardwareType
import app.foctuner.vesc.VescController
import app.foctuner.vesc.VescException
import app.foctuner.vesc.VescLinkException
import app.foctuner.vesc.VescTimeoutException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class FlashRegion { Bootloader, Firmware }

sealed interface FirmwareProgress {
    /** Reading the controller's firmware, checking the CAN bus, compressing the image. */
    data object Preparing : FirmwareProgress

    data class Erasing(val region: FlashRegion) : FirmwareProgress

    /** [written] of [total] bytes of [region] are on the controller (chunks of erased bytes are skipped). */
    data class Writing(val region: FlashRegion, val written: Int, val total: Int) : FirmwareProgress {
        val fraction: Float get() = if (total == 0) 1f else written.toFloat() / total
    }

    /** Final for a bootloader-only upload. */
    data object BootloaderWritten : FirmwareProgress

    /** The image is complete and the controller was told to install it. From here on nothing can be cancelled. */
    data object Installing : FirmwareProgress

    /**
     * Final: the controller answered again. A version that didn't change usually means the bootloader
     * rejected the image (or is missing) and started the old firmware. After an all-CAN update
     * [canDevices] has what each CAN device reported.
     */
    data class Restarted(val firmware: FirmwareInfo, val canDevices: Map<Int, FirmwareInfo> = emptyMap()) : FirmwareProgress

    /** Final: the link closed while the controller restarted, as USB always does. */
    data object LinkClosed : FirmwareProgress

    /** Final: no answer within [FirmwareUpdateOptions.restartTimeout]. */
    data object NoReply : FirmwareProgress
}

enum class FirmwareFailure {
    /** The image is built for other hardware, or doesn't say, and no override was given. */
    HardwareMismatch,

    /** Updating every CAN device: one has other hardware or didn't answer. */
    CanBusMismatch,

    /** The controller's firmware can't do what was asked (all-CAN updates need 3.45). */
    Unsupported,
    TooLarge,
    EraseFailed,
    WriteFailed,

    /** Still no reply after the retries. */
    NoReply,

    /** The link dropped before the controller was told to install anything. */
    LinkLost,
}

/**
 * The update stopped. The controller keeps running its old firmware unless [region] is
 * [FlashRegion.Bootloader]: then its bootloader is incomplete and must be uploaded again before any
 * firmware update.
 */
class FirmwareUpdateException(
    val failure: FirmwareFailure,
    message: String,
    val region: FlashRegion? = null,
    cause: Throwable? = null,
) : VescException(message, cause)

/** Timeouts from VESC Tool's fwUpload unless noted. */
data class FirmwareUpdateOptions(
    /** Erasing three 128 KB sectors takes up to about 6 s; an all-CAN erase first waits 1.5 s for the bus. */
    val eraseTimeout: Duration = 20.seconds,
    /** Per attempt. Only a missing reply is retried; a reported flash error ends the update. */
    val chunkTimeout: Duration = 3.seconds,
    val chunkAttempts: Int = 3,
    /** Pause after the jump before asking whether the controller is back. */
    val jumpSettle: Duration = 500.milliseconds,
    /**
     * How long to keep asking after the jump. VESC Tool tells users the reboot takes up to 10 s; the
     * bootloader unpacks and writes 512 KB byte by byte, so leave a generous margin.
     */
    val restartTimeout: Duration = 45.seconds,
    /** After an all-CAN update, time for the CAN devices to come back once the attached one answers. */
    val canSettle: Duration = 3.seconds,
)

/**
 * Uploads [firmware] and tells the controller to install it, writing [bootloader] first if given; with
 * only a bootloader, nothing is installed. The sequence is VESC Tool's: bootloader erase and write, update
 * buffer erase, 384-byte chunks (LZO-compressed where that helps and the firmware supports it, all-erased
 * chunks skipped), then COMM_JUMP_TO_BOOTLOADER. The flow ends with a final [FirmwareProgress] or throws
 * [FirmwareUpdateException].
 *
 * Safety:
 * - The images are checked against the controller's hardware name; a mismatch or an image that doesn't
 *   say what it is for is refused unless [allowHardwareMismatch].
 * - Cancelling the collector stops the upload between firmware chunks, which leaves the old firmware in
 *   place. The bootloader is written to the end once started, and the jump can't be taken back.
 * - With [allCan] the attached controller relays everything to every CAN device; all controllers on the
 *   bus must report the same hardware, as VESC Tool requires. Only the attached controller acknowledges.
 */
fun VescController.updateFirmware(
    firmware: FirmwareImage?,
    bootloader: FirmwareImage? = null,
    allCan: Boolean = false,
    allowHardwareMismatch: Boolean = false,
    options: FirmwareUpdateOptions = FirmwareUpdateOptions(),
): Flow<FirmwareProgress> {
    require(firmware != null || bootloader != null) { "Nothing to upload" }
    require(!allCan || canId == null) { "An all-CAN update goes through the attached controller" }
    val controller = this
    return channelFlow {
        trySend(FirmwareProgress.Preparing)
        val target = guarded(null) { firmwareInfo() }
        for (image in listOfNotNull(firmware, bootloader)) {
            if (!allowHardwareMismatch && image.check(target.hardwareName) != HardwareCheck.Match) {
                throw FirmwareUpdateException(
                    FirmwareFailure.HardwareMismatch,
                    "${image.name} is for ${image.hardware.ifEmpty { listOf("unknown hardware") }.joinToString()}, the controller is ${target.hardwareName}",
                )
            }
        }
        if (allCan) checkCanBus(target)
        val buffer = firmware?.let { withContext(Dispatchers.Default) { UpdateBuffer.firmware(it.bytes) } }
        val flash = FlashWriter(controller, target, allCan, options) { trySend(it) }

        if (bootloader != null) {
            withContext(NonCancellable) { flash.writeBootloader(bootloader.bytes, eraseSupported = target.atLeast(3, 59)) }
        }
        if (firmware == null || buffer == null) {
            trySend(FirmwareProgress.BootloaderWritten)
            return@channelFlow
        }
        flash.writeFirmware(buffer, eraseSize = maxOf(firmware.bytes.size, buffer.size))
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            flash.jump()
            trySend(FirmwareProgress.Installing)
        }
        delay(options.jumpSettle)
        trySend(awaitRestart(allCan, options))
    }.buffer(Channel.UNLIMITED)
}

private const val CHUNK_SIZE = 384

/** VESC Tool stops compressing when more than this many LZO chunks in a row failed but their raw write worked. */
private const val MAX_LZO_FAILURES = 3

private fun FirmwareInfo.atLeast(major: Int, minor: Int) = this.major > major || (this.major == major && this.minor >= minor)

private suspend fun VescController.checkCanBus(target: FirmwareInfo) {
    if (!target.atLeast(3, 45)) {
        throw FirmwareUpdateException(FirmwareFailure.Unsupported, "Firmware ${target.version} can't update all CAN devices at once")
    }
    if (target.hardwareType != HardwareType.VESC) return
    val ids = guarded(null) { session.pingCan() }
    for (id in ids) {
        val info = try {
            VescController(session, id).firmwareInfo()
        } catch (e: VescTimeoutException) {
            throw FirmwareUpdateException(FirmwareFailure.CanBusMismatch, "CAN device $id did not report its firmware", cause = e)
        }
        if (info.hardwareType == HardwareType.VESC && info.hardwareName != target.hardwareName) {
            throw FirmwareUpdateException(FirmwareFailure.CanBusMismatch, "CAN device $id is ${info.hardwareName}, not ${target.hardwareName}")
        }
    }
}

private suspend fun VescController.awaitRestart(allCan: Boolean, options: FirmwareUpdateOptions): FirmwareProgress = try {
    val info = withTimeoutOrNull(options.restartTimeout) {
        var answer: FirmwareInfo? = null
        while (answer == null) {
            answer = try {
                firmwareInfo()
            } catch (e: VescTimeoutException) {
                null
            }
        }
        answer
    }
    if (info == null) {
        FirmwareProgress.NoReply
    } else {
        FirmwareProgress.Restarted(info, if (allCan) canFirmware(options) else emptyMap())
    }
} catch (e: VescLinkException) {
    FirmwareProgress.LinkClosed
}

private suspend fun VescController.canFirmware(options: FirmwareUpdateOptions): Map<Int, FirmwareInfo> {
    delay(options.canSettle)
    val ids = try {
        session.pingCan()
    } catch (e: VescTimeoutException) {
        return emptyMap()
    }
    return ids.mapNotNull { id ->
        try {
            id to VescController(session, id).firmwareInfo()
        } catch (e: VescTimeoutException) {
            null
        }
    }.toMap()
}

private suspend fun <T> guarded(region: FlashRegion?, block: suspend () -> T): T = try {
    block()
} catch (e: VescTimeoutException) {
    throw FirmwareUpdateException(FirmwareFailure.NoReply, e.message ?: "No reply", region, e)
} catch (e: VescLinkException) {
    throw FirmwareUpdateException(FirmwareFailure.LinkLost, e.message ?: "Link closed", region, e)
}

/** Erase and write commands for one target, in the variant (single, all-CAN, all-CAN by hardware) it needs. */
private class FlashWriter(
    private val controller: VescController,
    private val target: FirmwareInfo,
    private val allCan: Boolean,
    private val options: FirmwareUpdateOptions,
    private val report: (FirmwareProgress) -> Unit,
) {
    private val session = controller.session

    /** LZO needs firmware 3.63 and is only implemented by motor controllers. */
    private var lzo = target.atLeast(3, 63) && target.hardwareType == HardwareType.VESC
    private var lzoFailures = 0

    /** Other device types only act on all-CAN commands that name their hardware (firmware 5.02 and later). */
    private val hardwarePrefix: ByteArray? =
        if (allCan && target.hardwareType != HardwareType.VESC && target.hardwareName.isNotEmpty()) {
            ByteWriter().u8(target.hardwareType.ordinal).cString(target.hardwareName).toByteArray()
        } else {
            null
        }

    /** Without erase support (before 3.59) the bootloader is written over whatever the sector holds, as VESC Tool does. */
    suspend fun writeBootloader(image: ByteArray, eraseSupported: Boolean) {
        if (eraseSupported) {
            erase(FlashRegion.Bootloader, command(COMM_ERASE_BOOTLOADER, COMM_ERASE_BOOTLOADER_ALL_CAN, COMM_ERASE_BOOTLOADER_ALL_CAN_HW), COMM_ERASE_BOOTLOADER)
        }
        write(FlashRegion.Bootloader, image, UpdateBuffer.bootloaderOffset(target.hardwareType, target.hardwareName), cancellable = false)
    }

    suspend fun writeFirmware(buffer: ByteArray, eraseSize: Int) {
        val erase = command(COMM_ERASE_NEW_APP, COMM_ERASE_NEW_APP_ALL_CAN, COMM_ERASE_NEW_APP_ALL_CAN_HW).u32(eraseSize.toLong())
        erase(FlashRegion.Firmware, erase, COMM_ERASE_NEW_APP)
        write(FlashRegion.Firmware, buffer, 0, cancellable = true)
    }

    suspend fun jump() = guarded(null) {
        session.send(controller.forwarded(command(COMM_JUMP_TO_BOOTLOADER, COMM_JUMP_TO_BOOTLOADER_ALL_CAN, COMM_JUMP_TO_BOOTLOADER_ALL_CAN_HW).toByteArray()))
    }

    private fun command(single: CommPacketId, allCanId: CommPacketId, allCanHw: CommPacketId?): ByteWriter {
        val w = ByteWriter()
        val prefix = hardwarePrefix
        when {
            !allCan -> w.u8(single.id)
            prefix != null && allCanHw != null -> w.u8(allCanHw.id).bytes(prefix)
            else -> w.u8(allCanId.id)
        }
        return w
    }

    private suspend fun erase(region: FlashRegion, payload: ByteWriter, reply: CommPacketId) {
        report(FirmwareProgress.Erasing(region))
        val ok = guarded(region) {
            session.request(controller.forwarded(payload.toByteArray()), reply, options.eraseTimeout) { it.u8() != 0 }
        }
        if (!ok) throw FirmwareUpdateException(FirmwareFailure.EraseFailed, "The controller could not erase the ${region.name.lowercase()} area", region)
    }

    private suspend fun write(region: FlashRegion, data: ByteArray, base: Int, cancellable: Boolean) {
        report(FirmwareProgress.Writing(region, 0, data.size))
        var pos = 0
        while (pos < data.size) {
            if (cancellable) currentCoroutineContext().ensureActive()
            val end = minOf(pos + CHUNK_SIZE, data.size)
            if ((pos until end).any { data[it] != UpdateBuffer.ERASED }) writeChunk(region, base + pos, data.copyOfRange(pos, end))
            pos = end
            report(FirmwareProgress.Writing(region, pos, data.size))
        }
    }

    private suspend fun writeChunk(region: FlashRegion, offset: Int, chunk: ByteArray) {
        val raw = command(COMM_WRITE_NEW_APP_DATA, COMM_WRITE_NEW_APP_DATA_ALL_CAN, COMM_WRITE_NEW_APP_DATA_ALL_CAN_HW)
            .u32(offset.toLong()).bytes(chunk).toByteArray()
        if (lzo) {
            val packed = Lzo1x.compress(chunk)
            if (packed.size + 2 < chunk.size) {
                val compressed = command(COMM_WRITE_NEW_APP_DATA_LZO, COMM_WRITE_NEW_APP_DATA_ALL_CAN_LZO, null)
                    .u32(offset.toLong()).u16(chunk.size).bytes(packed).toByteArray()
                if (send(region, offset, compressed)) {
                    lzoFailures = 0
                    return
                }
                if (!send(region, offset, raw)) throw writeFailed(region, offset)
                if (++lzoFailures > MAX_LZO_FAILURES) lzo = false
                return
            }
        }
        if (!send(region, offset, raw)) throw writeFailed(region, offset)
    }

    private fun writeFailed(region: FlashRegion, offset: Int) =
        FirmwareUpdateException(FirmwareFailure.WriteFailed, "The controller could not write offset $offset", region)

    /** True if the controller wrote the chunk, false if it reported a flash error. */
    private suspend fun send(region: FlashRegion, offset: Int, payload: ByteArray): Boolean {
        repeat(options.chunkAttempts) {
            val ok = guarded(region) { attempt(offset, payload) }
            if (ok != null) return ok
        }
        throw FirmwareUpdateException(FirmwareFailure.NoReply, "No reply writing offset $offset after ${options.chunkAttempts} attempts", region)
    }

    /**
     * One write and its reply, matched by the offset the reply echoes so a late reply to an earlier attempt
     * or chunk can't be taken for this one. Every write variant is answered as COMM_WRITE_NEW_APP_DATA.
     */
    private suspend fun attempt(offset: Int, payload: ByteArray): Boolean? = coroutineScope {
        val reply = async(start = CoroutineStart.UNDISPATCHED) {
            session.incoming.first { it.isWriteReply(offset) }
        }
        try {
            session.send(controller.forwarded(payload))
            withTimeoutOrNull(options.chunkTimeout) { reply.await() }?.let { it[1].toInt() != 0 }
        } finally {
            reply.cancel()
        }
    }

    /** Firmware older than the offset field sends only the result byte. */
    private fun ByteArray.isWriteReply(offset: Int): Boolean {
        if (size < 2 || (this[0].toInt() and 0xFF) != COMM_WRITE_NEW_APP_DATA.id) return false
        if (size < 6) return true
        val echoed = ((this[2].toInt() and 0xFF) shl 24) or ((this[3].toInt() and 0xFF) shl 16) or
            ((this[4].toInt() and 0xFF) shl 8) or (this[5].toInt() and 0xFF)
        return echoed == offset
    }
}
