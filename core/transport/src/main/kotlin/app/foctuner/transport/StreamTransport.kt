package app.foctuner.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [Transport] over a pair of blocking streams, such as a TCP socket or a Bluetooth RFCOMM socket.
 * [closeLink] must make a blocked read throw, which is what ending the read loop relies on.
 *
 * Reading starts immediately and bytes are buffered until [received] is collected, so nothing sent right
 * after connecting is lost. [received] has a single consumer.
 */
class StreamTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeLink: () -> Unit,
    override val maxWriteSize: Int,
) : Transport {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val writeLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var closedByUs = false

    init {
        scope.launch { readUntilClosed() }
    }

    override val received: Flow<ByteArray> = incoming.receiveAsFlow()

    override suspend fun write(bytes: ByteArray) {
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                output.write(bytes)
                output.flush()
            }
        }
    }

    override suspend fun close() {
        closedByUs = true
        withContext(Dispatchers.IO) { closeLink() }
        incoming.close()
        scope.cancel()
    }

    private fun readUntilClosed() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                incoming.trySend(buffer.copyOf(n))
            }
            incoming.close()
        } catch (e: IOException) {
            incoming.close(e.takeUnless { closedByUs })
        }
    }

    private companion object {
        const val READ_BUFFER_SIZE = 8192
    }
}
