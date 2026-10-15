package app.foctuner.vesc

import app.foctuner.protocol.PacketCodec
import app.foctuner.protocol.PacketDecoder
import app.foctuner.transport.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** Packets over a [Transport]: decodes what arrives and frames and splits what is sent. */
internal class PacketLink(
    private val transport: Transport,
    private val frameIdleTimeout: Duration,
) {
    private val decoder = PacketDecoder()
    private val writeLock = Mutex()

    /**
     * Passes every packet that arrives to [onPacket], in order, until the transport's flow completes
     * (returns) or fails (throws). Chunks are queued so a slow [onPacket] can't stall the transport.
     */
    suspend fun receive(onPacket: (ByteArray) -> Unit) = coroutineScope {
        val chunks = transport.received.buffer(Channel.UNLIMITED).produceIn(this)
        try {
            while (true) {
                val next = if (decoder.pendingBytes == 0) {
                    chunks.receiveCatching()
                } else {
                    withTimeoutOrNull(frameIdleTimeout) { chunks.receiveCatching() }
                }
                if (next == null) {
                    decoder.reset()
                    continue
                }
                val chunk = next.getOrNull() ?: break
                decoder.feed(chunk).forEach(onPacket)
            }
        } finally {
            chunks.cancel()
        }
    }

    /**
     * Frames [payload] and writes it in pieces of at most [Transport.maxWriteSize]. Cancelling in the
     * middle of a multi-piece write leaves a partial frame on the wire; the controller's parser
     * resynchronizes on the next valid frame.
     */
    suspend fun send(payload: ByteArray) {
        val frame = PacketCodec.encode(payload)
        val pieceSize = transport.maxWriteSize
        require(pieceSize > 0) { "transport.maxWriteSize must be positive" }
        writeLock.withLock {
            var offset = 0
            while (offset < frame.size) {
                val end = if (frame.size - offset <= pieceSize) frame.size else offset + pieceSize
                transport.write(frame.copyOfRange(offset, end))
                offset = end
            }
        }
    }
}
