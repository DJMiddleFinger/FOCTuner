package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reads something the controller hands out in pieces (custom config definitions, QML UIs): [request] asks for
 * `length` bytes at `offset`, and [parse] returns (total size, bytes) for a reply to that offset, null for any
 * other. A zero-length first request learns the size; VESC Tool asks for 10 bytes, but zero also works for data
 * shorter than that. Each piece is retried like VESC Tool does (1.5 s, 5 tries).
 */
internal suspend fun VescController.readChunked(
    replyId: CommPacketId,
    request: (offset: Int, length: Int) -> ByteArray,
    parse: (reply: ByteReader, offset: Int) -> Pair<Int, ByteArray>?,
    onProgress: (read: Int, total: Int) -> Unit,
): ByteArray {
    suspend fun piece(offset: Int, length: Int): Pair<Int, ByteArray> =
        retrying(CHUNK_TRIES) { exchange(request(offset, length), replyId, CHUNK_TIMEOUT) { parse(it, offset) } }
            ?: throw noReply(replyId, CHUNK_TIMEOUT)

    val total = piece(0, 0).first
    val data = ByteWriter(maxOf(total, 0))
    onProgress(0, total)
    while (data.length < total) {
        val (_, bytes) = piece(data.length, minOf(CHUNK_SIZE, total - data.length))
        if (bytes.isEmpty()) throw VescException("The controller sent no data at byte ${data.length}")
        data.bytes(bytes)
        onProgress(data.length, total)
    }
    return data.toByteArray()
}

internal fun VescController.noReply(id: CommPacketId, timeout: Duration): VescException =
    if (session.connected.value) VescTimeoutException("No reply to $id within $timeout") else VescLinkException("Link closed waiting for $id")

/** What VESC Tool asks for; the firmware refuses more than 502. */
private const val CHUNK_SIZE = 400
private const val CHUNK_TRIES = 5
private val CHUNK_TIMEOUT = 1500.milliseconds
