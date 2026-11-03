package app.foctuner.vesc.firmware.archive

import app.foctuner.sim.SimulatedArchiveHost
import java.nio.ByteBuffer

/**
 * The index of three bundles from the real server (the header and the names and tree at the end, as fetched
 * with Range requests on 2026-11-02), served with synthetic data for the files a test asks for.
 */
internal object RealBundles {
    const val RELEASE = "res_fw_7.01.rcc"
    const val ARCHIVE = "res_fw.rcc"
    const val EXPRESS = "res_fw_esp32.rcc"

    fun header(name: String): ByteArray = fixture(name).copyOfRange(0, RccIndex.HEADER_SIZE)

    fun tail(name: String): ByteArray = fixture(name).copyOfRange(RccIndex.HEADER_SIZE, fixture(name).size)

    fun index(name: String): RccIndex = RccIndex.parse(header(name), tail(name))

    /** A bundle that has the real index and, for each of [images] (path to bytes), the real offset holding that file. */
    fun bundle(name: String, images: Map<String, ByteArray>): SimulatedArchiveHost.Sparse {
        val header = header(name)
        val from = RccIndex.tailOffset(header)
        val index = index(name)
        val segments = HashMap<Long, ByteArray>()
        segments[0] = header
        segments[from] = tail(name)
        for ((path, bytes) in images) {
            val entry = index.entries.first { it.path == path }
            segments[index.dataSection + entry.dataOffset] = ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).array()
        }
        return SimulatedArchiveHost.Sparse(from + tail(name).size, segments)
    }

    private fun fixture(name: String): ByteArray =
        checkNotNull(RealBundles::class.java.getResourceAsStream("${name.removeSuffix(".rcc")}.index")) { "No fixture for $name" }.use { it.readBytes() }
}
