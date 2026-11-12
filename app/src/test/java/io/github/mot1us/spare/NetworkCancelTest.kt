package io.github.mot1us.spare

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test

class NetworkCancelTest {
    @Test fun interruptingADownloadWaitingOnASilentServerReleasesItsThreadPromptly() {
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { server ->
            val accepted = CountDownLatch(1)
            val holder = Thread { runCatching { server.accept().use { accepted.countDown(); Thread.sleep(30000) } } }.apply { isDaemon = true; start() }
            val pool = Executors.newSingleThreadExecutor()
            val task = pool.submit<Throwable?> {
                runCatching { Network.get("http://127.0.0.1:${server.localPort}/player_api.php", http = true, timeoutMs = 20000) }.exceptionOrNull()
            }
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)
            val began = System.nanoTime()
            pool.shutdownNow()
            assertTrue("worker stayed blocked on the socket", pool.awaitTermination(3, TimeUnit.SECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 3000)
            assertEquals(0, Network.openConnections)
            holder.interrupt(); task.cancel(true)
        }
    }
    @Test fun completedRequestsReuseOneKeepAliveConnection() {
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { server ->
            val accepted = java.util.concurrent.atomic.AtomicInteger()
            Thread {
                runCatching { while (true) { val socket = server.accept(); accepted.incrementAndGet()
                    Thread { runCatching { socket.use { s ->
                        val reader = s.getInputStream().bufferedReader(); val out = s.getOutputStream()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isNotEmpty()) continue
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\n[]".toByteArray()); out.flush()
                        }
                    } } }.apply { isDaemon = true; start() } } }
            }.apply { isDaemon = true; start() }
            repeat(3) { assertEquals("[]", Network.get("http://127.0.0.1:${server.localPort}/player_api.php", http = true).toString(Charsets.UTF_8)) }
            assertEquals(1, accepted.get())
            assertEquals(0, Network.openConnections)
        }
    }
}
