package app.foctuner.logs

import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferProgressTest {
    private fun items(vararg sizes: Long) = sizes.mapIndexed { i, size -> TransferItem("f$i.csv", size) }

    @Test
    fun reportsEachFileAndTheWholeBatchAsBytesMove() = runTest {
        val seen = mutableListOf<TransferQueue>()
        var now = 0L

        val result = runTransfers(items(1000, 3000), clock = { now }, onUpdate = { seen += it }) { item, report ->
            for (done in 0..item.totalBytes step 500) {
                now += 100
                report(done, item.totalBytes)
            }
        }

        assertEquals(2, result.doneCount)
        assertEquals(1f, result.fraction)
        assertEquals(4000L, result.doneBytes)
        assertTrue(seen.any { q -> q.items[1].state == TransferState.Active && q.items[0].state == TransferState.Done && q.fraction in 0.25f..0.99f })
        val midway = seen.first { it.doneBytes == 2500L }
        assertEquals(4166.7, midway.bytesPerSecond, 1.0)
        assertEquals(0.36, assertNotNull(midway.secondsLeft), 0.01)
    }

    @Test
    fun aFailedFileIsMarkedAndTheNextOneStillRuns() = runTest {
        val result = runTransfers(items(10, 10), clock = { 0 }, onUpdate = {}) { item, report ->
            if (item.name == "f0.csv") throw IOException("card removed")
            report(10, 10)
        }

        assertEquals(TransferState.Failed, result.items[0].state)
        assertEquals("card removed", result.items[0].error)
        assertEquals(TransferState.Done, result.items[1].state)
    }

    @Test
    fun aFatalFailureStopsTheBatchAndRunningAgainResumesTheRest() = runTest {
        val first = runTransfers(items(10, 10, 10), clock = { 0 }, isFatal = { it is IOException }, onUpdate = {}) { item, report ->
            if (item.name == "f1.csv") throw IOException("link closed")
            report(10, 10)
        }

        assertEquals(listOf(TransferState.Done, TransferState.Failed, TransferState.Queued), first.items.map { it.state })

        val ran = mutableListOf<String>()
        val second = runTransfers(first.items, clock = { 0 }, onUpdate = {}) { item, report ->
            ran += item.name
            report(10, 10)
        }

        assertEquals(listOf("f1.csv", "f2.csv"), ran)
        assertEquals(3, second.doneCount)
        assertNull(second.items[1].error)
    }

    @Test
    fun aFileThatGrowsRaisesTheTotal() = runTest {
        val result = runTransfers(items(100), clock = { 0 }, onUpdate = {}) { _, report ->
            report(100, 100)
            report(160, 160)
        }

        assertEquals(160L, result.totalBytes)
        assertEquals(160L, result.doneBytes)
    }

    @Test
    fun anEmptyBatchOfEmptyFilesIsComplete() = runTest {
        val result = runTransfers(items(0, 0), clock = { 0 }, onUpdate = {}) { _, _ -> }

        assertEquals(1f, result.fraction)
        assertNull(result.secondsLeft)
    }

    @Test
    fun theMeterForgetsWhatLiesOutsideItsWindow() {
        val meter = ThroughputMeter(windowMs = 1000)
        meter.add(0, 0)
        meter.add(500, 5000)
        val rate = meter.add(3000, 5000)

        assertEquals(0.0, rate, 1e-9)
    }
}
