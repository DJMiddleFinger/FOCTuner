package app.foctuner.sim

import app.foctuner.vesc.firmware.archive.ArchiveException
import app.foctuner.vesc.firmware.archive.ArchiveUrls
import app.foctuner.vesc.firmware.archive.HttpFetcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * The VESC Project archive and the VESC Tool repository as one in-memory [HttpFetcher], for tests and demo
 * mode: bundles under [ArchiveUrls.archive] (with a directory index like the real server's), repository
 * files and their directory listings under [ArchiveUrls.repository] and [ArchiveUrls.repositoryListing].
 * It answers Range requests like Apache does and records what it served, so a test can check that a listing
 * cost kilobytes, not a bundle.
 */
class SimulatedArchiveHost(private val urls: ArchiveUrls = ArchiveUrls()) : HttpFetcher {
    /** A file the host can serve without holding all of it. */
    interface Resource {
        val length: Long
        fun read(offset: Long, length: Int): ByteArray
    }

    private class Whole(private val bytes: ByteArray) : Resource {
        override val length: Long get() = bytes.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    /** A large file of which only [segments] (start offset to bytes) matter; the rest reads as zeros. */
    class Sparse(override val length: Long, private val segments: Map<Long, ByteArray>) : Resource {
        override fun read(offset: Long, length: Int): ByteArray {
            val out = ByteArray(length)
            for ((start, bytes) in segments) {
                val from = maxOf(start, offset)
                val to = minOf(start + bytes.size, offset + length)
                if (from < to) bytes.copyInto(out, (from - offset).toInt(), (from - start).toInt(), (to - start).toInt())
            }
            return out
        }
    }

    class Request(val url: String, val range: String?, val served: Int)

    val requests = mutableListOf<Request>()

    /** Bytes sent in answer to every request so far. */
    val servedBytes: Long get() = requests.sumOf { it.served.toLong() }

    /** Every request fails like a missing connection does. */
    var offline = false

    /** Answers a Range request with the whole file, as a server without range support would. */
    var ignoreRange = false

    /** URLs that fail like a dropped connection, e.g. one bundle of several. */
    val failing = mutableSetOf<String>()

    /** Pause after each 16 KB sent, to make downloads take a visible time in demo mode. */
    var chunkDelay: Duration = Duration.ZERO

    private val bundles = LinkedHashMap<String, Resource>()
    private val repository = LinkedHashMap<String, ByteArray>()

    fun addBundle(name: String, bytes: ByteArray) {
        bundles[name] = Whole(bytes)
    }

    fun addBundle(name: String, resource: Resource) {
        bundles[name] = resource
    }

    fun addRepositoryFile(path: String, bytes: ByteArray) {
        repository[path] = bytes
    }

    override suspend fun get(url: String, range: String?, maxBytes: Int, onProgress: (Int) -> Unit): ByteArray {
        if (offline || url in failing) throw ArchiveException(ArchiveException.Reason.Network, "Simulated connection failure")
        val resource = resolve(url) ?: throw ArchiveException(ArchiveException.Reason.NotFound, "$url was not found")
        if (range != null && ignoreRange) throw ArchiveException(ArchiveException.Reason.RangeUnsupported, "Simulated server ignores Range")
        var start = 0L
        var end = resource.length - 1
        if (range != null) {
            val match = RANGE.matchEntire(range) ?: throw ArchiveException(ArchiveException.Reason.Network, "Bad range $range")
            start = match.groupValues[1].toLong()
            end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLong()?.coerceAtMost(resource.length - 1) ?: (resource.length - 1)
            if (start > end) throw ArchiveException(ArchiveException.Reason.Network, "Range $range is not satisfiable")
        }
        val length = end - start + 1
        if (length > maxBytes) throw ArchiveException(ArchiveException.Reason.TooLarge, "$url is $length bytes")
        val out = ByteArray(length.toInt())
        var done = 0
        while (done < out.size) {
            coroutineContext.ensureActive()
            val n = minOf(CHUNK, out.size - done)
            resource.read(start + done, n).copyInto(out, done)
            done += n
            onProgress(done)
            if (chunkDelay > Duration.ZERO) delay(chunkDelay)
        }
        requests += Request(url, range, out.size)
        return out
    }

    private fun resolve(url: String): Resource? = when {
        url == urls.archive -> Whole(directoryIndex().toByteArray())
        url.startsWith(urls.archive) -> bundles[url.removePrefix(urls.archive)]
        url.startsWith(urls.repositoryListing) -> listing(url.removePrefix(urls.repositoryListing).trim('/'))?.let { Whole(it.toByteArray()) }
        url.startsWith(urls.repository) -> repository[url.removePrefix(urls.repository)]?.let(::Whole)
        else -> null
    }

    private fun directoryIndex(): String = buildString {
        append("<html><body><h1>Index of /vesc_fw_archive</h1><table>")
        for (name in bundles.keys) append("<tr><td><a href=\"$name\">$name</a></td></tr>")
        append("</table></body></html>")
    }

    /** GitHub's contents API for a directory: one object per direct child. */
    private fun listing(directory: String): String? {
        val prefix = "$directory/"
        val children = LinkedHashMap<String, Boolean>()
        for (path in repository.keys) {
            if (!path.startsWith(prefix)) continue
            val rest = path.removePrefix(prefix)
            children.putIfAbsent(rest.substringBefore('/'), rest.contains('/'))
        }
        if (children.isEmpty()) return null
        return children.entries.joinToString(",", "[", "]") { (name, dir) ->
            """{"name": "$name", "path": "$prefix$name", "size": 0, "type": "${if (dir) "dir" else "file"}", "_links": {"self": "x"}}"""
        }
    }

    private companion object {
        val RANGE = Regex("""bytes=(\d+)-(\d*)""")
        const val CHUNK = 16 * 1024
    }
}
