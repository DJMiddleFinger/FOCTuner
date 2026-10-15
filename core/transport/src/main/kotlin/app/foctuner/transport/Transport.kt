package app.foctuner.transport

import kotlinx.coroutines.flow.Flow

/**
 * A byte pipe to one VESC (BLE, USB serial, TCP, ...). It knows nothing about packets: feed [received]
 * into a `PacketDecoder` and frame writes with `PacketCodec`. Implementations that need Android APIs
 * live in the app/android modules; this interface stays pure JVM so the simulator can implement it.
 */
interface Transport {
    /** Raw chunks in arrival order; completes when the link closes, fails if it drops unexpectedly. */
    val received: Flow<ByteArray>

    /** Largest single write the link accepts (e.g. BLE MTU - 3). Callers split bigger frames. */
    val maxWriteSize: Int

    suspend fun write(bytes: ByteArray)

    suspend fun close()
}
