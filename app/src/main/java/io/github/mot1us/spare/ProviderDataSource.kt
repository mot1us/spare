package io.github.mot1us.spare

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

/** Applies source consent to every segment, key, subtitle, and manifest request. */
@UnstableApi
class ProviderDataSource(private val allowHttp: () -> Boolean) : BaseDataSource(true) {
    @Volatile private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var opened = false
    private var remaining = C.LENGTH_UNSET.toLong()
    private var destination: Uri? = null
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val consent = allowHttp(); var target = dataSpec.uri.toString()
        val visited = hashSetOf<String>(); var headers = dataSpec.httpRequestHeaders
        try {
            repeat(8) {
                Redirects.requireAllowed(target, consent)
                if (!visited.add(target)) throw java.net.ProtocolException("Redirect loop")
                val client = URI(target).toURL().openConnection() as HttpURLConnection
                connection = client; client.instanceFollowRedirects = false; client.connectTimeout = 12000; client.readTimeout = 15000
                client.requestMethod = DataSpec.getStringForHttpMethod(dataSpec.httpMethod)
                headers.forEach { (name, value) -> client.setRequestProperty(name, value) }
                client.setRequestProperty("User-Agent", "Spare/${BuildConfig.VERSION_NAME}"); client.setRequestProperty("Accept-Encoding", "identity")
                if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else (dataSpec.position + dataSpec.length - 1).toString()
                    client.setRequestProperty("Range", "bytes=${dataSpec.position}-$end")
                }
                dataSpec.httpBody?.let { body -> client.doOutput = true; client.outputStream.use { it.write(body) } }
                val code = client.responseCode
                if (code in setOf(300, 301, 302, 303, 307, 308)) {
                    val next = Redirects.next(target, client.getHeaderField("Location"), consent)
                    if (!Redirects.sameOrigin(target, next)) headers = headers.filterKeys { !it.equals("Authorization", true) && !it.equals("Cookie", true) }
                    client.disconnect(); connection = null; target = next
                } else {
                    if (code !in 200..299) throw HttpDataSource.InvalidResponseCodeException(code, client.responseMessage, null, responseHeaders(client), dataSpec, byteArrayOf())
                    destination = target.toUri()
                    input = client.inputStream.let { if (client.contentEncoding.equals("gzip", true)) GZIPInputStream(it) else it }
                    remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else if (client.contentEncoding.equals("gzip", true)) C.LENGTH_UNSET.toLong() else client.contentLengthLong
                    if (code == 200 && dataSpec.position > 0) {
                        val buffer = ByteArray(8192); var skip = dataSpec.position
                        while (skip > 0) {
                            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                            val n = input!!.read(buffer, 0, minOf(buffer.size.toLong(), skip).toInt()); if (n < 0) throw EOFException()
                            skip -= n
                        }
                        if (dataSpec.length == C.LENGTH_UNSET.toLong() && remaining >= 0) remaining = (remaining - dataSpec.position).coerceAtLeast(0)
                    }
                    opened = true; transferStarted(dataSpec); return remaining
                }
            }
            throw java.net.ProtocolException("Too many redirects")
        } catch (error: Exception) { close(); throw if (error is IOException) error else IOException(error) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val amount = if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()
        val read = input?.read(buffer, offset, amount) ?: C.RESULT_END_OF_INPUT
        if (read < 0 && remaining > 0) throw EOFException()
        if (read > 0) { if (remaining > 0) remaining -= read; bytesTransferred(read) }
        return read
    }
    override fun getUri(): Uri? = destination
    override fun getResponseHeaders(): Map<String, List<String>> = connection?.let(::responseHeaders).orEmpty()
    override fun close() { try { input?.close() } finally { input = null; connection?.disconnect(); connection = null; destination = null; if (opened) { opened = false; transferEnded() } } }
    private fun responseHeaders(client: HttpURLConnection) = client.headerFields.entries.filter { it.key != null }.associate { it.key!! to it.value.orEmpty() }
}

object Redirects {
    fun requireAllowed(url: String, http: Boolean) {
        if (!Network.allowed(url, http)) throw IOException(SourceFailure(if (runCatching { URI(url).scheme.equals("http", true) }.getOrDefault(false) && !http) SourceProblem.HTTP_REQUIRED else SourceProblem.ADDRESS))
    }
    fun next(current: String, location: String?, http: Boolean): String {
        if (location.isNullOrBlank() || location.length > 16384 || location.any { it.isISOControl() }) throw java.net.ProtocolException("Missing or invalid redirect location")
        val next = runCatching { URI(current).resolve(location.trim().replace(" ", "%20")).toString() }.getOrElse { throw java.net.ProtocolException("Invalid redirect") }
        requireAllowed(next, http); return next
    }
    fun sameOrigin(a: String, b: String): Boolean = runCatching { val x = URI(a); val y = URI(b); x.scheme.equals(y.scheme, true) && x.host.equals(y.host, true) && x.port == y.port }.getOrDefault(false)
}
