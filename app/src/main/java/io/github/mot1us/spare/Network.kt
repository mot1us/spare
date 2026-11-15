package io.github.mot1us.spare

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.zip.GZIPInputStream

object Network {
    /** Spare's own User-Agent, sent unless a playlist names its own for its servers. */
    val defaultUserAgent get() = "Spare/${BuildConfig.VERSION_NAME}"
    /** A playlist's User-Agent: printable ASCII only (no line breaks that could add headers), at most 200 characters. */
    fun userAgent(value: String): String = value.trim().takeIf { it.length <= 200 && it.all { c -> c in ' '..'~' } }.orEmpty()
    @Volatile private var agents: Map<String, String> = emptyMap()
    private fun origin(url: String): String? = runCatching { URI(url).let { "${it.scheme?.lowercase()}://${it.host?.lowercase()}:${it.port}" } }.getOrNull()
    /** Remember each playlist's User-Agent for its server addresses (main and backup). */
    fun useSources(sources: List<Source>) {
        agents = buildMap { sources.filter { it.userAgent.isNotBlank() }.forEach { source ->
            listOf(source.url, source.backupUrl).filter { it.isNotBlank() }.mapNotNull(::origin).forEach { put(it, source.userAgent) }
        } }
    }
    fun userAgentFor(url: String): String = origin(url)?.let(agents::get) ?: defaultUserAgent
    /** Large downloads (playlists, guides) may run up to ten minutes of network time, as long as they never stall. */
    const val LARGE_DEADLINE_MS = 600_000L
    /** A large download must bring at least this much in each read-timeout window (20 s by default). */
    const val STALL_BYTES = 32 * 1024
    /** Socket reads ignore Thread.interrupt(); close a cancelled request's connection so its worker is released at once. */
    private val open = java.util.concurrent.ConcurrentHashMap<HttpURLConnection, Thread>()
    private val reaper = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "spare-network-cancel").apply { isDaemon = true } }
        .apply { scheduleWithFixedDelay({ open.forEach { (connection, thread) -> if (thread.isInterrupted && open.remove(connection) != null) runCatching { connection.disconnect() } } }, 200, 200, java.util.concurrent.TimeUnit.MILLISECONDS) }
    internal val openConnections get() = open.size
    fun allowed(value: String, http: Boolean): Boolean = value.isNotEmpty() && runCatching {
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

    fun get(url: String, http: Boolean = false, limit: Int = 24 * 1024 * 1024, timeoutMs: Int = 20000, progress: ((Long, Long) -> Unit)? = null, large: Boolean = false): ByteArray {
        return if (large) consume(url, http, limit, timeoutMs, progress, networkTimeOnly = true, deadlineMs = LARGE_DEADLINE_MS, stallBytes = STALL_BYTES) { readLimited(it, limit) }
        else consume(url, http, limit, timeoutMs, progress) { readLimited(it, limit) }
    }

    internal fun <T> consume(url: String, http: Boolean, limit: Int = 24 * 1024 * 1024, timeoutMs: Int = 20000,
        progress: ((Long, Long) -> Unit)? = null, networkTimeOnly: Boolean = false,
        nanoTime: () -> Long = System::nanoTime, deadlineMs: Long = minOf(60000L, timeoutMs * 3L), stallBytes: Int = 0, read: (InputStream) -> T): T {
        var destination = url
        repeat(6) {
            if (!allowed(destination, http)) throw SourceFailure(
                if (runCatching { URI(destination).scheme.equals("http", true) }.getOrDefault(false) && !http) SourceProblem.HTTP_REQUIRED else SourceProblem.ADDRESS)
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Download cancelled")
            val connection = URI(destination).toURL().openConnection() as HttpURLConnection
            open[connection] = Thread.currentThread()
            // A fully read, closed response leaves its socket in the keep-alive pool: the next provider or
            // artwork request skips DNS, TCP and TLS setup. Failures and cancellations still disconnect.
            var reusable = false
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = minOf(12000, timeoutMs)
                connection.readTimeout = timeoutMs
                connection.setRequestProperty("User-Agent", userAgentFor(destination))
                connection.setRequestProperty("Accept-Encoding", "gzip")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: error("Invalid redirect")
                    destination = URI(destination).resolve(location).toString()
                } else {
                    if (status !in 200..299) throw HttpFailure(status)
                    if (connection.contentLengthLong > limit) throw SourceFailure(SourceProblem.LIMIT)
                    val deadline = DownloadDeadline(deadlineMs, networkTimeOnly, nanoTime, stallBytes, timeoutMs * 1_000_000L)
                    val total = connection.contentLengthLong
                    progress?.invoke(0, total)
                    val bounded = object : java.io.FilterInputStream(deadline.socketRead { connection.inputStream }) {
                        var consumed = 0L
                        var lastProgress = 0L
                        fun count(amount: Int) {
                            if (amount > 0) { consumed += amount; deadline.received(amount) }
                            if (consumed > limit) throw SourceFailure(SourceProblem.LIMIT)
                            deadline.check(arrived = amount > 0)
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
                    return decoded.use(read).also { progress?.invoke(bounded.consumed, total); reusable = !Thread.currentThread().isInterrupted }
                }
            } catch (error: java.io.IOException) {
                if (Thread.currentThread().isInterrupted && error !is java.io.InterruptedIOException) throw java.io.InterruptedIOException("Download cancelled")
                throw error
            } finally { open.remove(connection); if (!reusable) connection.disconnect() }
        }
        error("Too many redirects")
    }

    /**
     * The whole-download limit. With [stallBytes], a large download (a playlist, a guide, a catalog) instead fails only
     * when it stalls: each [windowNanos] must bring at least that many bytes, so a slow but healthy transfer can finish
     * while a trickle that will never complete still stops. The deadline then remains as a generous outer cap.
     */
    private class DownloadDeadline(deadlineMs: Long, private val networkTimeOnly: Boolean, private val now: () -> Long,
        private val stallBytes: Int = 0, private val windowNanos: Long = 20_000_000_000L) {
        private val budget = deadlineMs * 1_000_000
        private val started = now()
        private var networkNanos = 0L
        private var windowStart = 0L
        private var windowBytes = 0L
        fun received(amount: Int) { windowBytes += amount }

        inline fun <T> socketRead(read: () -> T): T {
            if (!networkTimeOnly) return read()
            val before = now()
            return read().also { networkNanos += now() - before }
        }

        /** [arrived] is false for the end of the stream and for decoded-side checks, which never judge a stall. */
        fun check(arrived: Boolean = false) {
            val elapsed = if (networkTimeOnly) networkNanos else now() - started
            if (elapsed > budget) throw SocketTimeoutException("Download deadline exceeded")
            if (arrived && stallBytes > 0 && elapsed - windowStart >= windowNanos) {
                if (windowBytes < stallBytes) throw SocketTimeoutException("Download stalled")
                windowStart = elapsed; windowBytes = 0
            }
        }
    }

    fun xmlPayload(bytes: ByteArray, limit: Int = 64 * 1024 * 1024): ByteArray =
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
            GZIPInputStream(bytes.inputStream()).use { readLimited(it, limit) }
        else bytes.also { require(it.size <= limit) }
}
