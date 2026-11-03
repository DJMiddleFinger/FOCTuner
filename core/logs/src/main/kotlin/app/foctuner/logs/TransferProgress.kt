package app.foctuner.logs

import java.util.concurrent.CancellationException

enum class TransferState { Queued, Active, Done, Failed }

data class TransferItem(
    val name: String,
    val totalBytes: Long,
    val doneBytes: Long = 0,
    val state: TransferState = TransferState.Queued,
    val error: String? = null,
)

/** Where a batch of file transfers stands: each file and the whole batch. */
data class TransferQueue(val items: List<TransferItem>, val bytesPerSecond: Double = 0.0) {
    val totalBytes: Long get() = items.sumOf { it.totalBytes }
    val doneBytes: Long get() = items.sumOf { if (it.state == TransferState.Done) it.totalBytes else it.doneBytes }
    val doneCount: Int get() = items.count { it.state == TransferState.Done }
    val failedCount: Int get() = items.count { it.state == TransferState.Failed }

    /** 0..1 over all bytes; a batch of empty files is as done as its files. */
    val fraction: Float get() = when {
        totalBytes > 0 -> (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        items.isNotEmpty() && doneCount == items.size -> 1f
        else -> 0f
    }

    /** Seconds left at the current speed, null while there is no speed to go by. */
    val secondsLeft: Double? get() = if (bytesPerSecond > 0.0) (totalBytes - doneBytes).coerceAtLeast(0) / bytesPerSecond else null
}

/** Bytes per second over the last [windowMs]; a short window follows a link that speeds up or stalls. */
class ThroughputMeter(private val windowMs: Long = 5_000) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Records that [totalBytes] have moved by [timeMs] and returns the current rate. */
    fun add(timeMs: Long, totalBytes: Long): Double {
        samples.addLast(timeMs to totalBytes)
        while (samples.size > 2 && timeMs - samples.first().first > windowMs) samples.removeFirst()
        val first = samples.first()
        val dt = timeMs - first.first
        return if (dt <= 0) 0.0 else (totalBytes - first.second) * 1000.0 / dt
    }
}

/**
 * Runs [transfer] for every item that isn't done, in order, reporting after each step. [transfer] calls its
 * `report(done, total)`; a file that grows while it is read raises its total. A failure marks that item failed and
 * carries on with the next unless [isFatal] says the link is gone, which stops the batch and leaves the rest
 * queued so the same call can be made again to resume. Cancelling stops at once.
 */
suspend fun runTransfers(
    initial: List<TransferItem>,
    clock: () -> Long,
    isFatal: (Throwable) -> Boolean = { false },
    onUpdate: (TransferQueue) -> Unit,
    transfer: suspend (item: TransferItem, report: (done: Long, total: Long) -> Unit) -> Unit,
): TransferQueue {
    val items = initial.toMutableList()
    val meter = ThroughputMeter()
    var rate = 0.0
    var moved = 0L
    fun publish() = TransferQueue(items.toList(), rate).also(onUpdate)

    publish()
    for (i in items.indices) {
        if (items[i].state == TransferState.Done) continue
        items[i] = items[i].copy(state = TransferState.Active, error = null)
        publish()
        val startedAt = items[i].doneBytes
        try {
            transfer(items[i]) { done, total ->
                moved += (done - items[i].doneBytes).coerceAtLeast(0)
                items[i] = items[i].copy(doneBytes = done, totalBytes = maxOf(total, items[i].totalBytes))
                rate = meter.add(clock(), moved)
                publish()
            }
            items[i] = items[i].copy(state = TransferState.Done, doneBytes = items[i].totalBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            items[i] = items[i].copy(state = TransferState.Failed, error = e.message ?: e.javaClass.simpleName, doneBytes = maxOf(items[i].doneBytes, startedAt))
            if (isFatal(e)) {
                publish()
                break
            }
        }
        publish()
    }
    return publish()
}
