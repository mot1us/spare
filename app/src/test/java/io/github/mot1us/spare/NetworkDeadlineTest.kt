package io.github.mot1us.spare

import java.io.ByteArrayOutputStream
import java.io.FilterReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkDeadlineTest {
    private val playlist = buildString {
        appendLine("#EXTM3U")
        repeat(600) { index ->
            appendLine("#EXTINF:-1,Fixture channel $index")
            appendLine("https://fixture.invalid/channel/$index.ts")
        }
    }.toByteArray()

    @Test fun parserPauseDoesNotConsumeTheSocketReadBudget() {
        fun import(networkTimeOnly: Boolean) = serve { url ->
            var time = 0L
            Network.consume(url, http = true, networkTimeOnly = networkTimeOnly, nanoTime = { time }) { input ->
                val reader = object : FilterReader(input.reader()) {
                    private var firstRead = true
                    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                        if (!firstRead) time = 61_000_000_000
                        firstRead = false
                        return super.read(buffer, offset, length)
                    }
                }
                Importers.m3u(reader, http = true)
            }
        }
        assertEquals(600, import(networkTimeOnly = true).channels.size)
        assertThrows(SocketTimeoutException::class.java) { import(networkTimeOnly = false) }
    }

    @Test fun cumulativeSocketReadTimeStillExpires() {
        serve { url ->
            var time = 0L
            assertThrows(SocketTimeoutException::class.java) {
                Network.consume(url, http = true, networkTimeOnly = true, nanoTime = { time += 15_000_000_000; time }) {
                    Importers.m3u(it.reader(), http = true)
                }
            }
        }
    }

    @Test fun m3uSocketBudgetKeepsTheDecodedGzipLimit() {
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(playlist) }
        }.toByteArray()
        serve(compressed, gzip = true) { url ->
            val lineup = Network.consume(url, http = true, limit = playlist.size, networkTimeOnly = true) {
                Importers.m3u(it.reader(), http = true)
            }
            assertEquals(600, lineup.channels.size)
        }
        serve(compressed, gzip = true) { url ->
            val failure = assertThrows(SourceFailure::class.java) {
                Network.consume(url, http = true, limit = playlist.size - 1, networkTimeOnly = true) {
                    Importers.m3u(it.reader(), http = true)
                }
            }
            assertEquals(SourceProblem.LIMIT, failure.problem)
        }
    }

    private fun <T> serve(body: ByteArray = playlist, gzip: Boolean = false, request: (String) -> T): T {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val lines = socket.getInputStream().bufferedReader()
                    while (!lines.readLine().isNullOrEmpty()) Unit
                    val response = socket.getOutputStream()
                    val encoding = if (gzip) "Content-Encoding: gzip\r\n" else ""
                    response.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n${encoding}Connection: close\r\n\r\n".toByteArray())
                    response.write(body)
                }
            }
            return request("http://127.0.0.1:${server.localPort}/playlist.m3u").also { worker.join(2000) }
        }
    }
}
