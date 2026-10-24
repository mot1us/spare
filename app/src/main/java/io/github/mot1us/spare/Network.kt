package io.github.mot1us.spare

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

object Network {
    fun allowed(value: String, http: Boolean): Boolean = runCatching {
        val uri = URI(value)
        uri.host != null && uri.rawUserInfo == null && (uri.scheme.equals("https", true) || (http && uri.scheme.equals("http", true)))
    }.getOrDefault(false)

    fun readLimited(input: InputStream, limit: Int): ByteArray {
        val result = ByteArrayOutputStream(minOf(limit, 65536))
        val buffer = ByteArray(16384)
        var total = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Download cancelled")
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw SourceFailure(SourceProblem.LIMIT)
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    fun get(url: String, http: Boolean = false, limit: Int = 24 * 1024 * 1024): ByteArray {
        var destination = url
        repeat(6) {
            if (!allowed(destination, http)) throw SourceFailure(
                if (runCatching { URI(destination).scheme.equals("http", true) }.getOrDefault(false) && !http) SourceProblem.HTTP_REQUIRED else SourceProblem.ADDRESS)
            val connection = URI(destination).toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 12000
                connection.readTimeout = 20000
                connection.setRequestProperty("User-Agent", "Spare/${BuildConfig.VERSION_NAME}")
                connection.setRequestProperty("Accept-Encoding", "gzip")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: error("Invalid redirect")
                    destination = URI(destination).resolve(location).toString()
                } else {
                    if (status !in 200..299) throw HttpFailure(status)
                    if (connection.contentLengthLong > limit) throw SourceFailure(SourceProblem.LIMIT)
                    val deadline = System.nanoTime() + 60000000000L
                    val bounded = object : java.io.FilterInputStream(connection.inputStream) {
                        var consumed = 0L
                        fun count(amount: Int) {
                            if (amount > 0) consumed += amount
                            if (consumed > limit) throw SourceFailure(SourceProblem.LIMIT)
                            if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException("Download deadline exceeded")
                            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Download cancelled")
                        }
                        override fun read(): Int = super.read().also { count(if (it < 0) 0 else 1) }
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also { count(it) }
                    }
                    val raw = bounded.buffered()
                    val input = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
                    return input.use { readLimited(it, limit) }
                }
            } finally { connection.disconnect() }
        }
        error("Too many redirects")
    }

    fun xmlPayload(bytes: ByteArray, limit: Int = 64 * 1024 * 1024): ByteArray =
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
            GZIPInputStream(bytes.inputStream()).use { readLimited(it, limit) }
        else bytes.also { require(it.size <= limit) }
}
