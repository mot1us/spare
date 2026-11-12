package io.github.mot1us.spare

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.zip.GZIPInputStream

object Network {
    /** Socket reads ignore Thread.interrupt(); close a cancelled request's connection so its worker is released at once. */
    private val open = java.util.concurrent.ConcurrentHashMap<HttpURLConnection, Thread>()
    private val reaper = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "spare-network-cancel").apply { isDaemon = true } }
        .apply { scheduleWithFixedDelay({ open.forEach { (connection, thread) -> if (thread.isInterrupted && open.remove(connection) != null) runCatching { connection.disconnect() } } }, 200, 200, java.util.concurrent.TimeUnit.MILLISECONDS) }
    internal val openConnections get() = open.size
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

    fun get(url: String, http: Boolean = false, limit: Int = 24 * 1024 * 1024, timeoutMs: Int = 20000, progress: ((Long, Long) -> Unit)? = null): ByteArray {
        return consume(url, http, limit, timeoutMs, progress) { readLimited(it, limit) }
    }

    internal fun <T> consume(url: String, http: Boolean, limit: Int = 24 * 1024 * 1024, timeoutMs: Int = 20000,
        progress: ((Long, Long) -> Unit)? = null, networkTimeOnly: Boolean = false,
        nanoTime: () -> Long = System::nanoTime, deadlineMs: Long = minOf(60000L, timeoutMs * 3L), read: (InputStream) -> T): T {
        var destination = url
        repeat(6) {
            if (!allowed(destination, http)) throw SourceFailure(
                if (runCatching { URI(destination).scheme.equals("http", true) }.getOrDefault(false) && !http) SourceProblem.HTTP_REQUIRED else SourceProblem.ADDRESS)
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Download cancelled")
            val connection = URI(destination).toURL().openConnection() as HttpURLConnection
            open[connection] = Thread.currentThread()
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = minOf(12000, timeoutMs)
                connection.readTimeout = timeoutMs
                connection.setRequestProperty("User-Agent", "Spare/${BuildConfig.VERSION_NAME}")
                connection.setRequestProperty("Accept-Encoding", "gzip")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: error("Invalid redirect")
                    destination = URI(destination).resolve(location).toString()
                } else {
                    if (status !in 200..299) throw HttpFailure(status)
                    if (connection.contentLengthLong > limit) throw SourceFailure(SourceProblem.LIMIT)
                    val deadline = DownloadDeadline(deadlineMs, networkTimeOnly, nanoTime)
                    val total = connection.contentLengthLong
                    progress?.invoke(0, total)
                    val bounded = object : java.io.FilterInputStream(deadline.socketRead { connection.inputStream }) {
                        var consumed = 0L
                        var lastProgress = 0L
                        fun count(amount: Int) {
                            if (amount > 0) consumed += amount
                            if (consumed > limit) throw SourceFailure(SourceProblem.LIMIT)
                            deadline.check()
                            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Download cancelled")
                            val now = System.nanoTime()
                            if (now - lastProgress > 100000000L) { progress?.invoke(consumed, total); lastProgress = now }
                        }
                        override fun read(): Int = deadline.socketRead { `in`.read() }.also { count(if (it < 0) 0 else 1) }
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                            deadline.socketRead { `in`.read(bytes, offset, length) }.also { count(it) }
                    }
                    val raw = bounded.buffered()
                    val input = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
                    val decoded = object : java.io.FilterInputStream(input) {
                        var size = 0L
                        fun check(n: Int) {
                            if (n > 0) size += n
                            if (size > limit) throw SourceFailure(SourceProblem.LIMIT)
                            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Download cancelled")
                            deadline.check()
                        }
                        override fun read(): Int = `in`.read().also { check(if (it < 0) 0 else 1) }
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also { check(it) }
                    }
                    return decoded.use(read).also { progress?.invoke(bounded.consumed, total) }
                }
            } catch (error: java.io.IOException) {
                if (Thread.currentThread().isInterrupted && error !is java.io.InterruptedIOException) throw java.io.InterruptedIOException("Download cancelled")
                throw error
            } finally { open.remove(connection); connection.disconnect() }
        }
        error("Too many redirects")
    }

    private class DownloadDeadline(deadlineMs: Long, private val networkTimeOnly: Boolean, private val now: () -> Long) {
        private val budget = deadlineMs * 1_000_000
        private val started = now()
        private var networkNanos = 0L

        inline fun <T> socketRead(read: () -> T): T {
            if (!networkTimeOnly) return read()
            val before = now()
            return read().also { networkNanos += now() - before }
        }

        fun check() {
            val elapsed = if (networkTimeOnly) networkNanos else now() - started
            if (elapsed > budget) throw SocketTimeoutException("Download deadline exceeded")
        }
    }

    fun xmlPayload(bytes: ByteArray, limit: Int = 64 * 1024 * 1024): ByteArray =
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
            GZIPInputStream(bytes.inputStream()).use { readLimited(it, limit) }
        else bytes.also { require(it.size <= limit) }
}
