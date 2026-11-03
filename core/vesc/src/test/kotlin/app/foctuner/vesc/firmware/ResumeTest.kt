package app.foctuner.vesc.firmware

import app.foctuner.sim.DemoArchive
import app.foctuner.sim.SimulatedVesc
import app.foctuner.vesc.VescClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumeTest {
    private val image = FirmwareImage("7.02.bin", DemoArchive.image("60_MK6", "7.02"), listOf("60_MK6"))

    private suspend fun TestScope.interruptedUpload(
        sim: SimulatedVesc,
        dropAt: Int,
        of: FirmwareImage = image,
    ): Pair<UploadTracker, FirmwareUpdateException> {
        val client = VescClient(sim.reattach(), backgroundScope)
        val tracker = UploadTracker(of, client.firmwareInfo())
        sim.dropLinkDuringUpload(dropAt)
        val failure = assertFailsWith<FirmwareUpdateException> {
            client.updateFirmware(of).collect(tracker::observe)
        }
        return tracker to failure
    }

    @Test
    fun continuesFromTheLastAcknowledgedChunkWithoutErasingAgain() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val (tracker, failure) = interruptedUpload(sim, dropAt = 60_000)

        assertEquals(FlashRegion.Firmware, failure.region)
        assertTrue(failure.failure == FirmwareFailure.NoReply || failure.failure == FirmwareFailure.LinkLost, "${failure.failure}")
        val checkpoint = assertNotNull(tracker.checkpoint)
        assertTrue(checkpoint.offset in 50_000..60_000, "stopped at ${checkpoint.offset}")
        assertEquals(1, sim.firmwareErases)

        val client = VescClient(sim.reattach(), backgroundScope)
        assertEquals("7.01", client.firmwareInfo().version, "the old firmware kept running")
        sim.firmwareWriteOffsets.size.let { before ->
            val progress = client.updateFirmware(image, resume = checkpoint).toList()

            assertEquals(1, sim.firmwareErases, "the partial image stayed on the controller")
            assertEquals(checkpoint.offset.toLong(), sim.firmwareWriteOffsets[before], "first chunk written is the first one not acknowledged")
            assertTrue(progress.none { it is FirmwareProgress.Erasing })
            assertEquals(FirmwareProgress.Writing(FlashRegion.Firmware, checkpoint.offset, checkpoint.bufferSize), progress.filterIsInstance<FirmwareProgress.Writing>().first())
            val done = assertIs<FirmwareProgress.Restarted>(progress.last())
            assertEquals("7.02", done.firmware.version)
        }
    }

    @Test
    fun noCheckpointExistsUntilTheEraseWasConfirmed() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val client = VescClient(sim, backgroundScope)
        val tracker = UploadTracker(image, client.firmwareInfo())

        tracker.observe(FirmwareProgress.Preparing)
        tracker.observe(FirmwareProgress.Erasing(FlashRegion.Firmware))
        assertNull(tracker.checkpoint)
        tracker.observe(FirmwareProgress.Writing(FlashRegion.Bootloader, 100, 5000))
        assertNull(tracker.checkpoint, "the bootloader's progress is not the firmware's")
        tracker.observe(FirmwareProgress.Writing(FlashRegion.Firmware, 0, 160_000))
        assertEquals(0, tracker.checkpoint?.offset)
        tracker.observe(FirmwareProgress.Writing(FlashRegion.Firmware, 768, 160_000))
        assertEquals(768, tracker.checkpoint?.offset)
        tracker.observe(FirmwareProgress.Installing)
        assertNull(tracker.checkpoint, "once the controller installs, there is nothing to resume")
    }

    @Test
    fun refusesToResumeAnotherImageAnotherControllerOrAnAllCanUpdate() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val (tracker, _) = interruptedUpload(sim, dropAt = 30_000)
        val checkpoint = assertNotNull(tracker.checkpoint)
        val client = VescClient(sim.reattach(), backgroundScope)

        val other = FirmwareImage("7.03.bin", DemoArchive.image("60_MK6", "7.03"), listOf("60_MK6"))
        assertEquals(FirmwareFailure.CannotResume, assertFailsWith<FirmwareUpdateException> { client.updateFirmware(other, resume = checkpoint).toList() }.failure)
        assertEquals(FirmwareFailure.CannotResume, assertFailsWith<FirmwareUpdateException> { client.updateFirmware(image, resume = checkpoint.copy(uuid = "FFFF")).toList() }.failure)
        assertEquals(FirmwareFailure.CannotResume, assertFailsWith<FirmwareUpdateException> { client.updateFirmware(image, resume = checkpoint.copy(hardwareName = "75_300")).toList() }.failure)
        assertEquals(FirmwareFailure.CannotResume, assertFailsWith<FirmwareUpdateException> { client.updateFirmware(image, resume = checkpoint.copy(offset = checkpoint.bufferSize + 1)).toList() }.failure)
        assertFailsWith<IllegalArgumentException> { client.updateFirmware(image, allCan = true, resume = checkpoint) }
        assertEquals(1, sim.firmwareErases)
    }

    @Test
    fun aPartialImageSomeoneElseReplacedIsRefusedByTheBootloaderAndTheOldFirmwareRuns() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        val (tracker, _) = interruptedUpload(sim, dropAt = 60_000)
        val checkpoint = assertNotNull(tracker.checkpoint)
        val other = FirmwareImage("7.09.bin", DemoArchive.image("60_MK6", "7.09"), listOf("60_MK6"))
        interruptedUpload(sim, dropAt = 20_000, of = other)

        val done = VescClient(sim.reattach(), backgroundScope).updateFirmware(image, resume = checkpoint).toList().last()

        assertEquals("7.01", assertIs<FirmwareProgress.Restarted>(done).firmware.version)
    }

    @Test
    fun aDroppedLinkBeforeTheInstallLeavesTheOldFirmwareRunning() = runTest {
        val sim = SimulatedVesc(testTimeSource)
        interruptedUpload(sim, dropAt = 90_000)

        assertEquals("7.01", VescClient(sim.reattach(), backgroundScope).firmwareInfo().version)
    }

    @Test
    fun theCheckpointSurvivesBeingWrittenToDisk() {
        val checkpoint = UploadCheckpoint("ab12", "60_MK6", "0123", 160_006, 38_400)
        assertEquals(checkpoint, UploadCheckpoint.fromProperties(checkpoint.toProperties()))
        assertNull(UploadCheckpoint.fromProperties(java.util.Properties()))
    }
}
