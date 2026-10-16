package app.foctuner.vesc

import app.foctuner.protocol.PacketCodec
import app.foctuner.protocol.PacketDecoder
import app.foctuner.transport.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Wraps [inner] and remembers what the client wrote, decoded back into packets. */
class RecordingTransport(private val inner: Transport) : Transport by inner {
    private val decoder = PacketDecoder()
    val writes = mutableListOf<ByteArray>()
    val packets = mutableListOf<ByteArray>()
    var closed = false
        private set

    override suspend fun write(bytes: ByteArray) {
        writes += bytes
        packets += decoder.feed(bytes)
        inner.write(bytes)
    }

    override suspend fun close() {
        closed = true
        inner.close()
    }

    /** Packets whose command id is [id], in the order they were sent. */
    fun sent(id: Int): List<ByteArray> = packets.filter { (it[0].toInt() and 0xFF) == id }
}

/** A transport the test feeds by hand through [inject] or [incoming]. */
class ScriptedTransport(override val maxWriteSize: Int = 512) : Transport {
    val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    val packets = mutableListOf<ByteArray>()
    private val decoder = PacketDecoder()

    override val received: Flow<ByteArray> = incoming.receiveAsFlow()

    override suspend fun write(bytes: ByteArray) {
        packets += decoder.feed(bytes)
    }

    override suspend fun close() {
        incoming.close()
    }

    fun inject(payload: ByteArray) {
        incoming.trySend(PacketCodec.encode(payload))
    }
}
