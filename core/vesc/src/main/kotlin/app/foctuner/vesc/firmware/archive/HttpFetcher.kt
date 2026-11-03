package app.foctuner.vesc.firmware.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** What the archive needs from the network: one GET, optionally for a byte range. */
interface HttpFetcher {
    /**
     * The body of [url]. With [range] (an HTTP Range header value such as `bytes=0-23` or `bytes=900-`) the
     * server must answer 206 with that range, otherwise the call fails before reading any of the body. Fails
     * with [ArchiveException] if the body is, or says it will be, longer than [maxBytes]. [onProgress] gets the
     * number of bytes read so far. Cancelling the caller closes the connection.
     */
    suspend fun get(url: String, range: String? = null, maxBytes: Int, onProgress: (Int) -> Unit = {}): ByteArray
}

/**
 * [HttpFetcher] on `HttpURLConnection`. Redirects are not followed, so a server can't send a download
 * somewhere else, and plain HTTP is only used for [cleartextHosts]: the VESC Project archive serves its
 * bundles over HTTP because its certificate doesn't cover the host name.
 */
class UrlConnectionFetcher(
    private val cleartextHosts: Set<String> = setOf(ArchiveUrls.ARCHIVE_HOST),
    private val userAgent: String = "FOCTuner",
) : HttpFetcher {
    override suspend fun get(url: String, range: String?, maxBytes: Int, onProgress: (Int) -> Unit): ByteArray =
        withContext(Dispatchers.IO) {
            val target = URL(url)
            if (target.protocol == "http" && target.host !in cleartextHosts) {
                throw ArchiveException(ArchiveException.Reason.Network, "Refusing plain HTTP to ${target.host}")
            }
            if (target.protocol != "http" && target.protocol != "https") {
                throw ArchiveException(ArchiveException.Reason.Network, "Unsupported address $url")
            }
            val connection = target.openConnection() as HttpURLConnection
            val closeOnCancel = coroutineContext.job.invokeOnCompletion { if (it != null) connection.disconnect() }
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (range != null) connection.setRequestProperty("Range", range)
                val code = connection.responseCode
                when {
                    code == 404 -> throw ArchiveException(ArchiveException.Reason.NotFound, "$url was not found")
                    range != null && code == 200 -> throw ArchiveException(ArchiveException.Reason.RangeUnsupported, "${target.host} ignored the Range header")
                    range != null && code != 206 || range == null && code != 200 ->
                        throw ArchiveException(ArchiveException.Reason.Network, "${target.host} answered HTTP $code")
                }
                val announced = connection.contentLengthLong
                if (announced > maxBytes) throw ArchiveException(ArchiveException.Reason.TooLarge, "$url is $announced bytes")
                val out = ByteArrayOutputStream(if (announced > 0) announced.toInt() else 8192)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > maxBytes) throw ArchiveException(ArchiveException.Reason.TooLarge, "$url is longer than $maxBytes bytes")
                        out.write(buffer, 0, n)
                        onProgress(out.size())
                    }
                }
                out.toByteArray()
            } catch (e: ArchiveException) {
                throw e
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                throw ArchiveException(ArchiveException.Reason.Network, e.message ?: "Network error", e)
            } finally {
                closeOnCancel.dispose()
                connection.disconnect()
            }
        }

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val BUFFER = 16 * 1024
    }
}
